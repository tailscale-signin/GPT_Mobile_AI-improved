package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class ScoreboardKind { MODEL, DELEGATION }

/** Deliberate public allowlist. Never serialize BenchmarkRun into a submission. */
@Serializable
data class ScoreboardTrial(
    val id: String,
    val outcome: BenchmarkOutcome,
    val durationMs: Long,
    val firstTextMs: Long? = null,
    val speed: Double? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedTokens: Boolean = false,
    val toolCalls: Int = 0,
    val successfulToolCalls: Int = 0,
    val intelligence: Map<String, Boolean> = emptyMap(),
    val reviewerScore: Int? = null,
    val workerCalls: Int = 0,
    val searches: Int = 0,
    val pagesRead: Int = 0,
    val handoffCharacters: Int = 0,
    val primaryInputTokens: Long = 0,
    val primaryOutputTokens: Long = 0,
    val reviewerCalls: Int = 0,
    val reviewerInputTokens: Long = 0,
    val reviewerOutputTokens: Long = 0,
    val outputCapViolations: Int = 0,
    val intelligenceParseable: Boolean? = null,
    val intelligenceFormatCompliant: Boolean? = null
)

@Serializable
data class ScoreboardSubmission(
    val schemaVersion: Int = 1,
    val id: String,
    val kind: ScoreboardKind,
    val plan: BenchmarkMode,
    val suiteVersion: Int,
    val startedAt: Long,
    val provider: String,
    val model: String,
    val primaryProvider: String? = null,
    val primaryModel: String? = null,
    val reviewerProvider: String? = null,
    val reviewerModel: String? = null,
    val trials: List<ScoreboardTrial>,
    val issueNumber: Long? = null,
    val effectiveConfigHash: String? = null,
    val comparisonContextHash: String? = null,
    val measurementVersion: Int? = null,
    val scoringVersion: Int? = null,
    val backend: String? = null,
    val accelerator: String? = null,
    val capabilityManifest: Set<String> = emptySet(),
    val loadRegime: String = "unknown",
    val cacheRegime: String = "unknown",
    val executionPolicy: BenchmarkExecutionPolicy? = null,
    val fixtureRotationSeed: Long? = null,
    val fixtureSetVersion: Int? = null,
    val fixtureManifestHash: String? = null
) {
    // Model IDs may be case-sensitive. Profile names and UIDs are deliberately absent.
    val identity: String get() = "$provider\u0000$model"
    val key: String get() = "$kind|$plan|$suiteVersion|$identity|$effectiveConfigHash|$comparisonContextHash|$backend|$accelerator|${capabilityManifest.sorted()}|${executionPolicy?.name ?: "legacy_unknown"}|$fixtureRotationSeed"
}

@Serializable
data class ScoreboardIndex(val schemaVersion: Int = 1, val updatedAt: Long = 0, val entries: List<ScoreboardSubmission> = emptyList())

data class ScoreboardMetric(val label: String, val value: Double?, val weight: Int = 0, val unit: String = "/100")
data class ScoreboardEntry(val submission: ScoreboardSubmission, val score: Double, val metrics: List<ScoreboardMetric>)

/** Absolute, versioned capability score. Timing is diagnostic, never an intelligence proxy. */
object BenchmarkScoreboard {
    val domains = setOf("Math", "Logic", "Reading", "Planning", "Code reasoning", "Knowledge")
    private val allowedOutcomes = setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED, BenchmarkOutcome.ERROR, BenchmarkOutcome.TIMED_OUT)
    private val providers = ClientType.entries.filterNot { it == ClientType.LITERT_LM }.map { it.name }.toSet()
    const val MODEL_FORMULA = "Task accuracy 35% · JSON 20% · tools 20% · intelligence 15% · completion 10%. Capped by completion. Timing is reported separately."
    const val DELEGATION_FORMULA = "Objective task accuracy 50% · tool calls 15% · research handoff 15% · independent reviewer 20%. Capped by objective task accuracy."

    fun manifest(kind: ScoreboardKind, plan: BenchmarkMode, version: Int = BenchmarkSuiteRegistry.VERSION, rotationSeed: Long = 0): List<BenchmarkCase> =
        if (kind == ScoreboardKind.DELEGATION) {
            delegationBenchmarkSuite(plan)
        } else if (version == 6) {
            BenchmarkSuiteRegistry.historical(plan)
        } else {
            benchmarkSuiteForVersion(version, plan, rotationSeed)
        }

    fun fromRun(run: BenchmarkRun): ScoreboardSubmission? {
        if (!run.finished || run.canceled || run.stoppedReason != null || benchmarkScoreboardIneligibility(run) != null) return null
        val delegation = run.mode == BenchmarkMode.DELEGATION
        if (!delegation && run.suiteVersion !in setOf(8, 9, BenchmarkSuiteRegistry.VERSION)) return null
        val worker = run.samples.mapNotNull { it.delegation }.firstOrNull()
        if ((!delegation && run.local) || (delegation && worker == null)) return null
        val plan = if (delegation) run.delegationPlan ?: return null else run.mode
        if (plan !in setOf(BenchmarkMode.QUICK, BenchmarkMode.FULL)) return null
        if (delegation && (run.delegationSettings?.reviewerEnabled != true || run.delegationSettings?.researchEnabled != true)) return null
        if (delegation && run.samples.mapNotNull { it.delegation }.any { it.workerProvider != worker?.workerProvider || it.workerModel != worker?.workerModel }) return null
        val submission = ScoreboardSubmission(
            schemaVersion = if (run.suiteVersion >= 10 && !delegation) 3 else 2,
            executionPolicy = run.executionPolicy, fixtureRotationSeed = run.fixtureRotationSeed, fixtureSetVersion = run.fixtureSetVersion, fixtureManifestHash = run.fixtureManifestHash,
            effectiveConfigHash = benchmarkHash(run.configKey), comparisonContextHash = benchmarkHash(DynamicScoreEngine.cohort(run)),
            measurementVersion = run.measurementVersion, scoringVersion = run.scoringVersion, backend = run.backend, accelerator = run.accelerator,
            capabilityManifest = run.capabilityManifest, loadRegime = run.loadRegime, cacheRegime = run.cacheRegime,
            id = run.id,
            kind = if (delegation) ScoreboardKind.DELEGATION else ScoreboardKind.MODEL,
            plan = plan,
            suiteVersion = if (delegation) run.delegationSuiteVersion else run.suiteVersion,
            startedAt = run.startedAt,
            provider = (if (delegation) worker!!.workerProvider else run.provider).trim().uppercase(Locale.ROOT),
            model = usedModel(if (delegation) worker!!.workerProvider else run.provider, if (delegation) worker!!.workerModel else run.model),
            primaryProvider = run.provider.takeIf { delegation },
            primaryModel = usedModel(run.provider, run.model).takeIf { delegation },
            reviewerProvider = run.reviewerProvider.takeIf { delegation },
            reviewerModel = run.reviewerModel?.let { usedModel(run.reviewerProvider.orEmpty(), it) }.takeIf { delegation },
            trials = run.samples.map { sample ->
                val helper = sample.delegation
                ScoreboardTrial(
                    id = sample.testId, outcome = sample.outcome, durationMs = sample.durationMs,
                    firstTextMs = if (delegation) helper?.workerFirstTextMs else sample.firstTextMs,
                    speed = if (delegation) helper?.workerDecodeTokensPerSecond else sample.referenceSpeed,
                    inputTokens = helper?.workerInputTokens ?: sample.inputTokens.toLong(),
                    outputTokens = helper?.workerOutputTokens ?: sample.outputTokens.toLong(),
                    estimatedTokens = helper?.workerEstimated ?: sample.estimatedTokens,
                    toolCalls = helper?.fixtureCalls ?: sample.toolCalls,
                    successfulToolCalls = helper?.successfulFixtureCalls ?: sample.successfulToolCalls,
                    intelligence = sample.intelligenceAnswers,
                    reviewerScore = helper?.reviewerScore,
                    workerCalls = helper?.workerCalls ?: 0, searches = helper?.searches ?: 0,
                    pagesRead = helper?.pagesRead ?: 0, handoffCharacters = helper?.handoffCharacters ?: 0,
                    primaryInputTokens = helper?.primaryInputTokens ?: 0, primaryOutputTokens = helper?.primaryOutputTokens ?: 0,
                    reviewerCalls = helper?.reviewerCalls ?: 0, reviewerInputTokens = helper?.reviewerInputTokens ?: 0,
                    reviewerOutputTokens = helper?.reviewerOutputTokens ?: 0, outputCapViolations = helper?.outputCapViolations ?: 0,
                    intelligenceParseable = sample.intelligenceSemantics?.parseable ?: sample.intelligenceParseable,
                    intelligenceFormatCompliant = sample.intelligenceSemantics?.formatCompliant ?: sample.intelligenceFormatCompliant
                )
            }
        )
        return submission.takeIf { evaluate(it) != null }
    }

    fun evaluate(data: ScoreboardSubmission): ScoreboardEntry? {
        if (data.schemaVersion !in 1..3 || data.plan !in setOf(BenchmarkMode.QUICK, BenchmarkMode.FULL)) return null
        if (data.provider !in providers || !validModel(data.model)) return null
        if (!data.id.matches(Regex("[A-Za-z0-9-]{1,80}")) || data.startedAt !in 0..253402300799999L || (data.issueNumber != null && data.issueNumber <= 0)) return null
        val delegation = data.kind == ScoreboardKind.DELEGATION
        if (data.schemaVersion >= 2 && (data.effectiveConfigHash?.matches(Regex("[a-f0-9]{64}")) != true || data.comparisonContextHash?.matches(Regex("[a-f0-9]{64}")) != true || (data.measurementVersion ?: 0) !in 1..100 || (data.scoringVersion ?: 0) !in 1..100)) return null
        if (listOfNotNull(data.backend, data.accelerator, data.loadRegime, data.cacheRegime).any { !it.matches(Regex("[A-Za-z0-9 _./-]{1,80}")) } || data.capabilityManifest.any { it !in setOf("text", "structured", "reasoning", "tools", "images") }) return null
        if (if (delegation) data.suiteVersion != 1 else data.suiteVersion !in setOf(6, 7, 8, 9, BenchmarkSuiteRegistry.VERSION)) return null
        if (delegation && (data.primaryProvider !in providers + "LITERT_LM" || data.reviewerProvider !in providers + "LITERT_LM" || !validModel(data.primaryModel) || !validModel(data.reviewerModel))) return null
        if (!delegation && listOf(data.primaryProvider, data.primaryModel, data.reviewerProvider, data.reviewerModel).any { it != null }) return null
        if (delegation && data.model.equals(data.reviewerModel, ignoreCase = true)) return null
        if (data.executionPolicy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1) return null
        if (data.schemaVersion >= 3 && (data.executionPolicy != BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1 || data.fixtureSetVersion != BenchmarkIntelligenceFixtures.VERSION || data.fixtureRotationSeed == null || data.fixtureRotationSeed < 0)) return null
        if (!delegation && data.suiteVersion >= 10 && data.schemaVersion < 3) return null
        val manifest = manifest(data.kind, data.plan, data.suiteVersion, data.fixtureRotationSeed ?: 0).associateBy { it.id }
        val domains = if (data.suiteVersion >= 10) this.domains + setOf("Multilingual", "Grounded reasoning") else this.domains
        if (data.schemaVersion >= 3 && data.fixtureManifestHash != benchmarkHash(manifest.values.joinToString("\n") { it.id + it.prompt + it.expected })) return null
        // A public score requires complete graded evidence. Execution failures have no invented accuracy.
        if (!delegation && data.suiteVersion >= 8 && data.trials.any { it.outcome !in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED) }) return null
        if (data.trials.size != manifest.size || data.trials.map { it.id }.toSet() != manifest.keys) return null
        if (data.trials.any { trial ->
                trial.outcome !in allowedOutcomes ||
                    trial.durationMs !in 0..3_600_000L ||
                    trial.firstTextMs?.let { it !in 0..trial.durationMs } == true ||
                    trial.speed?.let { !it.isFinite() || it <= 0 || it > 1_000_000 } == true ||
                    listOf(trial.inputTokens, trial.outputTokens, trial.primaryInputTokens, trial.primaryOutputTokens, trial.reviewerInputTokens, trial.reviewerOutputTokens).any { it !in 0..100_000_000L } ||
                    listOf(trial.toolCalls, trial.successfulToolCalls, trial.workerCalls, trial.searches, trial.pagesRead, trial.reviewerCalls, trial.outputCapViolations).any { it !in 0..10_000 } ||
                    trial.handoffCharacters !in 0..1_000_000 ||
                    trial.successfulToolCalls > trial.toolCalls ||
                    trial.reviewerScore?.let { it !in 0..100 } == true ||
                    (delegation && trial.outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED) && (trial.reviewerScore == null || trial.reviewerCalls == 0)) ||
                    (manifest.getValue(trial.id).category == IntelligenceBenchmark.CATEGORY && (trial.intelligence.keys != domains || data.schemaVersion >= 3 && trial.intelligenceParseable != true)) ||
                    (manifest.getValue(trial.id).category != IntelligenceBenchmark.CATEGORY && trial.intelligence.isNotEmpty())
            }
        ) {
            return null
        }
        fun selected(category: String) = data.trials.filter { manifest.getValue(it.id).category == category }
        fun percent(trials: List<ScoreboardTrial>): Double = 100.0 * trials.count { it.outcome == BenchmarkOutcome.PASSED } / trials.size
        val completion = 100.0 * data.trials.count { it.outcome == BenchmarkOutcome.PASSED || it.outcome == BenchmarkOutcome.FAILED } / data.trials.size
        val dimensions = if (delegation) {
            val calls = data.trials.sumOf { it.toolCalls }
            val research = data.trials.filter { manifest.getValue(it.id).workload in setOf("delegation-research", "delegation-handoff", "delegation-stopping", "delegation-progress") }
            listOf(
                ScoreboardMetric("Task accuracy", percent(data.trials), 50),
                ScoreboardMetric("Tool calls", if (calls == 0) 0.0 else 100.0 * data.trials.sumOf { it.successfulToolCalls } / calls, 15),
                ScoreboardMetric("Research handoff", percent(research), 15),
                ScoreboardMetric("Reviewer", data.trials.map { (it.reviewerScore ?: 0).toDouble() }.average(), 20),
                ScoreboardMetric("Completion", completion)
            )
        } else {
            listOf(
                ScoreboardMetric("Task accuracy", percent(selected("task")), 35),
                ScoreboardMetric("Structured output", percent(selected("json")), 20),
                ScoreboardMetric("Tool success", percent(selected("tools")), 20),
                ScoreboardMetric("Intelligence", selected(IntelligenceBenchmark.CATEGORY).flatMap { it.intelligence.values }.count { it } * 100.0 / (selected(IntelligenceBenchmark.CATEGORY).size * domains.size), 15),
                ScoreboardMetric("Completion", completion, 10)
            )
        }
        val speedTrials = data.trials.filter { !manifest.getValue(it.id).warmup && (delegation || manifest.getValue(it.id).category == "speed") }
        val speeds = speedTrials.mapNotNull { it.speed }
        val consistency = if (data.plan == BenchmarkMode.FULL) {
            speedTrials.groupBy { manifest.getValue(it.id).workload }.values.mapNotNull { group ->
                val values = group.mapNotNull { it.speed }
                if (values.size < 3) null else (100 * (1 - sqrt(values.map { (it - values.average()) * (it - values.average()) }.average()) / values.average())).coerceIn(0.0, 100.0)
            }.takeIf { it.isNotEmpty() }?.average()
        } else {
            null
        }
        val score = minOf(dimensions.sumOf { (it.value ?: 0.0) * it.weight } / 100, if (delegation) percent(data.trials) else completion)
        val domainScores = if (delegation) emptyList() else domains.map { domain -> ScoreboardMetric(domain, selected(IntelligenceBenchmark.CATEGORY).count { it.intelligence[domain] == true } * 100.0 / selected(IntelligenceBenchmark.CATEGORY).size) }
        val metrics = dimensions + listOf(
            ScoreboardMetric("Generation speed", median(speeds), unit = if (delegation) "tok/s" else "reference units/s"),
            ScoreboardMetric("First response", median(speedTrials.mapNotNull { it.firstTextMs?.toDouble() }), unit = "ms"),
            ScoreboardMetric("Case duration", median(data.trials.map { it.durationMs.toDouble() }), unit = "ms"),
            ScoreboardMetric("Consistency", consistency)
        ) + domainScores
        return ScoreboardEntry(data, score, metrics)
    }

    fun best(data: List<ScoreboardSubmission>): List<ScoreboardEntry> = data.mapNotNull(::evaluate)
        .sortedWith(compareByDescending<ScoreboardEntry> { it.score }.thenByDescending { it.submission.startedAt }.thenBy { it.submission.id })
        .distinctBy { it.submission.key }

    fun usedModel(provider: String, model: String): String = model.trim().let { if (provider == "OPENROUTER") it.removeSuffix(":batch") else it }
    private fun validModel(value: String?): Boolean = value != null && value.length in 1..200 && value == value.trim() && value.none { it.isISOControl() } && "://" !in value && !value.startsWith("/") && '\\' !in value
    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
}

/** Imported historic submissions retain their original formula; new submissions need an explicit policy. */
internal fun benchmarkScoreboardIneligibility(run: BenchmarkRun): String? = when {
    run.executionPolicy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1 -> "Saved-profile policy is a separate result domain; the portable scoreboard accepts capability policy only."
    run.executionPolicy == null -> "Execution policy was not recorded; retained history is not silently promoted into the capability scoreboard."
    benchmarkReadinessExclusion(run) != null -> "Finished positive readiness is required; a retained primary structured failure keeps compatibility fallback provisional."
    run.mode != BenchmarkMode.DELEGATION && !benchmarkIntelligenceSummary(run).complete -> "Semantic intelligence is incomplete or ungraded; malformed output cannot be submitted as measured zero accuracy."
    else -> null
}
