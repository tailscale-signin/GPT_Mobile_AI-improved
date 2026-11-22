package dev.chungjungsoo.gptmobile.data.voice

import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal object GatewayVoiceProtocol {
    const val PROTOCOL = "kokoro-timed-v1"
    const val FRONTEND = "kokoro-english-v1"
    const val FRONTEND_SYMBOLS_SHA256 = "ef67465bdf5b8a147574207fdf8eaba3d7b913161440f0896bb0dd90c04ac3f6"
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    const val MAX_FRAMES = 30 * 24_000
    private val digest = Regex("[a-f0-9]{64}")

    fun endpoint(base: String): String {
        val parsed = URI(base.trim())
        require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank())
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null)
        require(parsed.port == -1 || parsed.port in 1..65535)
        require(parsed.path.trimEnd('/') in setOf("", "/v1"))
        return "${parsed.scheme}://${parsed.rawAuthority}"
    }

    fun capabilityModel(json: JSONObject): String {
        envelope(json)
        check(json.getBoolean("ready") && json.getInt("sample_rate") == 24_000)
        check(json.getString("execution_provider") == "CUDAExecutionProvider" && json.getInt("executed_cuda_nodes") > 0)
        return json.getString("model_sha256").also { check(digest.matches(it)) }
    }

    fun request(id: String, model: String, tokens: LongArray, words: IntArray, speaker: Int, speed: Float): JSONObject {
        require(digest.matches(model) && tokens.size in 3..511 && tokens.first() == 0L && tokens.last() == 0L)
        require(tokens.all { it in 0..255 } && words.size in 2..128 && words.size % 2 == 0)
        require(speaker in 0..255 && speed.isFinite() && speed in 0.5f..2f)
        var previous = 1
        words.asList().chunked(2).forEach { (start, end) ->
            require(start >= previous && end > start && end < tokens.size)
            previous = end
        }
        return JSONObject().put("protocol", PROTOCOL).put("frontend_id", FRONTEND).put("request_id", id)
            .put("model_sha256", model).put("token_ids", JSONArray(tokens.toList())).put("word_tokens", JSONArray(words.toList()))
            .put("speaker", speaker).put("speed", speed.toDouble())
    }

    fun response(json: JSONObject, id: String, model: String, tokenCount: Int, expectedWords: IntArray): VoicePhraseAudio {
        envelope(json)
        check(json.getString("request_id") == id && json.getString("model_sha256") == model)
        check(json.getInt("sample_rate") == 24_000 && json.getString("execution_provider") == "CUDAExecutionProvider")
        check(json.getInt("executed_cuda_nodes") > 0)
        val times = json.getJSONArray("durations")
        check(times.length() == tokenCount)
        val durations = LongArray(times.length()) { i -> strictLong(times, i).also { check(it in 1..1200) } }
        val words = json.getJSONArray("word_tokens")
        check(words.length() == expectedWords.size)
        check(expectedWords.indices.all { strictLong(words, it) == expectedWords[it].toLong() })
        val encoded = json.getString("pcm_f32le")
        check(encoded.length <= (MAX_FRAMES * 4 + 2) / 3 * 4)
        val bytes = Base64.getDecoder().decode(encoded)
        check(bytes.isNotEmpty() && bytes.size <= MAX_FRAMES * 4 && bytes.size % 4 == 0)
        val count = bytes.size / 4
        check(durations.sum() * 600 == count.toLong())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(count)
        buffer.get(samples)
        check(samples.all(Float::isFinite))
        return VoicePhraseAudio(samples, durations, expectedWords)
    }

    private fun envelope(json: JSONObject) {
        check(json.getString("protocol") == PROTOCOL && json.getString("frontend_id") == FRONTEND)
        check(json.getString("frontend_symbols_sha256") == FRONTEND_SYMBOLS_SHA256 && json.getInt("frames_per_duration") == 600)
    }

    private fun strictLong(array: JSONArray, index: Int): Long {
        val number = array.get(index)
        check(number is Int || number is Long)
        return (number as Number).toLong()
    }
}
