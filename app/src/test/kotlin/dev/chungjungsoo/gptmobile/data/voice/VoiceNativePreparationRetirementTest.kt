package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceSpeechBenchmarkOwnership
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The actual retirement, runner, model-store and speech-lease owners with a fake native handle. */
class VoiceNativePreparationRetirementTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cancelledPreparationRetainsFailedHandleModelLeaseAndSpeechOwnership() = runBlocking {
        for (closeFails in listOf(false, true)) {
            val retirement = VoiceNativePreparationRetirement()
            val cancellation = CancellationException("Preparation cancelled")
            val closeFailure = IllegalStateException("Destructor did not complete")
            var closes = 0
            var opens = 0
            var preparationCancelled = false
            var returnedFailure: Throwable? = null
            val native = object : VoicePhraseEngine {
                override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio = error("Cancelled preparation cannot synthesize")
                override fun resetCancellation() = Unit
                override fun cancel() = Unit
                override fun close() {
                    closes++
                    if (closeFails) throw closeFailure
                }
            }
            lateinit var runner: VoiceSpeechBenchmarkRunner
            val factory = object : VoicePhraseEngineFactory {
                override val identity = "cancelled-preparation-fixture"
                override val nativeCleanupPending get() = retirement.nativeCleanupPending
                override fun cancelPending() {
                    preparationCancelled = true
                }
                override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                    opens++
                    runner.cancel()
                    return retirement.transfer(native) {
                        assertTrue(preparationCancelled)
                        throw cancellation
                    }
                }
            }
            val models = installedModel()
            assertTrue(models.acquireUse())
            val ownership = VoiceSpeechBenchmarkOwnership()
            val lease = requireNotNull(ownership.acquire())
            var failedFactory: VoicePhraseEngineFactory? = null
            runner = VoiceSpeechBenchmarkRunner(
                create = {
                    try {
                        factory.create(models.modelDirectory(), "fixture", 1)
                    } catch (failure: Throwable) {
                        returnedFailure = failure
                        throw failure
                    } finally {
                        if (factory.nativeCleanupPending) failedFactory = factory
                    }
                },
                cancelPreparation = factory::cancelPending,
                preparationOwner = { failedFactory },
                thermalStatus = { 0 }
            )
            val configuration = VoiceSpeechBenchmarkConfiguration(
                VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), KokoroVoice.HEART,
                1, 1f, "a".repeat(64), "runtime", 149, 34, "b".repeat(64)
            )
            val report = runner.run(VoiceSpeechBenchmarkReport(1, configuration)) {}
            assertSame(cancellation, returnedFailure)
            assertEquals(1, opens)
            assertEquals(1, closes)
            assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
            assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, report.results.last().state)
            assertEquals(closeFails, runner.nativeCleanupPending)
            assertEquals(if (closeFails) VoiceSpeechBenchmarkCleanupState.FAILED else VoiceSpeechBenchmarkCleanupState.RETIRED, report.results.first().cleanupState)
            val retained = mutableListOf<Any>()
            val released = lease.releaseAfterRetirement({}, { runner.nativeCleanupPending }) {
                retained += runner
                retained += models
            }
            if (closeFails) {
                assertSame(native, retirement.retainedOwner)
                assertSame(closeFailure, cancellation.suppressed.single())
                assertSame(factory, failedFactory)
                assertEquals(VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING, report.issue)
                assertNull(report.results.first().cancellationNativeRetirementMs)
                assertFalse(released)
                assertEquals(listOf(runner, models), retained)
                assertSame(lease, ownership.state.value)
                assertNull(ownership.acquire())
                assertFalse(lease.release())
                assertTrue(runCatching { runner.run(VoiceSpeechBenchmarkReport(2, configuration)) {} }.isFailure)
                assertEquals(1, opens)
                val removal = async { models.remove() }
                try {
                    withTimeout(1_000) {
                        while (!models.state.value.message.orEmpty().contains("Waiting")) delay(10)
                    }
                    assertFalse(removal.isCompleted)
                    assertTrue(models.modelDirectory().exists())
                    assertTrue(models.verifyInstalledFiles())
                } finally {
                    removal.cancelAndJoin()
                }
            } else {
                assertNull(retirement.retainedOwner)
                assertTrue(cancellation.suppressed.isEmpty())
                assertNull(failedFactory)
                assertTrue(released)
                assertTrue(retained.isEmpty())
                assertNull(ownership.quarantineReason.value)
                val replacement = requireNotNull(ownership.acquire())
                assertTrue(replacement.release())
                models.releaseUse()
                models.remove()
                assertFalse(models.modelDirectory().exists())
            }
        }
    }

    @Test fun failedDestructionDoesNotReplaceTheOriginalPreparationFailureOrCloseTwice() {
        for (failure in listOf(CancellationException("Cancelled"), IllegalStateException("Preparation failed"), LinkageError("Runtime failed"))) {
            val retirement = VoiceNativePreparationRetirement()
            val destructor = IllegalStateException("Destruction failed")
            var closes = 0
            val owner = AutoCloseable {
                closes++
                throw destructor
            }
            assertSame(failure, runCatching { retirement.transfer(owner) { throw failure } }.exceptionOrNull())
            assertSame(owner, retirement.retainedOwner)
            assertTrue(retirement.nativeCleanupPending)
            assertEquals(1, closes)
            assertSame(destructor, failure.suppressed.single())
        }
    }

    @Test fun successfulPreparationTransfersItsOwnerWithoutClosingOrQuarantiningIt() {
        val retirement = VoiceNativePreparationRetirement()
        var closes = 0
        val owner = AutoCloseable { closes++ }
        assertSame(owner, retirement.transfer(owner) { owner })
        assertEquals(0, closes)
        assertFalse(retirement.nativeCleanupPending)
        assertNull(retirement.retainedOwner)
    }

    private suspend fun installedModel(): LocalVoiceModelStore {
        val content = "Verified native preparation fixture".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val asset = LocalVoiceAsset("model.ort", content.size.toLong(), hash, listOf("https://models.example/model"))
        return LocalVoiceModelStore(files.newFolder(), listOf(asset)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(content), content.size.toLong())
                override fun cancel() = Unit
            }
        }.also { it.install() }
    }
}
