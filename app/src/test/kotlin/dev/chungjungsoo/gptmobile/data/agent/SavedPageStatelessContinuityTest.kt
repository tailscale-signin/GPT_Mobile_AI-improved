package dev.chungjungsoo.gptmobile.data.agent

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAICompatibleAdapter
import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderAttachmentEncoder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SavedPageStatelessContinuityTest {
    @Test fun stagedSavedPagesCarryReadingEvidenceIntoFreshStatelessRequestsAfterRestart() = runBlocking {
        val file = File.createTempFile("saved-page-continuity", ".json").apply { delete() }
        try {
            val expected = (0 until 12).map { "FACT_${it.toString().padStart(2, '0')}" }.toSet()
            val history = expected.mapIndexed { index, fact ->
                val fixture = GitHubSavedPageCapacityTest.Fixture(fact)
                // A terminal cursor can contain only remote continuation metadata.
                val sourcePage = fixture.history.first { exchange ->
                    val page = exchange.results.single()
                    "view_cursor" in exchange.calls.single().arguments && !page.isError && fact in ToolHandoffReceipt.payload(page.content)
                }
                val call = sourcePage.calls.single().copy(callId = "page-$index")
                val result = fixture.read(call)
                assertTrue(ToolHandoffReceipt.payload(result.content).contains(fact))
                AgentToolExchange(listOf(call), listOf(result))
            }
            val journal = ToolBatchCheckpoint(file, "same-authorized-task-and-profile")
            for (exchange in history) {
                val call = exchange.calls.single()
                journal.enqueue(listOf(call))
                journal.execute(call) { exchange.results.single() }
            }
            journal.finish()
            val required = ToolResultDeliveryProof.capture(history.flatMap { it.results })
            assertEquals(required, ToolResultDeliveryProof.capture(journal.recoverySnapshot().completedExchanges.flatMap { it.results }))
            // A schema-2 checkpoint can already claim delivery while retaining no notes.
            // Keep that historical record, but require current-context replay under the same grant.
            journal.markConsumed(required.keys, required)
            assertEquals(null, journal.recoverySnapshot().savedPageReading)
            val budget = (1800..12_000 step 100).last { tokens ->
                val sourceBudget = tokens - SavedPageReading.reservation(null, true)
                val allowances = GitHubReplayRecovery.savedPageResultAllowances(history, emptySet(), sourceBudget, 512)
                val replay = ToolExchangeCompactor.compact(history, sourceBudget, 512, resultTokenAllowances = allowances, requiredSavedPageDigests = required)
                val seen = ToolResultDeliveryProof.capture(replay.flatMap { it.results })
                required.count { (id, digest) -> seen[id] == digest } in 1 until required.size / 2
            }
            val limits = AgentRunLimits(githubTask = true, maxReplayTokens = budget, maxReplayResultTokens = 512, maxRounds = 24)
            var dispatchedTools = 0
            val admitted = object : AgentTool {
                override val definition = GitHubSavedPageCapacityTest.Fixture().tools.single()
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatchedTools++
                    error("Retained pages must not execute a tool again")
                }
            }
            val wrappers = WrapperState()
            val firstApi = StatelessApi(stopDuringSecondResponse = true)
            val firstVisible = mutableListOf<AgentRunEvent>()
            var cancelled = false
            try {
                AgentRunner(limits, journal).run(session(firstApi, wrappers), listOf(admitted), selectDefinitions = { emptyList() }).toList(firstVisible)
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(2, firstApi.requests.size)
            assertTrue(firstApi.partialSecondResponseEmitted)
            assertTrue(wrappers.providerWrites.isEmpty())
            assertEquals(null, wrappers.providerCheckpoint)
            assertTrue(wrappers.publicProgress.isEmpty())
            assertEquals(0, wrappers.reviewPasses)
            assertTrue(firstVisible.filterIsInstance<AgentRunEvent.Provider>().none { it.event is ProviderEvent.TextDelta })
            val beforeRestart = ToolBatchCheckpoint(file, "same-authorized-task-and-profile").recoverySnapshot()
            val carried = requireNotNull(beforeRestart.savedPageReading)
            val carriedIds = SavedPageReading.fingerprints(carried).keys
            assertTrue(carriedIds.size in 1 until required.size)
            assertEquals(required, beforeRestart.deliveredResultDigests)
            val firstFacts = facts(carried.toString())
            assertTrue(firstFacts.isNotEmpty())
            assertEquals(required.keys, beforeRestart.consumedCallIds)

            // Recreate the checkpoint, both production wrappers and the actual adapter.
            // The shared ordinary/review stores have received no private-note draft.
            var concurrentReading: JsonObject? = null
            val restartedApi = StatelessApi(beforeResponse = { count, privateRead ->
                if (count == 1) {
                    assertTrue(privateRead)
                    val competingJournal = ToolBatchCheckpoint(file, "same-authorized-task-and-profile")
                    val competingApi = StatelessApi(beforeResponse = { competingCount, _ ->
                        if (competingCount == 2) throw CancellationException("Competing runner stopped after its first durable read")
                    })
                    try {
                        AgentRunner(limits, competingJournal).run(session(competingApi, WrapperState()), listOf(admitted), selectDefinitions = { emptyList() }).toList()
                    } catch (_: CancellationException) {
                        // Only the second competing response was interrupted.
                    }
                    assertEquals(2, competingApi.requests.size)
                    concurrentReading = requireNotNull(competingJournal.recoverySnapshot().savedPageReading)
                    assertTrue(SavedPageReading.fingerprints(concurrentReading).size > carriedIds.size)
                }
            })
            val restartedJournal = ToolBatchCheckpoint(file, "same-authorized-task-and-profile")
            val visible = AgentRunner(limits, restartedJournal).run(session(restartedApi, wrappers), listOf(admitted), selectDefinitions = { emptyList() }).toList()
                .filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertTrue(restartedApi.requests.size >= 2)
            val concurrent = requireNotNull(concurrentReading)
            val reconciledRequest = resultRows(restartedApi.requests[1])
            val reconciledReading = reconciledRequest.firstNotNullOf { it["saved_page_reading"] as? JsonObject }
            assertEquals(concurrent["reads"], reconciledReading["reads"])
            val firstRestoredRows = resultRows(restartedApi.requests.first())
            assertTrue(firstRestoredRows.any { it["saved_page_reading"] != null })
            assertTrue(firstFacts.all { it in carriedFacts(firstRestoredRows) })
            assertTrue("At least one earlier fact must now depend on the carried notes, not on a raw replay body", firstFacts.any { it !in rawFacts(firstRestoredRows) })
            assertTrue(visible.filterIsInstance<ProviderEvent.Failed>().isEmpty())
            val answer = visible.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
            assertTrue(answer.startsWith("Final facts:"))
            assertEquals(expected, facts(answer))
            assertEquals(expected, facts(texts(restartedApi.requests.last()).joinToString("\n")))
            assertEquals(0, dispatchedTools)
            assertTrue(wrappers.publicProgress.isEmpty())
            assertTrue(wrappers.reviewPasses > 0)
            assertTrue(wrappers.providerWrites.isNotEmpty())
            assertTrue(
                wrappers.providerWrites.all { row ->
                    val draft = (row["draft"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    draft.isEmpty() || draft.startsWith("Final facts:")
                }
            )
            assertTrue(wrappers.providerWrites.none { "PRIVATE_PARTIAL_NOTE" in it.toString() })
            assertTrue(firstApi.requests.size + restartedApi.requests.size <= 24)
            val finalSnapshot = ToolBatchCheckpoint(file, "same-authorized-task-and-profile").recoverySnapshot()
            assertEquals(required, finalSnapshot.deliveredResultDigests)
            assertTrue(finalSnapshot.unsettledCalls.isEmpty())
            assertNotNull(finalSnapshot.savedPageReading)
            assertTrue((finalSnapshot.savedPageReading!!.getValue("reads") as JsonArray).take((carried.getValue("reads") as JsonArray).size) == carried["reads"])
        } finally {
            file.delete()
        }
    }

    private class WrapperState {
        var providerCheckpoint: JsonObject? = null
        val providerWrites = mutableListOf<JsonObject>()
        val publicProgress = mutableListOf<String>()
        val reviewRows = mutableMapOf<String, JsonObject>()
        var reviewPasses = 0
    }

    private suspend fun session(api: StatelessApi, state: WrapperState): AgentProviderSession {
        val encoder = object : ProviderAttachmentEncoder(ContextWrapper(null)) {
            override fun gatewayPerformanceHeaders(): Map<String, String> = emptyMap()
        }
        val groq = object : GroqAPI {
            override fun streamChatCompletion(request: GroqChatCompletionRequest, timeoutSeconds: Int, config: ProviderRequestConfig): Flow<GroqChatCompletionChunk> = error("The selected OpenRouter profile must be retained")
        }
        val platform = PlatformV2(uid = "same-profile", name = "Provider", compatibleType = ClientType.OPENROUTER, apiUrl = "https://provider.example/v1", token = "secret", model = "model-test", maxTokens = 512)
        val turns = listOf(ConversationTurn(MessageV2(content = "Compare the facts in the retained pages.", platformType = null), null, isCurrentTurn = true))
        val adapter = OpenAICompatibleAdapter(api, groq, encoder).openSession(turns, platform)
        val continuation = GitHubOutputContinuationSession(
            adapter,
            publishProgress = { text, _ -> state.publicProgress += text },
            readCheckpoint = { state.providerCheckpoint },
            saveCheckpoint = {
                state.providerWrites += it
                state.providerCheckpoint = it
            }
        ) { _, _, _ -> error("Private notes must never reopen as an ordinary draft") }
        val task = "Compare the facts in the retained pages."
        val reviewCheckpoint = dev.chungjungsoo.gptmobile.data.github.GitHubReviewCheckpoint(task, { state.reviewRows[it] }, { key, value -> state.reviewRows[key] = value })
        return dev.chungjungsoo.gptmobile.data.github.GitHubReviewedSession(
            continuation,
            dev.chungjungsoo.gptmobile.data.github.GitHubReviewEvidence(),
            task,
            8192,
            openReview = { prompt, _ ->
                state.reviewPasses++
                val records = Json.parseToJsonElement(prompt).jsonObject.getValue("records") as JsonArray
                val verdict = buildJsonObject {
                    put("schema_version", dev.chungjungsoo.gptmobile.data.github.GitHubReviewSchema.VERSION)
                    put("verdict", "PASS")
                    put("reason", "The deterministic fixture reviews this exact candidate and record set.")
                    put("reviewed_ids", JsonArray(records.map { it.jsonObject.getValue("id") }))
                    put("issues", JsonArray(emptyList()))
                    put("verified_claims", JsonArray(listOf(JsonPrimitive("The deterministic fixture checked every supplied record ID."))))
                }.toString()
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        assertTrue(tools.isEmpty() && exchanges.isEmpty())
                        emit(ProviderEvent.TextDelta(verdict))
                        emit(ProviderEvent.Completed)
                    }
                }
            },
            progress = {},
            checkpoint = reviewCheckpoint,
            reviewerRevision = "same-reviewer"
        )
    }

    /** Responses derive solely from this request's serialized messages, with no remembered facts. */
    private class StatelessApi(private val stopDuringSecondResponse: Boolean = false, private val beforeResponse: suspend (Int, Boolean) -> Unit = { _, _ -> }) : OpenAIAPI {
        val requests = mutableListOf<JsonObject>()
        var partialSecondResponseEmitted = false
            private set
        override fun streamChatCompletion(request: ChatCompletionRequest, timeoutSeconds: Int, config: ProviderRequestConfig): Flow<ChatCompletionChunk> = flow {
            val wire = NetworkClient.json.encodeToJsonElement(ChatCompletionRequest.serializer(), request).jsonObject
            requests += wire
            val rows = resultRows(wire)
            val previous = carriedFacts(rows)
            val current = rawFacts(rows)
            val privateRead = texts(wire).any { "This is a private reading stage" in it }
            beforeResponse(requests.size, privateRead)
            val response = if (privateRead) (current - previous).sorted().joinToString(" ").ifBlank { "No additional fact." } else "Final facts: " + (current + previous).sorted().joinToString(" ")
            if (stopDuringSecondResponse && requests.size == 2) {
                assertTrue("The interrupted second response must be another private page read", privateRead)
                emit(ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = "<progress>PRIVATE_PARTIAL_NOTE</progress>" + response.take(48))))))
                partialSecondResponseEmitted = true
                throw CancellationException("Private response interrupted after a real partial TextDelta")
            }
            emit(ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = response), finishReason = "stop"))))
        }
        override fun streamResponses(request: ResponsesRequest, timeoutSeconds: Int, config: ProviderRequestConfig): Flow<ResponsesStreamEvent> = error("No alternate provider API")
        override suspend fun uploadFile(filePath: String, fileName: String, mimeType: String, config: ProviderRequestConfig) = UploadedProviderFile("unused", mimeType)
        override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig) = false
    }

    companion object {
        private fun texts(request: JsonObject): List<String> = (request["messages"] as JsonArray).flatMap { row ->
            when (val content = (row as JsonObject)["content"]) {
                is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(content.contentOrNull)
                else -> emptyList()
            }
        }
        private fun resultRows(request: JsonObject): List<JsonObject> = texts(request)
            .filter { it.startsWith("Completed tool result ") }
            .mapNotNull { runCatching { Json.parseToJsonElement(it.substringAfter('\n')) as? JsonObject }.getOrNull() }
        private fun facts(value: String): Set<String> = Regex("FACT_[0-9]{2}").findAll(value).map { it.value }.toSet()
        private fun rawFacts(rows: List<JsonObject>): Set<String> = facts(rows.joinToString("\n") { (it["data"] ?: it).toString() })
        private fun carriedFacts(rows: List<JsonObject>): Set<String> = facts(rows.mapNotNull { it["saved_page_reading"] }.joinToString("\n"))
    }
}
