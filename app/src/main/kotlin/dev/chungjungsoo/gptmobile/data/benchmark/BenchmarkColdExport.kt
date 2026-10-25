package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.JsonElement

/**
 * Analysis-only input for selection/cohort/performance comparisons. Never serialize this as a
 * canonical run or pass it to semantic, general metric, coverage or legacy-grade derivation.
 * Those paths read one complete original run through BenchmarkExportSource.completeRuns.
 */
internal fun benchmarkExportComparisonRun(run: BenchmarkRun): BenchmarkRun =
    run.copy(
        samples =
        run.samples.map { sample ->
            sample.copy(canonicalAnswer = null, finalAnswer = null, preview = "", outputParts = emptyList())
        },
        caseManifest = null,
        readiness =
        run.readiness?.let { readiness ->
            readiness.copy(
                checks = readiness.checks.map { it.copy(sample = null) },
                probes = readiness.probes.map { it.copy(sample = null) }
            )
        }
    )

/**
 * The legacy preference already owns one String. Walk its array without building a second
 * whole-history JSON tree or UTF-8 copy; each original value is independently decoded intact.
 */
internal fun benchmarkLegacyExportRecords(
    raw: String,
    checkActive: () -> Unit = {}
): Sequence<JsonElement> =
    sequence {
        var offset = 0

        fun whitespace() {
            while (offset < raw.length && raw[offset] in " \t\r\n") offset++
        }
        whitespace()
        require(offset < raw.length && raw[offset++] == '[') { "The legacy benchmark history is not a JSON array." }
        whitespace()
        if (offset < raw.length && raw[offset] == ']') {
            offset++
        } else {
            while (true) {
                checkActive()
                whitespace()
                val start = offset
                var depth = 0
                var quoted = false
                var escaped = false
                while (offset < raw.length) {
                    if (offset and 8191 == 0) checkActive()
                    val character = raw[offset]
                    if (!quoted && depth == 0 && (character == ',' || character == ']')) break
                    if (quoted) {
                        if (escaped) {
                            escaped = false
                        } else if (character == '\\') {
                            escaped = true
                        } else if (character == '"') {
                            quoted = false
                        }
                    } else {
                        when (character) {
                            '"' -> quoted = true
                            '{', '[' -> depth++
                            '}', ']' -> depth--
                        }
                        require(depth >= 0) { "The legacy benchmark history has an unmatched JSON boundary." }
                    }
                    offset++
                }
                require(offset > start && depth == 0 && !quoted) { "The legacy benchmark history has an incomplete record." }
                yield(benchmarkExportJson.parseToJsonElement(raw.substring(start, offset)))
                require(offset < raw.length) { "The legacy benchmark history is incomplete." }
                if (raw[offset++] == ']') break
                whitespace()
                require(offset < raw.length && raw[offset] != ']') { "The legacy benchmark history has a trailing comma." }
            }
        }
        whitespace()
        require(offset == raw.length) { "The legacy benchmark history has trailing content." }
    }
