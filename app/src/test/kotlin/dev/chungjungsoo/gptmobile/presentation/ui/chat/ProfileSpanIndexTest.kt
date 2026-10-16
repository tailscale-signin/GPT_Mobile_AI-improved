package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSpanIndexTest {
    @Test
    fun `finds only ranges intersecting a Markdown token`() {
        val index = ProfileSpanIndex(
            listOf(
                MappedProfileSpan(40, 55, "C"),
                MappedProfileSpan(0, 15, "A"),
                MappedProfileSpan(20, 32, "B")
            )
        )
        assertEquals(listOf("B"), index.overlapping(25, 29).map { it.profileUid })
        assertEquals(listOf("B", "C"), index.overlapping(31, 43).map { it.profileUid })
        assertTrue(index.overlapping(15, 20).isEmpty())
        assertTrue(index.overlapping(100, 110).isEmpty())
        assertTrue(index.overlapping(20, 20).isEmpty())
    }

    @Test
    fun `long overlapping range is not lost behind shorter ranges`() {
        val index = ProfileSpanIndex(
            listOf(
                MappedProfileSpan(0, 400, "outer"),
                MappedProfileSpan(20, 30, "a"),
                MappedProfileSpan(55, 58, "b"),
                MappedProfileSpan(100, 102, "c")
            )
        )
        assertEquals(listOf("outer"), index.overlapping(300, 340).map { it.profileUid })
        assertEquals(listOf("outer", "b"), index.overlapping(56, 57).map { it.profileUid })
    }
}
