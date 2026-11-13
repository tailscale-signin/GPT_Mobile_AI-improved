package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOutputContinuationSessionTest {
    private val tool = AgentToolDefinition("github_read", "Read pinned files", JsonObject(emptyMap()))
    private val limited = ProviderEvent.Failed("The response reached its output limit.", transportComplete = true)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun explicitContinuationProhibitionAppliesBeforeLengthRepairAndAfterRestoration() = runBlocking {
        for (draft in listOf("", "Retained findings")) {
            var checkpoint: JsonObject? = null
            val failure = limited.copy(failureDetails = JsonObject(mapOf("resume_allowed" to JsonPrimitive(false))))
            val blocked = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta(draft), failure), continuationRevision = "same-config", recoveryRevision = "run-one", saveCheckpoint = { checkpoint = it }) { _, _, _ ->
                error("An explicit continuation prohibition applies before automatic length repair")
            }.streamRound(emptyList(), emptyList()).toList()
            assertEquals(failure, blocked.last())
            assertEquals(JsonPrimitive(false), checkpoint?.get("resume_allowed"))
            assertEquals(JsonPrimitive("run-one"), checkpoint?.get("recovery_revision"))
            val restored = GitHubOutputContinuationSession(
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("A saved prohibition cannot become a new provider request")
                },
                continuationRevision = "same-config",
                recoveryRevision = "run-two",
                readCheckpoint = { checkpoint }
            ) { _, _, _ -> error("A saved prohibition cannot be reopened") }
                .streamRound(emptyList(), emptyList()).toList()
            assertEquals("BLOCKED", (restored.single() as ProviderEvent.Failed).taskOutcome)
        }
    }

    @Test fun rejectedNewTurnDoesNotOfferAfterToolResultsRecoveryWithoutReceipts() = runBlocking {
        var checkpoint: JsonObject? = null
        val conflict = ProviderEvent.Failed(
            "The earlier gateway job is still waiting for its tool results.",
            errorCode = "CONVERSATION_CONFLICT",
            failureDetails = JsonObject(
                mapOf(
                    "retryable" to JsonPrimitive(false),
                    "active_job_id" to JsonPrimitive("earlier-job"),
                    "recovery_action" to JsonPrimitive("return_client_tool_results")
                )
            )
        )
        val output = GitHubOutputContinuationSession(session(conflict), saveCheckpoint = { checkpoint = it }) { _, _, _ ->
            error("A rejected new turn cannot reopen an earlier job")
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(conflict, output.last())
        assertEquals(JsonPrimitive(false), checkpoint?.get("resume_allowed"))
        assertEquals(JsonPrimitive("ACTIVE_GATEWAY_JOB"), checkpoint?.get("resume_kind"))
        assertEquals(JsonPrimitive("return_client_tool_results"), checkpoint?.get("next_action"))
        assertEquals(kotlinx.serialization.json.JsonArray(emptyList()), checkpoint?.get("completed_results"))
        assertEquals(conflict.failureDetails, checkpoint?.get("failure_details"))
    }

    @Test fun missingBaselineKeepsTheUserInputRequirementInTheSavedCheckpoint() = runBlocking {
        var checkpoint: JsonObject? = null
        val blocked = ProviderEvent.Failed(
            "Select the review baseline.",
            errorCode = "REVIEW_INPUT_REQUIRED",
            taskOutcome = "BLOCKED",
            failureDetails = JsonObject(mapOf("user_input_required" to JsonPrimitive(true), "next_action" to JsonPrimitive("SELECT_REVIEW_BASELINE")))
        )
        GitHubOutputContinuationSession(session(blocked), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No provider retry") }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(JsonPrimitive(false), checkpoint?.get("resume_allowed"))
        assertEquals(JsonPrimitive(true), checkpoint?.get("user_input_required"))
        assertEquals(JsonPrimitive("SELECT_REVIEW_BASELINE"), checkpoint?.get("next_action"))
    }

    @Test fun extractedSegmentCollectorCheckpointsLargeDraftWithoutDuplicatingPublishedText() = runBlocking {
        val body = "verified evidence\n" + "x".repeat(5_000)
        val checkpoints = mutableListOf<JsonObject>()

        val output = GitHubOutputContinuationSession(
            delegate = session(ProviderEvent.TextDelta(body), ProviderEvent.Completed),
            saveCheckpoint = { checkpoints += it }
        ) { _, _, _ -> error("A complete segment must not reopen the provider") }
            .streamRound(emptyList(), emptyList())
            .toList()

        assertEquals(body, output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, output.last())
        assertTrue(checkpoints.any { it["state"] == JsonPrimitive("WRITING") && it["draft"] == JsonPrimitive(body) })
        assertEquals(JsonPrimitive("COMPLETED"), checkpoints.last()["state"])
        assertEquals(JsonPrimitive(body), checkpoints.last()["draft"])
    }

    @Test fun usageIsAdditiveAcrossRequestsAndKeepsMeasuredSourcesSeparate() = runBlocking {
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("First"), ProviderEvent.Usage(outputTokens = 4096, cumulative = true, finishReason = "length"), limited)) { _, _, _ ->
            session(ProviderEvent.TextDelta(" second"), ProviderEvent.Usage(outputTokens = 1024, cumulative = true), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        val usage = output.filterIsInstance<ProviderEvent.Usage>()
        assertEquals(5120, usage.sumOf { it.outputTokens ?: 0 })
        assertTrue(usage.all { !it.cumulative && it.outputSource == "provider_reported" })
    }

    @Test fun limitedDraftContinuesWithToolsAndCompleteReceipts() = runBlocking {
        val receipt = AgentToolExchange(listOf(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap()))), listOf(AgentToolResult("read", ToolResultContent.Text("Verified file"), false)))
        var checkpoint: JsonObject? = null
        var requests = 0
        val continuing = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                assertEquals(listOf(tool), tools)
                assertTrue(exchanges.isEmpty())
                return flowOf(ProviderEvent.TextDelta(" and the remaining work."), ProviderEvent.Completed)
            }
        }
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Saved findings"), limited), saveCheckpoint = { checkpoint = it }) { draft, evidence, definitions ->
            requests++
            assertEquals("Saved findings", draft)
            assertEquals(listOf(receipt), evidence)
            assertEquals(listOf(tool), definitions)
            continuing
        }.streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals(1, requests)
        assertEquals("Saved findings and the remaining work.", output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(ProviderEvent.Completed, output.last())
        assertEquals(JsonPrimitive("COMPLETED"), checkpoint?.get("state"))
    }

    @Test fun providerCooldownRestoresTheSameToolBoundaryWithNoVisibleDraft() = runBlocking {
        val binding = "a".repeat(64)
        val scope = "2:17:profile"
        val journalKey = GitHubProviderToolCheckpoint.binding(scope, "original-dispatch-guard", null, null)
        val context = JsonObject(mapOf("task_binding" to JsonPrimitive(binding), "tool_checkpoint_binding" to JsonPrimitive(journalKey)))
        val calls = listOf("context", "search").map { id -> ProviderEvent.ToolCall(id, tool.name, JsonObject(emptyMap())) }
        val results = calls.map { AgentToolResult(it.callId, ToolResultContent.Text("Saved ${it.callId}"), false) }
        val receipts = listOf(AgentToolExchange(calls, results))
        val restored = calls.zip(results).map { (call, result) -> AgentToolExchange(listOf(call), listOf(result)) }
        val journals = mapOf(journalKey to restored)
        val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("OPENROUTER", "profile", "model", 30_000, "scope", "PROVIDER_RATE_LIMITED", true)
        val rateLimited = ProviderEvent.Failed("Provider limit", errorCode = "429", providerLimit = limit)
        var checkpoint: JsonObject? = null
        val rejected = GitHubOutputContinuationSession(
            session(rateLimited),
            recoveryRevision = "same-run",
            checkpointContext = context,
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> error("A durable wait owns this retry") }
            .streamRound(listOf(tool), receipts).toList().filterIsInstance<ProviderEvent.Failed>().single()
        val saved = requireNotNull(rejected.providerLimit?.gitHubCheckpoint)
        assertEquals(JsonPrimitive("WAITING_PROVIDER_LIMIT"), checkpoint?.get("state"))
        assertTrue(saved.matches(requireNotNull(checkpoint)))
        val resumed = GitHubOutputContinuationSession(
            session(),
            recoveryRevision = "same-run",
            expectedProviderCheckpoint = saved,
            toolCheckpointBinding = GitHubProviderToolCheckpoint.binding(scope, "resumed-provider-wait-guard", saved, checkpoint),
            checkpointContext = context,
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { draft, exchanges, _ ->
            assertEquals("", draft)
            assertEquals(restored, exchanges)
            session(ProviderEvent.TextDelta("Finished from saved results."), ProviderEvent.Completed)
        }
        val output = resumed.streamRound(listOf(tool), journals[resumed.toolCheckpointBinding].orEmpty()).toList()
        assertTrue(output.none { it is ProviderEvent.ToolCall })
        assertEquals("Finished from saved results.", output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun evictedProviderReplayRequiresMatchingDurableIdentitiesAndUnknownEffectsRemainBlocked() = runBlocking {
        for (scenario in listOf("retained", "changed-source", "missing", "unknown", "changed-checkpoint", "same-replay-source", "same-replay-dispatch", "same-replay-error", "same-replay-fingerprint", "same-replay-id", "same-replay-duplicate", "lost-cas")) {
            val call = ProviderEvent.ToolCall("saved", tool.name, JsonObject(emptyMap()))
            val result = ToolHandoffReceipt.identifySource(AgentToolResult(call.callId, ToolResultContent.Text("Retained original receipt"), false))
            val receipt = ToolHandoffReceipt.from(call, result).copy(receiptSource = "TOOL_BATCH_CHECKPOINT")
            var checkpoint: JsonObject? = null
            var requests = 0
            val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("TEST", "profile", "model", 30_000, "scope", "PROVIDER_RATE_LIMITED", true)
            val originalFailure = ProviderEvent.Failed(
                "Provider rejected the request",
                errorCode = "429",
                providerLimit = limit,
                failureDetails = buildJsonObject {
                    put("http_status", 429)
                    put("invocation_id", "original-invocation")
                    put("dispatched", true)
                }
            )
            val rejected = GitHubOutputContinuationSession(
                session(originalFailure),
                checkpointContext = buildJsonObject { put("task_binding", "a".repeat(64)) },
                saveCheckpoint = { checkpoint = it }
            ) { _, _, _ -> error("The durable wait owns recovery") }.streamRound(listOf(tool), listOf(AgentToolExchange(listOf(call), listOf(result)))).toList().filterIsInstance<ProviderEvent.Failed>().single()
            val expected = requireNotNull(rejected.providerLimit?.gitHubCheckpoint)
            if (scenario == "changed-checkpoint") checkpoint = JsonObject(requireNotNull(checkpoint) + ("draft" to JsonPrimitive("Newer owner draft")))
            val before = checkpoint
            val durable = when (scenario) {
                "missing" -> emptyList()
                "changed-source", "same-replay-source", "lost-cas" -> listOf(receipt.copy(sourceResultSha256 = "f".repeat(64)))
                "same-replay-dispatch" -> listOf(receipt.copy(dispatched = !receipt.dispatched))
                "same-replay-error" -> listOf(receipt.copy(errorCode = "TOOL_UNAVAILABLE"))
                "same-replay-fingerprint" -> listOf(receipt.copy(invocationFingerprint = "f".repeat(64)))
                "same-replay-id" -> listOf(receipt.copy(callId = "another-call"))
                "same-replay-duplicate" -> listOf(receipt, receipt.copy(callId = "another-call"))
                "unknown" -> listOf(receipt.copy(requiresReconciliation = true, errorCode = "TOOL_OUTCOME_UNKNOWN"))
                else -> listOf(receipt)
            }
            val restored = GitHubOutputContinuationSession(
                session(),
                expectedProviderCheckpoint = expected,
                readCheckpoint = { checkpoint },
                saveCheckpoint = { checkpoint = it },
                durableToolReceipts = { durable },
                saveRecoveryCheckpoint = { _, updated ->
                    if (scenario == "lost-cas") {
                        checkpoint = buildJsonObject {
                            put("state", "COMPLETED")
                            put("draft", "Newer checkpoint")
                        }
                        false
                    } else {
                        checkpoint = updated
                        true
                    }
                }
            ) { _, _, _ ->
                requests++
                session(ProviderEvent.TextDelta("Completed from durable receipts"), ProviderEvent.Completed)
            }
            val replay = if (scenario.startsWith("same-replay")) listOf(AgentToolExchange(listOf(call), listOf(result))) else emptyList()
            val outcome = runCatching { restored.streamRound(listOf(tool), replay).toList() }
            if (scenario == "retained") {
                assertTrue(outcome.isSuccess)
                assertEquals(1, requests)
                assertEquals(JsonPrimitive("COMPLETED"), checkpoint?.get("state"))
            } else {
                val failure = outcome.exceptionOrNull() as ProviderRecoveryFailure
                assertEquals(
                    if (scenario == "unknown") {
                        "PROVIDER_WAIT_OUTCOME_UNKNOWN"
                    } else if (scenario in setOf("changed-checkpoint", "lost-cas")) {
                        "PROVIDER_WAIT_CHECKPOINT_CHANGED"
                    } else {
                        "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE"
                    },
                    failure.code
                )
                assertEquals(0, requests)
                if (scenario == "lost-cas") {
                    assertEquals(JsonPrimitive("COMPLETED"), checkpoint?.get("state"))
                    assertEquals(JsonPrimitive("Newer checkpoint"), checkpoint?.get("draft"))
                } else if (scenario == "changed-checkpoint") {
                    assertEquals(before, checkpoint)
                } else {
                    assertEquals(JsonPrimitive("BLOCKED_RECOVERY"), checkpoint?.get("state"))
                    assertEquals(JsonPrimitive(false), checkpoint?.get("resume_allowed"))
                    assertEquals(before?.get("completed_results"), checkpoint?.get("completed_results"))
                    assertEquals(JsonPrimitive("429"), (checkpoint?.get("original_interruption") as JsonObject)["code"])
                    assertEquals(JsonPrimitive(failure.code), (checkpoint?.get("latest_recovery_failure") as JsonObject)["recovery_code"])
                }
            }
        }
    }

    @Test fun continuationCanRequestRemainingWorkWithoutDispatchingItInsideTheWrapper() = runBlocking {
        val call = ProviderEvent.ToolCall("next", tool.name, JsonObject(emptyMap()))
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Plan"), limited)) { _, _, _ ->
            session(call, ProviderEvent.Completed)
        }.streamRound(listOf(tool), emptyList()).toList()
        assertEquals(listOf(call), output.filterIsInstance<ProviderEvent.ToolCall>())
        assertTrue(output.none { it is ProviderEvent.TextDelta || it is ProviderEvent.ToolResult })
        assertEquals(ProviderEvent.Completed, output.last())
    }

    @Test fun toolHandoffResumesTheDraftWithConfirmedReceiptsWithoutReplenishingTheBudget() = runBlocking {
        val call = ProviderEvent.ToolCall("remaining", tool.name, JsonObject(emptyMap()))
        val receipt = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Confirmed remaining work"), false)))
        var reopenCount = 0
        val recovery = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Verified first section."), limited), maxContinuations = 1) { draft, evidence, definitions ->
            assertEquals("Verified first section.", draft)
            assertEquals(listOf(tool), definitions)
            if (++reopenCount == 1) {
                assertTrue(evidence.isEmpty())
                session(call, ProviderEvent.Completed)
            } else {
                assertEquals(listOf(receipt), evidence)
                session(ProviderEvent.TextDelta("\nVerified final section."), ProviderEvent.Completed)
            }
        }
        val handoff = recovery.streamRound(listOf(tool), emptyList()).toList()
        assertEquals(listOf(call), handoff.filterIsInstance<ProviderEvent.ToolCall>())
        val finished = recovery.streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals(2, reopenCount)
        assertTrue(finished.none { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
        assertEquals("Verified first section.\nVerified final section.", finished.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun restoredHandoffCannotRegenerateUnconfirmedOrUnknownActions() = runBlocking {
        val call = ProviderEvent.ToolCall("mutation", tool.name, JsonObject(emptyMap()))
        var checkpoint: JsonObject? = null
        GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Retained draft"), limited), saveCheckpoint = { checkpoint = it }) { _, _, _ -> session(call, ProviderEvent.Completed) }
            .streamRound(listOf(tool), emptyList()).toList()
        val unknown = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Mutation outcome unknown"), true, errorCode = "TOOL_OUTCOME_UNKNOWN")))
        for (receipts in listOf(emptyList(), listOf(unknown))) {
            val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Must not regenerate")), readCheckpoint = { checkpoint }) { _, _, _ -> error("Unconfirmed actions need reconciliation") }
                .streamRound(listOf(tool), receipts).toList()
            val failure = output.single() as ProviderEvent.Failed
            assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", failure.errorCode)
            assertEquals("BLOCKED", failure.taskOutcome)
        }
    }

    @Test fun interruptedNormalRoundAfterToolHandoffDoesNotConsumeOutputContinuationBudget() = runBlocking {
        val call = ProviderEvent.ToolCall("remaining", tool.name, JsonObject(emptyMap()))
        val receipt = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Confirmed result"), false)))
        var checkpoint: JsonObject? = null
        var opened = 0
        val first = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Saved draft"), limited), maxContinuations = 1, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { _, _, _ ->
            if (++opened == 1) session(call, ProviderEvent.Completed) else throw CancellationException("Interrupted normal round before dispatch")
        }
        first.streamRound(listOf(tool), emptyList()).toList()
        try {
            first.streamRound(listOf(tool), listOf(receipt)).toList()
        } catch (_: CancellationException) {
            // Restoring this normal post-tool round must not fabricate a length continuation.
        }
        val resumed = GitHubOutputContinuationSession(session(), maxContinuations = 1, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { draft, evidence, _ ->
            assertEquals("Saved draft", draft)
            assertEquals(listOf(receipt), evidence)
            session(ProviderEvent.TextDelta(" completed."), ProviderEvent.Completed)
        }.streamRound(listOf(tool), listOf(receipt)).toList()
        assertEquals("Saved draft completed.", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(JsonPrimitive(1), checkpoint?.get("continuations"))
    }

    @Test fun incompleteToolFramesAndUnknownMutationsNeverAutoContinue() = runBlocking {
        for (failure in listOf(limited.copy(errorCode = "TOOL_PROTOCOL_INCOMPLETE_FRAME"), limited.copy(errorCode = GatewayOutcomeRecovery.ERROR_CODE), limited.copy(transportComplete = false))) {
            val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Partial"), failure)) { _, _, _ ->
                error("An unsafe response must not be replayed")
            }.streamRound(listOf(tool), emptyList()).toList()
            assertEquals(failure, output.last())
            assertTrue(output.none { it is ProviderEvent.ToolCall || it is ProviderEvent.TextDelta })
        }
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Partial"), ProviderEvent.ToolCall("uncommitted", tool.name, JsonObject(emptyMap())), limited)) { _, _, _ -> error("Buffered calls require runner repair") }
            .streamRound(listOf(tool), emptyList()).toList()
        assertEquals(limited, output.last())
        assertTrue(output.none { it is ProviderEvent.ToolCall })
    }

    @Test fun processRestartRetainsDraftAndDoesNotReplenishClaimedBudget() = runBlocking {
        var checkpoint: JsonObject? = null
        var generations = 0
        val original = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                generations++
                emit(ProviderEvent.TextDelta("Saved before process death"))
                emit(limited)
            }
        }
        try {
            GitHubOutputContinuationSession(original, maxContinuations = 1, saveCheckpoint = { checkpoint = it }) { _, _, _ ->
                throw CancellationException("Process interrupted after persisted claim")
            }.streamRound(emptyList(), emptyList()).toList()
        } catch (_: CancellationException) {
            // Simulates stopping between a durable claim and receiving the next response.
        }
        val restored = GitHubOutputContinuationSession(original, maxContinuations = 1, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No budget may be fabricated after restart") }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, generations)
        assertEquals("OUTPUT_CONTINUATION_BUDGET_EXHAUSTED", (restored.last() as ProviderEvent.Failed).errorCode)
        assertEquals(JsonPrimitive("Saved before process death"), checkpoint?.get("draft"))
    }

    @Test fun interruptedTextResumesFromItsDurablePrefixWithoutRestartingGeneration() = runBlocking {
        var checkpoint: JsonObject? = null
        val interrupted = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Confirmed prefix"))
                throw CancellationException("Stopped")
            }
        }
        try {
            GitHubOutputContinuationSession(interrupted, saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("No continuation before cancellation") }.streamRound(emptyList(), emptyList()).toList()
        } catch (_: CancellationException) {
            // A fresh session models a later explicit resume after process restoration.
        }
        val unexpectedRestart = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The original generation must not restart")
        }
        val resumed = GitHubOutputContinuationSession(unexpectedRestart, readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { draft, _, definitions ->
            assertEquals("Confirmed prefix", draft)
            assertTrue(definitions.isEmpty())
            session(ProviderEvent.TextDelta(" and continued suffix"), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Confirmed prefix and continued suffix", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun repeatedOutputStopsWithoutPublishingDuplicatedText() = runBlocking {
        var calls = 0
        val draft = "A sufficiently long retained answer with exact prior findings."
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta(draft), limited)) { _, _, _ ->
            calls++
            session(ProviderEvent.TextDelta(draft), limited)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, calls)
        assertEquals("OUTPUT_CONTINUATION_NO_PROGRESS", (output.last() as ProviderEvent.Failed).errorCode)
        assertTrue(output.none { it is ProviderEvent.TextDelta })
    }

    @Test fun noProgressStaysBlockedUntilConfigurationChangesThenResumesTheSameDraft() = runBlocking {
        var checkpoint: JsonObject? = null
        val draft = "A saved draft with verified findings."
        GitHubOutputContinuationSession(session(ProviderEvent.TextDelta(draft), limited), continuationRevision = "old", saveCheckpoint = { checkpoint = it }) { _, _, _ -> session(ProviderEvent.TextDelta(draft), limited) }
            .streamRound(emptyList(), emptyList()).toList()
        val neverRestart = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The original generation must not repeat")
        }
        val blocked = GitHubOutputContinuationSession(neverRestart, continuationRevision = "old", readCheckpoint = { checkpoint }) { _, _, _ -> error("Unchanged input remains blocked") }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals("BLOCKED", (blocked.single() as ProviderEvent.Failed).taskOutcome)
        val resumed = GitHubOutputContinuationSession(neverRestart, continuationRevision = "larger-capacity", readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }) { saved, _, _ ->
            assertEquals(draft, saved)
            session(ProviderEvent.TextDelta(" More findings."), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(draft + " More findings.", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(JsonPrimitive(1), checkpoint?.get("continuations"))
    }

    @Test fun safeInterruptedFailureFrameRetainsPrefixForExplicitResume() = runBlocking {
        var checkpoint: JsonObject? = null
        val failure = ProviderEvent.Failed("Unexpected end of stream", errorCode = "STREAM_INTERRUPTED")
        val interrupted = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Confirmed prefix"), failure), saveCheckpoint = { checkpoint = it }) { _, _, _ -> error("An interrupted stream does not auto-retry") }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals(failure, interrupted.last())
        val resumed = GitHubOutputContinuationSession(session(), readCheckpoint = { checkpoint }) { draft, _, _ ->
            assertEquals("Confirmed prefix", draft)
            session(ProviderEvent.TextDelta(" and suffix"), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Confirmed prefix and suffix", resumed.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun foreignEvidenceCannotResumeAnotherRoundsDraft() = runBlocking {
        val stale = JsonObject(mapOf("evidence_digest" to JsonPrimitive("different"), "state" to JsonPrimitive("OUTPUT_LIMIT"), "draft" to JsonPrimitive("Foreign draft"), "continuations" to JsonPrimitive(0)))
        val output = GitHubOutputContinuationSession(session(ProviderEvent.TextDelta("Current answer"), ProviderEvent.Completed), readCheckpoint = { stale }) { _, _, _ -> error("Unrelated draft must not be sent") }
            .streamRound(emptyList(), emptyList()).toList()
        assertEquals("Current answer", output.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }

    @Test fun serverOwnedToolSessionsKeepTheirReconciliationBoundary() = runBlocking {
        val source = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(limited)
        }
        val output = GitHubOutputContinuationSession(source) { _, _, _ -> error("Server-owned work needs reconciliation") }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf(limited), output)
    }
}
