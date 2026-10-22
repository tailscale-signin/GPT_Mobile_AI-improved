package dev.chungjungsoo.gptmobile.data.repository

import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.provider.LiteRtLmAdapter
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.DeviceLocationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthClient
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthCoordinator
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.selectionId
import dev.chungjungsoo.gptmobile.data.catalog.CatalogCapabilities
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.database.dao.AgentToolBindingWithConnection
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.anthropic.request.MessageRequest
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.request.GenerateContentRequest
import dev.chungjungsoo.gptmobile.data.dto.google.response.Candidate
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.dto.google.response.PromptFeedback
import dev.chungjungsoo.gptmobile.data.dto.groq.request.GroqChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqChoice
import dev.chungjungsoo.gptmobile.data.dto.groq.response.GroqDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ResponsesRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatFunctionDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeEvent
import dev.chungjungsoo.gptmobile.data.localruntime.ScriptedToolInvocation
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.UploadedProviderFile
import dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import dev.chungjungsoo.gptmobile.data.research.currentReviewRecord
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.engine.cio.CIO
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryImplTest {
    @get:org.junit.Rule val testFiles = org.junit.rules.TemporaryFolder()

    @Test fun documentOnlyRecallIsRevalidatedAtPhysicalDispatchAfterDeletionOrConsentRevocation() = runBlocking {
        for (mode in listOf("allowed", "deleted", "revoked", "regranted_during_preparation")) {
            val storage = object : SecretVault {
                val values = mutableMapOf<String, ByteArray>()
                override suspend fun put(secretRef: String, secret: ByteArray) {
                    values[secretRef] = secret.copyOf()
                }
                override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
                override suspend fun delete(secretRef: String) {
                    values.remove(secretRef)
                }
            }
            val facts = FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
            facts.setEnabled(true)
            facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = true))
            assertTrue(facts.state.value.facts.isEmpty())
            val document = dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeDocument("doc", "Saved document", "revision", chatId = 1)
            val evidence = "Document-only confidential excerpt."
            val chunk = dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeChunk("doc:0", "doc", 0, 0, evidence.length, evidence)
            val packet = dev.chungjungsoo.gptmobile.data.knowledge.DocumentRecallPacket(1, dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope(), evidence, listOf(dev.chungjungsoo.gptmobile.data.knowledge.DocumentRecallSource(document, chunk)))
            val documents = mockk<dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository>(relaxed = true)
            var deleted = false
            coEvery { documents.scopeForChat(any()) } returns dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope()
            coEvery { documents.recall(any(), any(), any()) } coAnswers {
                if (mode == "regranted_during_preparation") {
                    facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = false))
                    facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = true))
                }
                packet
            }
            coEvery { documents.validateRecall(any()) } coAnswers { check(!deleted) { "DOCUMENT_RECALL_STALE: document deleted" } }
            val platform = groqPlatform(false, "model")
            val settings = mockk<SettingRepository>(relaxed = true)
            coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
            coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
            coEvery { persistence.getChatRoom(1) } returns ChatRoomV2(id = 1, title = "Document fixture")
            val api = FakeGroqAPI(
                flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "The saved document contains a private excerpt."), finishReason = "stop")))),
                enforcePhysicalBoundary = true,
                beforePhysicalBoundary = {
                    if (mode == "deleted") deleted = true
                    if (mode == "revoked") facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = false))
                }
            )
            val states = createRepository(groqAPI = api, factVault = facts, settingsOverride = settings, persistenceOverride = persistence, knowledgeOverride = documents)
                .completeChat(listOf(MessageV2(id = 2, chatId = 1, content = "Recall my saved document", platformType = null)), emptyList(), platform, runId = "document-$mode").toList()
            if (mode == "allowed") {
                assertEquals(1, api.streamCalls)
                assertTrue(api.lastRequest!!.messages.first().content.toString().contains(evidence))
            } else {
                assertEquals(states.toString(), 0, api.streamCalls)
                assertTrue(states.any { it is ApiState.Error })
            }
        }
    }

    @Test
    fun optedInAssistPlannerReachesSerializedToolCatalogueAndRespectsToolDisable() = runBlocking {
        val helper = PlatformV2(uid = "assist-helper", name = "Helper", compatibleType = ClientType.LITERT_LM, model = "fixture")
        for ((toolsDisabled, sharingAllowed) in listOf(false to true, true to true, false to false)) {
            val primary = customPlatform().copy(disableLocalTools = toolsDisabled)
            val settings = mockk<SettingRepository>(relaxed = true)
            coEvery { settings.fetchPlatformV2s() } returns listOf(primary, helper)
            coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
                localAssist = true,
                localAssistPolicy = dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy(
                    profileUid = helper.uid,
                    shareDerivedEvidence = sharingAllowed,
                    perTurnQueryPlanning = true,
                    permissionVersion = 1,
                    profilePermissions = mapOf(helper.uid to dev.chungjungsoo.gptmobile.data.assist.AssistProcessingPermission.ALLOWED)
                )
            )
            val api = RecordingOpenAIAPI(ArrayDeque(listOf(flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Hello"), finishReason = "stop")))))))
            val states = createRepository(openAIAPI = api, settingsOverride = settings).completeChat(
                listOf(MessageV2(content = "Hi", platformType = null)),
                emptyList(),
                primary,
                "assist-catalogue-$toolsDisabled-$sharingAllowed"
            ).toList()
            assertTrue(states.toString(), states.none { it is ApiState.Error })
            assertEquals(1, api.requests.size)
            assertEquals(sharingAllowed && !toolsDisabled, api.requests.single().tools.orEmpty().any { it.function.name == "local_assist_plan" })
        }
    }

    @Test
    fun combinedHonorsOneFactScopeWithoutRepairingLexicalOmissions() = runBlocking {
        val sources = listOf(
            dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("primary", "Primary", content = "Haiti became independent in 1804 after a successful revolution."),
            dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse("helper", "Helper", content = "Australian soldiers confronted migrating emus in 1932.")
        )
        val original = MessageV2(id = 2, chatId = 1, platformType = null, content = "Choose one interesting historical event under 100 words.")
        val platform = customPlatform().copy(disableAllTools = true, disableLocalTools = true)
        val prompt = dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt(original.content, sources)
        val snapshot = dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(
            version = 4, deliveryProtocol = "PASSAGES_V2",
            userMessage = original, history = emptyList(), replies = emptyList(), sources = sources,
            prompt = prompt, profileUid = platform.uid, model = platform.model,
            systemPrompt = platform.systemPrompt.orEmpty(), configKey = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(platform)
        )
        val runId = "combined-synthesis:repository-coverage"
        val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
        coEvery { persistence.recoveryRun(runId) } returns dev.chungjungsoo.gptmobile.data.database.entity.AgentRun(
            runId,
            1,
            2,
            3,
            platform.uid,
            platform.compatibleType.name,
            platform.model,
            synthesisSnapshot = kotlinx.serialization.json.Json.encodeToString(dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot.serializer(), snapshot)
        )
        fun chunk(text: String) = flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = text), finishReason = "stop"))))
        val passage = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("passage"))
            put("text", kotlinx.serialization.json.JsonPrimitive(sources.first().content))
            put("profiles", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("C1"))))
            put("claims", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("C1.1"))))
        }.toString() + "\n"
        val api = RecordingOpenAIAPI(ArrayDeque(listOf(chunk(passage))))
        val states = createRepository(openAIAPI = api, persistenceOverride = persistence).completeChat(
            listOf(original.copy(content = prompt)),
            emptyList(),
            platform,
            runId
        ).toList()
        assertEquals(states.toString(), 1, api.streamChatCompletionCalls)
        assertEquals(sources.first().content, states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
        assertTrue(states.toString(), states.none { it is ApiState.Error })
        assertTrue(api.requests.single().toString().contains(sources[1].content))
        val report = states.filterIsInstance<ApiState.CombinedQuality>().last().report
        assertTrue(report.lexicalMissing > 0)
        assertFalse(report.semanticCoverageVerified)
    }

    @Test
    fun frozenCombinedPassagesKeepTheirDeliveryContractWithResearchEnabledAndRetainedEvidence() = runBlocking {
        val platform = customPlatform().copy(disableAllTools = true, disableLocalTools = true)
        val original = MessageV2(id = 2, chatId = 1, platformType = null, content = "Choose one interesting historical event under 100 words using the retained research.")
        val text = "Haiti became independent in 1804 after a successful revolution."
        val contributions = listOf(dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse(platform.uid, platform.name, content = text))
        val runId = "combined-synthesis:retained-research-contract"
        val sessions = dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = testFiles.root
        })
        val research = dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot(
            original.content,
            sources = listOf(dev.chungjungsoo.gptmobile.data.research.ResearchSource("S1", "https://example.org/haiti", "Haitian independence", "Read", text)),
            complete = true
        )
        sessions.journal(runId, 1, false, "1:2", ownerScope = platform.uid).save(research)
        val prompt = dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt(original.content, contributions)
        val frozen = dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(
            version = 4,
            deliveryProtocol = "PASSAGES_V2",
            userMessage = original,
            history = emptyList(),
            replies = emptyList(),
            sources = contributions,
            prompt = prompt,
            profileUid = platform.uid,
            model = platform.model,
            systemPrompt = platform.systemPrompt.orEmpty(),
            configKey = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(platform),
            evidence = dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger.freeze(dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freeze(research), emptyList(), emptyMap())
        )
        val raw = frozen.encode()
        val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
        coEvery { persistence.recoveryRun(runId) } returns dev.chungjungsoo.gptmobile.data.database.entity.AgentRun(
            runId, 1, original.id, 3, platform.uid, platform.compatibleType.name, platform.model,
            status = "RUNNING", ownershipFence = 7, displayRole = "COMBINED_FINAL",
            synthesisSnapshot = raw, synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(raw)
        )
        coEvery { persistence.recoveryMessage(original.id) } returns original
        coEvery { persistence.recoveryMessage(3) } returns MessageV2(id = 3, chatId = 1, platformType = platform.uid, content = "", currentRunId = runId, ownershipFence = 7)
        val settings = mockk<SettingRepository>(relaxed = true)
        coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
        coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(queuedFollowUps = true)
        val queued = mockk<dev.chungjungsoo.gptmobile.data.queue.PendingPromptDao>(relaxed = true)
        every { queued.observePending() } returns flowOf(listOf(dev.chungjungsoo.gptmobile.data.queue.PendingPrompt("next-turn", 1, "Change the accepted request", "{}", 1)))
        val framed = buildJsonObject {
            put("type", "passage")
            put("text", text)
            put("profiles", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("C1"))))
            put("claims", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("C1.1"))))
        }.toString() + "\n"
        val api = RecordingOpenAIAPI(ArrayDeque(listOf(flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = framed), finishReason = "stop")))))))
        val tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(
            allToolsDisabled = true,
            research = dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(researchToggle = true, depth = 50)
        )
        val states = withTimeout(10_000) {
            createRepository(openAIAPI = api, settingsOverride = settings, persistenceOverride = persistence, researchOverride = sessions, pendingOverride = queued)
                .completeChat(listOf(original), emptyList(), platform, runId, tools).toList()
        }
        val delivered = states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk }
        assertEquals(states.toString(), text, delivered)
        assertEquals(1, api.requests.size)
        assertFalse(api.requests.single().toString().contains("Untrusted research packet:"))
        assertTrue(states.toString(), states.none { it is ApiState.Error })
        val quality = states.filterIsInstance<ApiState.CombinedQuality>().last().report
        assertTrue(quality.complete)
        assertEquals(dev.chungjungsoo.gptmobile.data.research.researchHash(delivered), quality.answerDigest)
        assertTrue(quality.passages.isNotEmpty())
        assertTrue(quality.passages.all { it.matches(delivered) })
        assertTrue(states.filterIsInstance<ApiState.Notice>().all { it.researchDelivery == null })
        assertEquals(research.sources, sessions.sessions.value.getValue(runId).snapshot.sources)
        io.mockk.verify(exactly = 0) { queued.observePending() }
        io.mockk.coVerify(exactly = 0) { queued.acceptPreparedFollowUp(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun queuedResearchFollowUpIsCheckedAsPartOfTheWholeReportAndReceipt() = runBlocking {
        for ((unsupported, referencesHeading) in listOf(false to false, true to false, true to true)) {
            val platform = customPlatform().copy(disableAllTools = true, disableLocalTools = true)
            val runId = "research-queued-$unsupported-$referencesHeading"
            val original = MessageV2(id = 12, chatId = 1, platformType = null, content = "Summarize the retained synthetic panel study.")
            val passage = "A synthetic panel study measured bending response under laboratory conditions. Long-term durability remains uncertain."
            val source = dev.chungjungsoo.gptmobile.data.research.ResearchSource("S1", "https://example.org/panel-study", "Synthetic panel study", "Read", passage)
            val snapshot = dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot(original.content, sources = listOf(source), complete = false)
            val packet = dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket.build(snapshot, dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail.BRIEF)
            val initial = "Partial coverage. Independent review is not available.\n\n$passage [S1]" +
                if (referencesHeading) "\n\n## References\n\n[S1] ${source.url}" else " ${source.url}"
            val appendix = if (unsupported) "Researchers observed improved recovery in 999 patients. [S1]" else "No verified long-term durability outcome is available. [S1]"
            assertTrue(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryValidator.validate(initial, packet).accepted)
            assertEquals(!unsupported, dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryValidator.validate("$initial\n\n$appendix", packet).accepted)
            val sessions = dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore(object : ContextWrapper(null) {
                override fun getFilesDir(): File = testFiles.root
            })
            sessions.journal(runId, 1, false, "1:12", ownerScope = platform.uid).save(snapshot)
            val tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(
                allToolsDisabled = true,
                research = dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(researchToggle = true, depth = 50)
            )
            val settings = mockk<SettingRepository>(relaxed = true)
            coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
            coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
                queuedFollowUps = true,
                delegation = dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(deepResearch = dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings(reportDetail = dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail.BRIEF))
            )
            val pending = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.queue.PendingPrompt>>(emptyList())
            val prompt = dev.chungjungsoo.gptmobile.data.queue.PendingPrompt("follow-up-$unsupported", 1, "Also explain the limitations", "{}", 1)
            val queued = mockk<dev.chungjungsoo.gptmobile.data.queue.PendingPromptDao>(relaxed = true)
            every { queued.observePending() } returns pending
            coEvery { queued.canAcceptFollowUp(prompt, original.id, runId, platform.uid, platform.model, any(), tools) } returns true
            var admitted = 0
            coEvery { queued.acceptPreparedFollowUp(prompt, original.id, runId, platform.uid, platform.model, any(), tools) } coAnswers {
                admitted++
                pending.value = emptyList()
                true
            }
            fun chunk(text: String) = ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = text), finishReason = "stop")))
            val api = RecordingOpenAIAPI(
                ArrayDeque(
                    listOf(
                        kotlinx.coroutines.flow.flow {
                            pending.value = listOf(prompt)
                            // Finish the fast fixture response only after the real inbox
                            // has made this addition visible and started its grace period.
                            withTimeout(5_000) {
                                dev.chungjungsoo.gptmobile.data.queue.FollowUpProgressStore.state.first {
                                    it[prompt.id]?.phase == dev.chungjungsoo.gptmobile.data.queue.FollowUpPhase.COUNTDOWN
                                }
                            }
                            emit(chunk(initial))
                        },
                        flowOf(chunk(appendix)),
                        flowOf(chunk("{\"replacements\":[],\"append\":\"\"}"))
                    )
                )
            )
            val states = withTimeout(15_000) {
                createRepository(openAIAPI = api, settingsOverride = settings, researchOverride = sessions, pendingOverride = queued)
                    .completeChat(listOf(original), emptyList(), platform, runId, tools).toList()
            }
            assertEquals(states.toString(), 1, admitted)
            assertEquals(if (unsupported) 3 else 2, api.requests.size)
            assertTrue(api.requests[1].toString().contains(original.content))
            assertTrue(api.requests[1].toString().contains(prompt.text))
            assertTrue(api.requests[1].toString().contains("Untrusted research packet:"))
            val delivered = states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk }
            val receipts = states.filterIsInstance<ApiState.Notice>().mapNotNull { it.researchDelivery }
            assertEquals(states.toString(), 1, receipts.size)
            val receipt = receipts.single()
            assertTrue(receipt.toString(), receipt.matchesRevision(delivered))
            assertFalse(receipt.matchesRevision(initial))
            assertTrue(delivered.contains(passage))
            if (unsupported) {
                assertFalse(delivered, delivered.contains("999"))
                assertFalse(receipt.accepted)
                assertEquals(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.EVIDENCE_ONLY, receipt.reportValidity)
                assertTrue(states.any { it is ApiState.Error && it.message.contains("RESEARCH_PARTIAL") })
            } else {
                assertTrue(delivered, delivered.contains(appendix))
                assertTrue(receipt.accepted)
                assertTrue(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryValidator.validate(delivered, packet).accepted)
                assertTrue(states.toString(), states.none { it is ApiState.Error })
            }
        }
    }

    @Test
    fun `cold remote greeting never joins blocked optional tool or memory producers`() = runBlocking {
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val facts = mockk<dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository>(relaxed = true)
        coEvery { facts.scopeForChat(any()) } returns dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope()
        coEvery { facts.prepareTurn(any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        every { facts.state } returns kotlinx.coroutines.flow.MutableStateFlow(dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot())
        val api = RecordingOpenAIAPI(ArrayDeque(listOf(flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Hello"), finishReason = "stop")))))))
        val states = kotlinx.coroutines.withTimeout(1000) {
            createRepository(openAIAPI = api, agentToolResolver = resolver, factVault = facts).completeChat(listOf(MessageV2(content = "Hi", platformType = null)), emptyList(), customPlatform(), "readiness-direct").toList()
        }
        assertEquals(1, api.streamChatCompletionCalls)
        assertTrue(states.none { it is ApiState.Error })
        io.mockk.coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { facts.prepareTurn(any(), any(), any()) }
    }

    @Test
    fun `helpers honor conversation options and approvals with distinct action identities`() = runBlocking {
        val config = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(maxToolCalls = 1).withToolDisabled("other")
        val resolver = mockk<AgentToolResolver>()
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github__work", "GitHub", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("Dispatched"), false)
            }
        }
        coEvery { resolver.resolve("custom-platform", config, null, null, any()) } returns listOf(
            ResolvedAgentTool(tool, "work", "Work account", "github", "github__work", false)
        )
        val approvals = mockk<dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager>(relaxed = true)
        val ids = mutableListOf<String>()
        coEvery { approvals.authorizeOrThrow("work", "parent", capture(ids), "github", any(), any()) } throws dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("AUTH_REQUIRED", "Permission is required")
        val repository = createRepository(agentToolResolver = resolver, toolApprovals = approvals)
        assertTrue(repository.resolveDelegatedTools(customPlatform(), "parent", config.copy(allToolsDisabled = true)).isEmpty())
        val denied = repository.resolveDelegatedTools(customPlatform(), "parent", config).single().execute("same-call", JsonObject(emptyMap()))
        assertTrue(denied.isError)
        assertEquals(0, executions)
        coEvery { approvals.authorizeOrThrow("work", "parent", capture(ids), "github", any(), any()) } returns true
        repeat(2) {
            val bound = repository.resolveDelegatedTools(customPlatform(), "parent", config).single()
            val result = bound.execute("same-call", JsonObject(emptyMap()))
            assertFalse(result.isError)
            assertEquals("same-call", result.callId)
            assertTrue(bound.execute("over-budget", JsonObject(emptyMap())).isError)
        }
        assertEquals(2, executions)
        assertEquals(3, ids.distinct().size)
        ids.drop(1).forEach { id -> io.mockk.coVerify { approvals.finish("parent", id, true) } }
    }

    @Test
    fun `delegated GitHub tools precede generic tools for repository tasks`() = runBlocking {
        val resolver = mockk<AgentToolResolver>()
        val tools = listOf("web_search", "current_date", "read_url", "github__work").map { name ->
            val agent = object : AgentTool {
                override val definition = AgentToolDefinition(name, "Test tool", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("ok"), false)
            }
            ResolvedAgentTool(agent, null, null, if (name == "github__work") "github" else name, name)
        }
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns tools
        val ordered = createRepository(agentToolResolver = resolver).resolveDelegatedTools(
            customPlatform(),
            "parent",
            dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(),
            "Create a branch in the repo"
        )
        assertEquals("github__work", ordered.first().definition.name)
        assertEquals(tools.size, ordered.size)
        assertEquals("github__work", orderPrimaryTools(tools).first().modelToolName)
    }

    @Test
    fun `primary tool ordering preserves catalogs larger than eight tools`() {
        val tools = (1..12).map { index ->
            val agentTool = object : AgentTool {
                override val definition = AgentToolDefinition("tool_$index", "Tool $index", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                    callId,
                    ToolResultContent.Text("unused"),
                    isError = false
                )
            }
            ResolvedAgentTool(
                tool = agentTool,
                connectionUid = null,
                connectionName = null,
                realToolName = "tool_$index",
                modelToolName = agentTool.definition.name
            )
        }

        val ordered = orderPrimaryTools(tools)

        assertEquals(12, ordered.size)
        assertEquals(tools.map { it.modelToolName }.toSet(), ordered.map { it.modelToolName }.toSet())
    }

    @Test
    fun `memory initialization failure and later class failures do not prevent chat replies`() = runBlocking {
        val facts = mockk<FactVaultRepository>(relaxed = true)
        every { facts.state } returns MutableStateFlow(FactVaultSnapshot(enabled = true))
        val api = RecordingOpenAIAPI(ArrayDeque(List(2) { flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Hello"), finishReason = "stop")))) }))
        val repository = createRepository(openAIAPI = api, factVault = facts)
        val failures = listOf(
            ExceptionInInitializerError(PatternSyntaxException("Invalid property", "[:]", 0)),
            NoClassDefFoundError("Could not initialize memory")
        )
        for ((index, error) in failures.withIndex()) {
            coEvery { facts.prepareTurn(any(), any(), any(), any(), any(), any(), any()) } throws error
            val states = repository.completeChat(
                userMessages = listOf(MessageV2(id = index + 1, chatId = 1, content = "Recall my saved preferences", platformType = null)),
                assistantMessages = emptyList(),
                platform = customPlatform(),
                runId = "memory-initializer-$index"
            ).toList()
            assertTrue(states.filterIsInstance<ApiState.Notice>().any { it.message.contains("Local memory is unavailable") })
            assertTrue(states.toString(), states.none { it is ApiState.Error || it is ApiState.MemoryRecalled })
            assertEquals("Hello", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
            val assist = states.filterIsInstance<ApiState.AssistStatus>().single().status
            assertEquals(dev.chungjungsoo.gptmobile.data.assist.AssistAdmission.OFF, assist.admission)
            assertEquals(dev.chungjungsoo.gptmobile.data.assist.AssistExecution.SKIPPED, assist.execution)
        }
        assertEquals(2, api.streamChatCompletionCalls)
    }

    @Test(expected = CancellationException::class)
    fun `memory cancellation still cancels the response`() = runBlocking {
        val facts = mockk<FactVaultRepository>(relaxed = true)
        coEvery { facts.prepareTurn(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("Stopped")
        createRepository(factVault = facts).completeChat(
            userMessages = listOf(MessageV2(id = 1, chatId = 1, content = "Recall my saved preferences", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform(),
            runId = "memory-cancelled"
        ).toList()
        Unit
    }

    @Test
    fun `Free requests neither recall saved facts nor capture new ones`() = runBlocking {
        val storage = object : SecretVault {
            var bytes: ByteArray? = null
            override suspend fun put(secretRef: String, secret: ByteArray) {
                bytes = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = bytes?.copyOf()
            override suspend fun delete(secretRef: String) {
                bytes = null
            }
        }
        val facts = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
        facts.setEnabled(true)
        facts.prepareTurn("I prefer Kotlin", 1, 1)
        val saved = facts.state.value.facts
        assertTrue(saved.isNotEmpty())
        val api = RecordingOpenAIAPI(ArrayDeque(listOf(flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Answer"), finishReason = "stop")))))))
        val states = createRepository(openAIAPI = api, factVault = facts).completeChat(
            userMessages = listOf(MessageV2(id = 2, chatId = 1, content = "I prefer Rust. What language do I prefer?", platformType = null)),
            assistantMessages = emptyList(),
            platform = FreeAiProvider.KILO.applyTo(customPlatform()).copy(systemPrompt = "Base instructions"),
            runId = "free-memory"
        ).toList()
        assertEquals(1, api.streamChatCompletionCalls)
        assertTrue(states.none { it is ApiState.MemoryRecalled })
        assertEquals(saved, facts.state.value.facts)
        assertFalse(api.requests.single().messages.toString().contains("Kotlin"))
        assertFalse(api.requests.single().messages.toString().contains("Saved local facts"))
    }

    @Test
    fun `Free attachments fail before any provider request`() = runBlocking {
        val api = RecordingOpenAIAPI()
        val attachment = ChatAttachment("/private.txt", "/private.txt", "Private", "text/plain", 40, extractedText = "Private memory")
        val states = createRepository(openAIAPI = api).completeChat(
            userMessages = listOf(MessageV2(id = 2, chatId = 1, content = "Summarize", platformType = null, attachments = listOf(attachment))),
            assistantMessages = emptyList(),
            platform = FreeAiProvider.KILO.applyTo(customPlatform()),
            runId = "free-attachment"
        ).toList()
        assertEquals(0, api.streamChatCompletionCalls)
        assertTrue(states.filterIsInstance<ApiState.Error>().any { it.message.contains("without attachments") })
    }

    @Test
    fun `first person facts inside attachments are never attributed to the user`() = runBlocking {
        val storage = object : SecretVault {
            var value: ByteArray? = null
            override suspend fun put(secretRef: String, secret: ByteArray) {
                value = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = value?.copyOf()
            override suspend fun delete(secretRef: String) {
                value = null
            }
        }
        val facts = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
        facts.setEnabled(true)
        val api = FakeGroqAPI(flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "Summary"), finishReason = "stop")))))
        val attachment = ChatAttachment("/unused.txt", "/unused.txt", "Memo", "text/plain", 40, extractedText = "I prefer Rust. I live in Lisbon.")
        createRepository(groqAPI = api, factVault = facts).completeChat(
            userMessages = listOf(MessageV2(id = 9, chatId = 1, content = "Summarize this memo", platformType = null, attachments = listOf(attachment))),
            assistantMessages = emptyList(),
            platform = groqPlatform(false, "model"),
            runId = "attachment-memory"
        ).toList()
        assertEquals(1, api.streamCalls)
        assertTrue(facts.state.value.facts.isEmpty())
    }

    @Test
    fun `research recall reaches actual request only with consent and dispatch validation`() = runBlocking {
        for (mode in listOf("denied", "allowed", "revoked")) {
            val storage = object : SecretVault {
                private val values = mutableMapOf<String, ByteArray>()
                override suspend fun put(secretRef: String, secret: ByteArray) {
                    values[secretRef] = secret.copyOf()
                }
                override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
                override suspend fun delete(secretRef: String) {
                    values.remove(secretRef)
                }
            }
            val facts = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
            facts.setEnabled(true)
            facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = true))
            val platform = groqPlatform(false, "model")
            val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
                researchFeature = dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings(researchMemory = true, shareResearchRecall = mode != "denied")
            ).withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH, true)
            val settings = mockk<SettingRepository>(relaxed = true)
            coEvery { settings.getFeatureSettings() } returns features
            coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
            val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
            coEvery { persistence.getChatRoom(1) } returns dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2(id = 1, title = "Research fixture", enabledPlatform = listOf(platform.uid))
            val recall = mockk<dev.chungjungsoo.gptmobile.data.research.memory.ResearchRecallProvider>()
            val evidence = "Historical study reported 10 points; https://example.org/trial; limitations: one small trial."
            coEvery { recall.recall(any(), any()) } coAnswers {
                val permitted = arg<suspend () -> Boolean>(1)
                dev.chungjungsoo.gptmobile.data.research.memory.ResearchRecallPacket(text = if (permitted()) evidence else "")
            }
            coEvery { recall.validate(any(), any()) } returns (mode != "revoked")
            val api = FakeGroqAPI(flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "The historical trial needs cautious interpretation."), finishReason = "stop")))), enforcePhysicalBoundary = true)
            val states = createRepository(groqAPI = api, factVault = facts, settingsOverride = settings, persistenceOverride = persistence, recallOverride = recall)
                .completeChat(listOf(MessageV2(id = 2, chatId = 1, content = "Recall the saved research and compare the mobility trial", platformType = null)), emptyList(), platform, runId = "research-$mode").toList()
            if (mode == "revoked") {
                assertEquals(0, api.streamCalls)
                assertTrue(states.any { it is ApiState.Error && it.recoveryBlock?.code == "SOURCE_PERMISSION_REVOKED" })
            } else {
                assertEquals(1, api.streamCalls)
                assertEquals(mode == "allowed", api.lastRequest!!.messages.first().content.toString().contains(evidence))
            }
            assertTrue(facts.state.value.facts.none { it.fact.toString().contains("10 points") })
        }
    }

    @Test
    fun `saved facts prefix both cloud and local system prompts`() = runBlocking {
        val storage = object : SecretVault {
            var bytes: ByteArray? = null
            override suspend fun put(secretRef: String, secret: ByteArray) {
                bytes = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = bytes?.copyOf()
            override suspend fun delete(secretRef: String) {
                bytes = null
            }
        }
        val facts = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(storage, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
        facts.setEnabled(true)
        facts.updateSettings(facts.state.value.settings.copy(allowCloudRecall = true))
        facts.prepareTurn("I prefer Kotlin", 1, 1)
        val api = FakeGroqAPI(emptyFlow())
        val cloud = createRepository(groqAPI = api, factVault = facts)
        val cloudStates = cloud.completeChat(
            userMessages = listOf(MessageV2(id = 2, chatId = 1, content = "Recall my preferences and help me with Kotlin", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = false, model = "qwen/qwen3-32b").copy(systemPrompt = "Base instructions"),
            runId = "memory-cloud"
        ).toList()
        val system = api.lastRequest!!.messages.first()
        assertEquals(dev.chungjungsoo.gptmobile.data.dto.openai.common.Role.SYSTEM, system.role)
        assertTrue(system.content.toString().contains("Kotlin"))
        assertTrue(system.content.toString().contains("Base instructions"))
        assertEquals(1, cloudStates.filterIsInstance<ApiState.MemoryRecalled>().single().facts.size)

        val runtime = FakeLocalRuntime()
        val local = createRepository(localRuntime = runtime, localModelRepository = FakeLocalModelRepository(downloadedPaths = mapOf("gemma3-1b-it" to "/models/gemma.litertlm")), factVault = facts)
        local.completeChat(
            userMessages = listOf(MessageV2(id = 3, chatId = 1, content = "Kotlin", platformType = null)),
            assistantMessages = emptyList(),
            platform = localPlatform(),
            runId = "memory-local"
        ).toList()
        assertTrue(runtime.createConversationCalls.single().systemPrompt.orEmpty().startsWith("Saved local facts"))
        assertTrue(runtime.createConversationCalls.single().systemPrompt.orEmpty().contains("Kotlin"))
    }

    @Test(expected = IllegalStateException::class)
    fun `blank response input without encodable parts throws`() {
        validateResponseInputPartsOrThrow("", 0, 42)
    }

    @Test
    fun `response input with text does not throw when image encoding fails`() {
        validateResponseInputPartsOrThrow("hello", 0, 42)
    }

    @Test
    fun `response input with encoded image parts does not throw when text is blank`() {
        validateResponseInputPartsOrThrow("", 1, 42)
    }

    @Test
    fun `complete chat emits loading before request preparation`() = runBlocking {
        val repository = createRepository()
        val firstState = withTimeout(100) {
            repository.completeChat(
                userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
                assistantMessages = emptyList(),
                platform = customPlatform(),
                runId = "test-run"
            ).first()
        }

        assertEquals(ApiState.Loading, firstState)
    }

    @Test
    fun `groq path uses groq api and emits parsed reasoning`() = runBlocking {
        val groqAPI = FakeGroqAPI(
            flowOf(
                GroqChatCompletionChunk(
                    choices = listOf(
                        GroqChoice(
                            index = 0,
                            delta = GroqDelta(
                                reasoning = "Plan",
                                content = "Answer"
                            ),
                            finishReason = "stop"
                        )
                    )
                )
            )
        )
        val openAIAPI = RecordingOpenAIAPI()
        val repository = createRepository(
            groqAPI = groqAPI,
            openAIAPI = openAIAPI
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = true, model = "qwen/qwen3-32b"),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit.") || it.message.startsWith("Local context:"))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Plan"),
                ApiState.Success("Answer"),
                ApiState.Done
            ),
            states
        )
        assertEquals(1, groqAPI.streamCalls)
        assertEquals(0, openAIAPI.streamChatCompletionCalls)
        assertEquals(4096, groqAPI.lastRequest?.maxCompletionTokens)
    }

    @Test
    fun `local documents reach native history and current prompt exactly once`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("Summary"), LocalRuntimeEvent.Done))
        }
        val repository = createRepository(
            localRuntime = runtime,
            modelCatalogRepository = FakeModelCatalogRepository(listOf(CatalogEntry(id = "gemma3-1b-it", maxContextTokens = 8192))),
            localModelRepository = FakeLocalModelRepository(downloadedPaths = mapOf("gemma3-1b-it" to "/models/gemma.litertlm"))
        )
        val document = ChatAttachment(
            localFilePath = "/unopened/document.pdf",
            preparedFilePath = "",
            displayName = "document.pdf",
            mimeType = "application/pdf",
            sizeBytes = 12,
            extractedText = "document contents"
        )
        val states = repository.completeChat(
            userMessages = listOf(
                MessageV2(id = 1, content = "First document", platformType = null, attachments = listOf(document.copy(extractedText = "prior document"))),
                MessageV2(id = 2, content = "Summarize", platformType = null, attachments = listOf(document))
            ),
            assistantMessages = listOf(listOf(MessageV2(content = "Prior answer", platformType = localPlatform().uid))),
            platform = localPlatform(),
            runId = "local-documents"
        ).toList()
        assertFalse(states.any { it is ApiState.Error })
        assertEquals(1, Regex("document contents").findAll(runtime.sendMessageCalls.single()).count())
        assertTrue("Document history was omitted: $states", runtime.createConversationCalls.single().initialMessages.isNotEmpty())
        assertEquals(1, Regex("prior document").findAll(runtime.createConversationCalls.single().initialMessages.first().text).count())
        assertFalse(states.filterIsInstance<ApiState.Notice>().any { it.message == LiteRtLmAdapter.DEFAULT_IGNORED_ATTACHMENTS })
    }

    @Test
    fun `litert lm path uses local runtime and streams thinking and text`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.ThinkingDelta("plan"),
                    LocalRuntimeEvent.TextDelta("hello"),
                    LocalRuntimeEvent.Done
                )
            )
        }
        val repository = createRepository(
            localRuntime = runtime,
            localModelRepository = FakeLocalModelRepository(
                downloadedPaths = mapOf("gemma3-1b-it" to "/models/gemma.litertlm")
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = localPlatform(),
            runId = "local-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit.") || it.message.startsWith("Local context:"))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.PhaseChanged(LocalInferencePhase.INSPECTING),
                ApiState.PhaseChanged(LocalInferencePhase.LOADING),
                ApiState.Notice(LiteRtLmAdapter.DEFAULT_LOADING_MODEL),
                ApiState.Thinking("plan"),
                ApiState.Success("hello"),
                ApiState.Done
            ),
            states
        )
        assertEquals(listOf("Hi"), runtime.sendMessageCalls)
        assertEquals(1, runtime.loadEngineCalls.size)
    }

    @Test
    fun `litert lm tool capable run records engine owned tool calls on the timeline`() = runBlocking {
        val dateTool = dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool()
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns listOf(
            ResolvedAgentTool(dateTool, null, null, dateTool.definition.name, dateTool.definition.name)
        )
        val runtime = FakeLocalRuntime().apply {
            // The native planner retains the date tool while respecting this model's context.
            deviceRamGb = 12L
            scriptedEvents = listOf(
                listOf(
                    LocalRuntimeEvent.TextDelta("before"),
                    LocalRuntimeEvent.TextDelta("after"),
                    LocalRuntimeEvent.Done
                )
            )
            scriptedToolInvocations = listOf(
                listOf(ScriptedToolInvocation("current_date", "{}"))
            )
        }
        val traceDao = RecordingToolEventDao()
        val repository = createRepository(
            localRuntime = runtime,
            localModelRepository = FakeLocalModelRepository(
                downloadedPaths = mapOf("gemma3-1b-it" to "/models/gemma.litertlm")
            ),
            modelCatalogRepository = FakeModelCatalogRepository(
                listOf(CatalogEntry(id = "gemma3-1b-it", capabilities = CatalogCapabilities(tools = true)))
            ),
            agentToolResolver = resolver,
            toolEventRecorder = ToolEventRecorder(traceDao.asDao(), proxy())
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "What is today's date?", platformType = null)),
            assistantMessages = emptyList(),
            platform = localPlatform().copy(maxTokens = 2048),
            runId = "run-local-tool"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit.") || it.message.startsWith("Local context:"))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.PhaseChanged(LocalInferencePhase.INSPECTING),
                ApiState.PhaseChanged(LocalInferencePhase.LOADING),
                ApiState.Notice(LiteRtLmAdapter.DEFAULT_LOADING_MODEL),
                ApiState.Success("before"),
                ApiState.ToolCall(toolSequence = 0),
                ApiState.ToolCall(toolSequence = 0),
                ApiState.Success("after"),
                ApiState.Done
            ),
            states.map { if (it is ApiState.ToolCall) it.copy(metrics = null) else it }
        )
        val completedMetrics = states.filterIsInstance<ApiState.ToolCall>().last().metrics
        assertTrue(completedMetrics?.durationMs != null)
        assertTrue(completedMetrics?.resultBytes != null)
        assertEquals(1, runtime.sendMessageCalls.size)
        assertEquals(
            listOf(
                dev.chungjungsoo.gptmobile.data.localruntime.LocalToolDescriptor(
                    dateTool.definition.name,
                    dateTool.definition.description,
                    dateTool.definition.inputSchema.toString()
                )
            ),
            runtime.createConversationCalls.single().tools
        )
        assertTrue(runtime.createConversationCalls.single().isConstrainedDecodingEnabled)
        assertEquals(2048, runtime.createConversationCalls.single().maxOutputTokens)
        val event = traceDao.events.single()
        assertEquals("run-local-tool", event.runId)
        assertEquals("current_date", event.toolName)
        assertEquals(ToolEventStatus.COMPLETED, event.status)
        assertFalse(event.isError)
    }

    @Test
    fun `litert GPU package keeps MCP tools without catalog tool recommendation`() = runBlocking {
        val runtime = FakeLocalRuntime().apply {
            deviceRamGb = 12L
            scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("tools visible"), LocalRuntimeEvent.Done))
        }
        val mcpTool = object : AgentTool {
            override val definition = AgentToolDefinition(
                name = "mcp__search__web_search",
                description = "Search the web through the connected MCP server.",
                inputSchema = JsonObject(emptyMap())
            )

            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Text("unused"),
                isError = false
            )
        }
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns listOf(
            ResolvedAgentTool(
                tool = mcpTool,
                connectionUid = "search-server",
                connectionName = "Search MCP",
                realToolName = "web_search",
                modelToolName = mcpTool.definition.name,
                shareableReadOnly = true
            )
        )
        val repository = createRepository(
            localRuntime = runtime,
            localModelRepository = FakeLocalModelRepository(
                downloadedPaths = mapOf("gemma3-1b-it-litert" to "/models/gemma-gpu.litertlm")
            ),
            modelCatalogRepository = FakeModelCatalogRepository(
                listOf(CatalogEntry(id = "gemma3-1b-it-litert", capabilities = CatalogCapabilities(tools = false)))
            ),
            agentToolResolver = resolver
        )

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Help me plan a project; use available tools when needed.", platformType = null)),
            assistantMessages = emptyList(),
            platform = localPlatform().copy(maxTokens = 512),
            runId = "run-local-litert-tools"
        ).toList()

        assertEquals(
            listOf("mcp__search__web_search"),
            runtime.createConversationCalls.single().tools.map { it.name }.sorted()
        )
        assertTrue(runtime.createConversationCalls.single().isConstrainedDecodingEnabled)
    }

    @Test
    fun `groq token limit reports failure instead of completing with only thinking`() = runBlocking {
        val groqAPI = FakeGroqAPI(
            flowOf(
                GroqChatCompletionChunk(
                    choices = listOf(
                        GroqChoice(
                            index = 0,
                            delta = GroqDelta(reasoning = "Still reasoning"),
                            finishReason = "length"
                        )
                    )
                )
            )
        )
        val repository = createRepository(groqAPI = groqAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = true, model = "qwen/qwen3.6-27b"),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Still reasoning"),
                ApiState.Error("Groq reached the model output limit before producing a final answer."),
                ApiState.Done
            ),
            states
        )
    }

    @Test
    fun `groq raw think fallback populates thinking state`() = runBlocking {
        val groqAPI = FakeGroqAPI(
            flowOf(
                GroqChatCompletionChunk(
                    choices = listOf(
                        GroqChoice(
                            index = 0,
                            delta = GroqDelta(content = "<think>Secret</think>\nVisible"),
                            finishReason = "stop"
                        )
                    )
                )
            )
        )
        val repository = createRepository(groqAPI = groqAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = true, model = "qwen/qwen3-32b"),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Thinking("Secret"),
                ApiState.Success("Visible"),
                ApiState.Done
            ),
            states
        )
    }

    @Test
    fun `groq reasoning disabled hides qwen reasoning`() = runBlocking {
        val groqAPI = FakeGroqAPI(emptyFlow())
        val repository = createRepository(groqAPI = groqAPI)

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = false, model = "qwen/qwen3-32b"),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        val request = groqAPI.lastRequest
        assertEquals("hidden", request?.reasoningFormat)
        assertNull(request?.includeReasoning)
        assertNull(request?.reasoningEffort)
    }

    @Test
    fun `groq reasoning disabled uses low effort and hides gpt oss reasoning output`() = runBlocking {
        val groqAPI = FakeGroqAPI(emptyFlow())
        val repository = createRepository(groqAPI = groqAPI)

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = groqPlatform(reasoning = false, model = "openai/gpt-oss-20b"),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        val request = groqAPI.lastRequest
        assertNull(request?.reasoningFormat)
        assertEquals(false, request?.includeReasoning)
        assertEquals("low", request?.reasoningEffort)
    }

    @Test
    fun `google request includes configured safety settings`() = runBlocking {
        val googleAPI = FakeGoogleAPI(flowOf(GenerateContentResponse(candidates = listOf(Candidate(content = Content(parts = listOf(Part.text("Answer"))), finishReason = "STOP")))))
        val repository = createRepository(googleAPI = googleAPI)

        repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = googlePlatform(),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(1, googleAPI.streamCalls)
        assertEquals(
            listOf(
                GeminiSafetySettings.HARM_CATEGORY_HARASSMENT to GeminiSafetySettings.BLOCK_LOW_AND_ABOVE,
                GeminiSafetySettings.HARM_CATEGORY_HATE_SPEECH to GeminiSafetySettings.BLOCK_MEDIUM_AND_ABOVE,
                GeminiSafetySettings.HARM_CATEGORY_SEXUALLY_EXPLICIT to GeminiSafetySettings.BLOCK_ONLY_HIGH,
                GeminiSafetySettings.HARM_CATEGORY_DANGEROUS_CONTENT to GeminiSafetySettings.BLOCK_NONE
            ),
            googleAPI.lastRequest?.safetySettings?.map { it.category to it.threshold }
        )
    }

    @Test
    fun `google prompt safety block emits error`() = runBlocking {
        val repository = createRepository(
            googleAPI = FakeGoogleAPI(
                flowOf(
                    GenerateContentResponse(
                        promptFeedback = PromptFeedback(blockReason = "SAFETY")
                    )
                )
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = googlePlatform(),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Error("Gemini safety settings blocked the prompt: SAFETY"),
                ApiState.Done
            ),
            states
        )
    }

    @Test
    fun `google safety finish reason emits error`() = runBlocking {
        val repository = createRepository(
            googleAPI = FakeGoogleAPI(
                flowOf(
                    GenerateContentResponse(
                        candidates = listOf(Candidate(finishReason = "SAFETY"))
                    )
                )
            )
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = googlePlatform(),
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Error("Gemini safety settings blocked the response."),
                ApiState.Done
            ),
            states
        )
    }

    @Test
    fun `failed historical turn is excluded from subsequent inline budget checks`() = runBlocking {
        val openAIAPI = RecordingOpenAIAPI(ArrayDeque(listOf(flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Answer"), finishReason = "stop")))))))
        val repository = createRepository(openAIAPI = openAIAPI)
        val tempDir = kotlin.io.path.createTempDirectory("context-inline-budget").toFile().apply {
            deleteOnExit()
        }
        val missingAttachmentFile = File(tempDir, "oversized-${UUID.randomUUID()}.png")
        if (missingAttachmentFile.exists()) {
            missingAttachmentFile.delete()
        }
        assertFalse(missingAttachmentFile.exists())
        val failedTurnAttachment = ChatAttachment(
            localFilePath = missingAttachmentFile.absolutePath,
            preparedFilePath = missingAttachmentFile.absolutePath,
            displayName = "oversized.png",
            mimeType = "image/png",
            sizeBytes = 13L * 1024 * 1024
        )
        val customPlatform = customPlatform()

        val states = repository.completeChat(
            userMessages = listOf(
                MessageV2(
                    id = 1,
                    content = "",
                    platformType = null,
                    attachments = listOf(failedTurnAttachment)
                ),
                MessageV2(
                    id = 2,
                    content = "Try again with text only",
                    platformType = null
                )
            ),
            assistantMessages = listOf(
                listOf(
                    MessageV2(
                        id = 11,
                        content = "Error: These images are too large to upload safely on this provider.",
                        platformType = customPlatform.uid
                    )
                ),
                listOf(
                    MessageV2(
                        id = 12,
                        content = "",
                        platformType = customPlatform.uid
                    )
                )
            ),
            platform = customPlatform,
            runId = "test-run"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(listOf(ApiState.Loading, ApiState.Success("Answer"), ApiState.Done), states)
        assertEquals(1, openAIAPI.streamChatCompletionCalls)
    }

    @Test
    fun `complete chat rejects unapproved web search and persists its trace`() = runBlocking {
        val connection = ToolConnection(
            connectionUid = "search-1",
            name = "Fixture search",
            alias = "fixture_search",
            type = ToolConnectionType.FIRECRAWL,
            endpointUrl = "https://api.firecrawl.dev/v2/search",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = null,
            oauthClientId = null
        )
        val toolDao = SingleToolConnectionDao(
            connection,
            AgentToolBinding(
                bindingUid = "binding-1",
                profileUid = "custom-platform",
                connectionUid = connection.connectionUid,
                toolName = "web_search"
            )
        )
        val vault = MapSecretVault(emptyMap())
        val traceDao = RecordingToolEventDao()
        val openAIAPI = RecordingOpenAIAPI(
            ArrayDeque(
                listOf(
                    flowOf(
                        ChatCompletionChunk(
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    delta = Delta(
                                        content = "before",
                                        toolCalls = listOf(
                                            ChatToolCallDelta(
                                                index = 0,
                                                id = "call_exact",
                                                function = ChatFunctionDelta("web_search", "{\"query\":\"fixture\"}")
                                            )
                                        )
                                    ),
                                    finishReason = "tool_calls"
                                )
                            )
                        )
                    ),
                    flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "after"), finishReason = "stop"))))
                )
            )
        )
        val repository = createRepository(
            openAIAPI = openAIAPI,
            agentToolResolver = toolResolver(toolDao, vault),
            toolEventRecorder = ToolEventRecorder(traceDao.asDao(), proxy())
        )

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Search", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform(),
            runId = "run-web",
            chatToolConfig = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(connection.connectionUid))
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Success("before"),
                ApiState.ToolCall(toolSequence = 0),
                ApiState.ToolCall(toolSequence = 0),
                ApiState.Success("after"),
                ApiState.Done
            ),
            states.map { if (it is ApiState.ToolCall) it.copy(metrics = null) else it }
        )
        val completedMetrics = states.filterIsInstance<ApiState.ToolCall>().last().metrics
        assertTrue(completedMetrics?.durationMs != null)
        assertTrue(completedMetrics?.resultBytes != null)
        assertEquals(
            listOf("web_search"),
            openAIAPI.requests.first().tools!!.map { it.function.name }.sorted()
        )
        assertEquals("call_exact", openAIAPI.requests.last().messages.takeLast(2).first().toolCalls!!.single().id)
        val event = traceDao.events.single()
        assertEquals("run-web", event.runId)
        assertEquals("call_exact", event.callId)
        assertEquals("Multi-engine search", event.connectionNameSnapshot)
        assertEquals(ToolEventStatus.FAILED, event.status)
        // A disconnected tool is rejected at its permission boundary, rather than dispatched with missing credentials.
        assertTrue(event.result.orEmpty(), event.result.orEmpty().contains("Tool permission was denied"))
    }

    @Test
    fun `complete chat propagates circuit breaker error as classified user message`() = runBlocking {
        val failingOpenAIAPI = object : OpenAIAPI by RecordingOpenAIAPI() {
            override fun streamChatCompletion(
                request: ChatCompletionRequest,
                timeoutSeconds: Int,
                config: ProviderRequestConfig
            ): Flow<ChatCompletionChunk> = kotlinx.coroutines.flow.flow {
                throw CircuitBreakerOpenException(5000L, "OpenAI")
            }
        }
        val repository = createRepository(openAIAPI = failingOpenAIAPI)

        val states = repository.completeChat(
            userMessages = listOf(MessageV2(content = "Hi", platformType = null)),
            assistantMessages = emptyList(),
            platform = customPlatform(),
            runId = "test-cb"
        ).toList().filterNot { it is ApiState.AssistStatus || it is ApiState.GatewayProgressChanged || it is ApiState.ProgressCheckpoint || (it is ApiState.Notice && (it.message.startsWith("Context estimate:") || it.message.startsWith("Context: no app-imposed limit."))) }

        assertEquals(
            listOf(
                ApiState.Loading,
                ApiState.Error("Service temporarily unavailable due to high error rates. Please wait a moment before trying again."),
                ApiState.Done
            ),
            states
        )
    }

    @Test fun knownForbiddenWriterBlocksContinueBeforeToolPreparationAndExplicitRetryRechecksOnlyWriter() = runBlocking {
        val api = RecordingOpenAIAPI(
            ArrayDeque(
                listOf(
                    flowOf(ChatCompletionChunk(error = dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail(message = "HTTP 403: access forbidden; specific cause unavailable", code = "403"))),
                    flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = "Resumed final answer"), finishReason = "stop"))))
                )
            )
        )
        var toolResolutions = 0
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } answers {
            toolResolutions++
            emptyList()
        }
        val repository = createRepository(openAIAPI = api, agentToolResolver = resolver)
        val original = MessageV2(id = 1, chatId = 0, content = "Compare synthetic materials", platformType = null)
        val first = repository.completeChat(listOf(original), emptyList(), customPlatform(), "first", null).toList()
        assertTrue(first.any { it is ApiState.Error && it.message.contains("403") })
        val afterFailure = toolResolutions
        val continued = repository.completeChat(listOf(original, original.copy(id = 3, content = "Continue")), emptyList(), customPlatform(), "continue", null).toList()
        assertTrue(continued.any { it is ApiState.Error && it.message.contains("Final writer unavailable") })
        assertEquals(1, api.streamChatCompletionCalls)
        assertEquals(afterFailure, toolResolutions)
        val descriptor = dev.chungjungsoo.gptmobile.data.agent.TaskResumeDescriptor("0:1", 1, "retry", dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_FAILED_STAGE)
        val pending = MessageV2(id = 2, chatId = 0, content = "", platformType = customPlatform().uid, currentRunId = "retry", timeline = listOf(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, recovery = descriptor)))
        val retry = repository.completeChat(listOf(original), listOf(listOf(pending)), customPlatform(), "retry", null).toList()
        assertTrue(retry.any { it is ApiState.Success && it.textChunk.contains("Resumed final answer") })
        assertEquals(2, api.streamChatCompletionCalls)
    }

    @Test fun helperCapabilitiesIntersectOwnersAndRejectAmbiguousAliases() {
        fun resolved(owner: String, alias: String = "work", name: String = "work") = ResolvedAgentTool(
            object : AgentTool {
                override val definition = AgentToolDefinition(alias, "fixture", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("ok"), false)
            },
            owner,
            owner,
            name,
            alias
        )
        val parent = listOf(resolved("one"), resolved("two", "other"), resolved("one", "delegate_to_model", "delegate_to_model"))
        assertTrue(delegatedCapabilityIntersection(parent, emptyList()).isEmpty())
        assertTrue(delegatedCapabilityIntersection(listOf(parent.first()), listOf(resolved("two"))).isEmpty())
        assertEquals(listOf(parent.first()), delegatedCapabilityIntersection(parent, listOf(parent.first(), parent.last())))
        assertTrue(runCatching { delegatedCapabilityIntersection(listOf(resolved("one"), resolved("two")), listOf(resolved("one"))) }.isFailure)
        assertTrue(runCatching { delegatedCapabilityIntersection(listOf(resolved("one")), listOf(resolved("one"), resolved("two"))) }.isFailure)
    }

    @Test fun incidentReplayRetainsEvidenceAcrossTwentyFourHelperRequestsWriter403AndContinue() = runBlocking {
        val primary = customPlatform()
        val helper = primary.copy(uid = "fixture-helper", name = "Private fixture helper", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:11434/v1", model = "qwen3-8b", maxTokens = 4096)
        val config = dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(
            enabled = true, targetProfileUid = helper.uid, processingOwnership = 50,
            reviewerEnabled = true, reviewerProfileUid = primary.uid,
            maxLocalModelCalls = 48, maxCallsPerTurn = 16, maxInputCharacters = 16000,
            maxInputTokensPerDelegate = 16000, maxOutputTokens = 2048,
            deepResearch = dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings(schemaVersion = 3, customSourceTarget = 12, effort = 50, concurrency = 1)
        ).normalized()
        val settings = mockk<SettingRepository>(relaxed = true)
        coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(delegation = config, profileBehavior = mapOf(primary.uid to dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings(delegationEnabled = true)))
        coEvery { settings.fetchPlatformV2s() } returns listOf(primary, helper)
        val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
        coEvery { persistence.getIncompleteRuns(any(), any()) } returns emptyList()
        coEvery { persistence.getCompletedRuns(any()) } returns emptyList()
        coEvery { persistence.getMessages(any()) } returns emptyList()
        coEvery { persistence.getChatRoom(1) } returns dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2(id = 1, title = "Synthetic incident", enabledPlatform = listOf(primary.uid))
        var repeatedReads = 0
        fun tool(name: String) = ResolvedAgentTool(
            object : AgentTool {
                override val definition = AgentToolDefinition(name, "Synthetic fixture", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    repeatedReads++
                    error("Completed acquisition was repeated")
                }
            },
            null,
            "Fixture",
            name,
            name,
            shareableReadOnly = true
        )
        val reader = tool("read_url")
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns listOf(reader, tool("delegate_to_model"))
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = testFiles.root
        }
        val sessions = dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore(context)
        val task = "Compare synthetic composite panel performance using research evidence"
        val passage = "The synthetic panel report measured performance under the stated conditions; uncertainty and scope remain explicit."
        val sources = (1..207).map { index ->
            dev.chungjungsoo.gptmobile.data.research.ResearchSource(
                "S$index",
                "https://example.org/panel/$index",
                "Synthetic panel report $index",
                status = when {
                    index <= 129 -> "Metadata only"
                    index <= 176 -> "Discovered"
                    index <= 195 -> "Failed"
                    index <= 202 -> "Partially read"
                    else -> "Read"
                },
                passage = if (index > 195) passage else "",
                paperId = if (index >= 206) "paper-$index" else "",
                retrievedAt = System.currentTimeMillis()
            )
        }
        val authority = dev.chungjungsoo.gptmobile.data.research.researchHash(config.deepResearch.collectionPolicyKey() + reader.selectionId())
        sessions.journal("acquisition", 1, false, "1:1", ownerScope = primary.uid).scoped(authority, true)
            .save(dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot(task, sources = sources, targetSources = 12))
        fun chunk(text: String) = flowOf(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = text), finishReason = "stop"))))
        val rounds = ArrayDeque<Flow<ChatCompletionChunk>>()
        rounds.add(chunk("""{"questions":[{"id":"Q1","text":"What are the panel findings?"}],"queries":["synthetic panel performance"]}"""))
        var reviewed = 0
        for (index in 196..207) {
            val count = if (index == 196 || index == 206) 2 else 1
            rounds.add(
                chunk(
                    buildJsonObject {
                        put(
                            "claims",
                            JsonArray(
                                (1..count).map { n ->
                                    buildJsonObject {
                                        put("sourceId", "S$index")
                                        put("text", "Panel finding $index/$n")
                                        put("questionId", "Q1")
                                        put("quote", passage)
                                    }
                                }
                            )
                        )
                    }.toString()
                )
            )
            if (index < 206) {
                repeat(count) {
                    reviewed++
                    rounds.add(chunk("""{"verdict":"${if (reviewed <= 5) "Supported" else "Insufficient"}","relevance":"Relevant","questionId":"Q1"}"""))
                }
            }
        }
        assertEquals(24, rounds.size)
        rounds.add(flowOf(ChatCompletionChunk(error = dev.chungjungsoo.gptmobile.data.dto.openai.response.ErrorDetail(message = "HTTP 403: access forbidden; specific cause unavailable", code = "403"))))
        rounds.add(
            kotlinx.coroutines.flow.flow {
                val snapshot = sessions.sessions.value.values.maxBy { it.snapshot.updatedAt }.snapshot
                val packet = dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket.build(snapshot, config.deepResearch.reportDetail)
                val report = buildString {
                    append("## Executive findings\n\nFinal answer from saved synthetic evidence. Partial coverage; independent review unavailable.\n\n")
                    append("## Scientific background\n\nA verified background synthesis is unavailable.\n\n")
                    append("## Leading studies\n\n$passage ")
                    append(packet.studies.joinToString(" ") { "[${it.id}]" })
                    append("\n\n## Latest papers\n\nUnread leads; results were not verified: ")
                    append(packet.unread.joinToString(" ") { "[${it.id}]" })
                    append("\n\n## Comparison of evidence\n\nA verified comparison is unavailable.\n\n")
                    append("## Safety and limitations\n\nSafety is not established.\n\n")
                    append("## Research gaps and outlook\n\nIndependent review remains unavailable.\n\n")
                    append("## References\n\n")
                    (packet.studies + packet.unread).forEach { append("[${it.id}] ${it.url}\n") }
                }
                val adapted = dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryPacket.SECTIONS.zip(packet.sections).fold(report) { text, (old, new) -> text.replace("## $old", "## $new") }
                assertTrue(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryValidator.validate(adapted, packet).accepted)
                emit(ChatCompletionChunk(choices = listOf(Choice(0, Delta(content = adapted), finishReason = "stop"))))
            }
        )
        val api = RecordingOpenAIAPI(rounds)
        val repository = createRepository(openAIAPI = api, agentToolResolver = resolver, settingsOverride = settings, persistenceOverride = persistence, researchOverride = sessions, toolEventRecorder = ToolEventRecorder(RecordingToolEventDao().asDao(), proxy()))
        val original = MessageV2(id = 1, chatId = 1, content = task, platformType = null)
        val first = repository.completeChat(listOf(original), emptyList(), primary, "run-a", null).toList()
        assertTrue(first.toString(), first.any { it is ApiState.Error && it.message.contains("403") })
        assertEquals(24, api.requests.count { it.model == helper.model })
        val saved = sessions.sessions.value.values.filter { it.runId == "run-a" }.maxBy { it.snapshot.updatedAt }.snapshot
        assertEquals(207, saved.sources.size)
        assertEquals("UNAVAILABLE", saved.currentReviewRecord()?.status)
        assertEquals(12, saved.sources.count { it.readable })
        assertEquals(mapOf("Supported" to 5, "Insufficient" to 6, "Unreviewed" to 3), saved.claims.groupingBy { it.verdict }.eachCount())
        val continued = repository.completeChat(listOf(original, original.copy(id = 3, content = "Continue")), emptyList(), primary, "run-b", null).toList()
        assertTrue(continued.any { it is ApiState.Error && it.message.contains("Final writer unavailable") })
        assertEquals(25, api.streamChatCompletionCalls)
        val retried = repository.completeChat(listOf(original, original.copy(id = 4, content = "Retry failed stage")), emptyList(), primary, "run-c", null).toList()
        assertTrue(retried.toString(), retried.any { it is ApiState.Success && it.textChunk.contains("Final answer from saved") })
        assertEquals(26, api.streamChatCompletionCalls)
        assertTrue(api.requests.last().toString().contains("Panel finding"))
        assertTrue(api.requests.last().toString().contains("handoffReviewStatus"))
        assertTrue(api.requests.last().toString().contains("UNAVAILABLE"))
        assertEquals(listOf("read_recovery_context"), api.requests.last().tools.orEmpty().map { it.function.name })
        assertEquals(24, api.requests.count { it.model == helper.model })
        assertEquals(0, repeatedReads)
    }

    @Test
    fun unusedRevokedToolCannotBlockAnOrdinaryChatRequest() = runBlocking {
        val resolver = mockk<AgentToolResolver>()
        val inactive = object : AgentTool {
            override val definition = AgentToolDefinition("stale_search", "No longer available", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) =
                AgentToolResult(callId, ToolResultContent.Text("Should not execute"), true)
        }
        coEvery { resolver.resolve(any(), any(), any(), any(), any(), any(), any()) } returns listOf(
            ResolvedAgentTool(inactive, "stale-connection", "Unavailable", "stale_search", "stale_search", canReuseResult = { false })
        )
        val api = FakeGroqAPI(
            flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "Chat is working"), finishReason = "stop")))),
            enforcePhysicalBoundary = true
        )
        val states = createRepository(groqAPI = api, agentToolResolver = resolver).completeChat(
            listOf(MessageV2(id = 22, chatId = 0, content = "Say hello", platformType = null)),
            emptyList(),
            groqPlatform(false, "working-model"),
            "unused-revoked-tool",
            null
        ).toList()
        assertEquals(states.toString(), 1, api.streamCalls)
        assertTrue(states.toString(), states.any { it is ApiState.Success && it.textChunk.contains("Chat is working") })
        assertFalse(states.any { it is ApiState.Error && it.message.contains("SOURCE_PERMISSION_REVOKED") })
    }

    @Test
    fun freshMessageCannotAutomaticallyRecoverUnrelatedPreviousTurn() = runBlocking {
        val persisted = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
        val inspectedUserIds = mutableListOf<List<Int>>()
        val previousRun = dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("old-failed-run", 1, 10, 20, "old-profile", "GROQ", "old-model", status = "FAILED")
        coEvery { persisted.getIncompleteRuns(1, any()) } coAnswers {
            val ids = secondArg<List<Int>>()
            inspectedUserIds += ids
            if (10 in ids) listOf(previousRun) else emptyList()
        }
        val api = FakeGroqAPI(
            flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "Fresh answer"), finishReason = "stop")))),
            enforcePhysicalBoundary = true
        )
        val states = createRepository(groqAPI = api, persistenceOverride = persisted).completeChat(
            listOf(
                MessageV2(id = 10, chatId = 1, content = "Previous failed task", platformType = null),
                MessageV2(id = 11, chatId = 1, content = "New unrelated question", platformType = null)
            ),
            emptyList(),
            groqPlatform(false, "working-model"),
            "fresh-unrelated-turn",
            null
        ).toList()
        assertTrue(inspectedUserIds.isNotEmpty())
        assertTrue(inspectedUserIds.toString(), inspectedUserIds.all { it == listOf(11) })
        assertEquals(states.toString(), 1, api.streamCalls)
        assertTrue(states.toString(), states.any { it is ApiState.Success && it.textChunk.contains("Fresh answer") })
    }

    @Test
    fun revokedSavedRecoveryIsDiscardedForFreshChatWithoutLeakingEvidence() = runBlocking {
        val platform = groqPlatform(false, "working-model")
        val current = MessageV2(id = 11, chatId = 1, content = "Answer a new question", platformType = null)
        val previous = MessageV2(id = 20, chatId = 1, content = "PRIVATE_EVIDENCE_DO_NOT_SEND", platformType = platform.uid, currentRunId = "failed-run")
        val persistence = mockk<dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao>(relaxed = true)
        coEvery { persistence.getIncompleteRuns(1, listOf(11)) } returns listOf(
            dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("failed-run", 1, 11, 20, platform.uid, "GROQ", platform.model, status = "FAILED")
        )
        coEvery { persistence.getToolEvents(listOf("failed-run")) } returns emptyList()
        coEvery { persistence.getMessages(1) } returns listOf(current, previous)
        coEvery { persistence.getChatRoom(1) } returns dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2(
            id = 1,
            title = "Previously shared chat",
            enabledPlatform = listOf("different-profile")
        )
        val settings = mockk<SettingRepository>(relaxed = true)
        coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
        coEvery { settings.fetchPlatformV2s() } returns listOf(platform)
        val api = FakeGroqAPI(
            flowOf(GroqChatCompletionChunk(choices = listOf(GroqChoice(0, GroqDelta(content = "Fresh safe answer"), finishReason = "stop")))),
            enforcePhysicalBoundary = true
        )
        val states = createRepository(groqAPI = api, persistenceOverride = persistence, settingsOverride = settings).completeChat(
            listOf(current),
            emptyList(),
            platform,
            "fresh-after-revocation",
            null
        ).toList()
        assertTrue(states.toString(), states.any { it is ApiState.Notice && it.message.contains("was not reused") })
        assertEquals(states.toString(), 1, api.streamCalls)
        assertTrue(states.toString(), states.any { it is ApiState.Success && it.textChunk.contains("Fresh safe answer") })
        assertFalse(api.lastRequest.toString().contains("PRIVATE_EVIDENCE_DO_NOT_SEND"))
    }

    private fun createRepository(
        groqAPI: GroqAPI = FakeGroqAPI(emptyFlow()),
        openAIAPI: OpenAIAPI = RecordingOpenAIAPI(),
        googleAPI: GoogleAPI = FakeGoogleAPI(),
        agentToolResolver: AgentToolResolver = emptyToolResolver(),
        toolEventRecorder: ToolEventRecorder = ToolEventRecorder(proxy(), proxy()),
        localRuntime: LocalRuntime = FakeLocalRuntime(),
        localModelRepository: LocalModelRepository = FakeLocalModelRepository(),
        modelCatalogRepository: ModelCatalogRepository = FakeModelCatalogRepository(),
        factVault: dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository? = null,
        toolApprovals: dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager? = null,
        settingsOverride: SettingRepository? = null,
        persistenceOverride: dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao? = null,
        researchOverride: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore? = null,
        recallOverride: dev.chungjungsoo.gptmobile.data.research.memory.ResearchRecallProvider? = null,
        knowledgeOverride: dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository? = null,
        pendingOverride: dev.chungjungsoo.gptmobile.data.queue.PendingPromptDao? = null
    ): ChatRepositoryImpl = ChatRepositoryImpl(
        context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = testFiles.root
            override fun getSystemService(name: String): Any? = null
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = mockk {
                every { getString(any(), any()) } answers { secondArg() }
                // Android exposes a mutable platform type; MockK otherwise casts
                // Kotlin's immutable emptySet default to MutableSet before returning it.
                every { getStringSet(any(), any()) } answers { secondArg<Set<String>?>()?.toMutableSet() }
            }
        },
        chatRoomV2Dao = proxy(),
        messageV2Dao = proxy(),
        chatPlatformModelV2Dao = proxy(),
        agentPersistenceDao = persistenceOverride ?: proxy(),
        agentRunDao = proxy(),
        settingRepository = settingsOverride ?: mockk<SettingRepository>(relaxed = true).also { settings ->
            io.mockk.coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
        },
        openAIAPI = openAIAPI,
        groqAPI = groqAPI,
        anthropicAPI = FakeAnthropicAPI(),
        googleAPI = googleAPI,
        attachmentUploadCoordinator = AttachmentUploadCoordinator(
            openAIAPI,
            FakeAnthropicAPI(),
            googleAPI
        ),
        contextBuilder = ContextBuilder(),
        agentToolResolver = agentToolResolver,
        toolEventRecorder = toolEventRecorder,
        localRuntime = localRuntime,
        localModelRepository = localModelRepository,
        modelCatalogRepository = modelCatalogRepository,
        deviceSocModel = "",
        factVault = factVault,
        toolApprovals = toolApprovals,
        researchSessions = researchOverride,
        researchRecall = recallOverride,
        knowledge = knowledgeOverride,
        pendingPromptDao = pendingOverride
    )

    private fun emptyToolResolver(): AgentToolResolver {
        val vault = MapSecretVault(emptyMap())
        return toolResolver(SingleToolConnectionDao(), vault)
    }

    private fun toolResolver(toolDao: ToolConnectionDao, vault: SecretVault): AgentToolResolver {
        val repository = ToolConnectionRepository(toolDao, vault)
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        return AgentToolResolver(
            toolConnectionRepository = repository,
            settingRepository = mockk<SettingRepository>(relaxed = true).also { settings ->
                io.mockk.coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            },
            secretVault = vault,
            networkClient = networkClient,
            mcpClientManager = manager,
            mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
            deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
        )
    }

    private fun groqPlatform(reasoning: Boolean, model: String) = PlatformV2(
        uid = "groq-platform",
        name = "Groq",
        compatibleType = ClientType.GROQ,
        apiUrl = "https://api.groq.com/openai/",
        model = model,
        reasoning = reasoning
    )

    private fun googlePlatform() = PlatformV2(
        uid = "google-platform",
        name = "Google",
        compatibleType = ClientType.GOOGLE,
        apiUrl = "https://generativelanguage.googleapis.com",
        model = "gemini-3-pro-preview",
        harassmentSafetyThreshold = GeminiSafetySettings.BLOCK_LOW_AND_ABOVE,
        hateSpeechSafetyThreshold = GeminiSafetySettings.BLOCK_MEDIUM_AND_ABOVE,
        sexuallyExplicitSafetyThreshold = GeminiSafetySettings.BLOCK_ONLY_HIGH,
        dangerousContentSafetyThreshold = GeminiSafetySettings.BLOCK_NONE
    )

    private fun localPlatform() = PlatformV2(
        uid = "local-platform",
        name = "Local",
        compatibleType = ClientType.LITERT_LM,
        apiUrl = "",
        model = "gemma3-1b-it",
        temperature = 1.0f,
        topP = 0.95f,
        topK = 40,
        maxTokens = 1024,
        accelerator = "gpu"
    )

    private fun customPlatform() = PlatformV2(
        uid = "custom-platform",
        name = "Custom",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.com",
        model = "custom-model",
        stream = true
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(): T {
        val handler = InvocationHandler { _, method, _ ->
            when {
                method.name in setOf("fetchPlatformV2s", "getIncompleteRuns") -> emptyList<PlatformV2>()
                method.name == "getFeatureSettings" -> dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Unit::class.java -> Unit
                    else -> null
                }
            }
        }

        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
            handler
        ) as T
    }

    private class FakeGroqAPI(
        private val chunks: Flow<GroqChatCompletionChunk>,
        private val enforcePhysicalBoundary: Boolean = false,
        private val beforePhysicalBoundary: suspend () -> Unit = {}
    ) : GroqAPI {
        var streamCalls = 0
        var lastRequest: GroqChatCompletionRequest? = null

        override fun streamChatCompletion(
            request: GroqChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<GroqChatCompletionChunk> = kotlinx.coroutines.flow.flow {
            beforePhysicalBoundary()
            if (enforcePhysicalBoundary) dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch()
            streamCalls += 1
            lastRequest = request
            chunks.collect { emit(it) }
        }
    }

    private class RecordingOpenAIAPI(
        private val chatRounds: ArrayDeque<Flow<ChatCompletionChunk>> = ArrayDeque()
    ) : OpenAIAPI {
        var streamChatCompletionCalls = 0
        val requests = mutableListOf<ChatCompletionRequest>()

        override fun streamChatCompletion(
            request: ChatCompletionRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ChatCompletionChunk> {
            streamChatCompletionCalls += 1
            requests += request
            return chatRounds.removeFirstOrNull() ?: emptyFlow()
        }

        override fun streamResponses(
            request: ResponsesRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<ResponsesStreamEvent> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String,
            config: ProviderRequestConfig
        ): UploadedProviderFile = UploadedProviderFile(id = "file-uploaded", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig): Boolean = false
    }

    private class FakeAnthropicAPI : AnthropicAPI {
        override fun streamChatMessage(
            messageRequest: MessageRequest,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<MessageResponseChunk> = emptyFlow()

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String,
            config: ProviderRequestConfig
        ): UploadedProviderFile = UploadedProviderFile(id = "anthropic-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileId: String, config: ProviderRequestConfig): Boolean = false
    }

    private class FakeGoogleAPI(
        private val chunks: Flow<GenerateContentResponse> = emptyFlow()
    ) : GoogleAPI {
        var streamCalls = 0
        var lastRequest: GenerateContentRequest? = null

        override fun streamGenerateContent(
            request: GenerateContentRequest,
            model: String,
            timeoutSeconds: Int,
            config: ProviderRequestConfig
        ): Flow<GenerateContentResponse> {
            streamCalls += 1
            lastRequest = request
            return chunks
        }

        override suspend fun uploadFile(
            filePath: String,
            fileName: String,
            mimeType: String,
            config: ProviderRequestConfig
        ): UploadedProviderFile = UploadedProviderFile(id = "google-file", mimeType = mimeType)

        override suspend fun isFileAvailable(fileName: String, config: ProviderRequestConfig): Boolean = false
    }
}

private class MapSecretVault(
    private val values: Map<String, ByteArray>
) : SecretVault {
    override suspend fun put(secretRef: String, secret: ByteArray) = Unit

    override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()

    override suspend fun delete(secretRef: String) = Unit
}

private class SingleToolConnectionDao(
    connection: ToolConnection? = null,
    binding: AgentToolBinding? = null
) : ToolConnectionDao {
    private val connections = listOfNotNull(connection)
    private val bindings = listOfNotNull(binding)

    override fun observeConnections(): kotlinx.coroutines.flow.Flow<List<ToolConnection>> = kotlinx.coroutines.flow.flow { emit(listConnections()) }

    override suspend fun listConnections(): List<ToolConnection> = connections

    override suspend fun getAllConnections(): List<ToolConnection> = connections

    override suspend fun getConnection(connectionUid: String): ToolConnection? = connections.firstOrNull { it.connectionUid == connectionUid }

    override suspend fun getConnectionsByUids(connectionUids: List<String>): List<ToolConnection> = connections.filter { it.connectionUid in connectionUids }

    override suspend fun upsertConnection(connection: ToolConnection) = Unit

    override suspend fun deleteConnectionByUid(connectionUid: String) = Unit

    override suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding> = bindings.filter { it.profileUid == profileUid }

    override suspend fun insertBinding(binding: AgentToolBinding) = Unit

    override suspend fun deleteConnectionToolBindingsForTypes(profileUid: String, toolName: String, connectionTypes: List<String>) = Unit

    override suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String) = Unit

    override suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String) = Unit

    override suspend fun listBindingsWithConnections(profileUid: String): List<AgentToolBindingWithConnection> = listBindingsByProfile(profileUid).map { row ->
        AgentToolBindingWithConnection(row, row.connectionUid?.let { uid -> connections.firstOrNull { it.connectionUid == uid } })
    }
}

private class RecordingToolEventDao {
    val events = mutableListOf<ToolEvent>()

    @Suppress("UNCHECKED_CAST")
    fun asDao(): dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao {
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "insertToolEvent" -> {
                    events += args!![0] as ToolEvent
                    Unit
                }

                "finishToolEvent" -> {
                    val eventId = args!![0] as String
                    val index = events.indexOfFirst { it.eventId == eventId && it.callId == args[1] }
                    if (index < 0) {
                        0
                    } else {
                        events[index] = events[index].copy(
                            result = args[2] as String,
                            resultType = args[3] as String,
                            status = args[4] as String,
                            isError = args[5] as Boolean,
                            completedAt = args[6] as Long,
                            error = args[7] as String?
                        )
                        1
                    }
                }

                "getToolEventById" -> events.firstOrNull { it.eventId == args!![0] }

                "cancelActiveToolEvents" -> Unit

                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Unit::class.java -> Unit
                    else -> null
                }
            }
        }
        return Proxy.newProxyInstance(
            dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao::class.java.classLoader,
            arrayOf(dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao::class.java),
            handler
        ) as dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
    }
}
