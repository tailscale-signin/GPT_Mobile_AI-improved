package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResultException
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PhoneIndexReadDeadlineTest {
    private val scope = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")

    @Test fun innerAndOuterPhoneDeadlinesRetainLocalStopProvenanceAndRequireStatusReadback() = runBlocking {
        val arguments = buildJsonObject {
            put("action", "index")
            put("map_source", "phone")
        }
        val call = ProviderEvent.ToolCall("owned", "repository_graph__connection", arguments)
        val failures = listOf(
            GitHubReadValidation("READ_DEADLINE", buildJsonObject { put("failed_stage", "phone_index_tree") }),
            GatewayGraphOperationException(GatewayGraphResultException("READ_DEADLINE", "Bounded phone index stopped", "READ_PHONE_INDEX_STATUS"), "", "On this phone", "READ_PHONE_INDEX_STATUS", false, targetRevision = scope.commitSha)
        )
        for (failure in failures) {
            val witness = PhoneIndexInterruption(call)
            val tool = RepositoryGraphTool(call.name, repositoryScope = scope, accountIdentity = "account") { payload ->
                currentCoroutineContext()[PhoneIndexInterruption.AttemptKey]?.admit(scope, "account", JsonObject(payload - "map_source"))
                GitHubDispatchAccounting.started(false, "/repos/owner/repo/git/trees/${"b".repeat(40)}")
                throw failure
            }
            val result = withContext(witness) { tool.execute(call.callId, arguments) }
            assertEquals("READ_DEADLINE", result.errorCode)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive("CANCELLED_LOCALLY"), receipt["phase"])
            assertEquals(JsonPrimitive(true), receipt["local_work_stopped"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
            assertEquals(JsonPrimitive(false), receipt["index_outcome_unknown"])
            assertEquals(JsonPrimitive(false), receipt["request_replay_allowed"])
            assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
            assertFalse(receipt.containsKey("graph_index_attempt_hash"))
            val content = (result.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive("INTERRUPTED"), content["state"])
            assertEquals(JsonPrimitive("status"), content.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject["action"])
            val retained = witness.close()
            assertNotNull(retained)
            assertEquals("PHONE_INDEX_INTERRUPTED", retained?.errorCode)
            assertEquals(JsonPrimitive("CANCELLED_LOCALLY"), retained?.executionReceipt?.get("phase"))
        }
    }

    @Test fun gatewayIndexDeadlineKeepsItsSeparateUnknownWriteObligation() = runBlocking {
        val tool = RepositoryGraphTool("repository_graph") {
            throw GatewayGraphOperationException(GatewayGraphResultException("READ_DEADLINE", "Gateway response deadline", "CHECK_SAVED_INDEX"), "gateway", "Remote", "CHECK_SAVED_INDEX", true, currentIndexOutcomeUnknown = true)
        }
        val result = tool.execute(
            "remote",
            buildJsonObject {
                put("action", "index")
                put("map_source", "gateway")
            }
        )
        assertEquals(JsonPrimitive("UNKNOWN_OUTCOME"), result.executionReceipt?.get("phase"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("requires_reconciliation"))
        assertFalse(result.executionReceipt.orEmpty().containsKey("local_work_stopped"))
    }
}
