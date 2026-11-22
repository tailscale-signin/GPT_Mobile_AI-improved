package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowPrerequisitesTest {
    private class Fixture {
        val expected = AgentRun("saved-run", 1, 2, 3, "saved-profile", "CUSTOM", "saved-model", "BLOCKED", terminalError = "DISPATCH_POLICY_CHANGED", ownershipFence = 7)
        val message = MessageV2(id = 3, chatId = 1, content = "Retained answer", platformType = "saved-profile", currentRunId = "saved-run", ownershipFence = 7)
        var run: AgentRun? = expected
        var answer: MessageV2? = message
        var profiles = listOf(PlatformV2(uid = "other-profile", name = "Same name"), PlatformV2(uid = "saved-profile", name = "Same name"))
        var events = listOf(event("saved-run", "saved-connection"), event("unrelated-run", "unrelated-connection"))
        val lookedUp = mutableListOf<String>()
        var lookup: suspend (String) -> ToolConnection? = { uid -> ToolConnection(uid, "Saved connection", "saved", "GITHUB", null, "OAUTH", null, null) }
        suspend fun review() = GitHubWorkflowPrerequisites.authorityReview(expected, { run }, { answer }, { events }, { profiles }) { uid ->
            lookedUp += uid
            lookup(uid)
        }
        private fun event(run: String, connection: String) = ToolEvent("event-$run", run, 0, "call-$run", connection, "Connection", "github_read", "github_read", "{}", null, null, "FAILED", true)
    }

    @Test fun authorityReviewReadsOnlySavedConnectionIdsAndRoutesTheSavedProfile() = runBlocking {
        val fixture = Fixture()
        val result = requireNotNull(fixture.review())
        assertEquals("saved-profile", result.profileUid)
        assertEquals(listOf("saved-connection"), fixture.lookedUp)
        assertEquals(listOf("Saved connection"), result.connectionNames)
        assertEquals(0, result.unavailableConnections)
        assertEquals(fixture.expected, fixture.run)
        assertEquals(fixture.message, fixture.answer)
    }

    @Test fun missingSavedConnectionNeverSelectsAnotherConfiguredConnection() = runBlocking {
        val fixture = Fixture()
        fixture.lookup = { null }
        val result = requireNotNull(fixture.review())
        assertEquals("saved-profile", result.profileUid)
        assertEquals(listOf("saved-connection"), fixture.lookedUp)
        assertTrue(result.connectionNames.isEmpty())
        assertEquals(1, result.unavailableConnections)
    }

    @Test fun combinedDisplayRoutesItsSavedWriterAndRejectsAChangedDisplaySlot() = runBlocking {
        val fixture = Fixture()
        val combined = fixture.message.copy(platformType = "combined-display")
        fixture.answer = combined
        assertEquals("saved-profile", fixture.review()?.profileUid)
        fixture.lookup = { uid ->
            fixture.answer = combined.copy(platformType = "replacement-display")
            ToolConnection(uid, "Saved", "saved", "GITHUB", null, "OAUTH", null, null)
        }
        assertNull(fixture.review())
    }

    @Test fun removedOrAmbiguousProfileCannotBecomeAnotherSettingsRoute() = runBlocking {
        val fixture = Fixture()
        fixture.profiles = listOf(PlatformV2(uid = "other-profile", name = "Same name"))
        assertNull(fixture.review())
        fixture.profiles = List(2) { PlatformV2(uid = "saved-profile", name = "Same name") }
        assertNull(fixture.review())
        assertTrue(fixture.lookedUp.isEmpty())
    }

    @Test fun changedOwnerOrConnectionSetDuringConfigurationReadRevokesTheRoute() = runBlocking {
        val fixture = Fixture()
        fixture.lookup = { uid ->
            fixture.run = fixture.expected.copy(ownershipFence = 8)
            ToolConnection(uid, "Saved", "saved", "GITHUB", null, "OAUTH", null, null)
        }
        assertNull(fixture.review())
        fixture.run = fixture.expected
        fixture.lookup = { uid ->
            fixture.events = emptyList()
            ToolConnection(uid, "Saved", "saved", "GITHUB", null, "OAUTH", null, null)
        }
        assertNull(fixture.review())
    }

    @Test fun deadlineProjectionNeedsAnActualWaitingOwnerAndNeverOffersGenericReplay() {
        val run = Fixture().expected.copy(status = "WAITING_PROVIDER_LIMIT", terminalError = null)
        val wait = ProviderWaitDeadline(run.runId, run.ownershipFence, 30_000, 120_000)
        val snapshot = GitHubWorkflowPresentation.snapshot(run, emptyList(), true, providerWait = wait)
        assertEquals(30_000L, snapshot.providerOriginalRetryAtMillis)
        assertEquals(120_000L, snapshot.providerRetryAtMillis)
        assertTrue(snapshot.providerRetrySchedulable)
        assertTrue(snapshot.recovery.isEmpty())
        assertFalse(GitHubWorkflowPresentation.snapshot(run, emptyList(), true, evidenceReadFailed = true, providerWait = wait).providerRetrySchedulable)
        assertNull(GitHubWorkflowPrerequisites.providerDeadline(run, wait.copy(fence = 8), null))
        assertNull(GitHubWorkflowPrerequisites.providerDeadline(run, wait.copy(runId = "other"), null))
        assertNull(GitHubWorkflowPrerequisites.providerDeadline(run.copy(status = "COMPLETED"), wait, null))
        assertNull(GitHubWorkflowPrerequisites.providerDeadline(run, wait, "RESTORE_AUTHORIZED_CONFIGURATION"))
        assertNull(GitHubWorkflowPrerequisites.providerDeadline(run, wait, "RECONCILE_REMOTE_OUTCOME"))
    }

    @Test fun returningToTheSameChatRunAndFenceCannotReviveAnOldOperation() {
        val operations = GitHubPrerequisiteOperation()
        val run = Fixture().expected
        val old = operations.begin(run)
        operations.clear()
        val other = operations.begin(run.copy(chatId = 2))
        operations.clear()
        val current = operations.begin(run)
        assertFalse(operations.owns(old))
        assertFalse(operations.owns(other))
        assertTrue(operations.owns(current))
        assertEquals(run.runId, current.runId)
        assertEquals(run.ownershipFence, current.fence)
        val replacement = operations.begin(run.copy(ownershipFence = 8))
        assertFalse(operations.owns(current))
        assertTrue(operations.owns(replacement))
    }
}
