package dev.chungjungsoo.gptmobile.data.benchmark

/** Immutable suite nine manifest and strict grading contract. */
internal object BenchmarkSuite9 {
    const val VERSION = 9
    const val MIN_TIMING_REFERENCE_UNITS = 128
    val quick: List<BenchmarkCase> by lazy { BenchmarkSuite7.quick.map(::current) }
    fun historical(mode: BenchmarkMode) = BenchmarkSuite7.historical(mode)
    fun legacyStandard(agent: Boolean) = BenchmarkSuite7.legacyStandard(agent)

    val full: List<BenchmarkCase> by lazy {
        val cases = BenchmarkSuite8.full.map(::current)
        val early = listOf("task", "json", "tools", IntelligenceBenchmark.CATEGORY).mapNotNull { category -> cases.firstOrNull { it.category == category } }
        early + cases.filterNot { it in early }
    }

    private fun current(test: BenchmarkCase): BenchmarkCase = test.copy(
        prompt = when {
            test.workload == "extraction" && test.expected?.endsWith(" kg") == true -> test.prompt + " Return exactly the numeric mass, one space, and kg, without any other text."
            test.category == "tools" -> test.prompt + " If a public progress update is necessary, put it only inside a closed <progress_update>...</progress_update> block. After that boundary, the final answer must contain only the requested code."
            else -> test.prompt
        },
        validatorVersion = "benchmark-validator-v4",
        outputPolicy = if (test.category == "tools") "TYPED_PROGRESS_V1" else "STRICT_RAW_ANSWER_V1",
        repeatIndex = test.seed.takeIf { test.category == "speed" },
        dependsOn = if (test.category == "speed" && !test.warmup && test.id.startsWith("speed-${test.workload}-")) setOf("speed-${test.workload}-0") else emptySet()
    )
}
