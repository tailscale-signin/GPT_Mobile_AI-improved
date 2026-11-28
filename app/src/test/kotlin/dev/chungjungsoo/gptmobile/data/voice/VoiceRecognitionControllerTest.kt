package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockMakers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

class VoiceRecognitionControllerTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun captureHandoffAlsoWaitsForTheRecognizerRetiredByASelectionChange() {
        val fixture = Fixture(files.newFolder(), permissionAllowed = true)
        try {
            // Cancel the queued capture before any injected/native decoder starts.
            fixture.controller.listen(fixture.listener)
            assertTrue(fixture.permissionChecks > 0)
            fixture.controller.configure(VoiceRecognitionConfiguration(VoiceRecognitionModel.MOONSHINE_SMALL))
            var released: Boolean? = null
            fixture.controller.stopCapture { released = it }
            assertNull(released)
            assertFalse(VoiceMicrophoneLease.acquire("retired-controller-capture"))
            fixture.drain()
            assertNull(released)
            fixture.captureChecks.removeFirst().invoke()
            assertEquals(true, released)
            assertTrue(fixture.captureChecks.isEmpty())
            assertTrue(VoiceMicrophoneLease.acquire("retired-controller-capture"))
            assertEquals(0, fixture.created)
        } finally {
            fixture.close()
            VoiceMicrophoneLease.release("retired-controller-capture")
        }
    }

    @Test
    fun selectionChangeWhileReleaseIsPendingCannotAcknowledgeTheOldRequest() {
        val fixture = Fixture(files.newFolder())
        try {
            fixture.controller.prewarm()
            fixture.drain()
            assertEquals(1, fixture.created)
            var completed = 0
            fixture.controller.releaseWarmModel { completed++ }
            assertEquals(1, fixture.checks.size)
            fixture.controller.configure(VoiceRecognitionConfiguration(VoiceRecognitionModel.MOONSHINE_SMALL))
            fixture.drain()
            assertEquals(0, completed)
            assertEquals(0, fixture.leases)
            assertEquals(1, fixture.destroyed)
            assertEquals(VoiceRecognitionModel.MOONSHINE_SMALL, fixture.controller.configuration.model)
            assertEquals("ASR_RETIREMENT_SUPERSEDED", fixture.controller.executionEvidence.value["recognitionFailure"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun captureAndPrewarmStayBlockedUntilTheRequestedReleaseCompletes() {
        val fixture = Fixture(files.newFolder())
        try {
            fixture.controller.prewarm()
            fixture.drain()
            var completed = 0
            fixture.controller.releaseWarmModel { completed++ }
            fixture.controller.prewarm()
            fixture.controller.listen(fixture.listener)
            assertEquals(0, fixture.permissionChecks)
            assertEquals(listOf("VOICE_RECOGNIZER_RETIRING"), fixture.failures)
            assertEquals(1, fixture.created)
            assertEquals(1, fixture.leases)
            assertEquals(0, completed)
            fixture.drain()
            assertEquals(1, completed)
            assertEquals(0, fixture.leases)
            assertEquals(1, fixture.destroyed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anExplicitCompletedReleaseSuppressesSpeculativeRewarmingUntilDeliberateListen() {
        val fixture = Fixture(files.newFolder())
        try {
            fixture.controller.prewarm()
            fixture.drain()
            var completed = 0
            fixture.controller.releaseWarmModel { completed++ }
            fixture.drain()
            assertEquals(1, completed)
            repeat(3) { fixture.controller.prewarm() }
            fixture.drain()
            assertEquals(1, fixture.created)
            assertEquals(0, fixture.leases)
            // The injected permission check prevents a real capture. The deliberate
            // listen still clears the controller's speculative-warmup suppression.
            fixture.controller.listen(fixture.listener)
            assertEquals(1, fixture.permissionChecks)
            fixture.controller.prewarm()
            fixture.drain()
            assertEquals(2, fixture.created)
            assertEquals(1, fixture.leases)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(directory: File, permissionAllowed: Boolean = false) {
        val checks = ArrayDeque<() -> Unit>()
        val captureChecks = ArrayDeque<() -> Unit>()
        private val callbacks = ArrayDeque<() -> Unit>()
        private val workers = mutableListOf<ManualWorker>()
        val failures = mutableListOf<String>()
        var permissionChecks = 0
        var created = 0
        var destroyed = 0
        var leases = 0
        private val bytes = "verified test model".toByteArray()
        private val asset = LocalVoiceAsset(
            "weights.ort",
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            emptyList()
        )
        private val legacy = LocalVoiceModelStore(File(directory, "legacy"), listOf(asset), sourceFactory = { error("Controller tests never download a model") }).also {
            it.modelDirectory().mkdirs()
            File(it.modelDirectory(), asset.name).writeBytes(bytes)
            File(it.modelDirectory(), "verified").writeText(LocalVoiceModelStore.MODEL_REVISION)
            runBlocking { it.refresh() }
            assertEquals(LocalVoiceModelStatus.READY, it.state.value.status)
        }
        val controller = StreamingVoiceRecognizer(
            mock(Context::class.java, withSettings().mockMaker(MockMakers.SUBCLASS)),
            RecognitionModelRegistry(directory, legacy),
            create = { store, _, observe ->
                val worker = ManualWorker().also(workers::add)
                MoonshineVoiceRecognizer(
                    modelsReady = { store.acquireUse().also { if (it) leases++ } },
                    modelDirectory = store::modelDirectory,
                    releaseModel = {
                        store.releaseUse()
                        leases--
                    },
                    hasPermission = {
                        permissionChecks++
                        permissionAllowed
                    },
                    post = { callbacks.addLast(it) },
                    schedule = { _, action ->
                        captureChecks.addLast(action)
                        val cancel: () -> Unit = { captureChecks.remove(action) }
                        cancel
                    },
                    decoderFactory = {
                        created++
                        object : LocalSpeechDecoder {
                            override val supportsReuse: Boolean = true
                            override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) = Unit
                            override fun accept(samples: FloatArray) = Unit
                            override fun finish() = Unit
                            override fun close() {
                                destroyed++
                            }
                        }
                    },
                    captureFactory = { error("Controller tests must not create a microphone") },
                    worker = worker,
                    observeExecution = observe
                )
            },
            scheduleReleaseCheck = { checks.addLast(it) }
        ).apply { languageTag = "en-US" }

        val listener = object : VoiceAudio.Listener {
            override fun ready() = Unit
            override fun level(value: Float) = Unit
            override fun partial(text: String) = Unit
            override fun final(text: String) = Unit
            override fun error(message: String) = Unit
            override fun failure(message: String, code: String, retryMillis: Long, retryable: Boolean, recoveryAction: VoiceRecoveryAction?, cause: Throwable?) {
                failures += code
            }
        }

        fun drain() {
            workers.forEach(ManualWorker::drain)
            while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
            val ready = checks.toList()
            checks.clear()
            ready.forEach { it() }
            assertTrue("Cleanup should have reached a terminal state after worker completion", checks.isEmpty())
        }

        fun close() {
            controller.close()
            drain()
            assertTrue(controller.awaitClosed(0))
            assertEquals(0, leases)
        }
    }

    /** Serial native-worker scheduling is controlled without waiting on real threads. */
    private class ManualWorker : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        private var running = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException("Worker has shut down")
            tasks.addLast(command)
        }
        fun drain() {
            while (tasks.isNotEmpty()) {
                running = true
                try {
                    tasks.removeFirst().run()
                } finally {
                    running = false
                }
            }
        }
        override fun shutdown() {
            stopped = true
        }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && !running && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
