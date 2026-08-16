package com.local.spacedcards.data.quiz

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.json.JSONException
import org.json.JSONObject

class QzdImporter(
    private val context: Context,
) {
    internal fun import(uri: Uri): ImportedQuizPack = try {
        read(uri)
    } catch (error: Throwable) {
        throw wrapFailure(error)
    }

    private fun read(uri: Uri): ImportedQuizPack {
        val sourceName = uri.displayName() ?: uri.lastPathSegment ?: "import.qzd"
        val tempZip = File.createTempFile("spacedcards-qzd-", ".zip", context.cacheDir)
        try {
            copyUriToTempFile(uri, tempZip)
            return readFromFile(tempZip, sourceName)
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri, outFile: File) {
        // Gli stream SAF non sono seekable: si copia prima in cache e poi si
        // usa ZipFile, che invece richiede accesso random alle entry.
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: throw QzdImportException(QzdImportError.OpenFailed)
    }

    private fun readFromFile(qzdFile: File, sourceName: String): ImportedQuizPack =
        ZipFile(qzdFile).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw QzdImportException(QzdImportError.MissingEntry(MANIFEST_ENTRY))
            val contentEntry = zip.getEntry(CONTENT_ENTRY)
                ?: throw QzdImportException(QzdImportError.MissingEntry(CONTENT_ENTRY))

            val manifest = parseManifest(
                manifestJson = readZipEntryText(zip, manifestEntry),
                sourceName = sourceName,
            )
            val contentPath = persistContentSqlite(zip, contentEntry, manifest.packUid)

            ImportedQuizPack(
                packUid = manifest.packUid,
                name = manifest.name,
                cardCount = manifest.cardCount,
                sectionCount = manifest.sectionCount,
                sectionsJson = manifest.sectionsJson,
                bakedAt = manifest.bakedAt,
                importedAt = System.currentTimeMillis(),
                schemaVersion = manifest.schemaVersion,
                bakerVersion = manifest.bakerVersion,
                llmModel = manifest.llmModel,
                contentPath = contentPath,
            )
        }

    private fun parseManifest(
        manifestJson: String,
        sourceName: String,
    ): ParsedManifest = try {
        val json = JSONObject(manifestJson)
        val schemaVersion = json.getInt("schema_version")

        // Il formato e' versionato: una versione sconosciuta va rifiutata
        // subito, prima di leggere il resto "a meta'" con ipotesi sbagliate.
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw QzdImportException(QzdImportError.UnsupportedSchema(schemaVersion))
        }

        val raccolta = json.getJSONObject("raccolta")
        val rawPackUid = raccolta.optString("uid", "").trim()
        val packUid = validatedPackUid(rawPackUid)
        val fallbackName = sourceName.substringBeforeLast('.', sourceName).trim().ifBlank { packUid }
        val name = raccolta.optString("name", "").trim().ifBlank { fallbackName }
        val sections = json.getJSONArray("sezioni")

        ParsedManifest(
            packUid = packUid,
            name = name,
            cardCount = json.getInt("card_count"),
            sectionCount = sections.length(),
            sectionsJson = sections.toString(),
            bakedAt = json.optString("baked_at", "").trim().ifEmpty { null },
            schemaVersion = schemaVersion,
            bakerVersion = json.getString("baker_version").trim(),
            llmModel = json.optJSONObject("llm")
                ?.optString("model", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        )
    } catch (error: QzdImportException) {
        throw error
    } catch (error: JSONException) {
        throw QzdImportException(
            QzdImportError.InvalidManifest(error.message ?: "campi obbligatori mancanti."),
            error,
        )
    }

    private fun persistContentSqlite(
        zip: ZipFile,
        entry: ZipEntry,
        packUid: String,
    ): String {
        val rootDir = File(context.filesDir, QUIZPACKS_DIR_NAME)
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw QzdImportException(
                QzdImportError.StorageFailure("Impossibile creare la cartella $QUIZPACKS_DIR_NAME."),
            )
        }

        val packDir = resolvePackDirectory(rootDir, packUid)
        if (!packDir.exists() && !packDir.mkdirs()) {
            throw QzdImportException(
                QzdImportError.StorageFailure("Impossibile creare la cartella del pacchetto $packUid."),
            )
        }

        val swapToken = randomPackUid()
        val stagingFile = File(packDir, "content.sqlite.importing-$swapToken")
        val finalFile = File(packDir, CONTENT_ENTRY)
        val backupFile = File(packDir, "content.sqlite.backup-$swapToken")

        try {
            // Il pacchetto quiz deve restare sul telefono finche' l'utente non
            // lo cancella: per questo `content.sqlite` vive sotto filesDir e
            // non sotto cacheDir, che Android puo' svuotare in autonomia.
            zip.getInputStream(entry).use { input ->
                stagingFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            validateContentDatabase(stagingFile)
            replaceContentFile(stagingFile, finalFile, backupFile)
            return finalFile.absolutePath
        } finally {
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
            if (backupFile.exists() && finalFile.exists()) {
                backupFile.delete()
            }
        }
    }

    private fun replaceContentFile(
        stagingFile: File,
        finalFile: File,
        backupFile: File,
    ) {
        try {
            if (finalFile.exists()) {
                moveOrCopy(finalFile, backupFile)
            }
            moveOrCopy(stagingFile, finalFile)
            if (backupFile.exists() && !backupFile.delete()) {
                throw QzdImportException(
                    QzdImportError.StorageFailure(
                        detail = "Impossibile rimuovere la vecchia copia di ${finalFile.absolutePath}.",
                    ),
                )
            }
        } catch (error: Throwable) {
            if (!finalFile.exists() && backupFile.exists()) {
                runCatching { moveOrCopy(backupFile, finalFile) }
            }
            throw error
        }
    }

    private fun moveOrCopy(source: File, target: File) {
        if (!source.exists()) {
            throw QzdImportException(
                QzdImportError.StorageFailure("File temporaneo mancante: ${source.absolutePath}"),
            )
        }
        if (target.exists() && !target.delete()) {
            throw QzdImportException(
                QzdImportError.StorageFailure("Impossibile sovrascrivere ${target.absolutePath}."),
            )
        }
        if (source.renameTo(target)) {
            return
        }
        source.copyTo(target, overwrite = true)
        if (!source.delete() && source.exists()) {
            throw QzdImportException(
                QzdImportError.StorageFailure("Impossibile pulire il file temporaneo ${source.absolutePath}."),
            )
        }
    }

    private fun validateContentDatabase(sqliteFile: File) {
        try {
            SQLiteDatabase.openDatabase(
                sqliteFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        throw QzdImportException(
                            QzdImportError.Corrupt("content.sqlite non risponde a quick_check."),
                        )
                    }
                    val result = cursor.getString(0) ?: "unknown"
                    if (!result.equals("ok", ignoreCase = true)) {
                        throw QzdImportException(
                            QzdImportError.Corrupt("content.sqlite fallisce quick_check: $result"),
                        )
                    }
                }
                probeSchema(
                    db = db,
                    sql = """
                        SELECT uid, front, back, answer_core, answer_note, core_norm, answer_type, blanks, source_ord
                        FROM card
                        LIMIT 0
                    """.trimIndent(),
                )
                probeSchema(
                    db = db,
                    sql = """
                        SELECT uid, name, src_name, imported_at, card_count
                        FROM sezione
                        LIMIT 0
                    """.trimIndent(),
                )
                probeSchema(
                    db = db,
                    sql = """
                        SELECT card_uid, sezione_uid
                        FROM card_sezione
                        LIMIT 0
                    """.trimIndent(),
                )
                probeSchema(
                    db = db,
                    sql = """
                        SELECT card_uid, text, origin, quality, gen_version
                        FROM distractor
                        LIMIT 0
                    """.trimIndent(),
                )
                probeSchema(
                    db = db,
                    sql = """
                        SELECT card_uid, other_uid, rank, sim
                        FROM neighbor
                        LIMIT 0
                    """.trimIndent(),
                )
                probeSchema(
                    db = db,
                    sql = """
                        SELECT card_uid, other_uid, reason
                        FROM exclusion
                        LIMIT 0
                    """.trimIndent(),
                )
            }
        } catch (error: QzdImportException) {
            throw error
        } catch (error: SQLiteDatabaseCorruptException) {
            throw QzdImportException(
                QzdImportError.Corrupt("content.sqlite corrotto o troncato.", error),
                error,
            )
        } catch (error: SQLiteCantOpenDatabaseException) {
            throw QzdImportException(
                QzdImportError.Corrupt("content.sqlite non leggibile.", error),
                error,
            )
        } catch (error: SQLiteException) {
            throw QzdImportException(
                QzdImportError.InvalidContentDatabase(
                    error.message ?: error::class.java.simpleName,
                ),
                error,
            )
        }
    }

    private fun probeSchema(
        db: SQLiteDatabase,
        sql: String,
    ) {
        db.rawQuery(sql, null).use { cursor ->
            cursor.columnCount
        }
    }

    private fun readZipEntryText(
        zip: ZipFile,
        entry: ZipEntry,
    ): String = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun wrapFailure(error: Throwable): QzdImportException = when (error) {
        is QzdImportException -> error
        is ZipException -> QzdImportException(QzdImportError.NotAZip, error)
        is IOException -> QzdImportException(
            QzdImportError.Corrupt("Errore I/O durante l'import del pacchetto .qzd.", error),
            error,
        )
        else -> QzdImportException(
            QzdImportError.Corrupt(
                detail = "Import .qzd fallito: ${error.message ?: error::class.java.simpleName}",
                rootCause = error,
            ),
            error,
        )
    }

    private fun Uri.displayName(): String? {
        if (scheme != "content") {
            return lastPathSegment
        }
        return context.contentResolver.query(
            this,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(0)
            }
        } ?: lastPathSegment
    }

    private data class ParsedManifest(
        val packUid: String,
        val name: String,
        val cardCount: Int,
        val sectionCount: Int,
        val sectionsJson: String,
        val bakedAt: String?,
        val schemaVersion: Int,
        val bakerVersion: String,
        val llmModel: String?,
    )

    private companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CONTENT_ENTRY = "content.sqlite"
        private const val SUPPORTED_SCHEMA_VERSION = 1

        private inline fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T =
            try {
                block(this)
            } finally {
                close()
            }

        private inline fun <T> Cursor.use(block: (Cursor) -> T): T =
            try {
                block(this)
            } finally {
                close()
            }
    }
}

private val VALID_PACK_UID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun validatedPackUid(
    rawPackUid: String,
    generatedPackUid: () -> String = ::randomPackUid,
): String {
    val candidate = rawPackUid.trim()
    return if (VALID_PACK_UID_REGEX.matches(candidate)) {
        candidate
    } else {
        generatedPackUid()
    }
}

internal fun storageDirName(
    packUid: String,
    generatedPackUid: () -> String = ::randomPackUid,
): String = validatedPackUid(packUid, generatedPackUid)

internal fun resolvePackDirectory(
    rootDir: File,
    packUid: String,
    generatedPackUid: () -> String = ::randomPackUid,
): File {
    val canonicalRoot = rootDir.canonicalFile
    val packDir = File(canonicalRoot, storageDirName(packUid, generatedPackUid)).canonicalFile
    val rootPrefix = canonicalRoot.path + File.separator
    if (packDir.path == canonicalRoot.path || !packDir.path.startsWith(rootPrefix)) {
        throw QzdImportException(
            QzdImportError.StorageFailure(
                detail = "Rifiutata la scrittura fuori da $QUIZPACKS_DIR_NAME: ${packDir.absolutePath}",
            ),
        )
    }
    return packDir
}

private fun randomPackUid(): String = UUID.randomUUID().toString().replace("-", "")
