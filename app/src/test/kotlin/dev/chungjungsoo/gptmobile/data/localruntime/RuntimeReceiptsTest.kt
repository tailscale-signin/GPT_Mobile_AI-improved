package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReceiptsTest {
    @Test fun workerLoadDeadlineSurvivesTransportWithoutBecomingAGenerationOrNativeSettingClaim() {
        val load = EngineLoadReceipt(
            receiptId = "load",
            artifactDigest = "artifact",
            runtimeRevision = "runtime",
            requestedBackend = "gpu",
            actualBackend = null,
            engineInstanceId = "app-engine",
            startedAtElapsedMs = 10,
            completedAtElapsedMs = 20,
            outcome = "initialized",
            loadDeadlinePolicy = GgufLoadDeadline.policy
        )
        val packet = dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire("RESULT", "load-request", state = LocalRuntimeState(engineLoadReceipt = load))
        val restored = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire>(Json.encodeToString(packet)).state!!.engineLoadReceipt!!
        assertEquals(GgufLoadDeadline.policy, restored.loadDeadlinePolicy)
        assertEquals(GgufLoadDeadline.budgetMs("OPENCL_CREATE_CONTEXT_BEGIN"), restored.loadDeadlinePolicy!!.stageBudgetsMs["BACKEND_INITIALIZATION"])
        assertNull(restored.actualBackend)
        assertTrue(restored.nativeAcknowledgedFields.isEmpty())
        assertNull(packet.executionReceipt?.configuration?.deadlinePolicy)
    }

    @Test fun nativeEntryIsKnownDispatchWithoutVisibleTextAndLimitIsATerminal() {
        val initial = RuntimeExecutionReceipt(runtimeRequestId = "request", physicalAttemptId = "attempt")
        assertFalse(initial.knownDispatched)
        val entered = initial.fromProgress(GgufNativeProgress(7, "NATIVE_ENTRY", observedAtMs = 100))
        assertTrue(entered.knownDispatched)
        assertNull(entered.firstVisibleTextAtElapsedMs)
        val returned = entered.fromProgress(GgufNativeProgress(7, "NATIVE_RETURN", 29, 29, 16, nativeCode = 1, observedAtMs = 200))
        assertTrue(returned.hasNativeTerminal)
        assertEquals("output_limit", returned.terminalKind)
        assertNull(returned.commandTerminalAcknowledged)
    }

    @Test fun terminalBookkeepingNeverErasesKnownCountersOrMixesOperations() {
        val observed = GgufNativeProgress(7, "PREFILL_BATCH_END", 29, 24, 0, observedAtMs = 100)
        val terminal = GgufNativeProgress(7, "TIMEOUT_RECEIPT", deadlineKind = "GGUF_OPERATION_DEADLINE").retainingMeasurements(observed)
        assertEquals(29L, terminal.promptTokens)
        assertEquals(24L, terminal.processedTokens)
        assertEquals(0L, terminal.sampledTokens)
        assertEquals(-1L, GgufNativeProgress(8, "NATIVE_ENTRY").retainingMeasurements(observed).promptTokens)
    }

    @Test fun terminationIntentAndEmptyCleanupCannotAuthorizeSuccessor() {
        assertFalse(RuntimeCleanupReceipt("test").safeForSuccessor)
        assertFalse(RuntimeCleanupReceipt("test", notRequiredReason = "unknown").safeForSuccessor)
        val pending =
            RuntimeCleanupReceipt(
                "test",
                obligationsObserved = true,
                requiredActions = setOf("cancel", "close_conversation"),
                cancellationRequested = true,
                terminationRequested = true
            )
        assertFalse(pending.safeForSuccessor)
        assertFalse(pending.copy(workerExitConfirmed = false).safeForSuccessor)
        assertTrue(pending.copy(workerExitConfirmed = true, workerExitEvidence = "binder_death").safeForSuccessor)
        assertTrue(pending.copy(nativeClosureConfirmed = true, conversationClosed = true, engineHealthy = true).safeForSuccessor)
    }

    @Test fun holderDoesNotTreatItsOwnLeaseAsNativeWorkAndKeepsUnknownCleanupQuarantined() =
        runTest {
            val base = FakeLocalRuntime()
            var busy = false
            var receipt =
                RuntimeCleanupReceipt(
                    "test",
                    workerGenerationId = "worker-a",
                    obligationsObserved = true,
                    quarantined = true,
                    failureReason = "No closure acknowledgement",
                    observedAtElapsedMs = 1000
                )
            val runtime =
                object : LocalRuntime by base {
                    override val isNativeOperationBusy get() = busy
                    override val latestCleanupReceipt get() = receipt

                    override suspend fun cleanupAfterRequest(origin: String) = receipt
                }
            val holder = LocalEngineHolder(runtime) { 1000 }
            holder.runExclusive {
                assertFalse(isNativeOperationBusy)
                assertFalse(cleanupAfterRequest("case").safeForSuccessor)
            }
            busy = false
            val spec = LocalEngineSpec("model", "cpu", 1024)
            assertTrue(runCatching { holder.loadEngine(spec) }.isFailure)
            assertTrue(holder.isNativeOperationBusy)
            assertTrue(base.loadEngineCalls.isEmpty())
            receipt =
                receipt.copy(
                    quarantined = false,
                    failureReason = null,
                    workerGenerationId = "unrelated",
                    workerExitConfirmed = true,
                    observedAtElapsedMs = 1001
                )
            assertTrue(runCatching { holder.loadEngine(spec) }.isFailure)
            receipt = receipt.copy(workerGenerationId = "worker-a", observedAtElapsedMs = 1002)
            assertFalse(holder.isNativeOperationBusy)
            holder.loadEngine(spec)
            assertEquals(listOf(spec), base.loadEngineCalls)
        }

    @Test fun budgetIsForwardedForEveryPhysicalGeneration() =
        runTest {
            val reserves = mutableListOf<Int>()
            val runtime =
                object : LocalRuntime by FakeLocalRuntime() {
                    override fun sendMessageWithBudget(
                        text: String,
                        images: List<ByteArray>,
                        safetyTokens: Int
                    ) = flowOf(LocalRuntimeEvent.Done).also {
                        reserves +=
                            safetyTokens
                    }
                }
            val holder = LocalEngineHolder(runtime) { 0 }
            holder.sendMessageWithBudget("initial", safetyTokens = 120).toList()
            holder.sendMessageWithBudget("tool evidence materialized", safetyTokens = 32).toList()
            assertEquals(listOf(120, 32), reserves)
        }
}
