package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatRunProjectionTest {
    @Test fun regroupingUnchangedMessagesRetainsTheRowsListIdentity() {
        val projection = ChatValueProjection<List<String>>()
        val original = projection.retain(listOf("saved answer", "saved revision"))
        assertSame(original, projection.retain(listOf("saved answer", "saved revision")))
        assertNotSame(original, projection.retain(listOf("edited answer", "saved revision")))
    }

    @Test fun anotherTurnsProgressKeepsTheRowsIdentityAndSelectedChangesInvalidateIt() {
        val projection = ChatRunProjection<List<String>>()
        val runs = setOf("saved", "revision")
        val initial = projection.select(runs, mapOf("saved" to listOf("answer"), "other" to listOf("0")))
        repeat(100) { tick ->
            assertSame(initial, projection.select(runs, mapOf("saved" to listOf("answer"), "other" to listOf("$tick"))))
        }
        val revised = projection.select(runs, mapOf("saved" to listOf("answer"), "revision" to listOf("older answer")))
        assertNotSame(initial, revised)
        assertEquals(setOf("saved", "revision"), revised.keys)
        val removed = projection.select(setOf("revision"), mapOf("saved" to listOf("answer"), "revision" to listOf("older answer")))
        assertEquals(setOf("revision"), removed.keys)
        assertEquals(emptyMap<String, List<String>>(), projection.select(emptySet(), emptyMap()))
    }
}
