package dev.chungjungsoo.gptmobile.data.benchmark

/** One isolated campaign, using the existing runner, validators, queue and retained receipts. */
internal object UnifiedProfileBenchmarkSuite {
    const val VERSION = 1
    const val SCOPE = "SYNTHETIC_PROFILE_AND_OFFLINE_ENGINEERING_V1"
    const val LIMITATION = "Original reasoning and research fixtures plus isolated GitHub workflow simulations. This is a custom profile benchmark, not an official SWE-bench score or proof of live repository performance."

    val toolCaseIds = setOf("diagnostic-selection", "diagnostic-arguments", "diagnostic-reading", "diagnostic-distractors", "diagnostic-final-handling")
    val recoveryCaseIds = setOf("diagnostic-recovery", "diagnostic-no-retry", "diagnostic-call-limit")

    fun cases(seed: Long): List<BenchmarkCase> {
        val basic = BenchmarkSuite9.quick.filter { it.category in setOf("task", "json") }.map { test ->
            test.copy(validatorVersion = "benchmark-validator-v5", responseSchemaJson = if (test.category == "json") "{\"type\":\"object\"}" else null)
        }
        val tools = BenchmarkCapabilitySuiteV2.cases(seed).filter {
            it.id in toolCaseIds || it.id in recoveryCaseIds || it.id == "diagnostic-research"
        }
        val timing = BenchmarkSuite9.full.filter { it.id in setOf("speed-short-0", "speed-short-1") }
        return basic + BenchmarkIntelligenceFixtures.batches(seed) + tools +
            GitHubEngineeringBenchmarkSuite.cases(autonomous = true) + timing
    }
}
