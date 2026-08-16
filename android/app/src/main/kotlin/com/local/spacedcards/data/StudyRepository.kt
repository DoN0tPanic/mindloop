package com.local.spacedcards.data

import android.net.Uri
import com.local.spacedcards.core.Grade
import com.local.spacedcards.core.Scheduler
import com.local.spacedcards.core.SimpleScheduler
import com.local.spacedcards.core.Uid
import com.local.spacedcards.data.content.CardSezioneEntity
import com.local.spacedcards.data.content.ContentCardEntity
import com.local.spacedcards.data.content.ContentDb
import com.local.spacedcards.data.content.RaccoltaEntity
import com.local.spacedcards.data.content.SezioneEntity
import com.local.spacedcards.data.importer.ApkgImportPreview
import com.local.spacedcards.data.importer.ApkgImporter
import com.local.spacedcards.data.quiz.ImportedQuizPackPayload
import com.local.spacedcards.data.quiz.QuizPackStudySection
import com.local.spacedcards.data.state.KnownThisPassEntity
import com.local.spacedcards.data.state.ReviewLogEntity
import com.local.spacedcards.data.state.StateDb
import com.local.spacedcards.data.state.toCore
import com.local.spacedcards.data.state.toEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StudyImportSummary(
    val notesRead: Int,
    val imported: Int,
)

data class StudyImportPreview(
    val sourceName: String,
    val suggestedSectionName: String,
    val detectedCardCount: Int,
)

data class RaccoltaSummary(
    val uid: String,
    val name: String,
    val totalCards: Int,
    val knownCount: Int,
)

data class SezioneInfo(
    val uid: String,
    val name: String,
    val srcName: String?,
    val importedAt: Long,
    val cardCount: Int,
)

data class ReviewCard(
    val uid: String,
    val front: String,
    val back: String,
    val sourceDeck: String?,
)

data class CollectionCardPayload(
    val uid: String,
    val front: String,
    val back: String,
)

data class RaccoltaRef(
    val uid: String,
    val name: String,
)

class StudyRepository(
    private val contentDb: ContentDb,
    private val stateDb: StateDb,
    private val importer: ApkgImporter,
    private val scheduler: Scheduler = SimpleScheduler(),
) {
    suspend fun previewApkg(uri: Uri): StudyImportPreview = withContext(Dispatchers.IO) {
        importer.preview(uri).toStudyPreview()
    }

    suspend fun listRaccolte(): List<RaccoltaSummary> = withContext(Dispatchers.IO) {
        val cardDao = contentDb.contentCardDao()
        val knownDao = stateDb.knownPassDao()
        contentDb.raccoltaDao().listRaccolte().map { raccolta ->
            val cardUids = cardDao.cardUidsInRaccolta(raccolta.uid)
            val known = knownDao.getAllKnownUids(raccolta.uid).toHashSet()
            RaccoltaSummary(
                uid = raccolta.uid,
                name = raccolta.name,
                totalCards = cardUids.size,
                knownCount = cardUids.count { it in known },
            )
        }
    }

    suspend fun createRaccolta(name: String): String = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Collection name cannot be blank." }
        val uid = Uid.containerUid()
        contentDb.raccoltaDao().createRaccolta(
            RaccoltaEntity(
                uid = uid,
                name = normalizedName,
                createdAt = System.currentTimeMillis(),
            ),
        )
        uid
    }

    suspend fun findBestMatchingRaccolta(
        cardUids: Set<String>,
        preferredRaccoltaUid: String? = null,
    ): RaccoltaRef? = withContext(Dispatchers.IO) {
        if (cardUids.isEmpty()) {
            return@withContext null
        }

        val packCardCount = cardUids.size.toDouble()
        val cardDao = contentDb.contentCardDao()
        contentDb.raccoltaDao().listRaccolte()
            .map { raccolta ->
                val overlapCount = cardDao.cardUidsInRaccolta(raccolta.uid).count { it in cardUids }
                MatchCandidate(
                    uid = raccolta.uid,
                    name = raccolta.name,
                    overlapCount = overlapCount,
                )
            }
            .filter { candidate ->
                candidate.overlapCount > 0 &&
                    (candidate.overlapCount.toDouble() / packCardCount) >= 0.5
            }
            .sortedWith(
                compareByDescending<MatchCandidate> { it.overlapCount }
                    .thenByDescending { if (it.uid == preferredRaccoltaUid) 1 else 0 }
                    .thenBy { it.name.lowercase() },
            )
            .firstOrNull()
            ?.let { RaccoltaRef(uid = it.uid, name = it.name) }
    }

    suspend fun createRaccoltaFromQuizPack(payload: ImportedQuizPackPayload): RaccoltaRef =
        withContext(Dispatchers.IO) {
            val normalizedName = payload.packName.trim()
            require(normalizedName.isNotEmpty()) { "Collection name cannot be blank." }

            val raccoltaUid = Uid.containerUid()
            val cardsByUid = payload.cards
                .distinctBy { it.uid }
                .associate { card ->
                    card.uid to ContentCardEntity(
                        uid = card.uid,
                        front = card.front,
                        back = card.back,
                        sourceDeck = null,
                    )
                }

            val sectionPlans = buildSectionPlans(
                normalizedName = normalizedName,
                sections = payload.sections,
                cardUids = cardsByUid.keys,
            )

            contentDb.withTransaction {
                contentDb.raccoltaDao().createRaccolta(
                    RaccoltaEntity(
                        uid = raccoltaUid,
                        name = normalizedName,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                if (cardsByUid.isNotEmpty()) {
                    contentDb.contentCardDao().insertAll(cardsByUid.values.toList())
                }
                sectionPlans.forEach { section ->
                    val sectionUid = Uid.containerUid()
                    contentDb.raccoltaDao().insertSezione(
                        SezioneEntity(
                            uid = sectionUid,
                            raccoltaUid = raccoltaUid,
                            name = section.name,
                            srcName = section.srcName,
                            importedAt = section.importedAt,
                            cardCount = section.cardUids.size,
                        ),
                    )
                    contentDb.raccoltaDao().insertCardSezioni(
                        section.cardUids.map { cardUid ->
                            CardSezioneEntity(
                                cardUid = cardUid,
                                sezioneUid = sectionUid,
                            )
                        },
                    )
                }
            }

            RaccoltaRef(uid = raccoltaUid, name = normalizedName)
        }

    suspend fun importApkg(
        uri: Uri,
        raccoltaUid: String,
        sezioneName: String?,
    ): StudyImportSummary = withContext(Dispatchers.IO) {
        val parsed = importer.read(uri)
        val sectionUid = Uid.containerUid()
        val normalizedSectionName = sezioneName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: parsed.suggestedSectionName
        var imported = 0
        contentDb.withTransaction {
            imported = contentDb.contentCardDao().insertAll(parsed.cards).count { it != -1L }
            contentDb.raccoltaDao().insertSezione(
                SezioneEntity(
                    uid = sectionUid,
                    raccoltaUid = raccoltaUid,
                    name = normalizedSectionName,
                    srcName = parsed.sourceName,
                    importedAt = System.currentTimeMillis(),
                    cardCount = 0,
                ),
            )
            contentDb.raccoltaDao().insertCardSezioni(
                parsed.cards.map { card ->
                    CardSezioneEntity(cardUid = card.uid, sezioneUid = sectionUid)
                },
            )
            contentDb.raccoltaDao().updateSezioneCardCount(sectionUid, parsed.cards.size)
        }
        StudyImportSummary(
            notesRead = parsed.notesRead,
            imported = imported,
        )
    }

    suspend fun sezioniInRaccolta(raccoltaUid: String): List<SezioneInfo> = withContext(Dispatchers.IO) {
        contentDb.raccoltaDao().sezioniInRaccolta(raccoltaUid).map { it.toInfo() }
    }

    suspend fun removeSezione(sezioneUid: String) = withContext(Dispatchers.IO) {
        contentDb.raccoltaDao().deleteSezione(sezioneUid)
    }

    suspend fun totalCards(raccoltaUid: String): Int = withContext(Dispatchers.IO) {
        contentDb.contentCardDao().cardCountInRaccolta(raccoltaUid)
    }

    suspend fun cardsInRaccolta(raccoltaUid: String): List<CollectionCardPayload> = withContext(Dispatchers.IO) {
        contentDb.contentCardDao().cardsInRaccolta(raccoltaUid).map { card ->
            CollectionCardPayload(
                uid = card.uid,
                front = card.front,
                back = card.back,
            )
        }
    }

    suspend fun knownCount(raccoltaUid: String): Int = withContext(Dispatchers.IO) {
        val cardUids = contentDb.contentCardDao().cardUidsInRaccolta(raccoltaUid)
        val known = stateDb.knownPassDao().getAllKnownUids(raccoltaUid).toHashSet()
        cardUids.count { it in known }
    }

    suspend fun nextDueCard(
        raccoltaUid: String,
        excludeUid: String? = null,
    ): ReviewCard? = withContext(Dispatchers.IO) {
        val cards = contentDb.contentCardDao().cardsInRaccolta(raccoltaUid)
        if (cards.isEmpty()) {
            return@withContext null
        }

        val known = stateDb.knownPassDao().getAllKnownUids(raccoltaUid).toSet()
        val pool = cards.filterNot { it.uid in known }
        if (pool.isEmpty()) {
            return@withContext null
        }

        // Si mostra sempre la card vista meno di recente, e chi non l'ha mai
        // vista viene prima di tutte.
        //
        // Prima si prendeva la prima del mazzo escludendo solo quella appena
        // valutata: marcando "ancora" due card di fila si finiva a rimbalzare
        // fra quelle due all'infinito, perche' la prima tornava utilizzabile
        // non appena l'esclusione si spostava sulla seconda. Le altre card del
        // mazzo non arrivavano mai.
        //
        // Ordinando per ultimo ripasso, una card appena valutata diventa la
        // piu' recente e finisce in fondo al giro: torna solo dopo che sono
        // passate le altre, che e' esattamente cio' che serve per ripassare.
        val ultimoRipasso = stateDb.reviewStateDao().getAll().associate { it.cardUid to it.lastReview }
        val inOrdine = pool.sortedBy { ultimoRipasso[it.uid] ?: Long.MIN_VALUE }

        val candidates = inOrdine.filterNot { it.uid == excludeUid }
        (candidates.ifEmpty { inOrdine }).first().toReviewCard()
    }

    suspend fun review(
        raccoltaUid: String,
        cardUid: String,
        grade: Grade,
        elapsedMs: Long,
        now: Long,
    ) = withContext(Dispatchers.IO) {
        val dao = stateDb.reviewStateDao()
        val current = dao.get(cardUid)?.toCore()
        val updated = scheduler.review(current, grade, now)
        dao.upsert(updated.toEntity(cardUid))
        dao.insertLog(
            ReviewLogEntity(
                cardUid = cardUid,
                ts = now,
                grade = grade.name,
                elapsedMs = elapsedMs,
            ),
        )
        if (grade == Grade.GOOD) {
            stateDb.knownPassDao().markKnown(
                KnownThisPassEntity(
                    cardUid = cardUid,
                    raccoltaUid = raccoltaUid,
                ),
            )
        }
    }

    suspend fun resetPass(raccoltaUid: String) = withContext(Dispatchers.IO) {
        stateDb.knownPassDao().clearAll(raccoltaUid)
    }

    private fun ContentCardEntity.toReviewCard(): ReviewCard = ReviewCard(
        uid = uid,
        front = front,
        back = back,
        sourceDeck = sourceDeck,
    )

    private fun SezioneEntity.toInfo(): SezioneInfo = SezioneInfo(
        uid = uid,
        name = name,
        srcName = srcName,
        importedAt = importedAt,
        cardCount = cardCount,
    )

    private fun buildSectionPlans(
        normalizedName: String,
        sections: List<QuizPackStudySection>,
        cardUids: Set<String>,
    ): List<QuizSectionPlan> {
        val plannedSections = sections.mapNotNull { section ->
            val linkedCardUids = section.cardUids
                .filter { it in cardUids }
                .distinct()
            if (linkedCardUids.isEmpty()) {
                null
            } else {
                QuizSectionPlan(
                    name = section.name,
                    srcName = section.srcName,
                    importedAt = section.importedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    cardUids = linkedCardUids,
                )
            }
        }.toMutableList()

        val assignedCardUids = plannedSections
            .flatMapTo(linkedSetOf()) { it.cardUids }
        val unassignedCardUids = cardUids
            .filterNot { it in assignedCardUids }

        if (unassignedCardUids.isNotEmpty()) {
            plannedSections += QuizSectionPlan(
                name = normalizedName,
                srcName = null,
                importedAt = System.currentTimeMillis(),
                cardUids = unassignedCardUids,
            )
        }

        return plannedSections
    }

    private data class MatchCandidate(
        val uid: String,
        val name: String,
        val overlapCount: Int,
    )

    private data class QuizSectionPlan(
        val name: String,
        val srcName: String?,
        val importedAt: Long,
        val cardUids: List<String>,
    )
}

private fun ApkgImportPreview.toStudyPreview(): StudyImportPreview = StudyImportPreview(
    sourceName = sourceName,
    suggestedSectionName = suggestedSectionName,
    detectedCardCount = detectedCardCount,
)
