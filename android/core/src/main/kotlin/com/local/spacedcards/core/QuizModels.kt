package com.local.spacedcards.core

/**
 * Il contratto fra chi legge il `.qzd` e chi monta la domanda.
 *
 * Qui non si normalizza niente. La ricetta di confronto vive nel baker
 * (`norm_for_compare`) e il suo risultato viaggia gia' cotto dentro il
 * pacchetto, in `core_norm`: il telefono confronta stringhe gia' normalizzate
 * e uid, non ricalcola. E' la stessa ragione per cui l'uid e' congelato --
 * due implementazioni della stessa regola divergono, prima o poi, e qui
 * divergere significa mostrare la risposta giusta fra quelle sbagliate.
 */
data class QuizCandidate(
    /** Testo mostrato all'utente. */
    val text: String,
    /** `core_norm` della card da cui viene, per il confronto con la risposta. */
    val norm: String,
    /** uid della card di provenienza, se e' la risposta di un'altra card. */
    val fromCardUid: String? = null,
)

data class QuizCard(
    val uid: String,
    val front: String,
    val answerCore: String,
    val answerNote: String?,
    val answerType: String,
    /** `core_norm` della risposta corretta di QUESTA card. */
    val answerNorm: String,
    /** Distrattori cotti dal baker: gia' validati, si usano come sono. */
    val distractors: List<String>,
    /** Risposte di card vicine, in ordine di somiglianza: il ripiego (C4). */
    val siblings: List<QuizCandidate>,
    /** uid di card la cui risposta risponde anche a questa domanda (C10). */
    val excludedCardUids: Set<String>,
)

data class QuizQuestion(
    val cardUid: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
) {
    val correctOption: String get() = options[correctIndex]
}
