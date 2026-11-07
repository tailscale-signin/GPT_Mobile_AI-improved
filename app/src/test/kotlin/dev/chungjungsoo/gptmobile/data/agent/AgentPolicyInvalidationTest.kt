package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyInvalidationTest {
    private val args = JsonObject(emptyMap())

    @Test fun precheckStopsQueuedWritesAndDiscoveryWhileKeepingCompletedReceipts() = runTest {
        var rounds = 0
        var invocations = 0
        var discoveries = 0
        var stale = false
        val limits = AgentRunLimits(githubTask = true, maxConcurrentTools = 1, maxToolCalls = 6)
        val budget = ToolExecutionBudget(limits)
        val read = budget.bind(
            tool("github_read__selected") { id ->
                invocations++
                AgentToolResult(id, ToolResultContent.Text("confirmed evidence"), false)
            }
        ) { _, _ ->
            if (stale) throw DispatchPolicyChangedException(listOf("profile.disableAllTools"))
            true
        }
        val write = budget.bind(tool("write_fixture") { error("A queued write cannot execute under changed authority") })
        val checkpoint = ToolBatchCheckpoint(null, "same-selected-profile")
        val session = session {
            when (rounds++) {
                0 -> listOf(ProviderEvent.ToolCall("confirmed", read.definition.name, args), ProviderEvent.Completed)
                1 -> {
                    stale = true
                    listOf(ProviderEvent.ToolCall("stale", read.definition.name, args), ProviderEvent.ToolCall("queued-write", write.definition.name, args), ProviderEvent.Completed)
                }
                else -> error("No provider continuation or replacement profile")
            }
        }
        val events = AgentRunner(limits, checkpoint).run(session, listOf(read, write), dynamicTools = {
            discoveries++
            emptyList()
        }).toList()
        val failure = terminal(events)
        assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
        assertEquals("CONFIGURATION_INVALIDATED", failure.taskOutcome)
        assertEquals(JsonPrimitive("tool_precheck"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(2, rounds)
        assertEquals(1, discoveries)
        assertEquals(1, invocations)
        assertEquals(1, budget.usedCalls())
        val retained = checkpoint.recoverySnapshot()
        assertEquals(1, retained.completedCount)
        assertEquals(2, retained.rejectedCount)
        assertEquals(0, retained.unknownCount)
        assertEquals("confirmed evidence", (retained.completedExchanges.first().results.single().content as ToolResultContent.Text).text)
        assertFalse(events.providerEvents().any { it is ProviderEvent.Completed })
    }

    @Test fun invokedUnmanagedMutationIsUnknownAndBothSchedulersStopQueuedSiblings() = runTest {
        for (github in listOf(false, true)) {
            var rounds = 0
            var writes = 0
            var discoveries = 0
            val write = tool("write_fixture") {
                writes++
                throw DispatchPolicyChangedException(listOf("engineering"))
            }
            val checkpoint = ToolBatchCheckpoint(null, "same-profile-$github")
            val session = session {
                check(rounds++ == 0) { "No finalization using invalidated authority" }
                // Distinct operations exercise the authority latch; identical writes
                // are already blocked by the independent unknown-outcome replay fence.
                (1..3).map { ProviderEvent.ToolCall("write-$it", write.definition.name, buildJsonObject { put("operation_id", it) }) } + ProviderEvent.Completed
            }
            val events = AgentRunner(AgentRunLimits(githubTask = github, maxConcurrentTools = 1), checkpoint)
                .run(session, listOf(write), dynamicTools = {
                    discoveries++
                    emptyList()
                }).toList()
            val first = events.filterIsInstance<AgentRunEvent.ToolFinished>().first().result
            assertEquals("DISPATCH_OUTCOME_UNKNOWN", first.errorCode)
            assertTrue(first.dispatched)
            assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(first))
            assertEquals(JsonPrimitive(true), first.executionReceipt?.get("requires_reconciliation"))
            assertEquals("DISPATCH_POLICY_CHANGED", ToolFailureMetadata.authorityCode(first))
            assertEquals(1, writes)
            assertEquals(1, rounds)
            assertEquals(0, discoveries)
            assertEquals("RECONCILIATION_REQUIRED", terminal(events).errorCode)
            assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
            assertTrue(
                events.filterIsInstance<AgentRunEvent.ToolFinished>().drop(1).all {
                    !it.result.dispatched && it.result.errorCode == "DISPATCH_POLICY_CHANGED" && ToolResultEnvelope.executionState(it.result) == "PRECHECK_REJECTED"
                }
            )
        }
    }

    @Test fun aSecondaryEvidencePolicyFailureCannotReplaceTheObservedDomainOutcome() = runTest {
        for ((code, policy) in listOf(
            "DISPATCH_POLICY_CHANGED" to DispatchPolicyChangedException(listOf("engineering")),
            "POLICY_STALE" to dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("POLICY_STALE", "Selected authority changed")
        )) {
            for (unknown in listOf(false, true)) {
                var writes = 0
                val original = AgentToolResult("write", ToolResultContent.Text("authoritative receipt"), unknown, errorCode = if (unknown) "TOOL_OUTCOME_UNKNOWN" else null)
                val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true))
                val write = budget.bind(
                    tool("write_fixture") {
                        writes++
                        original
                    }
                )
                val checkpoint = ToolBatchCheckpoint(null, "receipt-$unknown")
                val events = AgentRunner(AgentRunLimits(githubTask = true, maxConcurrentTools = 1), checkpoint, onToolResult = { _, result ->
                    if (result.dispatched) throw policy
                }).run(session { listOf(ProviderEvent.ToolCall(original.callId, write.definition.name, args), ProviderEvent.ToolCall("queued", write.definition.name, args), ProviderEvent.Completed) }, listOf(write)).toList()
                val result = events.filterIsInstance<AgentRunEvent.ToolFinished>().first().result
                assertEquals(original.content, result.content)
                assertEquals(original.errorCode, result.errorCode)
                assertEquals(original.isError, result.isError)
                assertTrue(result.dispatched)
                assertEquals(if (unknown) "RECONCILIATION_REQUIRED" else code, terminal(events).errorCode)
                val retained = checkpoint.recoverySnapshot().completedExchanges.first().results.single()
                assertEquals(original.content, retained.content)
                assertEquals(original.errorCode, retained.errorCode)
                assertEquals(1, writes)
            }
        }
    }

    @Test fun restoredUnknownReceiptOutranksLaterToolOrProviderPolicyRejection() = runTest {
        for (providerFailure in listOf(false, true)) {
            for (priorToolVisible in listOf(false, true)) {
                val checkpoint = ToolBatchCheckpoint(null, "retained-$providerFailure-$priorToolVisible")
                val prior = ProviderEvent.ToolCall("prior", "write_fixture", args)
                checkpoint.enqueue(listOf(prior))
                checkpoint.execute(prior) { AgentToolResult(prior.callId, ToolResultContent.Text("prior uncertain receipt"), true, errorCode = "TOOL_OUTCOME_UNKNOWN") }
                val write = tool(prior.name) { error("The earlier mutation cannot be repeated") }
                val read = ToolExecutionBudget(AgentRunLimits()).bind(tool("github_read__selected") { error("Admission rejected") }) { _, _ ->
                    throw DispatchPolicyChangedException(listOf("engineering"))
                }
                var rounds = 0
                val session = session {
                    check(rounds++ == 0)
                    if (providerFailure) {
                        listOf(
                            ProviderEvent.Failed(
                                "Connection reset while settings changed",
                                errorCode = "DISPATCH_POLICY_CHANGED",
                                failureDetails = buildJsonObject {
                                    put("failed_stage", "provider_admission")
                                    put("dispatched", false)
                                }
                            )
                        )
                    } else {
                        listOf(ProviderEvent.ToolCall("new-read", read.definition.name, args), ProviderEvent.Completed)
                    }
                }
                val events = AgentRunner(AgentRunLimits(githubTask = true), checkpoint).run(session, listOfNotNull(read, write.takeIf { priorToolVisible })).toList()
                val failure = terminal(events)
                assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
                assertEquals("PARTIAL", failure.taskOutcome)
                val classified = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
                assertEquals("TOOL_OUTCOME_UNKNOWN", classified.category)
                assertEquals(RemoteRequestAcceptance.UNKNOWN, classified.remoteAcceptance)
                assertFalse(classified.dispatched == false)
                assertFalse(classified.retryAllowed)
                assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
                assertEquals("TOOL_OUTCOME_UNKNOWN", checkpoint.recoverySnapshot().completedExchanges.first().results.single().errorCode)
                assertEquals(1, rounds)
            }
        }
    }

    @Test fun actualStaleInventoryPrecheckStopsWithoutImplicitRediscovery() = runTest {
        val definitions = (1..20).map { AgentToolDefinition("selected_$it", "Selected tool", args) }
        val catalog = ProgressiveToolCatalog(definitions, "tools", acquireCold = { _, _ -> error("Do not implicitly rediscover after inventory rejection") })
        var rounds = 0
        val session = session {
            check(rounds++ == 0)
            listOf(
                ProviderEvent.ToolCall(
                    "stale-page",
                    catalog.definition.name,
                    buildJsonObject {
                        put("inventory_cursor", 12)
                        put("inventory_version", "old-version")
                    }
                ),
                ProviderEvent.Completed
            )
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(catalog), dynamicTools = { error("No implicit catalog replacement") }).toList()
        val rejected = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
        assertFalse(rejected.dispatched)
        assertEquals("STALE_INVENTORY", rejected.errorCode)
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(rejected))
        assertEquals(JsonPrimitive("tool_precheck"), rejected.executionReceipt?.get("phase"))
        assertEquals("STALE_INVENTORY", terminal(events).errorCode)
        assertFalse(ProfileFailureRecord.fromProvider(terminal(events).message, terminal(events).errorCode, terminal(events).failureDetails).retryAllowed)
        assertEquals(1, rounds)
    }

    @Test fun policyFailureWhileRestoringEvidenceRetainsUnknownOutcomeWithoutAnotherRound() = runTest {
        for (policy in listOf(
            DispatchPolicyChangedException(listOf("engineering")),
            dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("POLICY_STALE", "Selected authority changed")
        )) {
            val checkpoint = ToolBatchCheckpoint(null, "restored-observer")
            val prior = ProviderEvent.ToolCall("prior", "write_fixture", args)
            val original = AgentToolResult(prior.callId, ToolResultContent.Text("retained uncertain receipt"), true, errorCode = "TOOL_OUTCOME_UNKNOWN")
            checkpoint.enqueue(listOf(prior))
            checkpoint.execute(prior) { original }
            val events = AgentRunner(AgentRunLimits(githubTask = true), checkpoint, onToolResult = { _, _ -> throw policy })
                .run(session { error("No provider round after restoration authority failure") }, listOf(tool(prior.name) { error("No replay") })).toList()
            val restored = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
            assertEquals(original.content, restored.content)
            assertEquals(original.errorCode, restored.errorCode)
            assertTrue(restored.dispatched)
            assertEquals("RECONCILIATION_REQUIRED", terminal(events).errorCode)
            assertEquals("PARTIAL", terminal(events).taskOutcome)
            assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
        }
    }

    @Test fun typedPolicyFailureCannotEnterTransportRetryOrToolFreeFinalization() = runTest {
        for (github in listOf(false, true)) {
            for (code in listOf("DISPATCH_POLICY_CHANGED", "POLICY_STALE", "STALE_INVENTORY")) {
                var rounds = 0
                val events = AgentRunner(AgentRunLimits(githubTask = github)).run(
                    session {
                        rounds++
                        listOf(ProviderEvent.Failed("Connection reset; HTTP 502", errorCode = code))
                    },
                    emptyList()
                ).toList()
                assertEquals(1, rounds)
                assertEquals(code, terminal(events).errorCode)
                assertEquals("CONFIGURATION_INVALIDATED", terminal(events).taskOutcome)
                assertFalse(events.providerEvents().any { it is ProviderEvent.Completed })
            }
        }
    }

    @Test fun thrownProviderGuardKeepsPriorUncertaintyAndPreservesPreparationPropagationOtherwise() = runTest {
        for (unknown in listOf(false, true)) {
            for (github in listOf(false, true)) {
                val checkpoint = ToolBatchCheckpoint(null, "thrown-provider-$unknown-$github")
                val prior = ProviderEvent.ToolCall("prior", "write_fixture", args)
                if (unknown) {
                    checkpoint.enqueue(listOf(prior))
                    checkpoint.execute(prior) { AgentToolResult(prior.callId, ToolResultContent.Text("retained uncertain operation"), true, errorCode = "TOOL_OUTCOME_UNKNOWN") }
                }
                val policy = DispatchPolicyChangedException(listOf("profile"))
                var rounds = 0
                val provider = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                        rounds++
                        throw policy
                    }
                }
                val observed = runCatching { AgentRunner(AgentRunLimits(githubTask = github), checkpoint).run(provider, emptyList()).toList() }
                if (unknown) {
                    val failure = terminal(observed.getOrThrow())
                    assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
                    assertEquals("PARTIAL", failure.taskOutcome)
                    assertEquals(JsonPrimitive("DISPATCH_POLICY_CHANGED"), failure.failureDetails?.get("authority_invalidation_code"))
                    assertFalse(failure.failureDetails?.get("dispatched") == JsonPrimitive(false))
                    assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
                } else {
                    assertSame(policy, observed.exceptionOrNull())
                }
                assertEquals(1, rounds)
            }
        }
    }

    @Test fun downstreamPolicyExceptionStillPropagatesWithPriorUncertainty() = runTest {
        val checkpoint = ToolBatchCheckpoint(null, "downstream-policy")
        val prior = ProviderEvent.ToolCall("prior", "write_fixture", args)
        checkpoint.enqueue(listOf(prior))
        checkpoint.execute(prior) { AgentToolResult(prior.callId, ToolResultContent.Text("retained uncertain operation"), true, errorCode = "TOOL_OUTCOME_UNKNOWN") }
        val policy = DispatchPolicyChangedException(listOf("engineering"))
        var rounds = 0
        val observed = runCatching {
            AgentRunner(AgentRunLimits(githubTask = true), checkpoint).run(
                session {
                    rounds++
                    listOf(ProviderEvent.TextDelta("delivery"), ProviderEvent.Completed)
                },
                emptyList()
            ).collect { event ->
                if (event is AgentRunEvent.Provider && event.event is ProviderEvent.TextDelta) throw policy
            }
        }
        assertSame(policy, observed.exceptionOrNull())
        assertEquals(1, rounds)
        assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
    }

    private fun tool(name: String, execute: suspend (String) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Selected fixture operation", args)
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute(callId)
    }

    private fun session(events: () -> List<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow { events().forEach { emit(it) } }
        override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = error("Invalid authority cannot generate another answer")
    }

    private fun List<AgentRunEvent>.providerEvents() = filterIsInstance<AgentRunEvent.Provider>().map { it.event }
    private fun terminal(events: List<AgentRunEvent>) = events.providerEvents().filterIsInstance<ProviderEvent.Failed>().single()
}
