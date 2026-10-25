package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkSemanticStatus { GRADED, UNPARSEABLE, INCOMPLETE, UNKNOWN }

@Serializable
data class BenchmarkIntelligenceQuestion(
    val id: String,
    val familyId: String,
    val domain: String,
    val prompt: String,
    val expected: String
) {
    val contentDigest: String get() = benchmarkHash(listOf(domain, prompt, expected).joinToString("\u0000"))
}

/** Null answers are ungraded. Historical outcome/maps remain unchanged beside this observation. */
@Serializable
data class BenchmarkIntelligenceEvidence(
    val status: BenchmarkSemanticStatus,
    val plannedQuestions: Int,
    val gradedQuestions: Int,
    val correctQuestions: Int?,
    val formatCompliant: Boolean?,
    val parseable: Boolean?,
    val perQuestion: Map<String, Boolean?>,
    val questionDomains: Map<String, String>,
    val questionDigests: Map<String, String>,
    val questionFamilies: Map<String, String>,
    val fixtureVersion: Int,
    val reason: String? = null,
    val policy: String = "intelligence-semantic-evidence-v1"
)

@Serializable
data class BenchmarkIntelligenceSummary(
    val plannedQuestions: Int,
    val gradedQuestions: Int,
    val correctQuestions: Int?,
    val unknownQuestions: Int,
    val accuracyPercent: Double?,
    val domains: Map<String, Double>,
    val domainGradedQuestions: Map<String, Int>,
    val formatPassed: Int,
    val formatObserved: Int,
    val parseableCases: Int,
    val parseabilityObserved: Int,
    val uniqueQuestions: Int,
    val uniqueQuestionFamilies: Int,
    val complete: Boolean,
    val fixtureVersions: Set<Int>,
    val policy: String = "intelligence-semantic-summary-v1"
)

internal fun benchmarkIntelligenceQuestions(test: BenchmarkCase, version: Int): List<BenchmarkIntelligenceQuestion> {
    if (test.intelligenceQuestions.isNotEmpty()) return test.intelligenceQuestions
    if (test.category != IntelligenceBenchmark.CATEGORY) return emptyList()
    if (!test.id.startsWith("intelligence-batch-")) return listOf(BenchmarkIntelligenceQuestion("v$version:${test.id}", test.id, test.workload, test.prompt, test.expected.orEmpty()))
    val expected = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(test.expected.orEmpty()) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return emptyList()
    return expected.map { (domain, value) ->
        BenchmarkIntelligenceQuestion("v$version:${test.id}:$domain", "${test.id}:$domain", domain, test.prompt.substringBefore("\nReturn only"), (value as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
    }
}

internal fun benchmarkIntelligenceEvidence(test: BenchmarkCase, raw: String?, complete: Boolean, fixtureVersion: Int = test.fixtureSetVersion ?: IntelligenceBenchmark.VERSION): BenchmarkIntelligenceEvidence? {
    val questions = benchmarkIntelligenceQuestions(test, fixtureVersion)
    if (questions.isEmpty()) return null
    val batch = test.id.startsWith("intelligence-batch-")
    val grade = if (raw != null && batch) IntelligenceBenchmark.evaluateBatch(test, raw, fixtureVersion) else null
    val objectAnswer = if (raw != null && batch) {
        val trimmed = raw.trim()
        val fenced = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\z", RegexOption.IGNORE_CASE).matchEntire(trimmed)?.groupValues?.get(1)
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(fenced ?: trimmed) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    } else {
        null
    }
    val parseable = if (batch) grade?.parseable else raw?.isNotBlank()
    val format = if (batch) grade?.formatCompliant else raw?.let { it.isNotBlank() && '\n' !in it.trim() && !it.trim().startsWith("Answer:", true) }
    val preliminaryStatus = when {
        raw == null -> BenchmarkSemanticStatus.UNKNOWN
        !complete -> BenchmarkSemanticStatus.INCOMPLETE
        parseable != true -> BenchmarkSemanticStatus.UNPARSEABLE
        else -> BenchmarkSemanticStatus.GRADED
    }
    val answers = questions.associate { question ->
        question.id to if (preliminaryStatus == BenchmarkSemanticStatus.GRADED) {
            if (batch) grade?.answers?.get(question.domain)?.takeIf { objectAnswer?.get(question.domain) is kotlinx.serialization.json.JsonPrimitive } else IntelligenceBenchmark.matches(raw.orEmpty(), question.expected)
        } else {
            null
        }
    }
    val graded = answers.values.count { it != null }
    val status = if (preliminaryStatus == BenchmarkSemanticStatus.GRADED && graded < questions.size) BenchmarkSemanticStatus.INCOMPLETE else preliminaryStatus
    return BenchmarkIntelligenceEvidence(
        status, questions.size, graded, answers.values.count { it == true }.takeIf { graded > 0 }, format, parseable, answers,
        questions.associate { it.id to it.domain }, questions.associate { it.id to it.contentDigest }, questions.associate { it.id to it.familyId }, fixtureVersion,
        reason = when (status) {
            BenchmarkSemanticStatus.UNKNOWN -> "RAW_ANSWER_OR_PARSEABILITY_NOT_RECORDED"
            BenchmarkSemanticStatus.UNPARSEABLE -> "MALFORMED_OUTPUT_SEMANTICS_UNGRADED"
            BenchmarkSemanticStatus.INCOMPLETE -> if (complete) "MISSING_OR_NON_SCALAR_ANSWERS_UNGRADED" else "EXECUTION_DID_NOT_COMPLETE"
            BenchmarkSemanticStatus.GRADED -> null
        }
    )
}

/** Interpret retained evidence without changing old grades, hashes, answers, or identifiers. */
internal fun benchmarkIntelligenceEvidence(run: BenchmarkRun): List<BenchmarkIntelligenceEvidence> {
    val plan = benchmarkSuiteForRun(run).filter { it.category == IntelligenceBenchmark.CATEGORY }
    return plan.mapNotNull { test ->
        val sample = run.samples.singleOrNull { it.testId == test.id }
        val version = test.fixtureSetVersion ?: sample?.intelligenceFixtureVersion ?: if (run.suiteVersion >= 7) 3 else 2
        sample?.intelligenceSemantics?.let { return@mapNotNull it }
        val raw = sample?.canonicalAnswer ?: sample?.preview?.takeIf { it.isNotEmpty() && sample.outputCharacters == it.length && !sample.answerTruncated }
        val retained = raw?.takeIf { sample?.answerLength?.let { length -> length == it.length } != false && sample?.answerHash?.let { hash -> hash == benchmarkHash(it) } != false }
        val evidence = benchmarkIntelligenceEvidence(test, retained, sample?.completed == true, version) ?: return@mapNotNull null
        if (retained != null || sample == null || !sample.completed) return@mapNotNull evidence
        // A stored parseability observation may authenticate explicit historical
        // per-domain grades. A map of false values alone cannot prove parsing.
        if (sample.intelligenceParseable == true && sample.intelligenceAnswers.isNotEmpty()) {
            val answers = evidence.questionDomains.mapValues { (_, domain) -> sample.intelligenceAnswers[domain] }
            evidence.copy(status = BenchmarkSemanticStatus.GRADED, gradedQuestions = answers.values.count { it != null }, correctQuestions = answers.values.count { it == true }, perQuestion = answers, parseable = true, formatCompliant = sample.intelligenceFormatCompliant, reason = null)
        } else if (!test.id.startsWith("intelligence-batch-")) {
            evidence.copy(status = BenchmarkSemanticStatus.GRADED, gradedQuestions = 1, correctQuestions = if (sample.outcome == BenchmarkOutcome.PASSED) 1 else 0, perQuestion = evidence.perQuestion.mapValues { sample.outcome == BenchmarkOutcome.PASSED }, reason = "RETAINED_INDIVIDUAL_FIXTURE_GRADE")
        } else {
            evidence.copy(status = if (sample.intelligenceParseable == false) BenchmarkSemanticStatus.UNPARSEABLE else BenchmarkSemanticStatus.UNKNOWN, parseable = sample.intelligenceParseable, formatCompliant = sample.intelligenceFormatCompliant, reason = if (sample.intelligenceParseable == false) "MALFORMED_OUTPUT_SEMANTICS_UNGRADED" else evidence.reason)
        }
    }
}

internal fun benchmarkIntelligenceSummary(run: BenchmarkRun): BenchmarkIntelligenceSummary {
    val evidence = benchmarkIntelligenceEvidence(run)
    val planned = evidence.sumOf { it.plannedQuestions }
    val graded = evidence.sumOf { it.gradedQuestions }
    val correct = evidence.sumOf { it.correctQuestions ?: 0 }.takeIf { graded > 0 }
    val domains = evidence.flatMap { it.questionDomains.values }.distinct()
    val domainQuestions = domains.associateWith { domain -> evidence.flatMap { item -> item.perQuestion.filterKeys { item.questionDomains[it] == domain }.values } }
    val complete = planned > 0 && planned == graded
    return BenchmarkIntelligenceSummary(
        planned, graded, correct, planned - graded, if (complete) 100.0 * requireNotNull(correct) / planned else null,
        domainQuestions.mapNotNull { (domain, answers) -> if (answers.isNotEmpty() && answers.all { it != null }) domain to (100.0 * answers.count { it == true } / answers.size) else null }.toMap(),
        domainQuestions.mapValues { (_, answers) -> answers.count { it != null } },
        evidence.count { it.formatCompliant == true }, evidence.count { it.formatCompliant != null }, evidence.count { it.parseable == true }, evidence.count { it.parseable != null },
        evidence.flatMap { it.questionDigests.values }.distinct().size, evidence.flatMap { it.questionFamilies.values }.distinct().size, complete, evidence.map { it.fixtureVersion }.toSet()
    )
}

internal fun benchmarkIntelligenceScore(run: BenchmarkRun): IntelligenceScore {
    val summary = benchmarkIntelligenceSummary(run)
    return IntelligenceScore(summary.accuracyPercent, summary.gradedQuestions, summary.plannedQuestions, summary.domains, version = summary.fixtureVersions.singleOrNull() ?: 0, provisional = run.mode == BenchmarkMode.QUICK || !summary.complete, questionsPerDomain = if (summary.domains.isNotEmpty()) summary.plannedQuestions / summary.domainGradedQuestions.size else 0, uniqueQuestions = summary.uniqueQuestions)
}

@Serializable
data class BenchmarkDiagnosticScenarioSummary(val passed: Int, val graded: Int, val planned: Int, val passPercent: Double?, val population: String = "non-intelligence diagnostic scenarios; not per-question intelligence accuracy")

internal fun benchmarkDiagnosticScenarioSummary(run: BenchmarkRun): BenchmarkDiagnosticScenarioSummary {
    val plan = benchmarkSuiteForRun(run).filter { it.category !in setOf(IntelligenceBenchmark.CATEGORY, "speed", "performance") }
    val results = plan.mapNotNull { test -> run.samples.singleOrNull { it.testId == test.id }?.takeIf { it.completed } }
    val passed = results.count { it.outcome == BenchmarkOutcome.PASSED }
    return BenchmarkDiagnosticScenarioSummary(passed, results.size, plan.size, if (results.isNotEmpty()) 100.0 * passed / results.size else null)
}
