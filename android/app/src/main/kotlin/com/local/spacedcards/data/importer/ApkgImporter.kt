package com.local.spacedcards.data.importer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import com.github.luben.zstd.ZstdInputStream
import com.local.spacedcards.data.content.ContentCardEntity
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ApkgImportException(
    val reason: Reason,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail, cause) {
    enum class Reason {
        OPEN_FAILED,
        NO_USABLE_NOTES,
        MISSING_COLLECTION_DB,
        MISSING_COLLECTION_ENTRY,
        IO_ERROR,
        IMPORT_FAILED,
    }
}

data class ApkgImportPreview(
    val sourceName: String,
    val suggestedSectionName: String,
    val detectedCardCount: Int,
)

data class ParsedApkg(
    val sourceName: String,
    val suggestedSectionName: String,
    val notesRead: Int,
    val cards: List<ContentCardEntity>,
)

class ApkgImporter(
    private val context: Context,
) {
    suspend fun preview(uri: Uri): ApkgImportPreview = read(uri).toPreview()

    suspend fun read(uri: Uri): ParsedApkg = runCatching {
        // Estrazione, decompressione zstd via libreria nativa, apertura SQLite e insert in Room
        // sono tutte operazioni bloccanti: vanno su Dispatchers.IO perche'
        // questa funzione la chiamera' il ViewModel dal thread principale.
        withContext(Dispatchers.IO) {
            val sourceName = uri.displayName() ?: uri.lastPathSegment ?: "import.apkg"
            val apkgFile = copyUriToTempFile(uri, ".apkg")
            try {
                readFromFile(apkgFile, sourceName)
            } finally {
                apkgFile.delete()
            }
        }
    }.getOrElse { throw wrapFailure(it) }

    private suspend fun readFromFile(apkgFile: File, sourceName: String): ParsedApkg {
        val collectionFile = createTempFile("spacedcards-collection-", ".sqlite").toFile()
        try {
            extractCollection(apkgFile, collectionFile)
            SQLiteDatabase.openDatabase(
                collectionFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                val deckNames = deckNames(db)
                val cards = ArrayList<ContentCardEntity>()
                var notesRead = 0
                db.rawQuery(
                    """
                    SELECT n.id AS nid, n.flds AS flds,
                           (SELECT c.did FROM cards c WHERE c.nid = n.id ORDER BY c.ord LIMIT 1) AS did
                    FROM notes n
                    ORDER BY n.id
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    val fldsIndex = cursor.getColumnIndexOrThrow("flds")
                    val didIndex = cursor.getColumnIndexOrThrow("did")
                    while (cursor.moveToNext()) {
                        notesRead += 1
                        val fields = (cursor.getString(fldsIndex) ?: "").split(FIELD_SEP)
                        if (fields.size < 2) {
                            continue
                        }
                        val front = fields[0]
                        val back = fields[1]
                        if (front.trim().isEmpty() || back.trim().isEmpty()) {
                            continue
                        }
                        val sourceDeck = if (cursor.isNull(didIndex)) {
                            null
                        } else {
                            deckNames[cursor.getLong(didIndex)]
                        }
                        cards += ContentCardEntity(
                            uid = com.local.spacedcards.core.Uid.cardUid(front),
                            front = front,
                            back = back,
                            sourceDeck = sourceDeck,
                        )
                    }
                }

                if (cards.isEmpty()) {
                    throw ApkgImportException(
                        reason = ApkgImportException.Reason.NO_USABLE_NOTES,
                        detail = "${apkgFile.name}: no usable notes found. Verify that the v3 export uses collection.anki21b.",
                    )
                }

                val dedupedCards = LinkedHashMap<String, ContentCardEntity>()
                cards.forEach { card -> dedupedCards.putIfAbsent(card.uid, card) }
                return ParsedApkg(
                    sourceName = sourceName,
                    suggestedSectionName = suggestedSectionName(
                        cards = cards,
                        fallbackName = sourceName.removeSuffix(".apkg"),
                    ),
                    notesRead = notesRead,
                    cards = dedupedCards.values.toList(),
                )
            }
        } finally {
            collectionFile.delete()
        }
    }

    private fun copyUriToTempFile(uri: Uri, suffix: String): File {
        val out = createTempFile("spacedcards-import-", suffix).toFile()
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ApkgImportException(reason = ApkgImportException.Reason.OPEN_FAILED)
        return out
    }

    private fun extractCollection(apkgFile: File, outFile: File) {
        ZipFile(apkgFile).use { zip ->
            val candidate = DB_CANDIDATES.firstOrNull { zip.getEntry(it.name) != null }
                ?: throw ApkgImportException(
                    reason = ApkgImportException.Reason.MISSING_COLLECTION_DB,
                    detail = "${apkgFile.name}: no collection database found in the zip.",
                )
            val entry = zip.getEntry(candidate.name)
                ?: throw ApkgImportException(
                    reason = ApkgImportException.Reason.MISSING_COLLECTION_ENTRY,
                    detail = "${apkgFile.name}: entry ${candidate.name} not found.",
                )

            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output ->
                    if (candidate.compressed) {
                        ZstdInputStream(input).use { zstd -> zstd.copyTo(output) }
                    } else {
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun deckNames(db: SQLiteDatabase): Map<Long, String> {
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }
        if ("decks" in tables) {
            val names = mutableMapOf<Long, String>()
            db.rawQuery("SELECT id, name FROM decks", null).use { cursor ->
                while (cursor.moveToNext()) {
                    names[cursor.getLong(0)] = cursor.getString(1).replace(FIELD_SEP, "::")
                }
            }
            return names
        }

        db.rawQuery("SELECT decks FROM col LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) {
                return emptyMap()
            }
            val rawDecks = cursor.getString(0) ?: return emptyMap()
            val json = JSONObject(rawDecks)
            val names = mutableMapOf<Long, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optJSONObject(key) ?: continue
                names[key.toLong()] = value.optString("name", "")
            }
            return names
        }
    }

    private fun wrapFailure(error: Throwable): Throwable = when (error) {
        is ApkgImportException -> error
        is IOException -> ApkgImportException(
            reason = ApkgImportException.Reason.IO_ERROR,
            detail = "I/O error while importing the .apkg file.",
            cause = error,
        )
        else -> ApkgImportException(
            reason = ApkgImportException.Reason.IMPORT_FAILED,
            detail = "Import .apkg failed: ${error.message ?: error::class.java.simpleName}",
            cause = error,
        )
    }

    private fun suggestedSectionName(
        cards: List<ContentCardEntity>,
        fallbackName: String,
    ): String {
        val mostFrequentDeck = cards
            .asSequence()
            .mapNotNull { it.sourceDeck?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }).thenBy { it.key.length })
            ?.key
        return mostFrequentDeck ?: fallbackName.ifBlank { "Imported section" }
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

    private companion object {
        private const val FIELD_SEP = "\u001F"

        private data class DbCandidate(
            val name: String,
            val compressed: Boolean,
        )

        private val DB_CANDIDATES = listOf(
            DbCandidate(name = "collection.anki21b", compressed = true),
            DbCandidate(name = "collection.anki21", compressed = false),
            DbCandidate(name = "collection.anki2", compressed = false),
        )

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

private fun ParsedApkg.toPreview(): ApkgImportPreview = ApkgImportPreview(
    sourceName = sourceName,
    suggestedSectionName = suggestedSectionName,
    detectedCardCount = cards.size,
)
