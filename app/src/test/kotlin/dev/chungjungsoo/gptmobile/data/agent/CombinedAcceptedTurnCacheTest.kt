package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.RunPolicyFixture
import dev.chungjungsoo.gptmobile.data.database.RunPolicySnapshotCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CombinedAcceptedTurnCacheTest {
    @Before fun clearBefore() {
        CombinedAcceptedTurnCache.clear()
    }

    @After fun clearAfter() {
        CombinedAcceptedTurnCache.clear()
    }

    @Test fun prewarmedExactBytesReuseOneTypedObjectAndDifferentBytesNeverAlias() {
        val raw = RunPolicyFixture.raw()
        CombinedAcceptedTurn.prepareDecoded(raw, 17)
        val first = requireNotNull(CombinedAcceptedTurn.decode(raw))
        assertSame(first, CombinedAcceptedTurn.decode(raw))
        assertSame(first.context, CombinedAcceptedTurn.context(raw))
        val formatted = raw.replace("\"version\":1,", "\"version\" : 1,")
        val separate = requireNotNull(CombinedAcceptedTurn.decode(formatted))
        assertEquals(first, separate)
        assertNotSame(first, separate)
        RunPolicySnapshotCodec.forgetChat(17)
        assertNotSame(first, CombinedAcceptedTurn.decode(raw))
    }

    @Test fun parsedPolicyRetentionHasBothEntryAndCharacterBounds() {
        for (index in 0..40) {
            assertNotNull(CombinedAcceptedTurn.decode(RunPolicyFixture.raw(revision = "revision-$index")))
        }
        assertTrue(CombinedAcceptedTurnCache.retainedSize().first <= CombinedAcceptedTurnCache.MAX_ENTRIES)
        val largeTask = "Frozen evidence. ".repeat(8_000)
        for (index in 0..12) {
            assertNotNull(CombinedAcceptedTurn.decode(RunPolicyFixture.raw(task = largeTask, revision = "large-$index")))
        }
        val retained = CombinedAcceptedTurnCache.retainedSize()
        assertTrue(retained.first <= CombinedAcceptedTurnCache.MAX_ENTRIES)
        assertTrue(retained.second <= CombinedAcceptedTurnCache.MAX_CHARACTERS)
        RunPolicySnapshotCodec.forgetChat(1)
        assertEquals(0 to 0, CombinedAcceptedTurnCache.retainedSize())
    }

    @Test fun legacyDefaultedAcceptedKindKeepsItsExistingDecodeContract() {
        val raw = RunPolicyFixture.raw().replace("\"policyRecordKind\":\"COMBINED_ACCEPTED_TURN\",", "")
        assertNotNull(CombinedAcceptedTurn.decode(raw))
    }
}
