package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Map exact or normalization-equivalent quotations back to original source bytes. All grounding gates still run. */
internal fun locateExaminationQuotes(reply: String, offset: Int, chunk: String): String {
    val root = Json.parseToJsonElement(examinationJson(reply)) as? JsonObject ?: error("Appraisal requires an object")
    val passages = root["passages"] as? JsonArray ?: error("Passages missing")
    require(passages.size <= 24) { "Too many appraisal passages" }
    val locator = ExaminationQuoteLocator(chunk)
    val issues = mutableListOf<ExaminationFieldIssue>()
    val located = examinationRows("passages", passages, issues) { element ->
        val row = element as? JsonObject ?: error("Malformed passage object")
        val quote = (row["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val given = (row["start"] as? JsonPrimitive)?.intOrNull?.let { it - offset }
        val found = locator.locate(quote, given)
        JsonObject(row + mapOf("start" to JsonPrimitive(offset + found.start), "quote" to JsonPrimitive(found.quote)))
    }
    if (issues.isNotEmpty()) throw ExaminationFieldValidationException(issues)
    return JsonObject(root + ("passages" to JsonArray(located))).toString()
}

internal data class ExaminationFieldIssue(val path: String, val detail: String)
internal class ExaminationFieldValidationException(val issues: List<ExaminationFieldIssue>) :
    IllegalArgumentException(
        issues.joinToString("; ") { "${it.path}: ${it.detail}" }
    )
internal inline fun <T> examinationField(path: String, validate: () -> T): T = try {
    validate()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (known: ExaminationFieldValidationException) {
    throw known
} catch (failure: Exception) {
    throw ExaminationFieldValidationException(listOf(ExaminationFieldIssue(path, failure.message ?: "Invalid field")))
}

internal fun <T : Any> examinationRows(section: String, rows: JsonArray, issues: MutableList<ExaminationFieldIssue>, validate: (JsonElement) -> T): List<T> =
    rows.mapIndexedNotNull { index, row ->
        try {
            examinationField("$section[$index]") { validate(row) }
        } catch (failure: ExaminationFieldValidationException) {
            issues += failure.issues
            null
        }
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
    var repairBase: String? = null
    var repairIssues = emptyList<ExaminationFieldIssue>()
    for (attempt in 1..2) {
        val reply = generate(prompt + feedback, attempt)
        try {
            val candidate = repairBase?.let { applyExaminationFieldRepair(it, repairIssues, reply) } ?: examinationJson(reply)
            return validate(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            invalid(attempt, reply, failure)
            if (attempt == 2) throw IllegalStateException("${if (reply.isBlank()) "EXAMINATION_OUTPUT_EMPTY" else "EXAMINATION_OUTPUT_INVALID"}: ${examinationFailureDetail(failure, includeFrames = false)}; failed stage stopped after two attempts; change source, settings or Examiner before retrying", failure)
            val issues = (failure as? ExaminationFieldValidationException)?.issues.orEmpty()
            val targeted = examinationRepairFeedback(reply, issues)
            if (targeted != null) {
                repairBase = examinationJson(reply)
                repairIssues = issues
                feedback = targeted
                continue
            }
            feedback = "\nYour previous artifact failed validation: ${examinationFailureDetail(failure, includeFrames = false)}. Return a corrected complete JSON object using only the supplied source. Quotes must be exact, fields extractive, unknown values null, and every cited ID must exist. Do not add commentary or change the source evidence."
        }
    }
    error("Examination validation did not execute")
}
