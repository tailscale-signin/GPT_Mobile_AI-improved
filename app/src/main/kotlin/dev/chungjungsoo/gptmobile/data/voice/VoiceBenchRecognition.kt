package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.speech.RecognizerIntent
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public sentences synthesized with CMU Flite slt and rms, mono G.711 mu-law at 16 kHz. */
internal val voiceBenchRecordings = mapOf("ready" to "The voice is ready for your next question.", "weather" to "Please check the weather and read the answer out loud.")

internal val voiceBenchRecordingHashes = mapOf(
    "ready" to "c413870702dcb1391947078bb63579ce74f55da0e9b9d2ff2c7afedfd6af5bf4",
    "weather" to "f5112c380389c8ec4c0e6be80e0ef96db191500b68c907a72986948f112d996a"
)

internal val voiceBenchRecordingFrames = mapOf("ready" to 43_760L, "weather" to 51_520L)

internal fun voiceBenchFixtureVerified(id: String, audio: ByteArray): Boolean =
    java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) } == voiceBenchRecordingHashes[id]

internal fun decodeVoiceBenchSample(value: Byte): Float {
    val code = value.toInt().inv() and 255
    val magnitude = (((code and 15) * 8 + 132) shl ((code shr 4) and 7)) - 132
    return (if (code and 128 == 0) magnitude else -magnitude) / 32768f
}

internal fun voiceBenchWords(text: String) = Regex("[\\p{L}\\p{M}\\p{N}']+").findAll(text.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()

internal fun voiceBenchWordErrors(expected: List<String>, heard: List<String>): Int {
    var previous = IntArray(heard.size + 1) { it }
    expected.forEachIndexed { row, word ->
        val next = IntArray(heard.size + 1)
        next[0] = row + 1
        heard.forEachIndexed { column, actual ->
            next[column + 1] = minOf(previous[column + 1] + 1, next[column] + 1, previous[column] + if (word == actual) 0 else 1)
        }
        previous = next
    }
    return previous.last()
}

internal suspend fun voiceBenchMoonshine(context: Context, style: VoiceListeningStyle, active: () -> Boolean, cleanupFailed: (Any) -> Unit, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
    val directory = File(context.applicationContext.noBackupFilesDir, "voice-models")
    val registry = RecognitionModelRegistry(directory, LocalVoiceModelStore(directory))
    val case = VoiceRecognitionBenchmarkCase.forPack(requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_TINY)), 2)
    return voiceBenchRecognition(context, registry, case, style, active, AtomicReference(null), cleanupFailed, checkpoint)
}

/** Downloading or selecting a provider is never evidence that its model or hardware executed. */
internal suspend fun voiceBenchRecognition(
    context: Context,
    registry: RecognitionModelRegistry,
    case: VoiceRecognitionBenchmarkCase,
    style: VoiceListeningStyle,
    active: () -> Boolean,
    interrupt: AtomicReference<(() -> Unit)?>,
    cleanupFailed: (Any) -> Unit,
    checkpoint: (List<VoiceBenchObservation>) -> Unit,
    calibrationWarmup: Boolean = false
): List<VoiceBenchObservation> {
    if (case.calibrationCandidates.isNotEmpty()) {
        return voiceBenchRecognitionCpuCalibration(context, registry, case, style, active, interrupt, cleanupFailed, checkpoint)
    }
    fun unavailable(reason: String) = listOf(
        VoiceBenchObservation(
            case.identity.packId,
            VoiceBenchStatus.UNAVAILABLE,
            reason,
            measurementScope = "RECOGNITION_PREREQUISITE_NOT_EXECUTION_OR_QUALITY_QUALIFICATION",
            recognition = case.identity
        )
    )
    if (!active()) throw CancellationException("Voice benchmark stopped")
    if (VoiceRecognitionRetirement.process.blocked) return unavailable("ASR_RETIREMENT_UNCONFIRMED")
    val pack = VoiceRecognitionModelCatalog.pack(case.configuration.model, case.configuration.compute)
    case.unavailableReason(pack)?.let { return unavailable(it) }
    val models = registry.store(case.configuration)
    models.refresh(false)
    if (!active()) throw CancellationException("Voice benchmark stopped")
    val lease = models.acquire() ?: return unavailable("RECOGNITION_MODEL_NOT_INSTALLED_OR_NOT_VERIFIED")
    val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
    val thermal = { runCatching { power?.currentThermalStatus }.getOrNull() }
    val backend = when (case.configuration.compute) {
        VoiceRecognitionCompute.CPU -> VoiceSpeechBackend.CPU
        VoiceRecognitionCompute.GPU -> VoiceSpeechBackend.QNN_GPU
        VoiceRecognitionCompute.NPU -> VoiceSpeechBackend.QNN_HTP
    }
    val energy = runCatching { VoiceSpeechBenchmarkBatterySource(context).begin(backend) }.getOrNull()
    val before = thermal()
    var observed = emptyList<VoiceBenchObservation>()
    fun save(rows: List<VoiceBenchObservation>) {
        observed = rows.map(case::qualify)
        checkpoint(observed)
    }
    try {
        withVoiceRecognitionBenchmarkDecoder(lease, interrupt, { owner ->
            VoiceRecognitionRetirement.process.block()
            cleanupFailed(owner)
        }, {
            createRecognitionDecoder(context, lease.pack, case.configuration)
        }) { decoder ->
            save(voiceBenchRecognitionFixtures(context, decoder, lease.directory, style, active, ::save, case.languageTag, thermal, calibrationWarmup))
        }
    } finally {
        // This window spans model preparation, every fixture and observed retirement. It must not
        // be divided into per-engine power, per-fixture energy or an automatic battery ranking.
        val after = thermal()
        val battery = runCatching { energy?.finish() }.getOrNull()
        val health = voiceRecognitionBenchmarkHealth(before, after, battery, case.identity)
        checkpoint(observed + health)
        observed = observed + health
    }
    return observed
}

/** Quarantine retains both native ownership and the verified model generation after failed close. */
internal fun <T> withVoiceRecognitionBenchmarkDecoder(
    lease: AutoCloseable,
    interrupt: AtomicReference<(() -> Unit)?>,
    cleanupFailed: (Any) -> Unit,
    create: () -> LocalSpeechDecoder,
    run: (LocalSpeechDecoder) -> T
): T {
    val current = AtomicReference<LocalSpeechDecoder?>(null)
    val stop = {
        current.get()?.cancel()
        Unit
    }
    interrupt.set(stop)
    try {
        return VoiceNativeEngineGate.exclusive {
            val decoder = create().also(current::set)
            run(decoder)
        }
    } finally {
        interrupt.compareAndSet(stop, null)
        var retired = false
        try {
            VoiceNativeEngineGate.exclusive { current.get()?.close() }
            retired = true
        } finally {
            if (retired) lease.close() else cleanupFailed(listOf(current.get(), lease))
        }
    }
}

/** Raw whole-device endpoints keep unsupported counters and power transitions explicitly unknown. */
internal fun voiceRecognitionBenchmarkHealth(
    before: Int?,
    after: Int?,
    battery: VoiceSpeechBenchmarkEnergyObservation?,
    identity: VoiceRecognitionBenchmarkIdentity
): VoiceBenchObservation {
    val available = before != null || after != null || battery?.start?.remainingNanowattHours != null || battery?.end?.remainingNanowattHours != null
    return VoiceBenchObservation(
        "recognition-device-health",
        if (available) VoiceBenchStatus.MEASURED else VoiceBenchStatus.UNAVAILABLE,
        "BATTERY_AND_THERMAL_COUNTERS_UNAVAILABLE".takeUnless { available },
        metrics = buildMap {
            before?.let { put("thermalBefore", it.toDouble()) }
            after?.let { put("thermalAfter", it.toDouble()) }
            battery?.elapsedMs?.let { put("batteryCounterWindowMs", it.toDouble()) }
        },
        measurementScope = "WHOLE_DEVICE_COUNTERS_AND_THERMAL_ENDPOINTS_NOT_RECOGNIZER_ENERGY_POWER_OR_BATTERY_LIFE",
        recognition = identity,
        metadata = buildMap {
            put("batteryCounterStatus", battery?.status?.name ?: "NOT_COLLECTED")
            put("batteryCounterWindow", "MODEL_PREPARATION_ALL_FIXTURES_AND_OBSERVED_RETIREMENT_NOT_PER_FIXTURE")
            put("batteryRanking", "NOT_ATTRIBUTABLE_TO_RECOGNIZER")
            put("thermalScope", "CASE_ENDPOINTS_NOT_SUSTAINED_THERMAL_QUALIFICATION")
            battery?.let {
                put("batteryMonitoringConfirmed", it.monitoringConfirmed.toString())
                put("observedPowerEvents", it.observedPowerEvents.toString())
                put("externalPowerObserved", it.externalPowerObserved.toString())
                put("unknownPowerStateObserved", it.unknownPowerStateObserved.toString())
                put("startChargeState", it.start.chargeState.name)
                it.start.plugged?.let { plugged -> put("startPlugged", plugged.toString()) }
                it.end?.let { end ->
                    put("endChargeState", end.chargeState.name)
                    end.plugged?.let { plugged -> put("endPlugged", plugged.toString()) }
                }
                it.start.remainingNanowattHours?.let { value -> put("startRemainingNanowattHours", value.toString()) }
                it.end?.remainingNanowattHours?.let { value -> put("endRemainingNanowattHours", value.toString()) }
                it.observedCounterDecreaseNanowattHours?.let { value -> put("observedCounterDecreaseNanowattHours", value.toString()) }
            }
        }
    )
}

/** Caller owns the model lease and native gate. Every recognizer receives the identical unbiased input. */
internal fun voiceBenchRecognitionFixtures(
    context: Context,
    decoder: LocalSpeechDecoder,
    directory: File,
    style: VoiceListeningStyle,
    active: () -> Boolean,
    checkpoint: (List<VoiceBenchObservation>) -> Unit,
    languageTag: String = "en-US",
    thermalStatus: () -> Int? = { null },
    calibrationWarmup: Boolean = false
): List<VoiceBenchObservation> = voiceBenchPacedRecognitionFixtures(
    decoder,
    directory,
    style,
    active,
    checkpoint,
    languageTag,
    thermalStatus,
    calibrationWarmup,
    readFixture = { id -> context.assets.open("voice-benchmark/$id.mulaw").use { it.readBytes() } }
)

/** The same fixture loop accepts a source and monotonic clock for deterministic recovery tests. */
internal fun voiceBenchPacedRecognitionFixtures(
    decoder: LocalSpeechDecoder,
    directory: File,
    style: VoiceListeningStyle,
    active: () -> Boolean,
    checkpoint: (List<VoiceBenchObservation>) -> Unit,
    languageTag: String = "en-US",
    thermalStatus: () -> Int? = { null },
    calibrationWarmup: Boolean = false,
    readFixture: (String) -> ByteArray,
    protocolFactory: (Long) -> VoiceRecognitionBenchmarkProtocol = { VoiceRecognitionBenchmarkProtocol(it) }
): List<VoiceBenchObservation> {
    decoder.configure(style)
    decoder.configureRecognition(languageTag, emptyList())
    val rows = mutableListOf<VoiceBenchObservation>()
    val schedule = (if (calibrationWarmup) listOf("warmup/ready" to "ready") else emptyList()) + voiceBenchRecordings.keys.map { it to it }
    for ((fixture, id) in schedule) {
        val reference = voiceBenchRecordings.getValue(id)
        if (!active()) throw CancellationException("Voice benchmark stopped")
        val data = readFixture(id)
        if (!voiceBenchFixtureVerified(id, data)) {
            rows += VoiceBenchObservation(fixture, VoiceBenchStatus.FAILED, "FIXTURE_DIGEST_MISMATCH")
            checkpoint(rows.toList())
            continue
        }
        val protocol = protocolFactory(data.size.toLong())
        var final = ""
        var failure: String? = null
        var revisions: VoiceRecognitionRevisions? = null
        fun admit(text: String, transcript: VoiceRecognitionTranscript?, terminalAllowed: Boolean): VoiceRecognitionTranscript? {
            if (transcript == null || !protocol.hasAcceptedInput) return null
            if (transcript.text != text || transcript.isFinal && !terminalAllowed) {
                failure = "RECOGNITION_TRANSCRIPT_EVENT_MISMATCH"
                return null
            }
            if (transcript.sampleRate != 16_000 || transcript.audioFrames > protocol.acceptedInputFrames) {
                failure = "RECOGNITION_TRANSCRIPT_INPUT_MISMATCH"
                return null
            }
            val ledger = revisions ?: VoiceRecognitionRevisions(transcript.streamId).also { revisions = it }
            return ledger.accept(transcript.trimmed())
        }
        val thermalBefore = thermalStatus()
        var hottest = thermalBefore
        var thermalSamples = 1
        var unknownThermalSamples = if (thermalBefore == null) 1 else 0
        var thermalReadAt = System.nanoTime()
        fun thermalLimit(): Boolean {
            val now = System.nanoTime()
            if (now - thermalReadAt >= 500_000_000) {
                val observed = thermalStatus()
                thermalSamples++
                if (observed == null) unknownThermalSamples++ else hottest = maxOf(hottest ?: observed, observed)
                thermalReadAt = now
            }
            return (hottest ?: -1) >= PowerManager.THERMAL_STATUS_MODERATE
        }
        if (thermalLimit()) {
            rows += VoiceBenchObservation(
                fixture,
                VoiceBenchStatus.INTERRUPTED,
                "THERMAL_LIMIT",
                metrics = mapOf("maximumObservedThermalStatus" to requireNotNull(hottest).toDouble()),
                measurementScope = protocol.measurementScope
            )
            checkpoint(rows.toList())
            break
        }
        try {
            decoder.start(directory) { event ->
                when (event) {
                    is LocalSpeechEvent.Partial -> {
                        val admitted = admit(event.text, event.transcript, false)
                        if (event.transcript == null || admitted != null) protocol.partial(admitted?.text ?: event.text)
                    }
                    is LocalSpeechEvent.Final -> {
                        val admitted = admit(event.text, event.transcript, true)
                        val text = admitted?.text ?: event.text
                        if (protocol.hasAcceptedInput && text.isNotBlank() && (event.transcript == null || admitted != null)) {
                            if (admitted?.isFinal == false) {
                                protocol.endpoint(text)
                            } else {
                                final = text
                                protocol.final(text, admitted)
                            }
                        }
                    }
                    is LocalSpeechEvent.SpeechEnd -> protocol.speechEnded(event.elapsedAgoMs)
                    is LocalSpeechEvent.Failure -> failure = event.code
                    else -> Unit
                }
            }
            protocol.prepared()
            var offset = 0
            while (offset < data.size + 16_000 && failure == null) {
                if (thermalLimit()) {
                    failure = "THERMAL_LIMIT"
                    break
                }
                val count = minOf(640, data.size + 16_000 - offset)
                val samples = FloatArray(count) { i -> data.getOrNull(offset + i)?.let(::decodeVoiceBenchSample) ?: 0f }
                protocol.feed(count, active) { decoder.accept(samples) }
                offset += count
            }
            if (failure == null) protocol.finish(active, decoder::finish)
            val thermalAfter = thermalStatus()
            thermalSamples++
            if (thermalAfter == null) unknownThermalSamples++
            thermalAfter?.let { hottest = maxOf(hottest ?: it, it) }
            if ((hottest ?: -1) >= PowerManager.THERMAL_STATUS_MODERATE) failure = "THERMAL_LIMIT"
            val words = voiceBenchWords(reference)
            val errors = voiceBenchWordErrors(words, voiceBenchWords(final))
            rows += VoiceBenchObservation(
                fixture,
                when {
                    failure == "THERMAL_LIMIT" -> VoiceBenchStatus.INTERRUPTED
                    failure == null && final.isNotBlank() -> VoiceBenchStatus.MEASURED
                    else -> VoiceBenchStatus.FAILED
                },
                reason = failure ?: "NO_FINAL_TRANSCRIPT".takeIf { final.isBlank() },
                metrics = (
                    if (failure == null) {
                        protocol.metrics() + mapOf(
                            "referenceWords" to words.size.toDouble(),
                            "wordErrors" to errors.toDouble(),
                            "wordErrorRate" to errors.toDouble() / words.size
                        )
                    } else {
                        mapOf("recognitionProtocolVersion" to VoiceRecognitionBenchmarkProtocol.VERSION.toDouble())
                    }
                    ) + buildMap {
                    thermalBefore?.let { put("thermalBefore", it.toDouble()) }
                    thermalAfter?.let { put("thermalAfter", it.toDouble()) }
                    hottest?.let { put("maximumObservedThermalStatus", it.toDouble()) }
                    put("thermalSampleCount", thermalSamples.toDouble())
                    put("unknownThermalSampleCount", unknownThermalSamples.toDouble())
                },
                measurementScope = protocol.measurementScope,
                metadata = decoder.executionEvidence().entries.take(32).associate { (key, value) -> key.take(128) to value.take(1_024) } + mapOf(
                    "thermalScope" to "OBSERVED_STATUS_SAMPLES_NOT_SUSTAINED_DEVICE_QUALIFICATION",
                    "recognitionFixtureId" to id,
                    "recognitionFixtureSha256" to voiceBenchRecordingHashes.getValue(id),
                    "recognitionWarmup" to (fixture != id).toString()
                )
            )
        } finally {
            decoder.releaseStream()
        }
        checkpoint(rows.toList())
        if (failure == "THERMAL_LIMIT") break
    }
    return rows
}

/** Query only Android's local recognizer. Capability is observed independently of transcription accuracy. */
internal suspend fun voiceBenchSystemRecognition(context: Context, active: () -> Boolean, interrupt: AtomicReference<(() -> Unit)?>): List<VoiceBenchObservation> = withContext(Dispatchers.Main.immediate) {
    val service = AndroidRecognitionService(context)
    if (!service.available(true)) return@withContext listOf(VoiceBenchObservation.unavailable("android", "NO_ON_DEVICE_RECOGNIZER"))
    SpeechLanguages.synthesis.map { language ->
        if (!active()) throw CancellationException("Voice benchmark stopped")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        val support = voiceBenchRecognitionSupport(active, interrupt) { voiceBenchRecognitionBinding(service, intent) }
        val status = when (support.qualification?.status) {
            RecognitionSupportStatus.SUPPORTED -> VoiceBenchStatus.MEASURED
            RecognitionSupportStatus.FAILED -> VoiceBenchStatus.FAILED
            else -> VoiceBenchStatus.UNAVAILABLE
        }
        VoiceBenchObservation(language.tag, status, support.reason, support.metrics, "LOCAL_RECOGNIZER_CAPABILITY_NOT_TRANSCRIPT_ACCURACY")
    }
}
