package com.local.spacedcards.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class Grade { AGAIN, GOOD }

data class ReviewState(
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    val reps: Int,
    val lapses: Int,
    val lastReview: Long,
)

interface Scheduler {
    fun review(state: ReviewState?, grade: Grade, now: Long): ReviewState
}

/**
 * NON e' FSRS. E' un placeholder deliberato: PLAN.md par. 6.4 richiede di
 * verificare se esiste un port JVM/Kotlin di FSRS riusabile prima di
 * scriverne uno proprio -- portare FSRS a mano senza un riferimento da
 * verificare bit a bit e' un rischio di correttezza che non vale la pena
 * correre in un task che punta a consegnare lo swipe/flip funzionante.
 * Questa implementazione va sostituita da FsrsScheduler in un task dedicato.
 */
class SimpleScheduler : Scheduler {
    override fun review(state: ReviewState?, grade: Grade, now: Long): ReviewState {
        val currentStability = state?.stability ?: 1.0
        val nextStability = when (grade) {
            Grade.AGAIN -> max(0.5, currentStability / 2.0)
            Grade.GOOD -> min(365.0, currentStability * 2.0)
        }
        return ReviewState(
            stability = nextStability,
            difficulty = state?.difficulty ?: 5.0,
            due = now + (nextStability * MILLIS_PER_DAY).roundToLong(),
            reps = (state?.reps ?: 0) + 1,
            lapses = (state?.lapses ?: 0) + if (grade == Grade.AGAIN) 1 else 0,
            lastReview = now,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 24.0 * 60.0 * 60.0 * 1000.0
    }
}
