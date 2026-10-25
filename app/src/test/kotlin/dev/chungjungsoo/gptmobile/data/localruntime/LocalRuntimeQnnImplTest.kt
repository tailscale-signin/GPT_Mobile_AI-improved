package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeQnnImplTest {
    private val spec = LocalEngineSpec("model.litertlm", "npu", 1280)
    private val ready = QnnEnvironment.QnnProbeStatus(
        true, "SM8750", "arm64-v8a", "/native", "/dispatch", "/dispatch", "/dispatch", emptyList(), true,
        "/dispatch/libQnnHtpV79Skel.so", true
    )
    private val noOpGuard = object : QnnLoadGuard {
        override fun beforeLoad(spec: LocalEngineSpec) = Unit
        override fun loadFinished() = Unit
    }

    @Test fun wrapperPreservesInspectionExecutionAndCleanupEvidenceWithoutInventingBackendFacts() = runTest {
        val executed = RuntimeExecutionReceipt(runtimeRequestId = "request", workerGenerationId = "worker", nativeEntered = false)
        val cleanup = RuntimeCleanupReceipt("test", quarantined = true)
        val identity = LocalRuntimeExecutionIdentity(workerGenerationId = "worker")
        val inspected = RuntimeInspectionReceipt("inspection", "runtime", "device", "firmware", "npu", "configuration", 10, 20, "PASSED")
        var inspectedSpec: LocalEngineSpec? = null
        val delegate = object : LocalRuntime by FakeLocalRuntime() {
            override val latestExecutionReceipt get() = executed
            override val latestCleanupReceipt get() = cleanup
            override val latestInspectionReceipt get() = inspected
            override val executionIdentity get() = identity
            override val isNativeOperationBusy get() = true
            override suspend fun inspectModel(spec: LocalEngineSpec): LocalModelCapabilities? {
                inspectedSpec = spec
                return null
            }
            override suspend fun cleanupAfterRequest(origin: String) = cleanup
        }
        val qnn = LocalRuntimeQnnImpl(delegate, noOpGuard, { ready }, enabled = true)
        qnn.inspectModel(spec)
        assertEquals(spec, inspectedSpec)
        org.junit.Assert.assertSame(inspected, qnn.latestInspectionReceipt)
        org.junit.Assert.assertSame(executed, qnn.latestExecutionReceipt)
        org.junit.Assert.assertSame(cleanup, qnn.cleanupAfterRequest("benchmark"))
        assertEquals(identity, qnn.executionIdentity)
        assertTrue(qnn.isNativeOperationBusy)
        assertFalse(qnn.latestCleanupReceipt!!.safeForSuccessor)
        assertEquals(null, qnn.latestEngineLoadReceipt)
    }

    @Test fun structuredValidationErrorsRetainTheirCategoryAndRawPartialText() = runTest {
        for (code in listOf(LocalStructuredOutputFailure.MALFORMED, LocalStructuredOutputFailure.SCHEMA_MISMATCH, LocalStructuredOutputFailure.VALIDATION_LIMIT)) {
            val original = LocalStructuredOutputFailure(code, "raw response retained")
            val native = FakeLocalRuntime().apply { scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("{broken"), LocalRuntimeEvent.Error(original.message.orEmpty(), original))) }
            val qnn = LocalRuntimeQnnImpl(native, noOpGuard, { ready }, enabled = true)
            qnn.loadEngine(spec)
            qnn.createConversation(LocalConversationConfig(LocalSamplerConfig(1, 1f, 0f), null, emptyList()))
            val events = qnn.sendMessage("JSON").toList()
            assertEquals("{broken", events.filterIsInstance<LocalRuntimeEvent.TextDelta>().single().text)
            org.junit.Assert.assertSame(original, events.filterIsInstance<LocalRuntimeEvent.Error>().single().cause)
        }
    }

    @Test fun nativeGrammarFailureUsesTheConstrainedDecodingCode() = runTest {
        val original = LocalStructuredOutputFailure(LocalStructuredOutputFailure.CONSTRAINED_DECODING_FAILURE, "failed to commit token: parser error: token does not satisfy the grammar")
        val native = FakeLocalRuntime().apply { scriptedEvents = listOf(listOf(LocalRuntimeEvent.Error(original.message.orEmpty(), original))) }
        val qnn = LocalRuntimeQnnImpl(native, noOpGuard, { ready }, enabled = true)
        qnn.loadEngine(spec)
        val failure = qnn.sendMessage("JSON").toList().filterIsInstance<LocalRuntimeEvent.Error>().single().cause as QnnRuntimeFailure
        assertEquals(QnnFailureCode.CONSTRAINED_DECODING_FAILURE, failure.code)
        org.junit.Assert.assertSame(original, failure.cause)
    }

    @Test fun selectedNpuWithTextAndDoneDoesNotClaimBackendAndCleanupQualification() = runTest {
        val native = FakeLocalRuntime().apply { scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("answer"), LocalRuntimeEvent.Done)) }
        val qnn = LocalRuntimeQnnImpl(native, noOpGuard, { ready }, enabled = true)
        qnn.loadEngine(spec)
        qnn.sendMessage("hello").toList()
        assertFalse(QnnExecutionEvidence.executionQualified)
        assertTrue(QnnExecutionEvidence.lastStatus.contains("completed=true"))
        assertTrue(QnnExecutionEvidence.lastStatus.contains("actualBackend=unknown"))
    }

    @Test fun configurationRejectionIsNotMisreportedAsNativeInitializationFailure() = runTest {
        val original = LocalModelConfigurationException("This export has no verified speculative-decoding support")
        val native = FakeLocalRuntime().apply { failLoadEngineIf = { original } }
        val qnn = LocalRuntimeQnnImpl(native, noOpGuard, { ready }, enabled = true)
        assertTrue(runCatching { qnn.loadEngine(spec) }.exceptionOrNull() === original)
    }

    @Test
    fun transformedDispatchSpecStillReusesTheWarmEngine() = runTest {
        val native = FakeLocalRuntime()
        val qnn = LocalRuntimeQnnImpl(
            runtime = native,
            enabled = true,
            loadGuard = noOpGuard,
            probeEnvironment = { ready }
        )
        val holder = LocalEngineHolder(qnn) { 1_000L }
        holder.loadEngine(spec)
        holder.loadEngine(spec)
        assertEquals(1, native.loadEngineCalls.size)
        assertEquals("/dispatch", native.loadEngineCalls.single().litertDispatchLibDir)
        assertTrue(holder.isEngineLoaded(spec))
        holder.unloadEngine()
        assertFalse(holder.isEngineLoaded(spec))
    }

    @Test
    fun missingPrerequisitesDoNotAttemptNativeInitialization() = runTest {
        val native = FakeLocalRuntime()
        val qnn = LocalRuntimeQnnImpl(
            runtime = native,
            enabled = true,
            loadGuard = noOpGuard,
            probeEnvironment = { ready.copy(isReady = false, errorMessage = "missing HTP") }
        )
        assertTrue(runCatching { qnn.loadEngine(spec) }.isFailure)
        assertTrue(native.loadEngineCalls.isEmpty())
    }

    @Test
    fun explicitGpuIsNeverSilentlyChangedToNpu() = runTest {
        val native = FakeLocalRuntime()
        val qnn = LocalRuntimeQnnImpl(
            runtime = native,
            enabled = true,
            loadGuard = noOpGuard,
            probeEnvironment = { ready }
        )
        assertTrue(runCatching { qnn.loadEngine(spec.copy(accelerator = "gpu")) }.isFailure)
        assertTrue(native.loadEngineCalls.isEmpty())
    }

    @Test
    fun conversationAndVisionPayloadAreForwardedToTheInitializedEngine() = runTest {
        val native = FakeLocalRuntime()
        val qnn = LocalRuntimeQnnImpl(
            runtime = native,
            enabled = true,
            loadGuard = noOpGuard,
            probeEnvironment = { ready }
        )
        qnn.loadEngine(spec)
        val config = LocalConversationConfig(LocalSamplerConfig(40, .95f, .8f), "system", emptyList())
        qnn.createConversation(config)
        val image = byteArrayOf(1, 2, 3)
        qnn.sendMessage("describe", listOf(image)).toList()
        assertEquals(config, native.createConversationCalls.single())
        assertTrue(image.contentEquals(native.sendMessageImages.single().single()))
        qnn.closeConversation()
        assertFalse(qnn.hasOpenConversation())
    }

    @Test
    fun previousNativeInitializationCrashBlocksAnotherAttempt() = runTest {
        val native = FakeLocalRuntime()
        val blockingGuard = object : QnnLoadGuard {
            override fun beforeLoad(spec: LocalEngineSpec) = error("previous QNN native crash")
            override fun loadFinished() = Unit
        }
        val qnn = LocalRuntimeQnnImpl(
            runtime = native,
            enabled = true,
            loadGuard = blockingGuard,
            probeEnvironment = { ready }
        )

        assertTrue(runCatching { qnn.loadEngine(spec) }.isFailure)
        assertTrue(native.loadEngineCalls.isEmpty())
    }

    @Test
    fun previewPackagingRejectsLiteRtNpuBeforeLoadingNativeCode() = runTest {
        val native = FakeLocalRuntime()
        val qnn = LocalRuntimeQnnImpl(native, noOpGuard, { ready }, enabled = false)
        assertTrue(runCatching { qnn.loadEngine(spec) }.isFailure)
        assertTrue(native.loadEngineCalls.isEmpty())
    }

    @Test
    fun libraryRequirementsFollowTheDeviceHtpVersion() {
        assertEquals(
            listOf(
                "libLiteRtDispatch_Qualcomm.so",
                "libQnnHtp.so",
                "libQnnHtpPrepare.so",
                "libQnnSystem.so",
                "libQnnHtpV79Stub.so",
                "libQnnHtpV79Skel.so"
            ),
            QualcommSocSupport.requiredLibraries("SM8750")
        )
        assertTrue("libQnnHtpV73Skel.so" in QualcommSocSupport.requiredLibraries("SM8550"))
        assertTrue("libQnnHtpV75Stub.so" in QualcommSocSupport.requiredLibraries("sm8650"))
        assertTrue("libQnnHtpV81Skel.so" in QualcommSocSupport.requiredLibraries("SM8850"))
        assertTrue(QualcommSocSupport.requiredLibraries("Tensor G5").isEmpty())
        assertTrue(QualcommSocSupport.requiredLibraries("MT6991").isEmpty())
    }

    @Test fun probeFailureRetainsNativeCauseAndNeverTouchesJournal() = runTest {
        val original = UnsatisfiedLinkError("undefined symbol QnnInterface_getProviders")
        val guard = object : QnnLoadGuard {
            override fun beforeLoad(spec: LocalEngineSpec) = error("Journal must not run")
            override fun loadFinished() = error("Unentered journal must not be cleared")
        }
        val qnn = LocalRuntimeQnnImpl(FakeLocalRuntime(), guard, { throw original }, enabled = true)
        val failure = runCatching { qnn.loadEngine(spec) }.exceptionOrNull() as QnnRuntimeFailure
        assertEquals(QnnFailureCode.ELF_LINK_FAILURE, failure.code)
        assertTrue(generateSequence(failure.cause) { it.cause }.take(8).any { it === original })
    }

    @Test fun rejectedJournalIsNotClearedByFailureCleanup() = runTest {
        var cleared = false
        val guard = object : QnnLoadGuard {
            override fun beforeLoad(spec: LocalEngineSpec) = error("previous QNN native crash")
            override fun loadFinished() {
                cleared = true
            }
        }
        val qnn = LocalRuntimeQnnImpl(FakeLocalRuntime(), guard, { ready }, enabled = true)
        val failure = runCatching { qnn.loadEngine(spec) }.exceptionOrNull() as QnnRuntimeFailure
        assertEquals(QnnFailureCode.PREVIOUS_NATIVE_CRASH, failure.code)
        assertFalse(cleared)
    }
}
