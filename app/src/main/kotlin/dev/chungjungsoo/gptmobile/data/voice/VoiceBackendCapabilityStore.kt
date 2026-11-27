package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import dev.melo.kokoro.TimedKokoro
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

/** Small, device/model/runtime-scoped receipts. Called only by the synthesis/I/O owner. */
internal class VoiceBackendCapabilityStore(
    context: Context,
    private val runtimeConfiguration: String = "htp=balanced;context=1",
    private val modelSha256: String = KokoroModelPack.MODEL_SHA256
) {
    init {
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
    }
    private val preferences = context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE)
    private val fixtureHash = VoiceSpeechWorkload.fixtureHash(VoiceSpeechBenchmarkWorkload.UTTERANCE)
    val deviceKey = digest("${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}|${Build.VERSION.SDK_INT}")

    fun revision(): Long = synchronized(lock) { runCatching { preferences.getLong("revision", 0) }.getOrDefault(0) }

    fun comparisonScope(lexicon: String, threads: Int, voice: KokoroVoice = KokoroVoice.HEART, speechRate: Float = 1f): String {
        require(threads in VoiceCpuSchedulingPolicy.supported && speechRate.isFinite() && speechRate in .5f..2f)
        return digest("${VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL}|$fixtureHash|${TimedKokoro.RUNTIME_ID}|$runtimeConfiguration|$modelSha256|$deviceKey|$lexicon|$threads|${voice.name}|${voice.speakerId}|${speechRate.toBits()}")
    }

    fun key(backend: VoiceSpeechBackend, lexicon: String, threads: Int, voice: KokoroVoice = KokoroVoice.HEART, speechRate: Float = 1f): String =
        digest("${comparisonScope(lexicon, threads, voice, speechRate)}|${backend.name}")

    fun read(key: String, now: Long): VoiceBackendCapability? = synchronized(lock) {
        records().firstOrNull { it.key == key && it.current(now) && it.cacheable }
    }

    fun save(record: VoiceBackendCapability) = synchronized(lock) {
        if (!record.cacheable) return@synchronized
        // An ordinary successful phrase establishes support, never a fresh performance ranking.
        if (record.failure == null &&
            record.scoreMs == null &&
            records().any {
                it.key == record.key && it.current(record.observedAtMillis) && it.failure == null && it.scoreMs != null
            }
        ) {
            return@synchronized
        }
        val values = records().filter { it.key != record.key && it.current(record.observedAtMillis) }
            .plus(record).sortedByDescending { it.observedAtMillis }.take(24)
        write(values)
    }

    /** One completed Recheck publishes its paired cohort together, never half a comparison. */
    fun saveComparison(comparison: List<VoiceBackendCapability>) = synchronized(lock) {
        if (comparison.isEmpty()) return@synchronized
        require(comparison.size <= 3 && comparison.map { it.key }.distinct().size == comparison.size)
        require(comparison.map { it.backend }.distinct().size == comparison.size)
        require(comparison.all { it.cacheable && it.protocol == VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL })
        require(comparison.map { it.comparisonId }.distinct().singleOrNull()?.matches(Regex("[0-9a-f]{32}")) == true)
        require(comparison.map { it.comparisonScope }.distinct().singleOrNull()?.matches(Regex("[0-9a-f]{64}")) == true)
        require(comparison.map { it.speakerId }.distinct().size == 1 && comparison.map { it.speechRate }.distinct().size == 1)
        val keys = comparison.mapTo(mutableSetOf()) { it.key }
        val at = comparison.maxOf { it.observedAtMillis }
        val values = records().filter { it.key !in keys && it.current(at) }
            .plus(comparison).sortedByDescending { it.observedAtMillis }.take(24)
        write(values)
    }

    private fun write(values: List<VoiceBackendCapability>, revision: Long? = null) {
        val encoded = JSONArray()
        values.forEach {
            encoded.put(
                JSONObject().put("key", it.key).put("backend", it.backend.name)
                    .put("at", it.observedAtMillis).put("samples", JSONArray(it.sampleMs))
                    .put("accelerated", it.verifiedAccelerator).put("failure", it.failure?.name)
                    .put("stage", it.failureStage?.name).put("native", it.nativeCode).put("tokens", it.tokenCount)
                    .put("protocol", it.protocol).put("scope", it.comparisonScope)
                    .put("speaker", it.speakerId).put("rate", it.speechRate)
                    .put("inputBytes", it.inputBytes).put("inputByteLimit", it.inputByteLimit).put("tokenLimit", it.tokenLimit)
                    .put("discovery", safeSpeechDiscoverySummary(it.discoverySummary))
                    .put("thermals", JSONArray(it.thermalStatuses))
                    .put("comparisonId", it.comparisonId)
                    .put("audioObservations", JSONArray().also { rows -> it.audioObservations.forEach { observation -> rows.put(JSONObject(speechAudioObservationJson(observation).toString())) } })
            )
        }
        var payload = encoded.toString()
        // Keep whole measured receipts; never truncate an audio timing
        // vector to make it look complete. Older receipts retire first.
        while (payload.length > MAX_STORED_CHARACTERS && encoded.length() > 1) {
            encoded.remove(encoded.length() - 1)
            payload = encoded.toString()
        }
        if (payload.length > MAX_STORED_CHARACTERS) return
        // Cache persistence never interrupts valid speech. A failed write means
        // the next session measures again; it cannot manufacture qualification.
        runCatching {
            val editor = preferences.edit().putString("receipts", payload)
            if (revision != null) editor.putLong("revision", revision)
            editor.commit()
        }
    }

    fun retest(backend: VoiceSpeechBackend) = synchronized(lock) {
        // A new revision deliberately invalidates the resident factory only for
        // explicit retests, rather than on every navigation/recomposition.
        val next = (preferences.getLong("revision", 0) + 1).coerceAtLeast(1)
        val retained = records().filter { backend != VoiceSpeechBackend.AUTO && it.backend != backend }
        write(retained, revision = next)
    }

    private fun records(): List<VoiceBackendCapability> = runCatching {
        val raw = preferences.getString("receipts", null) ?: return emptyList()
        if (raw.length > MAX_STORED_CHARACTERS) return emptyList()
        val entries = JSONArray(raw)
        if (entries.length() > 24) return emptyList()
        (0 until entries.length()).mapNotNull { index ->
            runCatching record@{
                val item = entries.getJSONObject(index)
                val key = item.getString("key").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return@record null
                val protocol = item.optString("protocol").takeIf { it == VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL } ?: return@record null
                val samples = item.getJSONArray("samples")
                if (samples.length() > VoiceMeasuredBackendPolicy.FIXTURE_COUNT) return@record null
                val times = (0 until samples.length()).map { sample ->
                    val value = (samples.get(sample) as? Number)?.toDouble() ?: return@record null
                    if (!value.isFinite() || value !in 1.0..300_000.0 || value != value.toLong().toDouble()) return@record null
                    value.toLong()
                }
                val accelerated = item.get("accelerated") as? Boolean ?: return@record null
                val thermals = item.optJSONArray("thermals") ?: JSONArray()
                if (thermals.length() > VoiceMeasuredBackendPolicy.thermalSampleCount) return@record null
                val temperatures = (0 until thermals.length()).map { sample ->
                    if (thermals.isNull(sample)) {
                        null
                    } else {
                        val value = (thermals.get(sample) as? Number)?.toDouble() ?: return@record null
                        if (!value.isFinite() || value !in 0.0..6.0 || value != value.toInt().toDouble()) return@record null
                        value.toInt()
                    }
                }
                fun integer(name: String, range: IntRange): Int? = if (item.has(name) && !item.isNull(name)) item.getInt(name).takeIf { it in range } else null
                VoiceBackendCapability(
                    key, VoiceSpeechBackend.valueOf(item.getString("backend")), item.getLong("at"),
                    times, accelerated,
                    item.optString("failure").takeIf { it.isNotEmpty() && it != "null" }?.let(VoiceSpeechFailureCode::valueOf),
                    item.optString("stage").takeIf { it.isNotEmpty() && it != "null" }?.let(VoiceSpeechStage::valueOf),
                    if (item.has("native") && !item.isNull("native")) item.getInt("native") else null,
                    integer("tokens", 0..511),
                    item.optString("scope").takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                    protocol,
                    integer("speaker", 0..255),
                    if (item.has("rate") && !item.isNull("rate")) item.getDouble("rate").toFloat().takeIf { it.isFinite() && it in .5f..2f } else null,
                    integer("inputBytes", 0..1_000_000),
                    integer("inputByteLimit", 1..1_000_000),
                    integer("tokenLimit", 1..1_000_000),
                    safeSpeechDiscoverySummary(item.optString("discovery")),
                    temperatures,
                    item.optString("comparisonId").takeIf { it.matches(Regex("[0-9a-f]{32}")) },
                    (item.optJSONArray("audioObservations") ?: JSONArray()).let { observations ->
                        require(observations.length() <= VoiceSpeechWorkload.calibrationPhrases.size)
                        (0 until observations.length()).map { observation -> readSpeechAudioObservation(Json.parseToJsonElement(observations.getJSONObject(observation).toString()).jsonObject) }
                    }
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_STORED_CHARACTERS = 262_144
        private val lock = Any()
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
