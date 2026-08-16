package com.local.spacedcards.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleSchedulerTest {
    private val scheduler = SimpleScheduler()
    private val now = 1_700_000_000_000L

    @Test
    fun againReducesIntervalAndIncrementsLapses() {
        val initial = ReviewState(
            stability = 8.0,
            difficulty = 5.0,
            due = now,
            reps = 3,
            lapses = 1,
            lastReview = now - 1_000L,
        )

        val updated = scheduler.review(initial, Grade.AGAIN, now)

        assertTrue(updated.stability < initial.stability)
        assertEquals(4, updated.reps)
        assertEquals(2, updated.lapses)
        assertEquals(now, updated.lastReview)
    }

    @Test
    fun goodIncreasesIntervalWithoutAddingLapses() {
        val initial = ReviewState(
            stability = 3.0,
            difficulty = 5.0,
            due = now,
            reps = 1,
            lapses = 0,
            lastReview = now - 1_000L,
        )

        val updated = scheduler.review(initial, Grade.GOOD, now)

        assertTrue(updated.stability > initial.stability)
        assertEquals(2, updated.reps)
        assertEquals(0, updated.lapses)
        assertTrue(updated.due > now)
    }
}
