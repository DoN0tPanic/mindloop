package com.local.spacedcards.data.quiz

data class QuizPackSummary(
    val packUid: String,
    val name: String,
    val cardCount: Int,
    val sectionCount: Int,
    val bakedAt: String?,
    val importedAt: Long,
    val schemaVersion: Int,
    val bakerVersion: String,
    val llmModel: String?,
    val contentPath: String,
    val raccoltaUid: String?,
)

data class ImportedQuizPackPayload(
    val packUid: String,
    val packName: String,
    val cardCount: Int,
    val sectionCount: Int,
    val cards: List<QuizPackStudyCard>,
    val sections: List<QuizPackStudySection>,
)

data class QuizPackStudyCard(
    val uid: String,
    val front: String,
    val back: String,
)

data class QuizPackStudySection(
    val name: String,
    val srcName: String?,
    val importedAt: Long,
    val cardUids: List<String>,
)

sealed interface QzdImportError {
    val message: String

    object OpenFailed : QzdImportError {
        override val message: String =
            "Impossibile aprire il file .qzd dal selettore documenti."
    }

    object NotAZip : QzdImportError {
        override val message: String =
            "Il file selezionato non e' un archivio .qzd valido."
    }

    data class MissingEntry(val name: String) : QzdImportError {
        override val message: String =
            "Pacchetto .qzd incompleto: manca l'entry $name."
    }

    data class UnsupportedSchema(val found: Int) : QzdImportError {
        override val message: String =
            "schema_version=$found non supportato: l'app legge solo pacchetti .qzd v1."
    }

    data class InvalidManifest(val detail: String) : QzdImportError {
        override val message: String =
            "manifest.json non valido: $detail"
    }

    data class InvalidContentDatabase(val detail: String) : QzdImportError {
        override val message: String =
            "content.sqlite non compatibile: $detail"
    }

    data class PackNotFound(val packUid: String) : QzdImportError {
        override val message: String =
            "Pacchetto quiz $packUid non trovato."
    }

    data class StorageFailure(
        val detail: String,
        val rootCause: Throwable? = null,
    ) : QzdImportError {
        override val message: String = detail
    }

    data class Corrupt(
        val detail: String,
        val rootCause: Throwable? = null,
    ) : QzdImportError {
        override val message: String = detail
    }
}

class QzdImportException(
    val error: QzdImportError,
    cause: Throwable? = error.rootCauseOrNull(),
) : Exception(error.message, cause)

internal data class ImportedQuizPack(
    val packUid: String,
    val name: String,
    val cardCount: Int,
    val sectionCount: Int,
    val sectionsJson: String,
    val bakedAt: String?,
    val importedAt: Long,
    val schemaVersion: Int,
    val bakerVersion: String,
    val llmModel: String?,
    val contentPath: String,
)

private fun QzdImportError.rootCauseOrNull(): Throwable? = when (this) {
    is QzdImportError.Corrupt -> rootCause
    is QzdImportError.StorageFailure -> rootCause
    else -> null
}
