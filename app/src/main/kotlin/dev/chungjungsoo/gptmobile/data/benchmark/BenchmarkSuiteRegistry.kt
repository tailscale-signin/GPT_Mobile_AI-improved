package dev.chungjungsoo.gptmobile.data.benchmark

/** Version ten records execution policy and rotates a complete, expanded fixture set. */
internal object BenchmarkSuiteRegistry {
    const val VERSION = 10
    const val MIN_TIMING_REFERENCE_UNITS = 128
    val quick: List<BenchmarkCase> by lazy { cases(BenchmarkMode.QUICK, 0) }
    val full: List<BenchmarkCase> by lazy { cases(BenchmarkMode.FULL, 0) }
    fun historical(mode: BenchmarkMode) = BenchmarkSuite7.historical(mode)
    fun legacyStandard(agent: Boolean) = BenchmarkSuite7.legacyStandard(agent)

    fun cases(mode: BenchmarkMode, rotationSeed: Long): List<BenchmarkCase> {
        if (mode == BenchmarkMode.PERFORMANCE) return BenchmarkPerformanceSuite.cases()
        if (mode == BenchmarkMode.CPU_EXPERIMENT) return BenchmarkCpuExperiment.cases()
        if (mode == BenchmarkMode.DELEGATION) return delegationBenchmarkSuite()
        if (mode == BenchmarkMode.DIAGNOSTIC) return BenchmarkCapabilitySuiteV2.cases(rotationSeed)
        val intelligence = BenchmarkIntelligenceFixtures.batches(rotationSeed).associateBy { it.id }
        val base = if (mode == BenchmarkMode.QUICK) BenchmarkSuite9.quick else BenchmarkSuite9.full
        return base.map { test ->
            intelligence[test.id] ?: test.copy(validatorVersion = "benchmark-validator-v5", responseSchemaJson = if (test.category == "json") "{\"type\":\"object\"}" else null)
        }
    }
}

internal fun benchmarkSuiteForVersion(version: Int, mode: BenchmarkMode, rotationSeed: Long = 0): List<BenchmarkCase> = when {
    mode == BenchmarkMode.CPU_EXPERIMENT && version >= 9 -> BenchmarkCpuExperiment.cases()
    version == 10 -> BenchmarkSuiteRegistry.cases(mode, rotationSeed)
    version == 9 -> when (mode) {
        BenchmarkMode.QUICK -> BenchmarkSuite9.quick
        BenchmarkMode.DIAGNOSTIC -> BenchmarkCapabilitySuite.cases
        BenchmarkMode.DELEGATION -> delegationBenchmarkSuite()
        else -> BenchmarkSuite9.full
    }
    version == 8 -> when (mode) {
        BenchmarkMode.QUICK -> BenchmarkSuite8.quick
        BenchmarkMode.DIAGNOSTIC -> BenchmarkCapabilitySuite.cases
        BenchmarkMode.DELEGATION -> delegationBenchmarkSuite()
        else -> BenchmarkSuite8.full
    }
    version == 7 -> when (mode) {
        BenchmarkMode.QUICK -> BenchmarkSuite7.quick
        BenchmarkMode.DIAGNOSTIC -> BenchmarkCapabilitySuite.cases
        BenchmarkMode.DELEGATION -> delegationBenchmarkSuite()
        else -> BenchmarkSuite7.full
    }
    version == 6 -> BenchmarkSuite7.historical(mode)
    else -> emptyList()
}
