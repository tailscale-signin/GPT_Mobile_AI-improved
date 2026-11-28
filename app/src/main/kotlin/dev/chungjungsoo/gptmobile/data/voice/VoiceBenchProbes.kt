package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
internal data class VoiceBenchObservation(val fixture: String, val status: VoiceBenchStatus, val reason: String? = null, val metrics: Map<String, Double> = emptyMap()) {
    companion object {
        fun unavailable(fixture: String, reason: String) = VoiceBenchObservation(fixture, VoiceBenchStatus.UNAVAILABLE, reason)
    }
}

internal fun List<VoiceBenchObservation>.summarize(label: String): VoiceBenchResult {
    val kinds = map { it.status }.distinct()
    val status = kinds.singleOrNull() ?: if (isEmpty()) VoiceBenchStatus.UNAVAILABLE else VoiceBenchStatus.PARTIAL
    val metrics = buildList {
        for (row in this@summarize) {
            for ((key, caption) in listOf("wordErrorRate" to "word error rate", "characterErrorRate" to "character error rate")) {
                row.metrics[key]?.let { add(VoiceBenchMetric("${row.fixture} · $caption", it * 100, "%", 1)) }
            }
            if (row.metrics["selectedForAuto"] == 1.0) row.metrics["threads"]?.let { add(VoiceBenchMetric("Auto calibration", it, "threads", 1)) }
        }
        for ((key, caption) in listOf("firstPcmMs" to "First PCM", "firstReadMs" to "First microphone read", "firstAudioCallbackMs" to "First system audio callback")) {
            val times = this@summarize.filter { it.status == VoiceBenchStatus.MEASURED }.mapNotNull { it.metrics[key] }
            VoiceSpeechBenchmarkBackendResult.median(times)?.let { add(VoiceBenchMetric(caption, it, "ms", times.size)) }
        }
    }
    return VoiceBenchResult(label, status, if (isEmpty()) "NO_OBSERVATIONS" else mapNotNull { it.reason }.distinct().singleOrNull(), count { it.metrics.isNotEmpty() || it.status == VoiceBenchStatus.MEASURED }, metrics)
}

internal fun elapsedVoiceBenchMs(start: Long) = (System.nanoTime() - start) / 1_000_000

internal class VoiceBenchProbes(
    private val context: Context,
    private val models: MediaPipeSpeechModelStore,
    private val gateway: VoicePhraseEngineFactory?,
    private val voice: KokoroVoice,
    private val active: () -> Boolean,
    private val cleanupFailed: (Any) -> Unit
) {
    private val interrupt = AtomicReference<(() -> Unit)?>(null)
    fun cancel() {
        interrupt.get()?.invoke()
    }

    suspend fun run(stage: VoiceBenchStage, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> = when (stage.probe) {
        VoiceBenchProbe.CPU_CALIBRATION -> voiceBenchCpuCalibration(context, stage.variant, voice, stage.rate, active, interrupt, cleanupFailed)
        VoiceBenchProbe.AUDIO_ROUTES -> voiceBenchAudioRoutes(context)
        VoiceBenchProbe.MICROPHONE -> microphone(checkpoint)
        VoiceBenchProbe.SYSTEM_SPEECH -> voiceBenchSystemSpeech(context, active, interrupt, checkpoint)
        VoiceBenchProbe.GATEWAY -> voiceBenchGateway(context, gateway, active, interrupt, checkpoint)
        VoiceBenchProbe.SYSTEM_RECOGNITION -> voiceBenchSystemRecognition(context, active)
        VoiceBenchProbe.RECOGNITION -> voiceBenchMoonshine(context, requireNotNull(stage.listeningStyle), active, cleanupFailed, checkpoint)
        VoiceBenchProbe.LANGUAGE -> language(requireNotNull(SpeechLanguages.forLocale(requireNotNull(stage.language))), checkpoint)
        VoiceBenchProbe.DIALOGUE -> voiceBenchDialogue()
        null -> error("Missing voice benchmark probe")
    }

    private fun checkActive() {
        if (!active()) throw CancellationException("Benchmark stopped")
    }

    private fun microphone(checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return listOf(VoiceBenchObservation.unavailable("microphone", "MICROPHONE_PERMISSION_MISSING"))
        }
        val rows = mutableListOf<VoiceBenchObservation>()
        for (batch in listOf(20, 40, 80, 100)) {
            checkActive()
            val capture = AndroidVoiceCapture(context)
            val started = System.nanoTime()
            val watchdog = VoiceSpeechBenchmarkWatchdog(VoiceSpeechBenchmarkDeadlines(backendMs = 3_000), onTimeout = { capture.stop() })
            interrupt.set(capture::stop)
            try {
                watchdog.beginBackend()
                checkActive()
                capture.configure(batch)
                capture.start()
                var first: Long? = null
                var frames = 0
                while (frames < 8_000 && watchdog.checkpoint() == null && elapsedVoiceBenchMs(started) < 3_000) {
                    checkActive()
                    val samples = capture.read()
                    if (samples.isNotEmpty() && first == null) first = elapsedVoiceBenchMs(started)
                    frames += samples.size
                    capture.recycle(samples)
                }
                val ok = frames >= 8_000
                rows += VoiceBenchObservation(
                    "capture-${batch}ms",
                    if (ok) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED,
                    if (ok) null else "INSUFFICIENT_CAPTURE",
                    buildMap {
                        put("sampleFrames", frames.toDouble())
                        first?.let { put("firstReadMs", it.toDouble()) }
                        put("elapsedMs", elapsedVoiceBenchMs(started).toDouble())
                    }
                )
            } finally {
                watchdog.close()
                interrupt.set(null)
                try {
                    capture.stop()
                } finally {
                    capture.close()
                }
            }
            checkpoint(rows)
        }
        return rows
    }

    private suspend fun language(profile: SpeechLanguage, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
        val observations = mutableListOf(voiceBenchDictionary(profile))
        checkpoint(observations)
        val engineType = MediaPipeSpeechEngine.KOKORO
        models.refresh(engineType, false)
        if (!models.isReady(engineType)) return observations + (profile.voices + "android-recognition-loopback-${profile.tag}").map { VoiceBenchObservation.unavailable(it, "STANDARD_MULTILINGUAL_PACK_MISSING") }
        val factory = MediaPipeVoicePhraseEngineFactory(models, engineType, VoiceSpeechBackend.LITERT_KOKORO_CPU, 1f, profile.voices.first())
        val engine = AtomicReference<VoicePhraseEngine?>(null)
        val stop = {
            factory.cancelPending()
            engine.get()?.cancel()
            Unit
        }
        var fixture: ByteArray? = null
        val measured = try {
            VoiceNativeEngineGate.exclusive {
                interrupt.set(stop)
                val deadline = VoiceSpeechBenchmarkWatchdog(VoiceSpeechBenchmarkDeadlines(backendMs = 1_200_000L), onTimeout = { stop() })
                try {
                    deadline.beginBackend()
                    deadline.beginPhase(VoiceSpeechBenchmarkDeadlineScope.PREPARATION)
                    checkActive()
                    val graph = factory.create(context.noBackupFilesDir, "", 1)
                    engine.set(graph)
                    check(deadline.endPhase() == null) { "Voice preparation timed out" }
                    for (voice in profile.voices) {
                        checkActive()
                        check(deadline.beginPhase(VoiceSpeechBenchmarkDeadlineScope.CHUNK))
                        val row = try {
                            graph.selectVoice(voice)
                            val signal = VoicePcmSignalMeasurement()
                            var capture = if (fixture == null) java.io.ByteArrayOutputStream() else null
                            val timeline = VoiceStreamFrames(profile.preview)
                            var first: Long? = null
                            var cues = 0
                            val start = System.nanoTime()
                            consumeVoiceStream(graph, profile.preview, 0, 1f, active) { chunk ->
                                cues += timeline.accept(chunk).size
                                if (chunk.samples.isNotEmpty() && first == null) first = elapsedVoiceBenchMs(start)
                                signal.accept(chunk.samples)
                                capture?.let { output ->
                                    if (output.size() + chunk.samples.size * 2 > 1_440_000) {
                                        capture = null
                                    } else {
                                        val bytes = ByteArray(chunk.samples.size * 2)
                                        chunk.samples.forEachIndexed { index, sample ->
                                            val value = (sample * 32768f).toInt().coerceIn(-32768, 32767)
                                            bytes[index * 2] = value.toByte()
                                            bytes[index * 2 + 1] = (value shr 8).toByte()
                                        }
                                        output.write(bytes)
                                    }
                                }
                                true
                            }
                            timeline.finish()
                            val pcm = requireNotNull(signal.snapshot())
                            val good = pcm.rms.isFinite() && pcm.nonSilent > 0 && pcm.clipped.toDouble() / pcm.samples <= .01
                            if (good && fixture == null) fixture = capture?.toByteArray()
                            VoiceBenchObservation(
                                voice,
                                when {
                                    !good -> VoiceBenchStatus.FAILED
                                    cues == 0 -> VoiceBenchStatus.PARTIAL
                                    else -> VoiceBenchStatus.MEASURED
                                },
                                when {
                                    !good -> "INVALID_SIGNAL"
                                    cues == 0 -> "NO_WORD_CUES"
                                    else -> null
                                },
                                mapOf(
                                    "frames" to pcm.samples.toDouble(),
                                    "firstPcmMs" to requireNotNull(first).toDouble(),
                                    "elapsedMs" to elapsedVoiceBenchMs(start).toDouble(),
                                    "wordCues" to cues.toDouble(),
                                    "rms" to pcm.rms,
                                    "clippedSamples" to pcm.clipped.toDouble()
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            VoiceBenchObservation(voice, VoiceBenchStatus.FAILED, "VOICE_SELECTION_OR_SYNTHESIS_FAILED")
                        }
                        observations += row
                        checkpoint(observations)
                        if (deadline.endPhase() != null) {
                            return@exclusive observations + profile.voices.drop(observations.size - 1).map { VoiceBenchObservation.unavailable(it, "PREVIOUS_VOICE_TIMEOUT") }
                        }
                    }
                    observations
                } finally {
                    deadline.close()
                    interrupt.set(null)
                    val current = engine.getAndSet(null)
                    try {
                        current?.close()
                    } catch (error: Throwable) {
                        cleanupFailed(listOfNotNull(factory, current))
                        throw error
                    } finally {
                        if (factory.nativeCleanupPending) cleanupFailed(factory)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val seen = observations.mapTo(mutableSetOf()) { it.fixture }
            observations + profile.voices.filterNot { it in seen }.map { VoiceBenchObservation(it, VoiceBenchStatus.FAILED, "MULTILINGUAL_RUNTIME_FAILED") }
        } catch (_: LinkageError) {
            val seen = observations.mapTo(mutableSetOf()) { it.fixture }
            observations + profile.voices.filterNot { it in seen }.map { VoiceBenchObservation.unavailable(it, "NATIVE_RUNTIME_UNAVAILABLE") }
        }
        checkActive()
        val loopback = fixture?.let { voiceBenchLoopback(context, profile, it, active, interrupt) }
            ?: VoiceBenchObservation.unavailable("android-recognition-loopback-${profile.tag}", "NO_VALID_LOCALIZED_FIXTURE")
        return measured + loopback
    }
}

internal fun voiceBenchDictionary(profile: SpeechLanguage): VoiceBenchObservation {
    val readings = LocalizedVoiceReadings.forLanguage(profile.dictionaryLanguage) + ("voxlab" to "sample")
    val dictionary = VoicePronunciationDictionary(mapOf("voxlab" to "sample"), profile.tag)
    val start = System.nanoTime()
    val correct = readings.count { (key, value) ->
        val projection = dictionary.project(key)
        projection.text == value && projection.sourceRange(0, value.length) == key.indices
    }
    return VoiceBenchObservation("dictionary-${profile.tag}", if (correct == readings.size) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED, metrics = mapOf("cases" to readings.size.toDouble(), "correctMappings" to correct.toDouble(), "elapsedMicros" to (System.nanoTime() - start) / 1000.0))
}

internal fun voiceBenchDialogue(): List<VoiceBenchObservation> {
    val run = AgentRun("voice-bench", 1, 2, 3, "fixture", "local", "fixture", status = AgentRunStatus.RUNNING)
    val search = ToolEvent("event", run.runId, 1, "call", null, null, "web_search", "web_search", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1)
    return SpeechLanguages.synthesis.map { profile ->
        val start = System.nanoTime()
        val thinking = VoiceTurnOpenings.plan("Explain how seasons change.", run, profile.tag, emptyList())
        val lookup = VoiceTurnOpenings.plan("Check the weather.", run, profile.tag, listOf(search))
        val quick = VoiceTurnOpenings.plan("Hello!", run, profile.tag, emptyList())
        val ok = thinking?.cue?.lookup == false && lookup?.cue?.lookup == true && quick == null
        VoiceBenchObservation(
            profile.tag,
            if (ok) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED,
            metrics = buildMap {
                put("plannerMicros", (System.nanoTime() - start) / 1000.0)
                thinking?.let { put("plannedThinkingDelayMs", it.delayMs.toDouble()) }
                lookup?.let { put("plannedLookupDelayMs", it.delayMs.toDouble()) }
            }
        )
    }
}
