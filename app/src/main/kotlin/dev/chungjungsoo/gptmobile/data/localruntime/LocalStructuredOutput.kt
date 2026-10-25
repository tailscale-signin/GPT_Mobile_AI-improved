package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A different decoding mode is a different measurement, never a repair of returned text. */
@Serializable
enum class LocalStructuredOutputMode { NATIVE_JSON_SAFE_V1, PROMPT_JSON_VERIFIED_V1 }

class LocalStructuredOutputFailure(
    val code: String,
    detail: String,
    cause: Throwable? = null
) : IOException("$code: ${safeQnnDetail(detail)}", cause) {
    init {
        require(code in CODES)
    }

    companion object {
        const val CONSTRAINED_DECODING_FAILURE = "CONSTRAINED_DECODING_FAILURE"
        const val MALFORMED = "STRUCTURED_OUTPUT_MALFORMED"
        const val SCHEMA_MISMATCH = "STRUCTURED_OUTPUT_SCHEMA_MISMATCH"
        const val VALIDATION_LIMIT = "STRUCTURED_OUTPUT_VALIDATION_LIMIT"
        const val MODE_UNSUPPORTED = "STRUCTURED_OUTPUT_MODE_UNSUPPORTED"
        const val COMPATIBILITY_CHANGED = "STRUCTURED_OUTPUT_COMPATIBILITY_CHANGED"
        val CODES = setOf(CONSTRAINED_DECODING_FAILURE, MALFORMED, SCHEMA_MISMATCH, VALIDATION_LIMIT, MODE_UNSUPPORTED, COMPATIBILITY_CHANGED)
    }
}

internal fun isConstrainedDecodingFailure(error: Throwable): Boolean {
    if (error is LocalStructuredOutputFailure) return error.code == LocalStructuredOutputFailure.CONSTRAINED_DECODING_FAILURE
    val detail = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
        .take(8).mapNotNull { it.message?.take(2048) }.joinToString(" ").lowercase()
    return "constrained_decoding_failure" in detail ||
        ("parser error" in detail && ("grammar" in detail || "commit token" in detail)) ||
        ("token" in detail && ("doesn't satisfy the grammar" in detail || "does not satisfy the grammar" in detail)) ||
        (
            ("constraint" in detail || "grammar" in detail || "llguidance" in detail) &&
                ("compute mask" in detail || "compute_mask" in detail || "no valid token" in detail)
            )
}

internal fun structuredNativeFailure(error: Throwable): Throwable = when {
    error is VirtualMachineError || error is ThreadDeath || error is kotlinx.coroutines.CancellationException -> error
    error is LocalStructuredOutputFailure -> error
    isConstrainedDecodingFailure(error) -> LocalStructuredOutputFailure(
        LocalStructuredOutputFailure.CONSTRAINED_DECODING_FAILURE,
        error.message ?: "The native decoder rejected a token under the active grammar",
        error
    )
    else -> error
}

/** The callback delivers deltas. Preserve them byte-for-byte; never insert or strip a brace. */
internal class LocalStructuredOutputCapture(schema: String, private val maximumCharacters: Int = 1024 * 1024) {
    private val objectSchema = Json.parseToJsonElement(schema) as JsonObject
    private val raw = StringBuilder()
    private var exceeded = false

    @Synchronized
    fun append(delta: String) {
        if (exceeded) return
        if (delta.length > maximumCharacters - raw.length) {
            exceeded = true
            return
        }
        raw.append(delta)
    }

    @Synchronized
    fun failure(): LocalStructuredOutputFailure? {
        if (exceeded) return LocalStructuredOutputFailure(LocalStructuredOutputFailure.VALIDATION_LIMIT, "The response exceeds the bounded validation buffer; raw streamed output is retained")
        val parsed = try {
            Json.parseToJsonElement(raw.toString())
        } catch (_: IllegalArgumentException) {
            return LocalStructuredOutputFailure(LocalStructuredOutputFailure.MALFORMED, "The native response is not one complete strict JSON value; raw streamed output is retained")
        }
        if (parsed !is JsonObject) return LocalStructuredOutputFailure(LocalStructuredOutputFailure.SCHEMA_MISMATCH, "The final-response contract requires a JSON object")
        val errors = ToolArgumentValidator.errors(parsed, objectSchema)
        return errors.takeIf { it.isNotEmpty() }?.let {
            // Values and schema property names can contain user data. Keep them out of logs.
            LocalStructuredOutputFailure(LocalStructuredOutputFailure.SCHEMA_MISMATCH, "The JSON object failed ${it.size} declared schema checks; raw streamed output is retained")
        }
    }
}

/** A worker error code retains its category across Binder instead of becoming a tool limit. */
internal fun localRuntimeErrorCode(error: Throwable?): String? {
    val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    causes.filterIsInstance<LocalStructuredOutputFailure>().firstOrNull()?.let { return it.code }
    causes.filterIsInstance<QnnRuntimeFailure>().firstOrNull()?.let { return it.errorCode }
    return causes.filterIsInstance<LocalToolLimitException>().firstOrNull()?.code
}

internal fun localRuntimeFailureFromRemote(code: String?, detail: String): Throwable? = when {
    code == null -> null
    code in LocalStructuredOutputFailure.CODES -> LocalStructuredOutputFailure(code, detail)
    QnnFailureCode.entries.any { it.name == code } -> QnnRuntimeFailure(QnnRuntimeStage.DECODE, QnnFailureCode.valueOf(code), detail)
    else -> LocalToolLimitException(code)
}
