package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.StartupDependencies
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical proof is per request and engine; requested NPU settings never prove NPU execution. */
@RunWith(AndroidJUnit4::class)
class QualcommNpuInstrumentedTest {
    @Test(timeout = 600_000)
    fun matchingSm8750ModelGeneratesOnNpu() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelPath = args.getString("qnnModelPath").orEmpty()
        val expected = args.getString("qnnModelSha256").orEmpty().lowercase()
        val runId = args.getString("qnnQualificationRunId") ?: UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        val json = Json { encodeDefaults = true }
        val turns = mutableListOf<JsonObject>()
        val releases = mutableListOf<JsonObject>()
        val issues = mutableListOf<String>()
        var complete = false
        suspend fun save(reason: String) = withContext(NonCancellable + Dispatchers.IO) {
            val evidence = buildJsonObject {
                put("schemaVersion", 1)
                put("status", "NOT_QUALIFIED")
                put("reason", reason)
                put("runId", runId)
                put("sourceRevision", BuildConfig.APP_COMMIT)
                put("modelSha256", expected)
                put("runtimeScope", "isolated_process")
                put("coldStartScope", "engine_recreation")
                put("startedAtElapsedMs", started)
                put("observedAtElapsedMs", SystemClock.elapsedRealtime())
                put("collectionComplete", complete)
                put("issues", JsonArray(issues.distinct().map(::JsonPrimitive)))
                put(
                    "device",
                    buildJsonObject {
                        put("fingerprint", Build.FINGERPRINT)
                        put("manufacturer", Build.MANUFACTURER)
                        put("model", Build.MODEL)
                        put("soc", QualcommSocSupport.canonicalSoc(Build.SOC_MODEL).orEmpty())
                        put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    }
                )
                put("turns", JsonArray(turns.toList()))
                put("engineReleases", JsonArray(releases.toList()))
            }
            val target = AtomicFile(File(context.filesDir, "device-qualification/qnn.json").apply { parentFile?.mkdirs() })
            val output = target.startWrite()
            try {
                output.write(json.encodeToString(evidence).toByteArray())
                target.finishWrite(output)
            } catch (failure: Throwable) {
                target.failWrite(output)
                throw failure
            }
        }
        save("Supply a trusted model and complete physical execution and cleanup evidence.")
        assumeTrue("NOT_QUALIFIED: supply qnnModelPath and qnnModelSha256 for physical qualification", modelPath.isNotBlank())
        try {
            assertTrue("Qualification requires a trusted model SHA-256", expected.matches(Regex("[a-f0-9]{64}")))
            assertTrue("Qualification requires SM8750", QualcommSocSupport.canonicalSoc(Build.SOC_MODEL) == "SM8750")
            val suppliedModel = File(modelPath)
            val model = (if (suppliedModel.isAbsolute) suppliedModel else File(context.dataDir, modelPath)).canonicalFile
            val roots = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.getExternalFilesDir(null)).map { it.canonicalFile }
            assertTrue("Qualification requires an installed, readable app-owned model", model.isFile && model.canRead() && roots.any { model.path.startsWith(it.path + File.separator) })
            withContext(Dispatchers.IO) { PackageDigest.verify(model, expected) }
            StartupRecoveryGate.await()
            val startup = EntryPointAccessors.fromApplication(context, StartupDependencies::class.java)
            val storedSettings = startup.settingRepository()
            val settings = object : SettingRepository by storedSettings {
                override suspend fun getLocalRuntimeBackend() = LocalRuntimeBackend.QUALCOMM_QNN
                override suspend fun getFeatureSettings() = storedSettings.getFeatureSettings().copy(qnnAutomaticFallback = false)
            }
            val shared = startup.localRuntime()
            val runtime = ProcessLocalRuntime(context, settings)
            InferenceAdmission.benchmark {
                val initial = shared.cleanupAfterRequest("qnn_qualification_prepare:$runId")
                check(initial.safeForSuccessor) { "Previous native work has no positive cleanup evidence" }
                try {
                    shared.unloadEngine()
                } catch (failure: Throwable) {
                    val cleanup = shared.cleanupAfterRequest("qnn_qualification_prepare_failed:$runId")
                    if (!cleanup.safeForSuccessor) shared.quarantineAfterUnconfirmedCleanup("Existing engine release failed before QNN qualification")
                    throw failure
                }
                try {
                    repeat(2) { coldStart ->
                        try {
                            runtime.loadEngine(LocalEngineSpec(model.path, LocalAccelerators.NPU, 1024, nativeMetricsEnabled = true, tuningLocked = true, contextPolicy = "MANUAL"))
                            repeat(2) { turn ->
                                val previousRequest = runtime.latestExecutionReceipt?.runtimeRequestId
                                val begin = SystemClock.elapsedRealtime()
                                val output = StringBuilder()
                                var done = false
                                var failure: Throwable? = null
                                val eventErrors = mutableListOf<String>()
                                var execution: RuntimeExecutionReceipt? = null
                                var cleanup: RuntimeCleanupReceipt? = null
                                try {
                                    runtime.createConversation(LocalConversationConfig(LocalSamplerConfig(1, 1f, 0f), null, emptyList(), maxOutputTokens = 32, allowAutomaticRecovery = false))
                                    withTimeout(120_000) {
                                        runtime.sendMessage("Reply with a short greeting.").collect { event ->
                                            when (event) {
                                                is LocalRuntimeEvent.TextDelta -> {
                                                    check(output.length + event.text.length <= 16_384) { "Greeting exceeded the bounded qualification output" }
                                                    output.append(event.text)
                                                }
                                                is LocalRuntimeEvent.Error -> eventErrors += localRuntimeErrorCode(event.cause ?: IllegalStateException(event.message)) ?: "runtime_error"
                                                LocalRuntimeEvent.Done -> done = true
                                                else -> Unit
                                            }
                                        }
                                    }
                                } catch (error: Throwable) {
                                    failure = error
                                } finally {
                                    execution = runtime.latestExecutionReceipt
                                    cleanup = runtime.cleanupAfterRequest("qnn_qualification:$runId:$coldStart:$turn")
                                    val finished = SystemClock.elapsedRealtime()
                                    val turnIssues = executionIssues(execution, cleanup, expected, previousRequest, begin, finished, output.isNotBlank(), done)
                                    issues += turnIssues
                                    if (failure != null) issues += "REQUEST_FAILED:${failure.javaClass.simpleName}"
                                    issues += eventErrors
                                    turns += buildJsonObject {
                                        put("coldStart", coldStart)
                                        put("turn", turn)
                                        put("startedAtElapsedMs", begin)
                                        put("finishedAtElapsedMs", finished)
                                        put("clientDeadlineMs", 120_000)
                                        put("nonemptyOutput", output.isNotBlank())
                                        put("visibleCharacters", output.length)
                                        put("outputSha256", PackageDigest.sha256(output.toString().toByteArray()))
                                        put("done", done)
                                        put("errors", JsonArray((eventErrors + listOfNotNull(failure?.javaClass?.simpleName)).map(::JsonPrimitive)))
                                        put("execution", json.encodeToJsonElement(execution))
                                        put("cleanup", json.encodeToJsonElement(cleanup))
                                    }
                                    save("Collected request evidence; qualification still requires every proof and engine cleanup.")
                                }
                                if (cleanup?.safeForSuccessor != true) {
                                    shared.quarantineAfterUnconfirmedCleanup("QNN device qualification did not confirm cleanup")
                                    error("NOT_QUALIFIED: native cleanup is unconfirmed")
                                }
                                failure?.let { throw it }
                                check(eventErrors.isEmpty() && done) { "NOT_QUALIFIED: request failed or did not complete" }
                            }
                        } finally {
                            var releaseFailure: Throwable? = null
                            try {
                                runtime.unloadEngine()
                            } catch (failure: Throwable) {
                                releaseFailure = failure
                            }
                            val released = runtime.cleanupAfterRequest("qnn_qualification_release:$runId:$coldStart")
                            releases += buildJsonObject {
                                put("coldStart", coldStart)
                                put("cleanup", json.encodeToJsonElement(released))
                                put("releaseError", releaseFailure?.javaClass?.simpleName)
                            }
                            if (!released.safeForSuccessor || released.engineReleased != true) {
                                issues += "ENGINE_RELEASE_UNCONFIRMED"
                                shared.quarantineAfterUnconfirmedCleanup("QNN device qualification did not confirm engine release")
                            }
                            save("Collected engine release evidence; no success is published before all cleanup.")
                            releaseFailure?.let { throw it }
                            check(released.safeForSuccessor && released.engineReleased == true) { "NOT_QUALIFIED: engine release is unconfirmed" }
                        }
                    }
                } finally {
                    val finalCleanup = runtime.cleanupAfterRequest("qnn_qualification_final:$runId")
                    if (!finalCleanup.safeForSuccessor) {
                        issues += "FINAL_CLEANUP_UNCONFIRMED"
                        shared.quarantineAfterUnconfirmedCleanup("QNN device qualification final cleanup is unconfirmed")
                    }
                }
            }
            complete = true
            save(if (issues.isEmpty()) "All request and cleanup assertions passed; external device gate must validate this evidence." else issues.distinct().joinToString("; "))
            assertTrue("NOT_QUALIFIED: ${issues.distinct().joinToString("; ")}", issues.isEmpty())
            Log.i("QnnQualification", "status=EVIDENCE_COMPLETE runId=$runId engineColdStarts=2 turnsPerStart=2 actualQualification=requires_artifact_gate")
        } catch (failure: Throwable) {
            val reason = issues.distinct().joinToString("; ").ifBlank { "Qualification failed: ${failure.javaClass.simpleName}: ${safeQnnDetail(failure.message.orEmpty())}" }
            save(reason)
            Log.e("QnnQualification", "status=NOT_QUALIFIED runId=$runId reason=${reason.take(1200)}")
            throw failure
        }
    }

    private fun executionIssues(
        execution: RuntimeExecutionReceipt?,
        cleanup: RuntimeCleanupReceipt?,
        expectedDigest: String,
        previousRequest: String?,
        started: Long,
        finished: Long,
        nonempty: Boolean,
        done: Boolean
    ): List<String> = buildList {
        val load = execution?.engineLoad
        if (!nonempty || !done) add("NONEMPTY_COMPLETED_OUTPUT_MISSING")
        if (execution?.nativeEntered != true || execution.nativeReturned != true || execution.terminalKind !in setOf("eos", "sdk_done")) add("NATIVE_ENTRY_OR_SUCCESSFUL_TERMINAL_UNOBSERVED")
        if (execution?.promptTokens?.let { it >= 0 } != true || execution.sampledTokens?.let { it > 0 } != true) add("NATIVE_TOKEN_COUNTERS_UNOBSERVED")
        if (load?.actualBackend == null) {
            add("ACTUAL_BACKEND_UNOBSERVED: pinned SDK does not report native backend; requested NPU is insufficient")
        } else if (!load.actualBackend.equals("npu", true) || load.nativeAcknowledgedFields.none { it in setOf("backend", "actualBackend") }) {
            add("ACTUAL_NPU_BACKEND_PROOF_MISSING")
        }
        if (load?.artifactDigest?.equals(expectedDigest, true) != true) add("ARTIFACT_IDENTITY_MISMATCH")
        if (execution?.runtimeRequestId.isNullOrBlank() || execution?.runtimeRequestId == previousRequest || execution?.nativeGenerationId.isNullOrBlank() || execution?.workerGenerationId.isNullOrBlank()) add("REQUEST_IDENTITY_MISSING_OR_STALE")
        if (execution?.configuration?.generationId != execution?.nativeGenerationId || execution?.engineInstanceId.isNullOrBlank() || execution?.engineInstanceId != load?.engineInstanceId || execution?.workerGenerationId != load?.workerGenerationId) add("GENERATION_OR_ENGINE_CORRELATION_MISSING")
        if (execution?.adapterInvokedAtElapsedMs?.let { it in started..finished } != true || execution.nativeReturnedAtElapsedMs?.let { it in started..finished } != true) add("REQUEST_TIME_CORRELATION_MISSING")
        if (execution?.commandTerminalAcknowledged != true) add("COMMAND_TERMINAL_UNACKNOWLEDGED")
        if (cleanup?.runtimeRequestId != execution?.runtimeRequestId || cleanup?.workerGenerationId != execution?.workerGenerationId || cleanup?.engineInstanceId != execution?.engineInstanceId) add("CLEANUP_CORRELATION_MISSING")
        if (cleanup?.safeForSuccessor != true || cleanup.nativeClosureConfirmed != true || cleanup.conversationClosed != true) add("REQUEST_CLEANUP_UNCONFIRMED")
    }
}
