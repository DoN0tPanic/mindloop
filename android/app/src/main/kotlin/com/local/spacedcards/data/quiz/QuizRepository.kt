package com.local.spacedcards.data.quiz

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.net.Uri
import com.local.spacedcards.core.QuizCandidate
import com.local.spacedcards.core.QuizCard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuizRepository(
    private val packStore: QuizPackStore,
    private val importer: QzdImporter,
) {
    constructor(context: Context) : this(
        packStore = QuizPackStore(context.applicationContext),
        importer = QzdImporter(context.applicationContext),
    )

    suspend fun importQzd(uri: Uri): ImportedQuizPackPayload = withContext(Dispatchers.IO) {
        try {
            val imported = importer.import(uri)
            val entity = packStore.upsertPack(imported)
            loadStudyPayload(entity)
        } catch (error: Throwable) {
            throw error.asQuizError()
        }
    }

    suspend fun attachPackToRaccolta(packUid: String, raccoltaUid: String) = withContext(Dispatchers.IO) {
        try {
            packStore.attachPackToRaccolta(packUid, raccoltaUid)
        } catch (error: Throwable) {
            throw error.asQuizError()
        }
    }

    suspend fun listPacks(): List<QuizPackSummary> = withContext(Dispatchers.IO) {
        packStore.listPacks().map { it.toSummary() }
    }

    suspend fun deletePack(packUid: String) = withContext(Dispatchers.IO) {
        try {
            packStore.deletePack(packUid)
        } catch (error: Throwable) {
            throw error.asQuizError()
        }
    }

    suspend fun loadQuizCards(packUid: String): List<QuizCard> = withContext(Dispatchers.IO) {
        val pack = try {
            packStore.requirePack(packUid)
        } catch (error: Throwable) {
            throw error.asQuizError()
        }

        openPackDatabase(
            packUid = pack.packUid,
            sqliteFile = File(pack.contentPath),
        ) { db ->
            val cardRows = loadCardRows(db)
            val validUids = cardRows.mapTo(LinkedHashSet(cardRows.size)) { it.uid }

            // Si leggono le tabelle in blocco e si raggruppa in memoria:
            // cosi' si evita una query per card (N+1) anche su pacchetti piu' grandi.
            val distractorsByCard = loadDistractors(db, validUids)
            val siblingsByCard = loadSiblings(db, validUids)
            val exclusionsByCard = loadExclusions(db, validUids)

            cardRows.map { row ->
                QuizCard(
                    uid = row.uid,
                    front = row.front,
                    answerCore = row.answerCore,
                    answerNote = row.answerNote,
                    answerType = row.answerType,
                    answerNorm = row.answerNorm,
                    distractors = distractorsByCard[row.uid].orEmpty(),
                    siblings = siblingsByCard[row.uid].orEmpty(),
                    excludedCardUids = exclusionsByCard[row.uid] ?: emptySet(),
                )
            }
        }
    }

    private fun loadStudyPayload(pack: QuizPackEntity): ImportedQuizPackPayload =
        openPackDatabase(
            packUid = pack.packUid,
            sqliteFile = File(pack.contentPath),
        ) { db ->
            val cards = loadStudyCards(db)
            val validCardUids = cards.mapTo(LinkedHashSet(cards.size)) { it.uid }
            val sections = loadStudySections(db, validCardUids)

            ImportedQuizPackPayload(
                packUid = pack.packUid,
                packName = pack.name,
                cardCount = pack.cardCount,
                sectionCount = pack.sectionCount,
                cards = cards,
                sections = sections,
            )
        }

    private fun <T> openPackDatabase(
        packUid: String,
        sqliteFile: File,
        block: (SQLiteDatabase) -> T,
    ): T {
        if (!sqliteFile.isFile) {
            throw QzdImportException(
                QzdImportError.StorageFailure(
                    detail = "content.sqlite mancante su disco per il pacchetto $packUid.",
                ),
            )
        }

        return try {
            SQLiteDatabase.openDatabase(
                sqliteFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use(block)
        } catch (error: QzdImportException) {
            throw error
        } catch (error: SQLiteDatabaseCorruptException) {
            throw QzdImportException(
                QzdImportError.Corrupt(
                    detail = "Il pacchetto $packUid contiene un content.sqlite corrotto o troncato.",
                    rootCause = error,
                ),
                error,
            )
        } catch (error: SQLiteCantOpenDatabaseException) {
            throw QzdImportException(
                QzdImportError.Corrupt(
                    detail = "Il pacchetto $packUid non ha un content.sqlite leggibile.",
                    rootCause = error,
                ),
                error,
            )
        } catch (error: SQLiteException) {
            throw QzdImportException(
                QzdImportError.InvalidContentDatabase(
                    detail = error.message ?: error::class.java.simpleName,
                ),
                error,
            )
        }
    }

    private fun loadCardRows(db: SQLiteDatabase): List<QuizCardRow> =
        db.rawQuery(
            """
            SELECT uid, front, answer_core, answer_note, answer_type, core_norm, source_ord
            FROM card
            ORDER BY
                CASE WHEN source_ord IS NULL THEN 1 ELSE 0 END,
                source_ord ASC,
                uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val rows = ArrayList<QuizCardRow>(cursor.count.coerceAtLeast(0))
            val uidIndex = cursor.getColumnIndexOrThrow("uid")
            val frontIndex = cursor.getColumnIndexOrThrow("front")
            val answerCoreIndex = cursor.getColumnIndexOrThrow("answer_core")
            val answerNoteIndex = cursor.getColumnIndexOrThrow("answer_note")
            val answerTypeIndex = cursor.getColumnIndexOrThrow("answer_type")
            val answerNormIndex = cursor.getColumnIndexOrThrow("core_norm")

            while (cursor.moveToNext()) {
                rows += QuizCardRow(
                    uid = cursor.getString(uidIndex),
                    front = cursor.getString(frontIndex),
                    answerCore = cursor.getString(answerCoreIndex),
                    answerNote = cursor.getStringOrNull(answerNoteIndex),
                    answerType = cursor.getString(answerTypeIndex),
                    answerNorm = cursor.getString(answerNormIndex),
                )
            }
            rows
        }

    private fun loadStudyCards(db: SQLiteDatabase): List<QuizPackStudyCard> =
        db.rawQuery(
            """
            SELECT uid, front, back, source_ord
            FROM card
            ORDER BY
                CASE WHEN source_ord IS NULL THEN 1 ELSE 0 END,
                source_ord ASC,
                uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val cards = ArrayList<QuizPackStudyCard>(cursor.count.coerceAtLeast(0))
            val uidIndex = cursor.getColumnIndexOrThrow("uid")
            val frontIndex = cursor.getColumnIndexOrThrow("front")
            val backIndex = cursor.getColumnIndexOrThrow("back")

            while (cursor.moveToNext()) {
                cards += QuizPackStudyCard(
                    uid = cursor.getString(uidIndex),
                    front = cursor.getString(frontIndex),
                    back = cursor.getString(backIndex),
                )
            }
            cards
        }

    private fun loadStudySections(
        db: SQLiteDatabase,
        validCardUids: Set<String>,
    ): List<QuizPackStudySection> {
        val sectionsByUid = LinkedHashMap<String, StudySectionBuilder>()
        db.rawQuery(
            """
            SELECT uid, name, src_name, imported_at
            FROM sezione
            ORDER BY imported_at DESC, name COLLATE NOCASE ASC, uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val uidIndex = cursor.getColumnIndexOrThrow("uid")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val srcNameIndex = cursor.getColumnIndexOrThrow("src_name")
            val importedAtIndex = cursor.getColumnIndexOrThrow("imported_at")

            while (cursor.moveToNext()) {
                val uid = cursor.getString(uidIndex)
                sectionsByUid[uid] = StudySectionBuilder(
                    name = cursor.getString(nameIndex),
                    srcName = cursor.getStringOrNull(srcNameIndex),
                    importedAt = cursor.getLong(importedAtIndex),
                )
            }
        }

        db.rawQuery(
            """
            SELECT card_uid, sezione_uid
            FROM card_sezione
            ORDER BY sezione_uid ASC, card_uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val cardUidIndex = cursor.getColumnIndexOrThrow("card_uid")
            val sectionUidIndex = cursor.getColumnIndexOrThrow("sezione_uid")

            while (cursor.moveToNext()) {
                val cardUid = cursor.getString(cardUidIndex)
                val sectionUid = cursor.getString(sectionUidIndex)
                ensureKnownCard(validCardUids, cardUid, "card_sezione.card_uid")
                val section = sectionsByUid[sectionUid] ?: throw QzdImportException(
                    QzdImportError.InvalidContentDatabase(
                        "card_sezione.sezione_uid=$sectionUid punta a una sezione assente.",
                    ),
                )
                section.cardUids += cardUid
            }
        }

        return sectionsByUid.values.map { section ->
            QuizPackStudySection(
                name = section.name,
                srcName = section.srcName,
                importedAt = section.importedAt,
                cardUids = section.cardUids.toList(),
            )
        }
    }

    private fun loadDistractors(
        db: SQLiteDatabase,
        validUids: Set<String>,
    ): Map<String, List<String>> =
        db.rawQuery(
            """
            SELECT card_uid, text
            FROM distractor
            ORDER BY
                card_uid ASC,
                CASE WHEN quality IS NULL THEN 1 ELSE 0 END ASC,
                quality DESC,
                text COLLATE NOCASE ASC,
                text ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val result = LinkedHashMap<String, MutableList<String>>()
            val cardUidIndex = cursor.getColumnIndexOrThrow("card_uid")
            val textIndex = cursor.getColumnIndexOrThrow("text")

            while (cursor.moveToNext()) {
                val cardUid = cursor.getString(cardUidIndex)
                ensureKnownCard(validUids, cardUid, "distractor.card_uid")
                result.getOrPut(cardUid) { ArrayList() }
                    .add(cursor.getString(textIndex))
            }
            result
        }

    private fun loadSiblings(
        db: SQLiteDatabase,
        validUids: Set<String>,
    ): Map<String, List<QuizCandidate>> =
        db.rawQuery(
            """
            SELECT
                n.card_uid,
                n.other_uid,
                c.answer_core,
                c.core_norm
            FROM neighbor n
            LEFT JOIN card c ON c.uid = n.other_uid
            ORDER BY n.card_uid ASC, n.rank ASC, n.other_uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val result = LinkedHashMap<String, MutableList<QuizCandidate>>()
            val cardUidIndex = cursor.getColumnIndexOrThrow("card_uid")
            val otherUidIndex = cursor.getColumnIndexOrThrow("other_uid")
            val answerCoreIndex = cursor.getColumnIndexOrThrow("answer_core")
            val answerNormIndex = cursor.getColumnIndexOrThrow("core_norm")

            while (cursor.moveToNext()) {
                val cardUid = cursor.getString(cardUidIndex)
                val otherUid = cursor.getString(otherUidIndex)
                ensureKnownCard(validUids, cardUid, "neighbor.card_uid")
                ensureKnownCard(validUids, otherUid, "neighbor.other_uid")

                val answerCore = cursor.getStringOrNull(answerCoreIndex)
                    ?: throw QzdImportException(
                        QzdImportError.InvalidContentDatabase(
                            "neighbor.other_uid=$otherUid non punta a una card valida.",
                        ),
                    )
                val answerNorm = cursor.getStringOrNull(answerNormIndex)
                    ?: throw QzdImportException(
                        QzdImportError.InvalidContentDatabase(
                            "neighbor.other_uid=$otherUid non ha core_norm disponibile.",
                        ),
                    )

                result.getOrPut(cardUid) { ArrayList() }
                    .add(
                        QuizCandidate(
                            text = answerCore,
                            norm = answerNorm,
                            fromCardUid = otherUid,
                        ),
                    )
            }
            result
        }

    private fun loadExclusions(
        db: SQLiteDatabase,
        validUids: Set<String>,
    ): Map<String, Set<String>> =
        db.rawQuery(
            """
            SELECT card_uid, other_uid
            FROM exclusion
            ORDER BY card_uid ASC, other_uid ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val result = LinkedHashMap<String, LinkedHashSet<String>>()
            val cardUidIndex = cursor.getColumnIndexOrThrow("card_uid")
            val otherUidIndex = cursor.getColumnIndexOrThrow("other_uid")

            while (cursor.moveToNext()) {
                val cardUid = cursor.getString(cardUidIndex)
                ensureKnownCard(validUids, cardUid, "exclusion.card_uid")
                result.getOrPut(cardUid) { LinkedHashSet() }
                    .add(cursor.getString(otherUidIndex))
            }
            result
        }

    private fun ensureKnownCard(
        validUids: Set<String>,
        cardUid: String,
        columnName: String,
    ) {
        if (cardUid !in validUids) {
            throw QzdImportException(
                QzdImportError.InvalidContentDatabase(
                    "$columnName=$cardUid punta a una card assente.",
                ),
            )
        }
    }

    private fun Throwable.asQuizError(): QzdImportException = when (this) {
        is QzdImportException -> this
        else -> QzdImportException(
            QzdImportError.Corrupt(
                detail = message ?: javaClass.simpleName,
                rootCause = this,
            ),
            this,
        )
    }

    private data class QuizCardRow(
        val uid: String,
        val front: String,
        val answerCore: String,
        val answerNote: String?,
        val answerType: String,
        val answerNorm: String,
    )

    private data class StudySectionBuilder(
        val name: String,
        val srcName: String?,
        val importedAt: Long,
        val cardUids: LinkedHashSet<String> = LinkedHashSet(),
    )

    private companion object {
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

        private fun Cursor.getStringOrNull(index: Int): String? =
            if (isNull(index)) {
                null
            } else {
                getString(index)
            }
    }
}
