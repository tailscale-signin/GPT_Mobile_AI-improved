package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderWaitReattachmentTest {
    private class Fixture {
        val expected = AgentRun("run", 1, 2, 3, "profile", "CUSTOM", "model", "WAITING_PROVIDER_LIMIT", ownershipFence = 7)
        val message = MessageV2(id = 3, chatId = 1, content = "Retained answer", platformType = "profile", currentRunId = "run", ownershipFence = 7)
        var run: AgentRun? = expected
        var answer: MessageV2? = message
        var row: ProviderWait? = ProviderWait("run", 7, "saved-scope", 30_000, "saved-checkpoint")
        val scheduled = mutableListOf<Pair<String, Long>>()
        var projection: suspend (ProviderWait) -> ProviderWaitDeadline = { ProviderWaitDeadline(it.runId, it.fence, it.retryAt, 120_000) }
        suspend fun attach(owner: AgentRun? = expected, visible: MessageV2 = message) = ProviderWaitReattachment.reattach(
            visible,
            owner,
            { answer },
            { run },
            { row },
            projection,
            { scope, retryAt -> scheduled += scope to retryAt }
        )
    }

    @Test fun prematureClickKeepsTheOriginalRowAndItsLaterSharedDeadline() = runBlocking {
        val fixture = Fixture()
        val original = fixture.row
        val deadline = fixture.attach()
        assertEquals(ProviderWaitDeadline("run", 7, 30_000, 120_000), deadline)
        assertEquals(listOf("saved-scope" to 120_000L), fixture.scheduled)
        assertEquals(original, fixture.row)
        assertEquals(fixture.expected, fixture.run)
        assertEquals(fixture.message, fixture.answer)
    }

    @Test fun anOwnedControlRejectsEveryChangedRunDestinationAndFence() = runBlocking {
        val fixture = Fixture()
        listOf(
            fixture.expected.copy(runId = "other"),
            fixture.expected.copy(chatId = 9),
            fixture.expected.copy(assistantMessageId = 9),
            fixture.expected.copy(userMessageId = 9),
            fixture.expected.copy(profileUid = "other"),
            fixture.expected.copy(modelSnapshot = "other"),
            fixture.expected.copy(providerSnapshot = "other"),
            fixture.expected.copy(ownershipFence = 8)
        ).forEach { assertNull(fixture.attach(it)) }
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test fun aCheckpointReplacementDuringProjectionCannotScheduleAnOldAction() = runBlocking {
        val fixture = Fixture()
        fixture.projection = { row ->
            fixture.row = row.copy(checkpoint = "replaced-checkpoint")
            ProviderWaitDeadline(row.runId, row.fence, row.retryAt)
        }
        assertNull(fixture.attach())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test fun aNewFenceDuringProjectionCannotScheduleTheFormerOwner() = runBlocking {
        val fixture = Fixture()
        fixture.projection = { row ->
            fixture.answer = fixture.message.copy(ownershipFence = 8)
            fixture.run = fixture.expected.copy(ownershipFence = 8)
            ProviderWaitDeadline(row.runId, row.fence, row.retryAt)
        }
        assertNull(fixture.attach())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test fun genericSlotReattachmentKeepsItsExistingBehaviorButOwnedControlsNeverRetarget() = runBlocking {
        val fixture = Fixture()
        val stale = fixture.message.copy(currentRunId = "old-run", ownershipFence = 6)
        assertNull(fixture.attach(fixture.expected.copy(runId = "old-run", ownershipFence = 6), stale))
        assertTrue(fixture.scheduled.isEmpty())
        assertEquals(ProviderWaitDeadline("run", 7, 30_000, 120_000), fixture.attach(null, stale))
        assertEquals(1, fixture.scheduled.size)
    }

    @Test fun aProjectedDeadlineForAnotherCheckpointDoesNotScheduleAnything() = runBlocking {
        val fixture = Fixture()
        fixture.projection = { row -> ProviderWaitDeadline(row.runId, row.fence + 1, row.retryAt) }
        assertNull(fixture.attach())
        fixture.projection = { row -> ProviderWaitDeadline(row.runId, row.fence, row.retryAt - 1) }
        assertNull(fixture.attach())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test fun aCombinedDisplaySlotRetainsItsDistinctSavedWriterProfile() = runBlocking {
        val fixture = Fixture()
        val visible = fixture.message.copy(platformType = "combined-display")
        fixture.answer = visible
        assertEquals(ProviderWaitDeadline("run", 7, 30_000, 120_000), fixture.attach(visible = visible))
        assertEquals("profile", fixture.run?.profileUid)
        assertEquals("combined-display", fixture.answer?.platformType)
        assertEquals(listOf("saved-scope" to 120_000L), fixture.scheduled)
    }

    @Test fun cancellingTheUiActionBeforeItsFinalOwnerCheckCannotEnqueue() = runBlocking {
        val fixture = Fixture()
        fixture.projection = { row ->
            currentCoroutineContext().cancel()
            ProviderWaitDeadline(row.runId, row.fence, row.retryAt)
        }
        launch { fixture.attach() }.join()
        assertTrue(fixture.scheduled.isEmpty())
    }
}
