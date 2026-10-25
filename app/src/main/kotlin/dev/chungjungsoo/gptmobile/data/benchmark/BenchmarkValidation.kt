package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/** Independent observations; gradePassed always records the accepted fixture policy. */
@Serializable
data class BenchmarkValidationResult(
    val validatorVersion: String,
    val gradePassed: Boolean,
    val factualCorrect: Boolean? = null,
    val unitsCompliant: Boolean? = null,
    val formatCompliant: Boolean? = null,
    val toolExecutionSuccessful: Boolean? = null,
    val protocolCompliant: Boolean? = null,
    val observedWordCount: Int? = null,
    val minimumWords: Int? = null,
    val maximumWords: Int? = null,
    val lengthCompliant: Boolean? = null,
    val requiredConcepts: Map<String, Boolean> = emptyMap(),
    val expectedValue: String? = null,
    val observedValue: String? = null,
    val reasons: List<String> = emptyList(),
    val timingExclusions: List<String> = emptyList(),
    val outputPolicy: String = "STRICT_RAW_ANSWER_V1"
)

@Serializable
enum class BenchmarkOutputChannel { FINAL_ANSWER, PUBLIC_PROGRESS, UNPARSED }

/** Coordinates address the raw stream. Tags and protocol errors remain available for audit. */
@Serializable
data class BenchmarkOutputPart(val channel: BenchmarkOutputChannel, val startUtf16: Int, val endUtf16: Int)

internal data class BenchmarkOutputView(val finalAnswer: String, val parts: List<BenchmarkOutputPart>, val protocolComplete: Boolean, val containsProgress: Boolean)

internal fun benchmarkOutputView(raw: String): BenchmarkOutputView {
    val open = "<progress_update>"
    val close = "</progress_update>"
    val parts = mutableListOf<BenchmarkOutputPart>()
    var position = 0
    var hasProgress = false
    while (position < raw.length) {
        val begin = raw.indexOf(open, position)
        val unexpectedClose = raw.indexOf(close, position)
        if (unexpectedClose >= 0 && (begin < 0 || unexpectedClose < begin)) return BenchmarkOutputView(raw, listOf(BenchmarkOutputPart(BenchmarkOutputChannel.UNPARSED, 0, raw.length)), false, true)
        if (begin < 0) {
            parts += BenchmarkOutputPart(BenchmarkOutputChannel.FINAL_ANSWER, position, raw.length)
            break
        }
        val end = raw.indexOf(close, begin + open.length)
        val nested = raw.indexOf(open, begin + open.length)
        if (end < 0 || nested in (begin + open.length) until end) return BenchmarkOutputView(raw, listOf(BenchmarkOutputPart(BenchmarkOutputChannel.UNPARSED, 0, raw.length)), false, true)
        if (begin > position) parts += BenchmarkOutputPart(BenchmarkOutputChannel.FINAL_ANSWER, position, begin)
        parts += BenchmarkOutputPart(BenchmarkOutputChannel.PUBLIC_PROGRESS, begin + open.length, end)
        hasProgress = true
        position = end + close.length
    }
    return BenchmarkOutputView(parts.filter { it.channel == BenchmarkOutputChannel.FINAL_ANSWER }.joinToString("") { raw.substring(it.startUtf16, it.endUtf16) }.trim(), parts, true, hasProgress)
}

internal fun benchmarkWordCount(answer: String): Int = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*").findAll(answer).count()

internal fun benchmarkValidation(
    test: BenchmarkCase,
    raw: String,
    answer: String,
    expected: String?,
    passed: Boolean,
    toolSuccess: Boolean?,
    timingExclusions: List<String> = emptyList()
): BenchmarkValidationResult {
    val output = if (test.category == "tools") benchmarkOutputView(raw) else BenchmarkOutputView(raw.trim(), emptyList(), true, false)
    val wordCount = benchmarkWordCount(answer)
    val range = when {
        "180 to 220" in test.prompt -> 180..220
        "about 120 words" in test.prompt -> 90..150
        else -> null
    }
    val concepts = if (test.category == "speed" && "180 to 220" in test.prompt) listOf("evaporation", "condensation", "cloud", "precipitation").associateWith { answer.contains(it, true) } else emptyMap()
    val mass = expected?.takeIf { test.workload == "extraction" }?.let { Regex("^([+-]?[0-9]+(?:\\.[0-9]+)?) kg$").matchEntire(it) }
    val number = if (mass != null) Regex("(?<![\\p{L}\\p{N}])[+-]?[0-9]+(?:\\.[0-9]+)?(?![\\p{L}\\p{N}])").find(answer)?.value else null
    val factual = when {
        mass != null -> number?.toBigDecimalOrNull()?.compareTo(mass.groupValues[1].toBigDecimal()) == 0
        test.category == "tools" && expected != null -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(expected)}(?![\\p{L}\\p{N}])").containsMatchIn(answer)
        concepts.isNotEmpty() -> concepts.values.all { it }
        expected != null && test.category == "task" -> answer == expected
        else -> null
    }
    val units = if (mass != null) Regex("(?i)\\bkg\\b").containsMatchIn(answer) else null
    val format = when {
        mass != null || test.category == "tools" || test.category == "task" -> expected?.let { answer == it }
        test.category == "json" -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(answer) }.isSuccess
        test.category == "speed" -> !invalidBenchmarkEnvelope(answer) && (!test.prompt.contains("No heading", true) || !Regex("(?m)^\\s*#{1,6}\\s+").containsMatchIn(answer))
        else -> null
    }
    val protocol = output.protocolComplete && !invalidBenchmarkEnvelope(answer) && (!output.containsProgress || test.outputPolicy == "TYPED_PROGRESS_V1")
    val length = range?.contains(wordCount)
    return BenchmarkValidationResult(
        test.validatorVersion, passed, factual, units, format, toolSuccess, protocol,
        wordCount.takeIf { test.category == "speed" }, range?.first, range?.last, length, concepts,
        expectedValue = expected, observedValue = number ?: answer.take(512),
        reasons = buildList {
            if (length == false) add("WORD_RANGE_UNMET:$wordCount/${range!!.first}-${range.last}")
            concepts.filterValues { !it }.keys.forEach { add("MISSING_CONCEPT:$it") }
            if (factual == false) add("FACTUAL_VALUE_MISMATCH")
            if (units == false) add("UNIT_MISSING_OR_WRONG")
            if (format == false) add("OUTPUT_FORMAT_MISMATCH")
            if (toolSuccess == false) add("TOOL_CONTRACT_UNMET")
            if (!protocol) add("OUTPUT_PROTOCOL_MISMATCH")
        },
        timingExclusions = timingExclusions,
        outputPolicy = test.outputPolicy
    )
}

/** Bounded observation only; repetition never changes a grade or requests a continuation. */
internal fun benchmarkRepetitionDiagnostic(answer: String): String? {
    val sentences = Regex("[^.!?\\n]+[.!?]?").findAll(answer.take(16_384)).map { it.value.trim().lowercase() }.filter { it.length >= 12 }.toList()
    val repeat = sentences.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
    return if (repeat.value >= 4 && repeat.value * repeat.key.length >= answer.length / 3) "REPEATED_PASSAGE:${repeat.value}:${benchmarkHash(repeat.key)}" else null
}
