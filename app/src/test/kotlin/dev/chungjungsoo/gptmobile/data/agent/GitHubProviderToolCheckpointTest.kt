package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProviderToolCheckpointTest {
    private val task = "a".repeat(64)
    private val scope = "2:17:profile"
    private val original = "$scope:configured:first:documents:retained-sources"

    private fun checkpoint(key: String = original, binding: String = task) = buildJsonObject {
        put("state", "WAITING_PROVIDER_LIMIT")
        put("resume_allowed", true)
        put(
            "context",
            buildJsonObject {
                put("task_binding", binding)
                put("tool_checkpoint_binding", key)
            }
        )
    }

    private fun expected(value: JsonObject) = GitHubProviderCheckpoint(task, MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun resumedCoroutineUsesAcceptedReceiptJournalInsteadOfItsNewParentGuardIdentity() {
        val saved = checkpoint()
        val journals = mapOf(original to listOf("context-receipt", "search-receipt"))
        val restored = GitHubProviderToolCheckpoint.binding(scope, "configured:new:provider-wait:parent", expected(saved), saved)
        assertEquals(listOf("context-receipt", "search-receipt"), journals[restored])
        assertEquals(original, restored)
    }

    @Test fun changedCheckpointCannotRestoreAnOlderReceiptJournal() {
        val saved = checkpoint()
        val changed = JsonObject(saved + ("draft" to JsonPrimitive("A newer owner changed this boundary")))
        val error = runCatching { GitHubProviderToolCheckpoint.binding(scope, "new", expected(saved), changed) }.exceptionOrNull()
        assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", error?.message)
        assertTrue(error is ProviderRecoveryFailure)
        val details = (error as ProviderRecoveryFailure).event().failureDetails
        assertEquals(JsonPrimitive(false), details?.get("dispatched"))
        assertEquals(JsonPrimitive(expected(saved).digest), details?.get("checkpoint_digest"))
    }

    @Test fun anotherTaskProfileOrTurnCannotBeSubstitutedEvenWithAMatchingDigest() {
        listOf(checkpoint(key = "2:18:profile:configured:first"), checkpoint(key = "2:17:other:configured:first"), checkpoint(binding = "b".repeat(64))).forEach { saved ->
            assertTrue(runCatching { GitHubProviderToolCheckpoint.binding(scope, "new", expected(saved), saved) }.isFailure)
        }
    }

    @Test fun ordinaryRequestDoesNotAdoptRetainedWaitWithoutItsTypedAuthority() {
        assertEquals("$scope:current", GitHubProviderToolCheckpoint.binding(scope, "current", null, checkpoint()))
    }

    @Test fun legacyWaitStillRequiresTheExactCheckpointAndUsesItsCurrentJournalKey() {
        val saved = buildJsonObject {
            put("state", "WAITING_PROVIDER_LIMIT")
            put("resume_allowed", true)
        }
        assertEquals("$scope:current", GitHubProviderToolCheckpoint.binding(scope, "current", expected(saved), saved))
        assertTrue(runCatching { GitHubProviderToolCheckpoint.binding(scope, "current", expected(saved), null) }.isFailure)
    }
}
