package com.local.spacedcards.core

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class QuizAssemblerTest {
    @Test
    fun assembleIncludesCorrectAnswerExactlyOnce() {
        val question = assertNotNull(
            QuizAssembler.assemble(
                card = quizCard(
                    distractors = listOf("Rome", "Madrid", "Berlin"),
                ),
                random = Random(7),
            ),
        )

        assertEquals(1, question.options.count { it == "Paris" })
        assertEquals("Paris", question.options[question.correctIndex])
        assertEquals(4, question.options.size)
    }

    @Test
    fun siblingFromExcludedCardUidNeverAppearsAmongOptions() {
        val question = assertNotNull(
            QuizAssembler.assemble(
                card = quizCard(
                    siblings = listOf(
                        QuizCandidate(
                            text = "Lione",
                            norm = "lione",
                            fromCardUid = "card-excluded",
                        ),
                        QuizCandidate(
                            text = "Roma",
                            norm = "roma",
                            fromCardUid = "card-valid",
                        ),
                    ),
                    excludedCardUids = setOf("card-excluded"),
                ),
                random = Random(3),
            ),
        )

        assertTrue("Lione" !in question.options)
        assertTrue("Roma" in question.options)
    }

    @Test
    fun siblingWithSameNormAsAnswerDoesNotAppear() {
        val question = assertNotNull(
            QuizAssembler.assemble(
                card = quizCard(
                    siblings = listOf(
                        QuizCandidate(
                            text = "Paris, France",
                            norm = "paris",
                            fromCardUid = "card-same-norm",
                        ),
                        QuizCandidate(
                            text = "Roma",
                            norm = "roma",
                            fromCardUid = "card-valid",
                        ),
                    ),
                ),
                random = Random(5),
            ),
        )

        assertTrue("Paris, France" !in question.options)
        assertTrue("Roma" in question.options)
    }

    @Test
    fun assembleReturnsNullWhenNoWrongOptionSurvives() {
        val question = QuizAssembler.assemble(
            card = quizCard(
                siblings = listOf(
                    QuizCandidate(
                        text = "Paris, France",
                        norm = "paris",
                        fromCardUid = "card-same-norm",
                    ),
                    QuizCandidate(
                        text = "Lione",
                        norm = "lione",
                        fromCardUid = "card-excluded",
                    ),
                ),
                excludedCardUids = setOf("card-excluded"),
            ),
            random = Random(11),
        )

        assertNull(question)
    }

    @Test
    fun assembleFallsBackToTwoOptionsWhenOnlyOneWrongOptionExists() {
        val question = assertNotNull(
            QuizAssembler.assemble(
                card = quizCard(
                    distractors = listOf("Rome"),
                ),
                random = Random(13),
            ),
        )

        assertEquals(2, question.options.size)
        assertTrue("Paris" in question.options)
        assertTrue("Rome" in question.options)
    }

    @Test
    fun sameSeedProducesSameResultAndDifferentSeedCanChangeOrder() {
        val cards = listOf(
            quizCard(
                uid = "card-1",
                distractors = listOf("Rome", "Madrid", "Berlin"),
            ),
            quizCard(
                uid = "card-2",
                front = "Capital of Spain?",
                answerCore = "Madrid",
                answerNorm = "madrid",
                distractors = listOf("Rome", "Paris", "Berlin"),
            ),
            quizCard(
                uid = "card-3",
                front = "Capital of Germany?",
                answerCore = "Berlin",
                answerNorm = "berlin",
                distractors = listOf("Rome", "Paris", "Madrid"),
            ),
        )

        val first = QuizAssembler.assembleAll(cards, random = Random(17))
        val second = QuizAssembler.assembleAll(cards, random = Random(17))
        val differentSeed = QuizAssembler.assembleAll(cards, random = Random(23))

        assertEquals(first, second)
        assertNotEquals(first, differentSeed)
    }

    private fun quizCard(
        uid: String = "card-1",
        front: String = "Capital of France?",
        answerCore: String = "Paris",
        answerNorm: String = "paris",
        distractors: List<String> = emptyList(),
        siblings: List<QuizCandidate> = emptyList(),
        excludedCardUids: Set<String> = emptySet(),
    ): QuizCard =
        QuizCard(
            uid = uid,
            front = front,
            answerCore = answerCore,
            answerNote = null,
            answerType = "basic",
            answerNorm = answerNorm,
            distractors = distractors,
            siblings = siblings,
            excludedCardUids = excludedCardUids,
        )
}
