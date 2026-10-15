package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessSnapshotStoreTest {
    @Test fun invalidationRejectsLateProducerAndClockRollback() {
        var now = 100L
        val store = ReadinessSnapshotStore<String, String>(clock = { now })
        val epoch = store.epoch
        assertTrue(store.publish("key", "metadata", 8, epoch))
        assertEquals("metadata", store.peek("key")?.value)
        now = 99
        assertNull(store.peek("key"))
        store.invalidate()
        assertFalse(store.publish("key", "late", 8, epoch))
        assertNull(store.peek("key"))
    }

    @Test fun metadataBoundsAndHardAgeAreEnforced() {
        var now = 0L
        val store = ReadinessSnapshotStore<String, String>(maxEntries = 2, maxBytes = 10, clock = { now })
        assertTrue(store.publish("a", "a", 6, store.epoch))
        assertTrue(store.publish("b", "b", 6, store.epoch))
        assertNull(store.peek("a"))
        assertFalse(store.publish("huge", "huge", 11, store.epoch))
        now = 3_600_001
        assertNull(store.peek("b"))
    }

    @Test fun snapshotReadsDoNotWaitForAnUnfinishedRefresh() {
        val store = ReadinessSnapshotStore<String, String>()
        store.publish("key", "old", 3, store.epoch)
        val refresh = kotlinx.coroutines.CompletableDeferred<Unit>()
        assertFalse(refresh.isCompleted)
        assertEquals("old", store.peek("key")?.value)
    }
}
