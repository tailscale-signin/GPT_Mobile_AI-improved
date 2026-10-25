package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent as HttpTextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeQueuedTransportTest {
    @Test fun continuationReceiptBindsOnlyTheLastAssistantPrefixAndDoesNotGuessAnInitialAnswer() {
        val prefix = "Saved answer to continue."
        val continuation = request("automatic").copy(messages = listOf(ChatMessage(Role.USER, listOf(TextContent("Question"))), ChatMessage(Role.ASSISTANT, listOf(TextContent("Earlier draft"))), ChatMessage(Role.ASSISTANT, listOf(TextContent(prefix))), ChatMessage(Role.USER, listOf(TextContent("Continue")))))
        assertEquals(dev.chungjungsoo.gptmobile.data.research.researchHash(prefix), queueContinuationPrefixDigest(continuation, "requested_length_continuation"))
        assertNull(queueContinuationPrefixDigest(continuation, "primary"))
        assertNull(queueContinuationPrefixDigest(request("automatic"), "provider_output_limit_continuation"))
    }

    @Test fun boundedTailContinuationUsesTheCompleteSavedPrefixIdentityInsteadOfAnOlderAssistantMessage() {
        val completeDraft = "Retained answer paragraph. ".repeat(200)
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(completeDraft)
        val continuation = request("automatic").copy(
            messages = listOf(
                ChatMessage(Role.USER, listOf(TextContent("Earlier unrelated question"))),
                ChatMessage(Role.ASSISTANT, listOf(TextContent("Older unrelated answer"))),
                ChatMessage(Role.USER, listOf(TextContent("Continue the current task from this bounded tail: ${completeDraft.takeLast(100)}")))
            )
        )
        assertEquals(digest, queueContinuationPrefixDigest(continuation, "requested_length_continuation", digest))
        assertNull(queueContinuationPrefixDigest(continuation, "primary", digest))
        assertThrows(IllegalArgumentException::class.java) { queueContinuationPrefixDigest(continuation, "requested_length_continuation", "invalid") }
    }

    @Test fun resumedPollingCannotResetTheAcceptedJobDeadline() {
        assertEquals(500L, queueRemainingWaitMillis(1000, 1200500, 1200, null))
        assertEquals(200L, queueRemainingWaitMillis(1000, 1200500, 1200, 200))
        assertThrows(IllegalStateException::class.java) { queueRemainingWaitMillis(1000, 1201000, 1200, null) }
        assertEquals(15000L, queueRemainingWaitMillis(null, 1000, 0, null))
    }

    @Test fun cancelledAcceptedJobRetainsItsScopedUnconfirmedCleanupReceipt() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        var submissions = 0
        val cancelledIds = mutableListOf<String>()
        val run = java.util.UUID.randomUUID().toString()
        val client = HttpClient(
            MockEngine { wire ->
                when {
                    wire.url.encodedPath.endsWith("/status/models") -> respond(workerCatalog)
                    wire.method == HttpMethod.Post -> {
                        submissions++
                        respond("""{"id":"cancel-receipt-job"}""")
                    }
                    wire.method == HttpMethod.Delete -> {
                        cancelledIds += wire.url.encodedPath
                        respond("{}", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    }
                    else -> {
                        accepted.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, profileUid = "horde", queueScope = "73:1:$run")
            val task = launch(dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet(), run)) {
                OpenAIAPIImpl(network).streamChatCompletion(request("automatic"), 30, config).collect()
            }
            kotlinx.coroutines.withTimeout(5000) { accepted.await() }
            task.cancel()
            task.join()
            val saved = dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.forRun(run, 73, "horde", false)!!
            assertEquals(1, submissions)
            assertEquals(listOf("/api/v2/generate/text/status/cancel-receipt-job"), cancelledIds)
            assertEquals("CANCELLED", saved.state)
            assertEquals("cancel-receipt-job", saved.cleanup?.jobId)
            assertEquals(503, saved.cleanup?.responseStatus)
            assertEquals(false, saved.cleanup?.transportAcknowledged)
            assertNull(saved.cleanup?.remoteTerminalConfirmed)
            org.junit.Assert.assertTrue(saved.cleanup?.durationMillis != null)
            org.junit.Assert.assertTrue(OpenAIAPIImpl(network).checkSavedHordeJob(run, 73, config).contains("unconfirmed"))
        } finally {
            client.close()
        }
    }

    @org.junit.Before fun resetHealth() {
        dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
    }
    private val gradioConfig = javaClass.getResource("/free/gradio/qwen-config.json")!!.readText()
    private val workerCatalog = """[{"name":"koboldcpp/Qwen2.5-7B-Instruct","count":1}]"""

    @Test fun changedPermissionResumesTheAcceptedJobUnderCurrentAuthorityWithoutAnotherPost() = runBlocking {
        var allowed = true
        var submissions = 0
        val polls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { wire ->
                when {
                    wire.url.encodedPath.endsWith("/status/models") -> respond(workerCatalog)
                    wire.method == HttpMethod.Post -> {
                        submissions++
                        allowed = false
                        respond("""{"id":"policy-change-job"}""")
                    }
                    else -> {
                        polls += wire.url.encodedPath
                        respond("""{"done":true,"generations":[{"text":"Saved complete answer","model":"worker/model"}]}""")
                    }
                }
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val runId = java.util.UUID.randomUUID().toString()
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, profileUid = "horde", queueScope = "71:1:$runId", permissionRevision = "before")
            val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet(), runId)
            val guard = dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("current") { check(allowed) { "DISPATCH_POLICY_CHANGED" } }
            val failed = kotlinx.coroutines.withContext(dispatch + guard) { api.streamChatCompletion(request("automatic"), 30, config).toList() }
            org.junit.Assert.assertTrue(failed.last().error?.message.orEmpty().contains("DISPATCH_POLICY_CHANGED"))
            assertEquals(1, submissions)
            org.junit.Assert.assertTrue(polls.isEmpty())
            val retained = requireNotNull(dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.forRun(runId, 71, "horde", false))
            dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.save(retained.copy(state = "DISCONNECTED"))
            allowed = true
            val resumed = kotlinx.coroutines.withContext(dispatch + guard) { api.streamChatCompletion(request("automatic"), 30, config.copy(permissionRevision = "after")).toList() }
            assertEquals("Saved complete answer", resumed.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
            assertEquals(1, submissions)
            assertEquals(listOf("/api/v2/generate/text/status/policy-change-job"), polls)
        } finally {
            client.close()
        }
    }

    @Test fun legacyAcceptedJobCannotDisappearWhenItsPermissionRevisionChanges() = runBlocking {
        val run = java.util.UUID.randomUUID().toString()
        dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.save(
            dev.chungjungsoo.gptmobile.data.free.FreeQueueJob("legacy-$run", "legacy-job", "QUEUED", chatId = 72, protocol = "HORDE", ownerRunId = run, profileUid = "horde", requestedModel = "automatic")
        )
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                error("A legacy receipt must be reviewed before any new submission")
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, profileUid = "horde", queueScope = "72:1:$run", permissionRevision = "new")
            val result = kotlinx.coroutines.withContext(dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet(), run)) {
                OpenAIAPIImpl(network).streamChatCompletion(request("automatic"), 30, config).toList()
            }
            org.junit.Assert.assertTrue(result.last().error?.message.orEmpty().contains("QUEUE_RECEIPT_REVIEW_REQUIRED"))
            assertEquals(0, requests)
        } finally {
            client.close()
        }
    }

    @Test fun checkingPendingSavedJobUsesOnlyGetAndCannotPublishOverNewWriter() = runBlocking {
        val key = java.util.UUID.randomUUID().toString()
        val run = "combined-synthesis:$key"
        dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.save(dev.chungjungsoo.gptmobile.data.free.FreeQueueJob(key, "pending-receipt", "QUEUED", createdAt = System.currentTimeMillis() - 21 * 60_000, chatId = 19, protocol = "HORDE", ownerRunId = run, profileUid = "horde", requestedModel = "horde-text"))
        val methods = mutableListOf<HttpMethod>()
        val client = HttpClient(
            MockEngine { wire ->
                methods += wire.method
                assertEquals("/api/v2/generate/text/status/pending-receipt", wire.url.encodedPath)
                respond("{\"done\":false,\"queue_position\":3}")
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, profileUid = "horde")
            val text = OpenAIAPIImpl(network).checkSavedHordeJob(run, 19, config)
            org.junit.Assert.assertTrue(text.contains("still pending"))
            assertEquals(listOf(HttpMethod.Get), methods)
            assertEquals("pending-receipt", dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.read(key)!!.id)
            assertEquals("QUEUED", dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.read(key)!!.state)
        } finally {
            client.close()
        }
    }

    @Test fun checkingCompletedSavedImageRetainsTheImageWithoutResubmission() = runBlocking {
        val key = java.util.UUID.randomUUID().toString()
        val run = "image:$key"
        val imageUrl = "https://images.example.org/result.webp"
        dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.save(dev.chungjungsoo.gptmobile.data.free.FreeQueueJob(key, "image-receipt", "QUEUED", chatId = 20, protocol = "HORDE", ownerRunId = run, profileUid = "horde", requestedModel = "horde-image"))
        val methods = mutableListOf<HttpMethod>()
        val client = HttpClient(
            MockEngine { wire ->
                methods += wire.method
                assertEquals("/api/v2/generate/status/image-receipt", wire.url.encodedPath)
                respond("""{"done":true,"generations":[{"img":"$imageUrl","model":"image-worker"}]}""")
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, profileUid = "horde")
            val inspected = api.checkSavedHordeJob(run, 20, config)
            val saved = requireNotNull(dev.chungjungsoo.gptmobile.data.free.FreeQueueJobs.read(key))
            assertEquals("COMPLETE", saved.state)
            assertEquals("![AI Horde generated image]($imageUrl)", saved.answer)
            assertEquals("image-worker", saved.servedModel)
            org.junit.Assert.assertTrue(inspected.contains(requireNotNull(saved.answer)))
            assertEquals(inspected, api.checkSavedHordeJob(run, 20, config))
            assertEquals(listOf(HttpMethod.Get), methods)
        } finally {
            client.close()
        }
    }

    @Test fun gradioSocketFailureRetainsSnapshotAndDoesNotResubmit() = runBlocking {
        val receivedSnapshot = CompletableDeferred<Unit>()
        val stream = ByteChannel(autoFlush = true)
        var posts = 0
        var reads = 0
        val client = HttpClient(
            MockEngine { wire ->
                when {
                    wire.url.encodedPath.endsWith("/config") -> respond(gradioConfig)
                    wire.url.encodedPath.endsWith("/info") -> respond("""{"named_endpoints":{"/add_message":{"parameters":[{"parameter_name":"input_value"},{"parameter_name":"settings_form_value"}]}}}""")
                    wire.method == HttpMethod.Post -> {
                        posts++
                        respond("""{"event_id":"socket-failure-job"}""")
                    }
                    else -> {
                        reads++
                        respond(stream, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                    }
                }
            }
        ) { install(HttpTimeout) }
        val writer = launch {
            stream.writeStringUtf8("event: generating\ndata: [[{\"role\":\"assistant\",\"content\":\"Retain this partial answer\"}]]\n\n")
            receivedSnapshot.await()
            stream.cancel(java.io.IOException("socket reset after snapshot"))
        }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val provider = FreeAiProvider.HUGGING_FACE
            val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, queueScope = java.util.UUID.randomUUID().toString())
            val api = OpenAIAPIImpl(network)
            val chunks = mutableListOf<dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk>()
            api.streamChatCompletion(request(provider.model), 30, config).collect { chunk ->
                chunks += chunk
                if (chunk.applicationNotice?.contains("generating · job") == true) receivedSnapshot.complete(Unit)
            }
            assertEquals("Retain this partial answer", chunks.flatMap { it.choices.orEmpty() }.mapNotNull { it.effectiveDelta.content }.joinToString(""))
            org.junit.Assert.assertTrue(chunks.last().error!!.message.contains("QUEUE_STREAM_DISCONNECTED"))
            api.streamChatCompletion(request(provider.model), 30, config).toList()
            assertEquals(1, posts)
            assertEquals(1, reads)
        } finally {
            writer.cancel()
            client.close()
        }
    }

    @Test fun ambiguousSubmissionTimeoutDoesNotSubmitTheJobTwice() = runBlocking {
        var submissions = 0
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            submissions++
            respond("{}", io.ktor.http.HttpStatusCode.RequestTimeout)
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, queueScope = java.util.UUID.randomUUID().toString())
            repeat(2) { assertNotNull(api.streamChatCompletion(request("automatic"), 30, config).toList().last().error) }
            assertEquals(1, submissions)
        } finally {
            client.close()
        }
    }

    @Test fun gradioKeepsTheLastGeneratingSnapshotWhenCompletionContainsOnlyUpdates() = runBlocking {
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            val response = when {
                wire.url.encodedPath.endsWith("/config") -> gradioConfig
                wire.url.encodedPath.endsWith("/info") -> """{"named_endpoints":{"/add_message":{"parameters":[{"parameter_name":"input_value"},{"parameter_name":"settings_form_value"}]}}}"""
                wire.method == HttpMethod.Post -> {
                    assertNull(Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject["session_hash"])
                    """{"event_id":"gradio-job"}"""
                }
                else -> "event: generating\ndata: [null,null,null,null,null,{\"value\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"content\":\"A final answer.\"}]}]}]\n\nevent: complete\ndata: [null,{\"__type__\":\"update\"}]\n\n"
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, if (response.startsWith("event:")) "text/event-stream" else "application/json"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val config = ProviderRequestConfig(FreeAiProvider.HUGGING_FACE.apiUrl, null, freeProvider = FreeAiProvider.HUGGING_FACE, queueScope = java.util.UUID.randomUUID().toString())
            val chunks = OpenAIAPIImpl(network).streamChatCompletion(request(FreeAiProvider.HUGGING_FACE.model), 30, config).toList()
            assertEquals("A final answer.", chunks.last().choices!!.single().effectiveDelta.content)
            assertNull(chunks.last().error)
        } finally {
            client.close()
        }
    }

    @Test fun terminalGradioFailureIsNotAdvertisedOrRetriedAsAResumableJob() = runBlocking {
        var submissions = 0
        var polls = 0
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            val response = when {
                wire.url.encodedPath.endsWith("/config") -> gradioConfig
                wire.url.encodedPath.endsWith("/info") -> """{"named_endpoints":{"/add_message":{"parameters":[{"parameter_name":"input_value"},{"parameter_name":"settings_form_value"}]}}}"""
                wire.method == HttpMethod.Post -> {
                    submissions++
                    """{"event_id":"failed-job"}"""
                }
                else -> {
                    polls++
                    "event: error\ndata: null\n\n"
                }
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val config = ProviderRequestConfig(FreeAiProvider.HUGGING_FACE.apiUrl, null, freeProvider = FreeAiProvider.HUGGING_FACE, queueScope = java.util.UUID.randomUUID().toString())
            repeat(2) {
                val error = OpenAIAPIImpl(network).streamChatCompletion(request(FreeAiProvider.HUGGING_FACE.model), 30, config).toList().last().error
                org.junit.Assert.assertTrue(error!!.message.contains("cannot resume") || error.message.contains("cannot be resumed"))
            }
            assertEquals(1, submissions)
            assertEquals(1, polls)
        } finally {
            client.close()
        }
    }

    @Test fun hordeRejectionKeepsItsSemanticCauseAndAllowsAnExplicitNewAttempt() = runBlocking {
        var submissions = 0
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            if (wire.method == HttpMethod.Post) {
                submissions++
                if (submissions == 1) {
                    respond("""{"rc":"KudosUpfront","message":"private server detail"}""", io.ktor.http.HttpStatusCode.Forbidden)
                } else {
                    respond("""{"id":"accepted-job"}""")
                }
            } else {
                respond("""{"done":true,"generations":[{"text":"Hello","model":"worker/model"}]}""")
            }
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, queueScope = java.util.UUID.randomUUID().toString())
            val failure = api.streamChatCompletion(request("automatic"), 30, config).toList().last().error!!.message
            org.junit.Assert.assertTrue(failure.contains("anonymous allowance"))
            org.junit.Assert.assertTrue(failure.contains("private server detail"))
            assertEquals("Hello", api.streamChatCompletion(request("automatic"), 30, config).toList().last().choices!!.single().effectiveDelta.content)
            assertEquals(2, submissions)
        } finally {
            client.close()
        }
    }

    @Test fun gradioRetainsTerminalDetailsForStructuredEmptyAndUnterminatedEvents() = runBlocking {
        val cases = listOf(
            "event: error\ndata: {\"error\":{\"code\":\"QuotaExceeded\",\"message\":\"Downstream quota reached\"}}" to "Downstream quota reached",
            "event: error\n\n" to "No more specific reason",
            "event: error\ndata: null\n\n" to "No more specific reason"
        )
        for ((event, expected) in cases) {
            dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.resetForTest()
            var posts = 0
            val engine = MockEngine { wire ->
                if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
                val body = when {
                    wire.url.encodedPath.endsWith("/config") -> gradioConfig
                    wire.url.encodedPath.endsWith("/info") -> """{"named_endpoints":{"/add_message":{"parameters":[{"parameter_name":"input_value"},{"parameter_name":"settings_form_value"}]}}}"""
                    wire.method == HttpMethod.Post -> {
                        posts++
                        """{"event_id":"terminal-details-job"}"""
                    }
                    else -> event
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            val client = HttpClient(engine) { install(HttpTimeout) }
            try {
                val network = mockk<NetworkClient>()
                every { network.invoke() } returns client
                val api = OpenAIAPIImpl(network)
                val provider = FreeAiProvider.HUGGING_FACE
                val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, queueScope = java.util.UUID.randomUUID().toString())
                val error = api.streamChatCompletion(request(provider.model), 30, config).toList().last().error!!
                org.junit.Assert.assertTrue(error.message, error.message.contains(expected))
                org.junit.Assert.assertTrue(error.message.contains("cannot resume"))
                api.streamChatCompletion(request(provider.model), 30, config).toList()
                assertEquals(1, posts)
            } finally {
                client.close()
            }
        }
    }

    @Test fun gradioDisconnectRetainsReceiptAndNeverSubmitsAContinuation() = runBlocking {
        var posts = 0
        var reads = 0
        val client = HttpClient(
            MockEngine { wire ->
                val response = when {
                    wire.url.encodedPath.endsWith("/config") -> gradioConfig
                    wire.url.encodedPath.endsWith("/info") -> """{"named_endpoints":{"/add_message":{"parameters":[{"parameter_name":"input_value"},{"parameter_name":"settings_form_value"}]}}}"""
                    wire.method == HttpMethod.Post -> {
                        posts++
                        """{"event_id":"disconnected-job"}"""
                    }
                    else -> {
                        reads++
                        "event: generating\ndata: [[{\"role\":\"assistant\",\"content\":\"A saved partial answer\"}]]\n\n"
                    }
                }
                respond(response, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        ) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val provider = FreeAiProvider.HUGGING_FACE
            val config = ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider, queueScope = java.util.UUID.randomUUID().toString())
            val api = OpenAIAPIImpl(network)
            val first = api.streamChatCompletion(request(provider.model), 30, config).toList()
            org.junit.Assert.assertTrue(first.any { it.choices?.any { choice -> choice.effectiveDelta.content == "A saved partial answer" } == true })
            org.junit.Assert.assertTrue(first.last().error!!.message.contains("QUEUE_"))
            api.streamChatCompletion(request(provider.model), 30, config).toList()
            assertEquals(1, posts)
            assertEquals(1, reads)
        } finally {
            client.close()
        }
    }

    @Test fun externalOpenCodeRouteIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                respond("{}")
            }
        )
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val provider = FreeAiProvider.OPENCODE
            val chunks = OpenAIAPIImpl(network).streamChatCompletion(request(provider.model), 30, ProviderRequestConfig(provider.apiUrl, null, freeProvider = provider)).toList()
            org.junit.Assert.assertTrue(chunks.last().error!!.message.contains("removed"))
            assertEquals(0, calls)
        } finally {
            client.close()
        }
    }

    private fun request(model: String) = ChatCompletionRequest(model, listOf(ChatMessage(Role.USER, listOf(TextContent("A short novel sentence about clouds.")))), maxTokens = 512)

    @Test fun interruptedHordePollResumesSavedJobWithoutDuplicateSubmission() = runBlocking {
        var posts = 0
        var polls = 0
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            assertNull(wire.headers[HttpHeaders.Authorization])
            if (wire.method == HttpMethod.Post) {
                posts++
                assertEquals("0000000000", wire.headers["apikey"])
                val body = Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject
                assertEquals("true", body.getValue("allow_downgrade").jsonPrimitive.content)
                assertEquals("256", body.getValue("params").jsonObject.getValue("max_length").jsonPrimitive.content)
                org.junit.Assert.assertTrue(body.getValue("prompt").jsonPrimitive.content.startsWith("<|im_start|>user"))
                org.junit.Assert.assertTrue(body.getValue("models").toString().contains("Qwen2.5-7B-Instruct"))
                respond("""{"id":"job-1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                polls++
                if (polls == 1) throw java.io.IOException("connection interrupted")
                respond("""{"done":true,"generations":[{"text":"A cloud folds its silver map.","model":"worker/model"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.AI_HORDE.apiUrl, null, freeProvider = FreeAiProvider.AI_HORDE, queueScope = java.util.UUID.randomUUID().toString(), profileUid = "horde")
            val interrupted = api.streamChatCompletion(request("automatic"), 30, config).toList()
            assertNotNull(interrupted.last().error)
            assertEquals(listOf(256), interrupted.mapNotNull { it.serializedOutputTokens })
            val result = api.streamChatCompletion(request("automatic"), 30, config).toList()
            assertEquals(1, posts)
            assertEquals(listOf(256), result.mapNotNull { it.serializedOutputTokens })
            assertEquals("A cloud folds its silver map.", result.last().choices!!.single().delta.content)
            assertEquals("worker/model", result.last().model)
            assertNull(result.last().usage)
            // Checking the same persisted generation can reuse its result; Generate again cannot.
            val cached = api.streamChatCompletion(request("automatic"), 30, config).toList()
            assertEquals(listOf(256), cached.mapNotNull { it.serializedOutputTokens })
            assertEquals("queue_complete", cached.last().choices!!.single().finishReason)
            assertEquals(1, posts)
            api.streamChatCompletion(request("automatic"), 30, config.copy(queueScope = java.util.UUID.randomUUID().toString())).toList()
            assertEquals(2, posts)
        } finally {
            client.close()
        }
    }

    @Test fun strictFreeRouteDoesNotDispatchPaidModelAndPreservesNamedFreeModel() = runBlocking {
        var calls = 0
        val engine = MockEngine { wire ->
            if (wire.url.encodedPath.endsWith("/status/models")) return@MockEngine respond(workerCatalog)
            calls++
            assertNull(wire.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((wire.body as HttpTextContent).text).jsonObject
            assertEquals("cohere/north-mini-code", body.getValue("model").jsonPrimitive.content)
            respond("data: {\"model\":\"cohere/north-mini-code\",\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val network = mockk<NetworkClient>()
            every { network.invoke() } returns client
            val api = OpenAIAPIImpl(network)
            val config = ProviderRequestConfig(FreeAiProvider.BLOCKRUN.apiUrl, "never-send", freeProvider = FreeAiProvider.BLOCKRUN)
            assertNotNull(api.streamChatCompletion(request("paid/model"), 30, config).toList().last().error)
            assertEquals(0, calls)
            assertEquals("Hello", api.streamChatCompletion(request("cohere/north-mini-code"), 30, config).toList().last().choices!!.single().delta.content)
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }

    @Test fun gradioExtractsOnlyFinalAnswerAndRejectsInvalidRecords() {
        val data = Json.parseToJsonElement("""["",{"value":[{"role":"user","content":"hello"},{"role":"assistant","content":[{"type":"tool","content":"private reasoning"},{"type":"text","content":"Hello there"}]}]}]""")
        assertEquals("Hello there", FreeQueuedTransport.gradioAnswer(data))
        assertThrows(IllegalStateException::class.java) { FreeQueuedTransport.gradioAnswer(Json.parseToJsonElement("[]")) }
        assertThrows(IllegalArgumentException::class.java) { FreeQueuedTransport.hordePayload(request("automatic").copy(maxTokens = 8), "test") }
    }

    @Test fun ovhQuotaIsPerModelWhileBlockrunProfilesShareTheSamePool() {
        val limiter = FreeAiRequestLimiter { 1000L }
        limiter.reserve(FreeAiProvider.OVHCLOUD, "a")
        limiter.reserve(FreeAiProvider.OVHCLOUD, "a")
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD, "a") }
        limiter.reserve(FreeAiProvider.OVHCLOUD, "b")
        repeat(30) { limiter.reserve(FreeAiProvider.BLOCKRUN, "model-$it") }
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.BLOCKRUN, "new-model") }
    }
}
