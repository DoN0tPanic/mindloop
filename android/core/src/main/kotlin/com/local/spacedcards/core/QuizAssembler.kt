package com.local.spacedcards.core

import kotlin.random.Random

object QuizAssembler {
    fun assemble(
        card: QuizCard,
        optionCount: Int = 4,
        random: Random,
    ): QuizQuestion? {
        val totalOptions = optionCount.coerceAtLeast(2)
        val maxWrongOptions = totalOptions - 1
        val correctKey = optionKey(card.answerCore)
        val seenKeys = linkedSetOf(correctKey)
        val wrongOptions = ArrayList<String>(maxWrongOptions)

        fun addWrongOption(text: String) {
            if (wrongOptions.size >= maxWrongOptions) {
                return
            }
            val key = optionKey(text)
            if (key == correctKey || !seenKeys.add(key)) {
                return
            }
            wrongOptions += text
        }

        // Il pool viene mescolato prima di sceglierne tre.
        //
        // Il baker ne produce piu' di quante ne servano (sei per card, dove il
        // modello collabora). Prendendo sempre le prime tre nell'ordine di
        // arrivo, le altre non si vedevano mai: riaprendo lo stesso quiz le
        // opzioni sbagliate erano identiche, solo in posizione diversa, e
        // bastava ricordare "non quella" invece di sapere la risposta.
        // Mescolando, ogni ripasso pesca una combinazione diversa.
        card.distractors.shuffled(random).forEach(::addWrongOption)

        if (wrongOptions.size < maxWrongOptions) {
            // I vicini restano nell'ordine di somiglianza: sono il ripiego di
            // quando il pool e' povero, e li' conta prendere i piu' credibili,
            // non variare.
            card.siblings.forEach { sibling ->
                if (wrongOptions.size >= maxWrongOptions) {
                    return@forEach
                }
                if (sibling.fromCardUid != null && sibling.fromCardUid in card.excludedCardUids) {
                    return@forEach
                }
                if (sibling.norm == card.answerNorm) {
                    return@forEach
                }
                addWrongOption(sibling.text)
            }
        }

        if (wrongOptions.isEmpty()) {
            return null
        }

        val shuffledOptions = buildList {
            add(card.answerCore to true)
            wrongOptions.forEach { add(it to false) }
        }.shuffled(random)

        return QuizQuestion(
            cardUid = card.uid,
            prompt = card.front,
            options = shuffledOptions.map { it.first },
            correctIndex = shuffledOptions.indexOfFirst { it.second },
        )
    }

    fun assembleAll(
        cards: List<QuizCard>,
        optionCount: Int = 4,
        random: Random,
    ): List<QuizQuestion> =
        cards
            .mapNotNull { assemble(card = it, optionCount = optionCount, random = random) }
            .shuffled(random)

    private fun optionKey(text: String): String = text.trim().lowercase()
}
