package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ToolBatchDeliveryProofTest {
    private val name = "github_context__selected"
    private val tool = GitHubOperationTool.definition("github_context", name)
    private fun arguments(offset: Int) = buildJsonObject { put("view_cursor", "retained:$offset:1536") }
    private fun recipe(offset: Int) = buildJsonObject {
        put("tool", name)
        put("arguments", arguments(offset))
    }
    private fun call() = ProviderEvent.ToolCall("saved-page", name, arguments(50))
    private fun result() = AgentToolResult(
        call().callId,
        ToolResultContent.Json(
            buildJsonObject {
                put("schema", "github.operation.v1")
                put("operation", "github_context")
                put("fragments", JsonArray(listOf(buildJsonObject { put("value", "source".repeat(800)) })))
                put("fragment_offset", 50)
                put("fragment_end", 123)
                put("total_fragments", 131)
                put("recovery_call", recipe(50))
                put("continuation_call", recipe(123))
            }
        ),
        false
    )
    private fun next(snapshot: ToolBatchRecoverySnapshot): JsonObject {
        val state = GitHubReplayRecovery.State(snapshot.completedExchanges.flatMap { it.calls })
        state.observeDelivered(snapshot.completedExchanges, snapshot.deliveredResultDigests)
        return state.advanceSavedRead(ProviderEvent.ToolCall("saved-result-consume-planning", name, arguments(50)), tool).arguments
    }
    private suspend fun stored(file: File, result: AgentToolResult = result()): ToolBatchCheckpoint {
        val journal = ToolBatchCheckpoint(file, "same-grant")
        journal.enqueue(listOf(call()))
        journal.execute(call()) { result }
        journal.finish()
        return journal
    }

    @Test fun restartPreservesExactBoundedPageWhenProviderEnvelopeWouldCrowdItOut() = runBlocking {
        val fixture = GitHubSavedPageCapacityTest.Fixture()
        val native = requireNotNull(fixture.next(tokens = 3000))
        val original = ProviderToolInvocation(native.name, buildJsonObject { put("legacy_argument", "x".repeat(24_000)) })
        val call = native.copy(originalInvocation = original, originalInvocationFingerprint = original.fingerprint(native.callId))
        val page = fixture.read(call)
        assertFalse(page.isError)
        val directory = java.nio.file.Files.createTempDirectory("exact-page-checkpoint").toFile()
        try {
            val file = File(directory, "turn.json")
            val checkpoint = ToolBatchCheckpoint(file, "original-scope")
            checkpoint.enqueue(listOf(call))
            checkpoint.execute(call) { page }
            val restarted = ToolBatchCheckpoint(file, "original-scope")
            val restored = restarted.execute(call) { error("Saved page must reopen without another dispatch") }
            assertEquals(page.content, restored.content)
            assertEquals(ToolResultDeliveryProof.digest(page.content), ToolResultDeliveryProof.digest(restored.content))
            assertTrue(runCatching { ToolBatchCheckpoint(file, "other-scope").recoverySnapshot() }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun oversizedSavedBatchIsDeliveredAcrossRoundsAndTheCheckpointKeepsEveryPageProof() = runBlocking {
        val file = File.createTempFile("saved-pages-in-parts", ".json").apply { delete() }
        try {
            val history = (0 until 12).map { index ->
                val fixture = GitHubSavedPageCapacityTest.Fixture("narrow-batch-$index")
                val call = requireNotNull(fixture.next(tokens = 3000)).copy(callId = "page-$index")
                AgentToolExchange(listOf(call), listOf(fixture.read(call)))
            }
            val journal = ToolBatchCheckpoint(file, "same-grant")
            for (exchange in history) {
                val call = exchange.calls.single()
                journal.enqueue(listOf(call))
                journal.execute(call) { exchange.results.single() }
            }
            journal.finish()
            val required = ToolResultDeliveryProof.capture(history.flatMap { it.results })
            val budget = (1800..12_000 step 100).last { tokens ->
                val allowances = GitHubReplayRecovery.savedPageResultAllowances(history, emptySet(), tokens, 512)
                val replay = ToolExchangeCompactor.compact(history, tokens, 512, resultTokenAllowances = allowances, requiredSavedPageDigests = required)
                val seen = ToolResultDeliveryProof.capture(replay.flatMap { it.results })
                required.count { (id, digest) -> seen[id] == digest } in 1 until required.size
            }
            var privateReads = 0
            var regularReads = 0
            val model = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    val partial = kotlinx.coroutines.currentCoroutineContext()[SavedPageDeliveryRound] != null
                    if (partial) privateReads++ else regularReads++
                    emit(ProviderEvent.TextDelta(if (partial) "private acknowledgement" else "Reviewed the retained pages."))
                    emit(ProviderEvent.Completed)
                }.let { SavedPageDeliveryRound.providerRound(exchanges, it) }
            }
            val limits = AgentRunLimits(githubTask = true, maxReplayTokens = budget, maxReplayResultTokens = 512, maxRounds = 24)
            val admittedTool = object : AgentTool {
                override val definition = GitHubSavedPageCapacityTest.Fixture().tools.single()
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("No tool should be re-executed while delivering saved pages")
            }
            val visible = AgentRunner(limits, journal).run(model, listOf(admittedTool), selectDefinitions = { emptyList() }).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertTrue("Expected multiple privately acknowledged pages at $budget tokens: $visible", privateReads > 0)
            assertEquals(1, regularReads)
            assertFalse(visible.filterIsInstance<ProviderEvent.TextDelta>().any { it.text == "private acknowledgement" })
            assertTrue(visible.filterIsInstance<ProviderEvent.Failed>().isEmpty())
            val reopened = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertTrue(required.all { (id, digest) -> reopened.deliveredResultDigests[id] == digest })
            assertTrue(reopened.unsettledCalls.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test fun privateProviderRequestContainsOnlyPagesItCanAcknowledgeWhenTheLastPageIsDecorated() = runBlocking {
        val facts = (0..2).map { "MIXED_PAGE_FACT_$it" }
        val fixtures = facts.map { GitHubSavedPageCapacityTest.Fixture(it) }
        val history = fixtures.mapIndexed { i, fixture ->
            val source = fixture.history.first { exchange ->
                val result = exchange.results.single()
                "view_cursor" in exchange.calls.single().arguments && !result.isError && facts[i] in ToolHandoffReceipt.payload(result.content)
            }
            val call = source.calls.single().copy(callId = "page-$i")
            AgentToolExchange(listOf(call), listOf(fixture.read(call)))
        }
        val group = AgentToolExchange(history.flatMap { it.calls }, history.flatMap { it.results })
        val proofs = ToolResultDeliveryProof.capture(group.results)
        // Admission tries the entire group before it reserves space for private notes.
        // Exercise a budget where that ordinary request genuinely needs staging.
        val rawTokens = ToolExchangeCompactor.estimateTokens(listOf(group))
        val reservation = SavedPageReading.reservation(null, true)
        val budget = ((rawTokens - 1) downTo 1200 step 8).first { tokens ->
            val allowance = tokens - reservation
            val perPage = GitHubReplayRecovery.savedPageResultAllowances(listOf(group), emptySet(), allowance, 512)
            val replay = ToolExchangeCompactor.compact(listOf(group), allowance, 512, resultTokenAllowances = perPage, requiredSavedPageDigests = proofs)
            val seen = ToolResultDeliveryProof.capture(replay.flatMap { it.results })
            proofs.count { (id, digest) -> seen[id] == digest } in 1..2
        }
        val journal = ToolBatchCheckpoint(null, "same-profile")
        var toolExecutions = 0
        val reader = object : AgentTool {
            override val definition = fixtures.first().tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                toolExecutions++
                return group.results.single { it.callId == callId }
            }
        }
        var privateReads = 0
        var ordinaryReads = 0
        val model = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (exchanges.isEmpty()) {
                    group.calls.forEach { emit(it) }
                } else if (kotlinx.coroutines.currentCoroutineContext()[SavedPageDeliveryRound] != null) {
                    privateReads++
                    val results = exchanges.flatMap { it.results }
                    val ids = results.map { it.callId }.toSet()
                    assertEquals(ids, exchanges.flatMap { it.calls }.map { it.callId }.toSet())
                    assertTrue(ids.isNotEmpty() && "page-2" !in ids)
                    results.forEach { assertEquals(proofs[it.callId], ToolResultDeliveryProof.digest(it.content)) }
                    val body = results.joinToString { ToolHandoffReceipt.payload(it.content) }
                    assertFalse(facts.last() in body)
                    emit(ProviderEvent.TextDelta(facts.filter { it in body }.joinToString(" ")))
                } else {
                    ordinaryReads++
                    val last = exchanges.flatMap { it.results }.single { it.callId == "page-2" }
                    val body = (last.content as ToolResultContent.Json).value.jsonObject
                    assertTrue("agent_instruction" in body && facts.last() in body.toString())
                    emit(ProviderEvent.TextDelta("Read the retained sources."))
                }
                emit(ProviderEvent.Completed)
            }.let { SavedPageDeliveryRound.providerRound(exchanges, it) }
        }
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4, maxRounds = 12, maxReplayTokens = budget, maxReplayResultTokens = 512)
        val events = AgentRunner(limits, journal).run(model, listOf(ToolExecutionBudget(limits).bind(reader))).toList()
        val failures = events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { it.event as? ProviderEvent.Failed }
        val receipts = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result.callId to it.result.errorCode }
        assertTrue("Expected a private read before ordinary delivery: budget=$budget, raw=$rawTokens, receipts=$receipts, failures=$failures", privateReads > 0)
        assertEquals("Replay allowance $budget, private rounds $privateReads, failures $failures", 1, ordinaryReads)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().none { it.event is ProviderEvent.Failed })
        assertEquals(group.calls.size, toolExecutions)
        val recovered = journal.recoverySnapshot()
        val reading = requireNotNull(recovered.savedPageReading)
        assertFalse("page-2" in SavedPageReading.fingerprints(reading))
        assertFalse(facts.last() in reading.toString())
        assertEquals(proofs["page-2"], ToolResultDeliveryProof.digest(recovered.completedExchanges.flatMap { it.results }.single { it.callId == "page-2" }.content))
    }

    @Test fun insufficientPrivateReadingAllowanceRetainsExactPagesWithoutAcknowledgementOrReexecution() = runBlocking {
        val facts = (0..2).map { "CAPACITY_PAGE_FACT_$it" }
        val fixtures = facts.map { GitHubSavedPageCapacityTest.Fixture(it) }
        val history = fixtures.mapIndexed { i, fixture ->
            val source = fixture.history.first { exchange ->
                val result = exchange.results.single()
                "view_cursor" in exchange.calls.single().arguments && !result.isError && facts[i] in ToolHandoffReceipt.payload(result.content)
            }
            val call = source.calls.single().copy(callId = "page-$i")
            AgentToolExchange(listOf(call), listOf(fixture.read(call)))
        }
        val group = AgentToolExchange(history.flatMap { it.calls }, history.flatMap { it.results })
        val proofs = ToolResultDeliveryProof.capture(group.results)
        val journal = ToolBatchCheckpoint(null, "same-profile")
        var toolExecutions = 0
        val reader = object : AgentTool {
            override val definition = fixtures.first().tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                toolExecutions++
                return group.results.single { it.callId == callId }
            }
        }
        var providerRounds = 0
        val model = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                assertTrue("No saved-page body fits after the mandatory private-reading reservation", exchanges.isEmpty())
                group.calls.forEach { emit(it) }
                emit(ProviderEvent.Completed)
            }.let { SavedPageDeliveryRound.providerRound(exchanges, it) }
        }
        val budget = SavedPageReading.reservation(null, true)
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 4, maxRounds = 12, maxReplayTokens = budget, maxReplayResultTokens = 512)
        val events = AgentRunner(limits, journal).run(model, listOf(ToolExecutionBudget(limits).bind(reader))).toList()
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { it.event as? ProviderEvent.Failed }.single()
        assertEquals("REPLAY_EVIDENCE_REQUIRED", failure.errorCode)
        assertEquals(JsonPrimitive("SAVED_PAGE_EXCEEDS_ALLOWANCE"), failure.failureDetails?.get("replay_budget_state"))
        assertEquals(JsonPrimitive("BODY_ALLOWANCE"), failure.failureDetails?.get("replay_missing_record"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(1, providerRounds)
        assertEquals(group.calls.size, toolExecutions)
        val recovered = journal.recoverySnapshot()
        assertEquals(null, recovered.savedPageReading)
        assertTrue(recovered.deliveredResultDigests.isEmpty())
        assertEquals(proofs, ToolResultDeliveryProof.capture(recovered.completedExchanges.flatMap { it.results }))
        assertTrue(recovered.unsettledCalls.isEmpty())
    }

    @Test fun ingressCompactionCannotPromoteSummaryDeliveryToTheOriginalIntervalAfterRestart() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result()
            val journal = stored(file, original)
            assertEquals(original.content, journal.recoverySnapshot().completedExchanges.single().results.single().content)
            val compact = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 2000)))
            assertFalse((compact.content as ToolResultContent.Json).value.jsonObject.containsKey("fragments"))
            // collectRound may consume this exact ingress projection, while the
            // checkpoint retains a different, fuller payload under the same ID.
            journal.markConsumed(setOf(original.callId), ToolResultDeliveryProof.capture(listOf(compact)))
            val restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(setOf(original.callId), restarted.consumedCallIds)
            assertTrue(restarted.deliveredResultDigests.isEmpty())
            assertEquals(arguments(50), next(restarted))
            assertUnreadReplay(restarted)
        } finally {
            file.delete()
        }
    }

    @Test fun exactDeliveredProjectionSurvivesRestartAndLaterSummariesCannotEraseItsProof() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result().copy(retainedContent = ToolResultContent.Text("Original source outside the displayed page"))
            val journal = stored(file, original)
            val proof = ToolResultDeliveryProof.capture(listOf(original))
            journal.markConsumed(setOf(original.callId), proof)
            var restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(proof, restarted.deliveredResultDigests)
            assertEquals(arguments(123), next(restarted))
            val compact = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 2000)))
            journal.markConsumed(setOf(original.callId), ToolResultDeliveryProof.capture(listOf(compact)))
            restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(proof, restarted.deliveredResultDigests)
            assertEquals(arguments(123), next(restarted))
        } finally {
            file.delete()
        }
    }

    @Test fun stagedNotesChooseJournalBytesAndRejectChangedProjectionsWithoutPartialConsumption() = runBlocking {
        val fixture = GitHubSavedPageCapacityTest.Fixture()
        val first = requireNotNull(fixture.next(tokens = 3000)).copy(callId = "wrapped")
        val second = first.copy(callId = "plain")
        val journal = ToolBatchCheckpoint(null, "same-grant")
        val exchanges = listOf(first, second).map { call ->
            journal.enqueue(listOf(call))
            val native = journal.execute(call) { fixture.read(call) }
            val body = (native.content as ToolResultContent.Json).value.jsonObject
            val live = if (call == first) native.copy(content = ToolResultContent.Json(JsonObject(body + ("agent_instruction" to JsonPrimitive("Finish the remaining reads."))))) else native
            AgentToolExchange(listOf(call), listOf(live))
        }
        journal.finish()
        val calls = listOf(first, second).associateBy { it.callId }
        val proofs = ToolResultDeliveryProof.capture(exchanges.flatMap { it.results })
        assertEquals(setOf(second.callId), journal.eligibleReadingSources(calls, proofs))
        assertTrue(journal.eligibleReadingSources(calls + (second.callId to second.copy(arguments = JsonObject(emptyMap()))), proofs).isEmpty())
        val rejected = SavedPageReading.append(null, SavedPageReading.sources(exchanges, proofs), "Do not checkpoint these notes.")
        assertFalse(journal.markConsumed(proofs.keys, proofs, rejected))
        val untouched = journal.recoverySnapshot()
        assertTrue(untouched.consumedCallIds.isEmpty())
        assertTrue(untouched.deliveredResultDigests.isEmpty())
        assertEquals(null, untouched.savedPageReading)

        val exact = proofs.filterKeys { it == second.callId }
        val accepted = SavedPageReading.append(null, SavedPageReading.sources(exchanges, exact), "Verified source data.")
        assertTrue(journal.markConsumed(exact.keys, exact, accepted))
        assertEquals(accepted, journal.recoverySnapshot().savedPageReading)
        assertTrue(journal.eligibleReadingSources(calls, proofs).isEmpty())
        assertFalse(journal.markConsumed(exact.keys, exact, accepted))
        assertEquals(exact, journal.recoverySnapshot().deliveredResultDigests)

        val wrapped = exchanges.first()
        val state = GitHubReplayRecovery.State(wrapped.calls, wrapped.results)
        val pending = state.pendingSavedPageDeliveries(listOf(wrapped), emptyMap())
        assertEquals(setOf(first.callId), pending.keys)
        assertEquals(null, state.restoreMissingPage(pending, listOf(wrapped), fixture.tools))
        val reread = requireNotNull(state.restoreMissingPage(pending, listOf(wrapped), fixture.tools, reopenAvailable = true))
        assertEquals(first.arguments, reread.arguments)
        val native = fixture.read(reread)
        assertFalse(native.dispatched)
        state.record(listOf(reread), listOf(native))
        assertEquals(null, state.restoreMissingPage(pending, listOf(wrapped), fixture.tools, reopenAvailable = true))
    }

    @Test fun legacyConsumptionIdsRemainCompatibleWithoutGrantingIntervalCoverage() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val journal = stored(file)
            journal.markConsumed(setOf(call().callId))
            val legacy = Json.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(legacy - "deliveredResultDigests").toString())
            val restarted = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
            assertEquals(setOf(call().callId), restarted.consumedCallIds)
            assertTrue(restarted.deliveredResultDigests.isEmpty())
            assertEquals(arguments(50), next(restarted))
            assertUnreadReplay(restarted)
        } finally {
            file.delete()
        }
    }

    @Test fun failedUnknownAndMismatchedPayloadsNeverAcquireDeliveryProof() = runBlocking {
        for (failed in listOf(
            result().copy(isError = true),
            result().copy(errorCode = "GATEWAY_OUTCOME_UNKNOWN"),
            result().copy(executionReceipt = buildJsonObject { put("requires_reconciliation", true) })
        )) {
            val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
            try {
                val journal = stored(file, failed)
                assertTrue(ToolResultDeliveryProof.capture(listOf(failed)).isEmpty())
                journal.markConsumed(setOf(failed.callId, "unknown"), mapOf(failed.callId to ToolResultDeliveryProof.digest(failed.content), "unknown" to "a".repeat(64)))
                val snapshot = ToolBatchCheckpoint(file, "same-grant").recoverySnapshot()
                assertEquals(setOf(failed.callId), snapshot.consumedCallIds)
                assertTrue(snapshot.deliveredResultDigests.isEmpty())
                assertEquals(arguments(50), next(snapshot))
            } finally {
                file.delete()
            }
        }
    }

    @Test fun malformedAndOversizedProofProposalsLeaveTheJournalUnchanged() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val journal = stored(file)
            val before = file.readText()
            for (proof in listOf(
                mapOf(call().callId to "not-a-digest"),
                mapOf("outside-delivery" to "a".repeat(64)),
                (0..ToolResultDeliveryProof.MAX_RESULTS).associate { "id-$it" to "a".repeat(64) }
            )) {
                assertTrue(runCatching { journal.markConsumed(setOf(call().callId), proof) }.isFailure)
                assertEquals(before, file.readText())
            }
        } finally {
            file.delete()
        }
    }

    @Test fun malformedPersistedProofIsRejectedBeforeReconstruction() = runBlocking {
        val file = File.createTempFile("delivery-proof", ".json").apply { delete() }
        try {
            val original = result()
            val journal = stored(file, original)
            val digest = ToolResultDeliveryProof.digest(original.content)
            journal.markConsumed(setOf(original.callId), mapOf(original.callId to digest))
            val valid = Json.parseToJsonElement(file.readText()).jsonObject
            val proofs = listOf(
                buildJsonObject { put(original.callId, "not-a-digest") },
                buildJsonObject { put(original.callId, "a".repeat(64)) },
                buildJsonObject { put("unknown", digest) },
                JsonObject((0..ToolResultDeliveryProof.MAX_RESULTS).associate { "id-$it" to JsonPrimitive(digest) })
            )
            for (proof in proofs) {
                file.writeText(JsonObject(valid + ("deliveredResultDigests" to proof)).toString())
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            }
            file.writeText(JsonObject(valid + ("consumedCallIds" to JsonArray(emptyList()))).toString())
            assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            val entries = valid.getValue("entries").jsonObject
            val entry = entries.getValue(original.callId).jsonObject
            val result = entry.getValue("result").jsonObject
            for (changed in listOf(
                "error" to JsonPrimitive(true),
                "code" to JsonPrimitive("GATEWAY_OUTCOME_UNKNOWN"),
                "execution" to buildJsonObject { put("requires_reconciliation", true) }
            )) {
                val altered = JsonObject(entry + ("result" to JsonObject(result + changed)))
                file.writeText(JsonObject(valid + ("entries" to JsonObject(entries + (original.callId to altered)))).toString())
                assertTrue(runCatching { ToolBatchCheckpoint(file, "same-grant").recoverySnapshot() }.isFailure)
            }
        } finally {
            file.delete()
        }
    }

    private fun assertUnreadReplay(snapshot: ToolBatchRecoverySnapshot) {
        val consumed = ToolResultDeliveryProof.qualifiedConsumed(snapshot.completedExchanges, snapshot.consumedCallIds, snapshot.deliveredResultDigests)
        assertTrue(consumed.isEmpty())
        val replay = ToolExchangeCompactor.compact(snapshot.completedExchanges, 1024, 512, consumed)
        val body = requireNotNull(GitHubReplayRecovery.envelope(replay.single().results.single().content))
        assertEquals(JsonPrimitive(false), body["previously_delivered"])
        assertEquals(arguments(50), (body["continuation_call"] as JsonObject)["arguments"])
        assertTrue(body["prior_delivery_scope"] != JsonPrimitive("FULL_RESULT"))
    }
}
