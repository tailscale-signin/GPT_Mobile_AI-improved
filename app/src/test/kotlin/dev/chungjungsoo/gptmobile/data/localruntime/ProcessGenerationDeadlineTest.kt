package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessGenerationDeadlineTest {
    private val sdk = RuntimeProcessDeadlinePolicy.forGeneration(LocalRuntimeBackend.LITERT_LM, 2)
    private val gguf = RuntimeProcessDeadlinePolicy.forGeneration(LocalRuntimeBackend.LLAMA_CPP, 2)

    private fun progress(processed: Long, sampled: Long = 0) =
        LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(1, "PREFILL_BATCH_END", 8000, processed, sampled))

    @Test fun firstOutputDoesNotDisableDecodeStallSupervision() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        assertTrue(deadline.observe(LocalRuntimeEvent.TextDelta("hello"), 1000))
        assertNull(deadline.expiration(45_999))
        assertEquals("LOCAL_PROCESS_DECODE_STALL", deadline.expiration(46_000))
        assertFalse(deadline.observe(LocalRuntimeEvent.TextDelta("too late"), 47_000))
        assertEquals("LOCAL_PROCESS_DECODE_STALL", deadline.expiration(47_000))
    }

    @Test fun thinkingIsWorkButEmptyTextAndHeartbeatAreNotWork() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        assertTrue(deadline.observe(LocalRuntimeEvent.ThinkingDelta("reasoning"), 1000))
        assertFalse(deadline.observe(LocalRuntimeEvent.TextDelta(""), 40_000))
        assertFalse(deadline.observe(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.GENERATING), 44_000))
        assertEquals("LOCAL_PROCESS_DECODE_STALL", deadline.expiration(46_000))
    }

    @Test fun repeatedNativeCountersCannotRenewIdleTime() {
        val deadline = ProcessGenerationDeadline(0, gguf)
        deadline.observe(progress(10), 1000)
        deadline.observe(progress(10), 329_000)
        assertNull(deadline.expiration(330_999))
        assertEquals("LOCAL_PROCESS_PREFILL_DEADLINE", deadline.expiration(331_000))
    }

    @Test fun advancingGgufPrefillCountersOutliveShortPromptTimeButNotAbsoluteTime() {
        val deadline = ProcessGenerationDeadline(0, gguf)
        deadline.observe(progress(10), 329_000)
        assertNull(deadline.expiration(400_000))
        deadline.observe(progress(20), 600_000)
        assertNull(deadline.expiration(629_999))
        assertEquals("LOCAL_PROCESS_OPERATION_DEADLINE", deadline.expiration(630_000))
    }

    @Test fun productiveSdkDecodeStillHasAnAbsoluteLimit() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        for (time in 1000L until 600_000L step 30_000L) deadline.observe(LocalRuntimeEvent.TextDelta("x"), time)
        assertEquals("LOCAL_PROCESS_OPERATION_DEADLINE", deadline.expiration(600_000))
    }

    @Test fun toolWaitPausesIdleAndOnlyItsDeliveredResultStartsFreshPrefill() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        deadline.observe(progress(10, 1), 1000)
        deadline.toolStarted("tool-1", 2000)
        assertNull(deadline.expiration(100_000))
        deadline.toolFinished("tool-1", 102_000)
        deadline.toolResponseDelivered("tool-1", 102_000)
        // Prefill work after a tool does not become decode merely because the cumulative
        // sample count still includes the earlier assistant tool-call token.
        deadline.observe(progress(20, 1), 103_000)
        deadline.toolResponseDelivered("unknown", 160_000)
        deadline.toolResponseDelivered("tool-1", 170_000)
        assertNull(deadline.expiration(176_999))
        assertEquals("LOCAL_PROCESS_PREFILL_DEADLINE", deadline.expiration(177_000))
    }

    @Test fun toolCallbackAndWholeCommandRemainBoundedDuringPausedIdle() {
        val callback = ProcessGenerationDeadline(0, sdk)
        callback.toolStarted("tool", 1000)
        assertEquals("LOCAL_PROCESS_TOOL_CALLBACK_DEADLINE", callback.expiration(121_000))
        val absolute = ProcessGenerationDeadline(0, sdk)
        for (time in 1000L until 590_000L step 30_000L) absolute.observe(LocalRuntimeEvent.TextDelta("x"), time)
        absolute.toolStarted("tool", 590_000)
        assertEquals("LOCAL_PROCESS_OPERATION_DEADLINE", absolute.expiration(600_000))
    }

    @Test fun overlappingToolsPauseTheUnionOfTheirWaitIntervals() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        deadline.observe(LocalRuntimeEvent.TextDelta("x"), 1000)
        deadline.toolStarted("one", 2000)
        deadline.toolStarted("two", 3000)
        deadline.toolFinished("one", 8000)
        deadline.toolFinished("two", 10_000)
        assertNull(deadline.expiration(53_999))
        assertEquals("LOCAL_PROCESS_DECODE_STALL", deadline.expiration(54_000))
    }

    @Test fun clockRollbackDoesNotExtendTheDeadline() {
        val deadline = ProcessGenerationDeadline(0, sdk)
        deadline.observe(LocalRuntimeEvent.TextDelta("x"), 1000)
        assertEquals(35_000L, deadline.remainingMs(11_000))
        assertEquals(35_000L, deadline.remainingMs(100))
        assertEquals("LOCAL_PROCESS_DECODE_STALL", deadline.expiration(46_000))
    }

    @Test fun backendSpecificPoliciesPreserveWorkerGraceAndEstimateLongSeededPrompts() {
        assertEquals(75_000L, sdk.prefillMs)
        assertEquals(300_000L, RuntimeProcessDeadlinePolicy.forGeneration(LocalRuntimeBackend.QUALCOMM_QNN, 30_000).prefillMs)
        assertEquals(330_000L, gguf.prefillMs)
        assertEquals(600_000L, sdk.absoluteMs)
        assertEquals(630_000L, gguf.absoluteMs)
        assertEquals(45_000L, sdk.decodeStallMs)
        assertEquals(75_000L, gguf.decodeStallMs)
        assertEquals(180_000L, GgufLoadDeadline.policy.absoluteMs)
        assertEquals(210_000L, RuntimeProcessDeadlinePolicy.loadCommandMs)
        assertNull(GgufLoadDeadline.policy.supervisorAbsoluteMs)
    }

    @Test fun firstVisibleTextDoesNotIncludeThinkingOrClaimNativeEntry() {
        val receipt = RuntimeExecutionReceipt(runtimeRequestId = "request")
        assertSame(receipt, receipt.observingVisibleOutput("reasoning", true, 1000))
        assertSame(receipt, receipt.observingVisibleOutput("", false, 2000))
        val visible = receipt.observingVisibleOutput("answer", false, 3000)
        assertEquals(3000L, visible.firstVisibleTextAtElapsedMs)
        assertNull(visible.nativeEntered)
        assertNull(visible.nativeEnteredAtElapsedMs)
        assertSame(visible, visible.observingVisibleOutput("more", false, 4000))
    }

    @Test fun schemaCompatibilityBindingPreventsAutomaticBackendChanges() {
        val config = LocalConversationConfig(LocalSamplerConfig(1, 1f, 0f), null, emptyList())
        assertTrue(config.allowsAutomaticBackendRecovery())
        assertFalse(config.copy(allowAutomaticRecovery = false).allowsAutomaticBackendRecovery())
        assertFalse(config.copy(finalResponseSchemaJson = "{\"type\":\"object\"}").allowsAutomaticBackendRecovery())
    }

    @Test fun ipcSerializationRetainsDistinctAppliedDeadlinePoliciesAndUnknownNativeFacts() {
        val load = EngineLoadReceipt(
            receiptId = "load", artifactDigest = "artifact", runtimeRevision = "runtime", requestedBackend = "cpu", actualBackend = null,
            engineInstanceId = "app-instance", startedAtElapsedMs = 1, completedAtElapsedMs = 2, outcome = "loaded",
            loadDeadlinePolicy = GgufLoadDeadline.policy.copy(supervisorAbsoluteMs = RuntimeProcessDeadlinePolicy.loadCommandMs)
        )
        val nativePolicy = NativeGenerationDeadlinePolicy(600_000, 300_000, 45_000, 0.25, 30_000)
        val configuration = GenerationConfigReceipt(
            generationId = "generation",
            nativeOperationId = null,
            engineInstanceId = load.engineInstanceId,
            requestedSampler = null,
            requestedOutputTokens = 128,
            deadlinePolicy = nativePolicy,
            observedAtElapsedMs = 3
        )
        val receipt = RuntimeExecutionReceipt(
            runtimeRequestId = "request",
            physicalAttemptId = "attempt",
            workerGenerationId = "worker",
            processDeadlinePolicy = gguf,
            engineLoad = load,
            configuration = configuration
        )
        val wire = RuntimeWire("METRICS", "request", generation = "worker", sequence = 4, executionReceipt = receipt)
        val json = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }
        val encoded = json.encodeToString(wire)
        val decoded = json.decodeFromString<RuntimeWire>(encoded)
        assertEquals(receipt, decoded.executionReceipt)
        assertEquals(630_000L, decoded.executionReceipt?.processDeadlinePolicy?.absoluteMs)
        assertEquals(600_000L, decoded.executionReceipt?.configuration?.deadlinePolicy?.absoluteMs)
        assertEquals(180_000L, decoded.executionReceipt?.engineLoad?.loadDeadlinePolicy?.absoluteMs)
        assertEquals(210_000L, decoded.executionReceipt?.engineLoad?.loadDeadlinePolicy?.supervisorAbsoluteMs)
        assertNull(decoded.executionReceipt?.nativeEntered)
        assertNull(decoded.executionReceipt?.engineLoad?.actualBackend)
        val historical = json.decodeFromString<RuntimeWire>("{\"operation\":\"METRICS\",\"requestId\":\"old\",\"executionReceipt\":{\"runtimeRequestId\":\"old\"}}")
        assertNull(historical.executionReceipt?.processDeadlinePolicy)
    }
}
