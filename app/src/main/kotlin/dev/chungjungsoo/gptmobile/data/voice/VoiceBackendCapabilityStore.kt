package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import dev.melo.kokoro.TimedKokoro
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Small, device/model/runtime-scoped receipts. Called only by the synthesis/I/O owner. */
internal class VoiceBackendCapabilityStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE)
    val deviceKey = digest("${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}|${Build.VERSION.SDK_INT}")

    fun revision(): Long = synchronized(lock) { runCatching { preferences.getLong("revision", 0) }.getOrDefault(0) }

    fun key(backend: VoiceSpeechBackend, lexicon: String, threads: Int): String =
        digest("${VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL}|${TimedKokoro.RUNTIME_ID}|${KokoroModelPack.MODEL_SHA256}|$deviceKey|${backend.name}|$lexicon|$threads")

    fun read(key: String, now: Long): VoiceBackendCapability? = synchronized(lock) {
        records().firstOrNull { it.key == key && it.current(now) }
    }

    fun save(record: VoiceBackendCapability) = synchronized(lock) {
        val values = records().filter { it.key != record.key && it.current(record.observedAtMillis) }
            .plus(record).sortedByDescending { it.observedAtMillis }.take(24)
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
            )
        }
        // Cache persistence never interrupts valid speech. A failed write means
        // the next session measures again; it cannot manufacture qualification.
        runCatching {
            val editor = preferences.edit().putString("receipts", encoded.toString())
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
        if (raw.length > 32_768) return emptyList()
        val entries = JSONArray(raw)
        if (entries.length() > 24) return emptyList()
        (0 until entries.length()).mapNotNull { index ->
            runCatching record@{
                val item = entries.getJSONObject(index)
                val key = item.getString("key").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return@record null
                val samples = item.getJSONArray("samples")
                if (samples.length() > 4) return@record null
                VoiceBackendCapability(
                    key, VoiceSpeechBackend.valueOf(item.getString("backend")), item.getLong("at"),
                    (0 until samples.length()).map { samples.getLong(it) }, item.getBoolean("accelerated"),
                    item.optString("failure").takeIf { it.isNotEmpty() && it != "null" }?.let(VoiceSpeechFailureCode::valueOf),
                    item.optString("stage").takeIf { it.isNotEmpty() && it != "null" }?.let(VoiceSpeechStage::valueOf),
                    if (item.has("native") && !item.isNull("native")) item.getInt("native") else null,
                    if (item.has("tokens") && !item.isNull("tokens")) item.getInt("tokens").takeIf { it in 0..511 } else null
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private val lock = Any()
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
