package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OptionalAssistArtifactsTest {
    @Test fun completedArtifactRequiresExactSourceAndLiveConsent() = runTest {
        var allowed = true
        val original = AgentToolResult("call", ToolResultContent.Text("trusted original"), false)
        val prepared = original.copy(assistMetadata = listOf(JsonObject(mapOf("selection" to JsonPrimitive("source 1")))))
        val pending = OptionalAssistArtifacts.enqueue(coroutineContext, original, { allowed }, { "runtime" }) { prepared }
        runCurrent()
        assertEquals(prepared.assistMetadata, OptionalAssistArtifacts.ready(pending).assistMetadata)
        assertTrue(OptionalAssistArtifacts.ready(pending.copy(content = ToolResultContent.Text("changed original"))).assistMetadata.isEmpty())
        allowed = false
        assertTrue(OptionalAssistArtifacts.ready(pending).assistMetadata.isEmpty())
    }

    @Test fun normalParentCompletionCancelsAbandonedOptionalProducerAndReleasesAdmission() = runTest {
        val parent = Job()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val original = AgentToolResult("cancel", ToolResultContent.Text("original"), false)
        OptionalAssistArtifacts.enqueue(coroutineContext, original, { true }, { "runtime" }, ownerJob = parent) {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        entered.await()
        parent.complete()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        val next = OptionalAssistArtifacts.enqueue(coroutineContext, original, { true }, { "runtime" }) { original }
        assertTrue(next.optionalAssistKey != null)
        runCurrent()
    }
}
