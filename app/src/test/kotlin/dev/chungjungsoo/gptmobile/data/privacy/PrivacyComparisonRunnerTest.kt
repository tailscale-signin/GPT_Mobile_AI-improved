package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyComparisonRunnerTest {
    private val policy = PrivacyModeState(enabled = true, revision = 7)

    @Test
    fun closedCorpusHasFourScoredTasksAndNoUserSuppliedTerms() {
        assertEquals(listOf("travel", "email", "student", "contract"), PrivacyComparisonCase.scored.map { it.id })
        assertNull(PrivacyComparisonCase.fromId("user message: private content"))
        assertTrue(PrivacyComparisonCase.entries.all { it.customTerms.isEmpty() })
        PrivacyComparisonCase.scored.forEach { case ->
            assertTrue(case.expectedPrivateValues.all { case.prompt.contains(it.first) })
            assertTrue(case.publicPhrases.all { case.prompt.contains(it) })
            assertTrue(case.assess(answer(case)).all { it.passed })
        }
    }

    @Test
    fun pairedRunExcludesWarmupsUsesCounterbalancedOrderAndUniqueRequestIds() = runTest {
        val calls = mutableListOf<Triple<String, Boolean, String>>()
        val result = PrivacyComparisonRunner(openSession = { id, protection, requestId, timeout ->
            assertEquals(60_000L, timeout)
            calls += Triple(id, protection, requestId)
            prepared(PrivacyComparisonCase.fromId(id)!!, protection)
        }).run("paired-test")
        assertEquals(PrivacyComparisonStatus.COMPLETE, result.status)
        assertEquals(2, result.warmupCompleted)
        assertEquals(8, result.legs.size)
        assertEquals(8, result.scoredCompleted)
        assertTrue(result.legs.all { it.taskCriteriaPassed })
        assertEquals(
            listOf("warmup" to false, "warmup" to true, "travel" to false, "travel" to true, "email" to true, "email" to false, "student" to false, "student" to true, "contract" to true, "contract" to false),
            calls.map { it.first to it.second }
        )
        assertEquals(10, calls.map { it.third }.toSet().size)
        assertTrue(result.legs.all { it.inputTokens == null && it.outputTokens == null })
        assertEquals("REQUESTED_CONFIGURATION_MATCHES_OBSERVATION_INCOMPLETE", result.pairStatus(PrivacyComparisonCase.TRAVEL))
    }

    @Test
    fun failedWarmupStopsBeforeScoredOrAdditionalProviderRequests() = runTest {
        var opens = 0
        val result = PrivacyComparisonRunner(openSession = { _, _, _, _ ->
            opens++
            session(flowOf(ProviderEvent.Failed("Do not retain this arbitrary provider detail")))
        }).run("warmup-failure")
        assertEquals(1, opens)
        assertEquals(0, result.warmupCompleted)
        assertEquals(PrivacyComparisonStatus.INCOMPLETE, result.status)
        assertEquals(PrivacyComparisonFailure.PROVIDER_FAILED, result.failure)
        assertTrue(result.legs.isEmpty())
        assertFalse(result.toString().contains("arbitrary provider detail"))
    }

    @Test
    fun partialFailurePreservesPrefixAndDoesNotScoreItOrReplayTheRequest() = runTest {
        var opens = 0
        val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            opens++
            if (id == "warmup") {
                prepared(PrivacyComparisonCase.WARMUP, protection)
            } else {
                session(flowOf(ProviderEvent.TextDelta("accepted prefix"), ProviderEvent.Failed("private-error-canary")))
            }
        }).run("partial")
        assertEquals(3, opens)
        val leg = result.legs.single()
        assertEquals(PrivacyComparisonLegStatus.PARTIAL, leg.status)
        assertEquals("accepted prefix", leg.answer)
        assertNull(leg.completionMs)
        assertTrue(leg.elapsedMs != null)
        assertTrue(leg.criteria.isEmpty())
        assertFalse(leg.taskCriteriaPassed)
        assertFalse(result.toString().contains("private-error-canary"))
    }

    @Test
    fun missingCompletionCannotPassEvenWithAnOtherwiseCorrectAnswer() = runTest {
        val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            val case = PrivacyComparisonCase.fromId(id)!!
            if (case == PrivacyComparisonCase.WARMUP) {
                prepared(case, protection)
            } else {
                session(flowOf(ProviderEvent.TextDelta(answer(case))))
            }
        }).run("incomplete")
        val leg = result.legs.single()
        assertEquals(PrivacyComparisonFailure.INCOMPLETE_STREAM, leg.failure)
        assertTrue(leg.criteria.isEmpty())
        assertNull(leg.completionMs)
        assertFalse(leg.taskCriteriaPassed)
        assertEquals("INCOMPLETE", result.pairStatus(PrivacyComparisonCase.TRAVEL))
    }

    @Test
    fun outputLimitFinishNeverPassesEvenWithCorrectJsonAndCompleted() = runTest {
        for (finish in listOf("length", "MAX_TOKENS", "output_limit")) {
            var terminalUnwound = false
            var opens = 0
            val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
                opens++
                val case = PrivacyComparisonCase.fromId(id)!!
                if (case == PrivacyComparisonCase.WARMUP) {
                    prepared(case, protection)
                } else {
                    session(
                        flow {
                            emit(ProviderEvent.TextDelta(answer(case)))
                            emit(ProviderEvent.Usage(inputTokens = 12, outputTokens = 512, finishReason = finish))
                            emit(ProviderEvent.Completed)
                            terminalUnwound = true
                        }
                    )
                }
            }).run("truncated")
            assertTrue(terminalUnwound)
            assertEquals(3, opens)
            val leg = result.legs.single()
            assertEquals(PrivacyComparisonFailure.OUTPUT_LIMIT, leg.failure)
            assertEquals(512, leg.outputTokens)
            assertTrue(leg.criteria.isEmpty())
            assertNull(leg.completionMs)
            assertFalse(leg.taskCriteriaPassed)
        }
    }

    @Test
    fun finalUsageKeepsReportedCumulativeTotalsWhenLaterFieldsAreAbsentOrEstimated() = runTest {
        val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            val case = PrivacyComparisonCase.fromId(id)!!
            session(
                flowOf(
                    ProviderEvent.Usage(inputTokens = 999, outputTokens = 999, phase = ProviderUsagePhase.INTERIM),
                    ProviderEvent.Usage(inputTokens = 999, outputTokens = 999, cumulative = false),
                    ProviderEvent.Usage(inputTokens = 12, outputTokens = 7),
                    ProviderEvent.Usage(finishReason = "stop"),
                    ProviderEvent.Usage(inputTokens = 2, outputTokens = -1),
                    ProviderEvent.Usage(inputTokens = 900, outputTokens = 900, inputSource = "estimated", outputSource = "estimated"),
                    ProviderEvent.TextDelta(answer(case)),
                    ProviderEvent.Completed
                ),
                if (protection) policy else null
            )
        }).run("usage")
        assertEquals(PrivacyComparisonStatus.COMPLETE, result.status)
        assertTrue(result.legs.all { it.inputTokens == 12 && it.outputTokens == 7 })
    }

    @Test
    fun providerTerminalEventsReturnNormallyInsteadOfCancellingTheirProducer() = runTest {
        for (success in listOf(true, false)) {
            var returned = 0
            val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
                val case = PrivacyComparisonCase.fromId(id)!!
                if (case == PrivacyComparisonCase.WARMUP) {
                    prepared(case, protection)
                } else {
                    session(
                        flow {
                            emit(ProviderEvent.TextDelta(answer(case)))
                            emit(if (success) ProviderEvent.Completed else ProviderEvent.Failed("test failure"))
                            returned++
                        },
                        if (protection) policy else null
                    )
                }
            }).run("terminal")
            assertEquals(if (success) 8 else 1, returned)
            assertEquals(if (success) PrivacyComparisonStatus.COMPLETE else PrivacyComparisonStatus.INCOMPLETE, result.status)
        }
    }

    @Test
    fun requestDeadlineUnwindsProducerAndRetainsUnscoredPartialOutput() = runTest {
        var cleaned = false
        val result = PrivacyComparisonRunner(
            openSession = { id, protection, _, budget ->
                assertEquals(50L, budget)
                if (id == "warmup") {
                    prepared(PrivacyComparisonCase.WARMUP, protection)
                } else {
                    session(
                        flow {
                            try {
                                emit(ProviderEvent.TextDelta("partial before deadline"))
                                awaitCancellation()
                            } finally {
                                cleaned = true
                            }
                        }
                    )
                }
            },
            nowMillis = { testScheduler.currentTime },
            requestTimeoutMs = 50
        ).run("deadline")
        assertTrue(cleaned)
        assertEquals(PrivacyComparisonFailure.TIMEOUT, result.failure)
        assertEquals(PrivacyComparisonLegStatus.TIMED_OUT, result.legs.single().status)
        assertEquals(50L, result.legs.single().elapsedMs)
        assertNull(result.legs.single().completionMs)
        assertEquals("partial before deadline", result.legs.single().answer)
    }

    @Test
    fun cancellationRetainsCompletedAndActivePartialLegsAndStopsFutureRequests() = runTest {
        var latest: PrivacyComparisonResult? = null
        val reached = CompletableDeferred<Unit>()
        var opens = 0
        var cleaned = false
        val runner = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            opens++
            val case = PrivacyComparisonCase.fromId(id)!!
            if (case == PrivacyComparisonCase.TRAVEL && protection) {
                session(
                    flow {
                        try {
                            emit(ProviderEvent.TextDelta("partial protected answer"))
                            reached.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cleaned = true
                        }
                    },
                    policy
                )
            } else {
                prepared(case, protection)
            }
        })
        val job = launch { runner.run("cancel") { latest = it } }
        reached.await()
        job.cancelAndJoin()
        val result = requireNotNull(latest)
        assertTrue(cleaned)
        assertEquals(4, opens)
        assertEquals(PrivacyComparisonStatus.CANCELLED, result.status)
        assertEquals(2, result.legs.size)
        assertTrue(result.legs.first().taskCriteriaPassed)
        assertEquals(PrivacyComparisonLegStatus.CANCELLED, result.legs.last().status)
        assertEquals("partial protected answer", result.legs.last().answer)
        assertTrue(result.legs.last().criteria.isEmpty())
    }

    @Test
    fun overallDeadlineDoesNotBecomeACompletedComparison() = runTest {
        var cleaned = false
        val result = PrivacyComparisonRunner(
            openSession = { id, protection, _, _ ->
                if (id == "warmup") {
                    prepared(PrivacyComparisonCase.WARMUP, protection)
                } else {
                    session(
                        flow {
                            try {
                                emit(ProviderEvent.TextDelta("partial"))
                                awaitCancellation()
                            } finally {
                                cleaned = true
                            }
                        }
                    )
                }
            },
            nowMillis = { testScheduler.currentTime },
            requestTimeoutMs = 100,
            overallTimeoutMs = 50
        ).run("total-budget")
        assertTrue(cleaned)
        assertEquals(PrivacyComparisonStatus.INCOMPLETE, result.status)
        assertEquals(PrivacyComparisonFailure.OVERALL_TIMEOUT, result.failure)
        assertEquals(PrivacyComparisonLegStatus.CANCELLED, result.legs.single().status)
        assertFalse(result.legs.single().taskCriteriaPassed)
    }

    @Test
    fun outputBoundStopsTheProducerBeforeAnyFurtherRequest() = runTest {
        var opens = 0
        var cleaned = false
        val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            opens++
            if (id == "warmup") {
                prepared(PrivacyComparisonCase.WARMUP, protection)
            } else {
                session(
                    flow {
                        try {
                            emit(ProviderEvent.TextDelta("x".repeat(PrivacyComparisonRunner.MAX_ANSWER_CHARACTERS + 1)))
                            emit(ProviderEvent.Completed)
                        } finally {
                            cleaned = true
                        }
                    }
                )
            }
        }).run("output-bound")
        assertTrue(cleaned)
        assertEquals(3, opens)
        assertEquals(PrivacyComparisonRunner.MAX_ANSWER_CHARACTERS, result.legs.single().answer.length)
        assertEquals(PrivacyComparisonFailure.OUTPUT_LIMIT, result.failure)
        assertFalse(result.legs.single().taskCriteriaPassed)
    }

    @Test
    fun changedPolicyBlocksTheNextPrivateStreamBeforeCollection() = runTest {
        var collections = 0
        val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
            val case = PrivacyComparisonCase.fromId(id)!!
            session(
                flow {
                    collections++
                    emit(ProviderEvent.TextDelta(answer(case)))
                    emit(ProviderEvent.Completed)
                },
                if (protection) policy.copy(revision = if (id == "warmup") 7 else 8) else null
            )
        }).run("policy")
        assertEquals(3, collections)
        assertEquals(PrivacyComparisonFailure.POLICY_CHANGED, result.failure)
        assertEquals(PrivacyComparisonLegStatus.BLOCKED, result.legs.last().status)
        assertTrue(result.legs.first().taskCriteriaPassed)
    }

    @Test
    fun capturedPermissionRevisionCannotChangeBetweenPairedRequests() = runTest {
        for (changed in listOf("changed-authority", null)) {
            var collected = 0
            val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
                val case = PrivacyComparisonCase.fromId(id)!!
                session(
                    flow {
                        collected++
                        emit(ProviderEvent.TextDelta(answer(case)))
                        emit(ProviderEvent.Completed)
                    },
                    if (protection) policy else null
                ).copy(authorityRevision = if (case == PrivacyComparisonCase.WARMUP) "initial-authority" else changed)
            }).run("permission-change")
            assertEquals(2, collected)
            assertEquals(PrivacyComparisonStatus.INCOMPLETE, result.status)
            assertEquals(PrivacyComparisonFailure.POLICY_CHANGED, result.failure)
            assertEquals(PrivacyComparisonLegStatus.BLOCKED, result.legs.single().status)
            assertTrue(result.legs.single().criteria.isEmpty())
        }
    }

    @Test
    fun toolsAndOversizedProviderCeilingsAreRejectedWithoutExecutionOrQualityPasses() = runTest {
        for (event in listOf(
            ProviderEvent.ToolCall("unexpected", "some_device_tool", buildJsonObject {}),
            ProviderEvent.RequestConfigured(effectiveOutputTokens = 2048)
        )) {
            var opens = 0
            val result = PrivacyComparisonRunner(openSession = { id, protection, _, _ ->
                opens++
                if (id == "warmup") {
                    prepared(PrivacyComparisonCase.WARMUP, protection)
                } else {
                    session(flowOf(event, ProviderEvent.TextDelta(answer(PrivacyComparisonCase.TRAVEL)), ProviderEvent.Completed))
                }
            }).run("no-tools")
            assertEquals(3, opens)
            assertTrue(result.legs.single().criteria.isEmpty())
            assertFalse(result.legs.single().taskCriteriaPassed)
            assertEquals(PrivacyComparisonStatus.INCOMPLETE, result.status)
        }
    }

    @Test
    fun observedBackendDriftAndMissingObservationsStayDistinct() {
        val base = PrivacyComparisonLeg("travel", PrivacyComparisonArm.OFF, PrivacyComparisonLegStatus.COMPLETE, "{}", actualRuntime = "runtime_a", effectiveOutputTokens = 512)
        val second = base.copy(arm = PrivacyComparisonArm.ON, actualRuntime = "runtime_b")
        val result = PrivacyComparisonResult("drift", legs = listOf(base, second))
        assertEquals("OBSERVED_CONFIGURATION_DIFFERS", result.pairStatus(PrivacyComparisonCase.TRAVEL))
        assertEquals("OBSERVED_CONFIGURATION_MATCHES", result.copy(legs = listOf(base, second.copy(actualRuntime = "runtime_a"))).pairStatus(PrivacyComparisonCase.TRAVEL))
        assertEquals("REQUESTED_CONFIGURATION_MATCHES_OBSERVATION_INCOMPLETE", result.copy(legs = listOf(base, second.copy(actualRuntime = null))).pairStatus(PrivacyComparisonCase.TRAVEL))
        assertEquals("OBSERVED_CONFIGURATION_DIFFERS", result.copy(legs = listOf(base, second.copy(actualRuntime = "runtime_a", configurationChanged = true))).pairStatus(PrivacyComparisonCase.TRAVEL))
        assertEquals("CONFIGURATION_NOT_COMPARABLE", result.copy(legs = listOf(base, second.copy(actualRuntime = "runtime_a", measurements = PrivacyComparisonMeasurements(comparisonLimitation = "STRICT_ROUTE_SETTINGS_UNVERIFIED")))).pairStatus(PrivacyComparisonCase.TRAVEL))
    }

    @Test
    fun nearestRankTimingSummariesExcludeWarmupsAndDoNotFilterTaskFailures() {
        val legs = PrivacyComparisonCase.scored.flatMapIndexed { index, case ->
            val value = (index + 1) * 100L
            timingPair(case, value, value + 50)
        } + timingPair(PrivacyComparisonCase.WARMUP, 99_999, 99_999)
        val summary = PrivacyComparisonResult("percentiles", legs = legs).timingSummaries().single()
        assertEquals(4, summary.pairedCases)
        assertEquals(PrivacyComparisonPercentiles(4, 200, 400), summary.arms[0].firstTextMs)
        assertEquals(PrivacyComparisonPercentiles(4, 250, 450), summary.arms[1].firstTextMs)
        assertEquals(PrivacyComparisonPercentiles(4, 1200, 1400), summary.arms[0].completionMs)
        assertEquals(PrivacyComparisonPercentiles(0, null, null), summary.arms[0].maskingNanos)
        assertEquals(PrivacyComparisonPercentiles(4, 250_000, 450_000), summary.arms[1].maskingNanos)
        assertTrue(legs.all { !it.taskCriteriaPassed })
    }

    @Test
    fun timingsExcludeMissingIncompleteDriftingAndStrictRoutePairs() {
        val legs = PrivacyComparisonCase.scored.flatMapIndexed { index, case ->
            val pair = timingPair(case, 100, 200)
            listOf(
                pair.first(),
                when (index) {
                    0 -> pair.last().copy(status = PrivacyComparisonLegStatus.PARTIAL)
                    1 -> pair.last().copy(actualRuntime = null)
                    2 -> pair.last().copy(configurationChanged = true)
                    else -> pair.last().copy(measurements = pair.last().measurements.copy(comparisonLimitation = "STRICT_ROUTE_SETTINGS_UNVERIFIED"))
                }
            )
        }
        assertTrue(PrivacyComparisonResult("excluded", legs = legs).timingSummaries().isEmpty())
    }

    @Test
    fun timingGroupsKeepDifferentRuntimesSeparateAndPairEachMetricPopulation() {
        val legs = timingPair(PrivacyComparisonCase.TRAVEL, 100, 200) +
            timingPair(PrivacyComparisonCase.EMAIL, 300, 400).map { if (it.arm == PrivacyComparisonArm.ON) it.copy(firstTextMs = null) else it } +
            timingPair(PrivacyComparisonCase.STUDENT, 500, 600).map { it.copy(actualRuntime = "other_runtime") }
        val groups = PrivacyComparisonResult("groups", legs = legs).timingSummaries()
        assertEquals(2, groups.size)
        val first = groups.single { it.runtime == "selected_runtime" }
        assertEquals(2, first.pairedCases)
        assertTrue(first.arms.all { it.firstTextMs.sampleCount == 1 && it.completionMs.sampleCount == 2 })
        assertEquals(100L, first.arms[0].firstTextMs.p95)
        assertEquals(200L, first.arms[1].firstTextMs.p95)
        assertEquals(1, groups.single { it.runtime == "other_runtime" }.pairedCases)
    }

    @Test
    fun detectionUsesActualBoundReceiptAndCountsMissesSeparatelyFromPublicPhraseLoss() {
        val case = PrivacyComparisonCase.TRAVEL
        val name = case.prompt.indexOf("Mira Voss")
        val public = case.prompt.indexOf(case.publicPhrases.first())
        val receipt = PrivacyProtectionReceipt.create(case.prompt, listOf(PrivacyProtectedRange(name, name + "Mira Voss".length), PrivacyProtectedRange(public, public + case.publicPhrases.first().length)))
        val scored = requireNotNull(PrivacyComparisonRunner.detect(case, policy, receipt))
        assertEquals(3, scored.expectedValues)
        assertEquals(1, scored.protectedValues)
        assertEquals(2, scored.missedValues)
        assertEquals(1, scored.alteredPublicPhrases)
        assertEquals(1, PrivacyComparisonRunner.detect(case, policy.copy(categories = setOf(PrivacyCategory.PRIVATE_DATES)), receipt)?.expectedValues)
        assertNull(PrivacyComparisonRunner.detect(case, policy, receipt.copy(contentDigest = "wrong-content")))
        assertNull(PrivacyComparisonRunner.detect(case, policy, null))
    }

    @Test
    fun taskRubricRejectsMissingValuesDroppedObligationsAndRepeatedFamilyCircumstances() {
        assertFalse(PrivacyComparisonCase.TRAVEL.assess(answer(PrivacyComparisonCase.TRAVEL).replace("Mira Voss", "unknown")).all { it.passed })
        assertFalse(PrivacyComparisonCase.CONTRACT.assess(answer(PrivacyComparisonCase.CONTRACT).replace("Review the draft", "Omitted")).all { it.passed })
        assertFalse(PrivacyComparisonCase.STUDENT.assess(answer(PrivacyComparisonCase.STUDENT).replace("I understand", "Your anxiety means")).all { it.passed })
        assertFalse(PrivacyComparisonCase.EMAIL.assess("Please supply the protected email address.").all { it.passed })
        assertFalse(PrivacyComparisonCase.TRAVEL.assess(answer(PrivacyComparisonCase.TRAVEL).replace("Mira Voss", "[PRIVATE_unknown]")).all { it.passed })
    }

    @Test
    fun deterministicProfileDoesNotMutateOrSendTheSavedSystemPrompt() {
        val saved = PlatformV2(name = "Test profile", compatibleType = ClientType.OPENAI, model = "fixed-model", systemPrompt = "SAVED_PRIVATE_SYSTEM_CANARY", temperature = 1f, maxTokens = 16000, reasoning = true, batchMode = true)
        val prepared = privacyComparisonProfile(saved)
        assertEquals("SAVED_PRIVATE_SYSTEM_CANARY", saved.systemPrompt)
        assertEquals(16000, saved.maxTokens)
        assertEquals("fixed-model", prepared.model)
        assertEquals(PRIVACY_COMPARISON_SYSTEM_PROMPT, prepared.systemPrompt)
        assertEquals(512, prepared.maxTokens)
        assertEquals(0f, prepared.temperature)
        assertFalse(prepared.reasoning)
        assertFalse(prepared.batchMode)
        assertTrue(prepared.disableAllTools && prepared.disableRemoteTools && prepared.disableLocalTools)
    }

    @Test
    fun ollamaFixtureKeepsNumericResourcesAndExcludesEverySavedTextOption() {
        val raw = """{"stop":["PRIVATE_STOP_CANARY"],"keep_alive":"PRIVATE_KEEP_ALIVE_CANARY","kv_cache_type":"PRIVATE_CACHE_CANARY","unknown":"PRIVATE_UNKNOWN_CANARY","num_ctx":8192,"num_gpu":0,"temperature":1.0,"auto_continue":true}"""
        val saved = PlatformV2(name = "Ollama fixture", compatibleType = ClientType.OLLAMA, model = "fixture", ollamaOptions = raw)
        val prepared = privacyComparisonProfile(saved)
        val encoded = requireNotNull(prepared.ollamaOptions)
        val options = Json.decodeFromString<OllamaOptions>(encoded)
        assertEquals(raw, saved.ollamaOptions)
        assertFalse(encoded.contains("PRIVATE_"))
        assertEquals(emptyList<String>(), options.stop)
        assertEquals(OllamaOptions.DEFAULT_KEEP_ALIVE, options.keepAlive)
        assertEquals(OllamaOptions.DEFAULT_KV_CACHE_TYPE, options.kvCacheType)
        assertEquals(8192, options.numCtx)
        assertEquals(0, options.numGpu)
        assertEquals(0f, options.temperature)
        assertEquals(false, options.autoContinue)
    }

    private fun timingPair(case: PrivacyComparisonCase, off: Long, on: Long): List<PrivacyComparisonLeg> = PrivacyComparisonArm.entries.map { arm ->
        val value = if (arm == PrivacyComparisonArm.OFF) off else on
        PrivacyComparisonLeg(
            case.id, arm, PrivacyComparisonLegStatus.COMPLETE, "{}",
            criteria = listOf(PrivacyComparisonCriterion("unmet", "Example unmet task criterion", false)),
            firstTextMs = value, completionMs = value + 1000, actualRuntime = "selected_runtime", effectiveOutputTokens = 512,
            measurements = if (arm == PrivacyComparisonArm.OFF) {
                PrivacyComparisonMeasurements(routeKind = "ORDINARY_TEXT")
            } else {
                PrivacyComparisonMeasurements(routeKind = "MASKED_TEXT", maskingNanos = value * 1000, restorationNanos = value * 2000)
            }
        )
    }

    private fun prepared(case: PrivacyComparisonCase, protection: Boolean): PrivacyComparisonSession =
        session(flowOf(ProviderEvent.TextDelta(answer(case)), ProviderEvent.Completed), if (protection) policy else null)

    private fun session(events: Flow<ProviderEvent>, state: PrivacyModeState? = null) = PrivacyComparisonSession(
        session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
                check(tools.isEmpty() && exchanges.isEmpty())
                return events
            }
        },
        policy = state
    )

    private fun answer(case: PrivacyComparisonCase): String = when (case) {
        PrivacyComparisonCase.WARMUP -> "READY"
        PrivacyComparisonCase.TRAVEL -> """{"traveler":"Mira Voss","companion":"Elian Reed","departure":"February 4th","plan":["Visit a local park","Enjoy a quiet museum","Take a relaxed walking tour"]}"""
        PrivacyComparisonCase.EMAIL -> """{"recipient":"Dana Hale","email":"dana@cedarlab.example","reply":"I will get the license fixed and complete the project as requested.","commitments":["license fixed","project completed"]}"""
        PrivacyComparisonCase.STUDENT -> """{"student":"Noor Vale","decision":"extension granted","reply":"I understand and can grant the extension for your academic work."}"""
        PrivacyComparisonCase.CONTRACT -> """{"client":"Mira Voss","contractor":"Elian Reed","fee":"1 234,50 EUR","services":["Write a project brief","Review the draft","Deliver a final report"],"summary":"The contractor will write the brief, review the draft and deliver the final report."}"""
    }
}
