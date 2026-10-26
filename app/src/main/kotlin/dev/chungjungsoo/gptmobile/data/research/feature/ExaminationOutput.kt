package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Accept a single fenced JSON artifact, never prose containing an embedded object. */
internal fun examinationJson(reply: String): String {
    val trimmed = reply.trim().removePrefix("\uFEFF").trim()
    val lines = trimmed.lines()
    return if (lines.size >= 3 && lines.first().lowercase() in setOf("```json", "```") && lines.last() == "```") {
        lines.drop(1).dropLast(1).joinToString("\n").trim()
    } else {
        trimmed
    }
}

/** Fix only a uniquely located, verbatim quote within the inspected chunk. All grounding gates still run. */
internal fun locateExaminationQuotes(reply: String, offset: Int, chunk: String): String {
    val root = Json.parseToJsonElement(examinationJson(reply)) as? JsonObject ?: error("Appraisal requires an object")
    val passages = root["passages"] as? JsonArray ?: error("Passages missing")
    require(passages.size <= 24) { "Too many appraisal passages" }
    val located = passages.map { element ->
        val row = element as? JsonObject ?: error("Malformed passage")
        val quote = (row["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        require(quote.length in 1..2000) { "Passage quote is empty or oversized" }
        val given = (row["start"] as? JsonPrimitive)?.intOrNull
        if (given != null && given >= offset && given - offset <= chunk.length - quote.length && chunk.regionMatches(given - offset, quote, 0, quote.length)) {
            row
        } else {
            val found = chunk.indexOf(quote)
            require(found >= 0 && chunk.indexOf(quote, found + 1) < 0) { "Passage quote is absent or ambiguous in the inspected chunk" }
            JsonObject(row + ("start" to JsonPrimitive(offset + found)))
        }
    }
    return JsonObject(root + ("passages" to JsonArray(located))).toString()
}

internal fun examinationFailureDetail(failure: Throwable, includeFrames: Boolean = true): String = generateSequence(failure) { it.cause }
    .take(5).joinToString(" <- ") { "${it.javaClass.simpleName}: ${DiagnosticRedactor.redact(it.message.orEmpty().substringBefore("JSON input:")).replace('\n', ' ').take(500)}${if (includeFrames) " at " + it.stackTrace.firstOrNull { frame -> frame.className.startsWith("dev.chungjungsoo.gptmobile") } else ""}" }

/** Retry invalid structure once with concrete validation feedback; transport and cancellation keep their own semantics. */
internal suspend fun <T> validatedExaminationOutput(
    prompt: String,
    generate: suspend (String, Int) -> String,
    invalid: (Int, String, Exception) -> Unit,
    validate: (String) -> T
): T {
    var feedback = ""
    for (attempt in 1..2) {
        val reply = generate(prompt + feedback, attempt)
        try {
            return validate(examinationJson(reply))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            invalid(attempt, reply, failure)
            if (attempt == 2) throw IllegalStateException("${if (reply.isBlank()) "EXAMINATION_OUTPUT_EMPTY" else "EXAMINATION_OUTPUT_INVALID"}: ${examinationFailureDetail(failure, includeFrames = false)}; failed stage stopped after two attempts; change source, settings or Examiner before retrying", failure)
            feedback = "\nYour previous artifact failed validation: ${examinationFailureDetail(failure, includeFrames = false)}. Return a corrected complete JSON object using only the supplied source. Quotes must be exact, fields extractive, unknown values null, and every cited ID must exist. Do not add commentary or change the source evidence."
        }
    }
    error("Examination validation did not execute")
}
