package com.local.spacedcards.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UidTest {
    @Test
    fun goldenValues() {
        assertEquals(
            "faooyfp2g47nkmzojbk3lpt7kn",
            Uid.cardUid("In the 5-layer TCP/IP model, what is Layer 5?"),
        )
        assertEquals(
            "id33p4flrhw4lmuyx7gwg54kau",
            Uid.cardUid("What does PDU stand for?"),
        )
    }

    @Test
    fun invariantToPresentation() {
        val base = "In the 5-layer TCP/IP model, what is Layer 5?"
        val variants = listOf(
            "In the 5-layer TCP/IP model, what is&nbsp;Layer 5?",
            "  IN THE 5-LAYER TCP/IP MODEL, WHAT IS   LAYER 5?  ",
            "<div>In the 5-layer TCP/IP model, what is Layer 5?</div>",
            "In the 5-layer TCP/IP model,\twhat is\nLayer 5?",
        )
        variants.forEach { assertEquals(Uid.cardUid(base), Uid.cardUid(it), it) }
    }

    @Test
    fun differentQuestionDifferentUid() {
        assertNotEquals(Uid.cardUid("What is Layer 4?"), Uid.cardUid("What is Layer 5?"))
    }
}

class TextNormTest {
    @Test
    fun nbspIsWhitespace() = assertEquals("a b", TextNorm.hnorm("a\u00A0b"))

    @Test
    fun tagBecomesSpace() = assertEquals("a b", TextNorm.hnorm("a<br>b"))

    @Test
    fun entitiesAfterTags() =
        assertEquals("uso di <b> in html", TextNorm.hnorm("uso di &lt;b&gt; in HTML"))

    @Test
    fun zeroWidthRemoved() = assertEquals("abc", TextNorm.hnorm("ab\u200Bc"))
}
