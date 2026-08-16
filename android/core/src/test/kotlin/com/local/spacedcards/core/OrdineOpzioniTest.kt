package com.local.spacedcards.core

import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Test

/**
 * L'ordine in cui compaiono le opzioni deve cambiare fra una sessione e
 * l'altra: se la risposta giusta finisse sempre nello stesso posto, si
 * imparerebbe la posizione invece del contenuto.
 */
class OrdineOpzioniTest {

    private fun cardDiProva() = QuizCard(
        uid = "c1",
        front = "In the 5-layer TCP/IP model, what is Layer 5?",
        answerCore = "Application layer",
        answerNote = null,
        answerType = "term",
        answerNorm = "application layer",
        distractors = listOf(
            "Presentation layer", "Session layer", "Transport layer",
            "Physical layer", "Internet layer", "Data link layer",
        ),
        siblings = emptyList(),
        excludedCardUids = emptySet(),
    )

    @Test
    fun laPosizioneDellaRispostaGiustaCambiaTraSessioni() {
        val posizioni = (1..40).map { sessione ->
            val domanda = QuizAssembler.assemble(
                card = cardDiProva(),
                random = Random(sessione * 7919),   // un seme diverso per sessione
            )!!
            domanda.correctIndex
        }.toSet()

        assertTrue(
            posizioni.size > 1,
            "la risposta giusta finisce sempre in posizione ${posizioni.first()}",
        )
    }

    @Test
    fun leOpzioniSbagliateNonSonoSempreLeStesse() {
        val combinazioni = (1..40).map { sessione ->
            val domanda = QuizAssembler.assemble(
                card = cardDiProva(),
                random = Random(sessione * 7919),
            )!!
            (domanda.options - domanda.correctOption).toSet()
        }.toSet()

        assertTrue(
            combinazioni.size > 1,
            "con sei distrattori disponibili vengono mostrati sempre gli stessi tre: $combinazioni",
        )
    }
}
