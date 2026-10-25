package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.CpuExperimentTrial

/** Explicit diagnostic manifest: one prompt, independent cold/hot pairs, no preference changes. */
internal object BenchmarkCpuExperiment {
    private const val PROMPT = "Explain how rain forms in 180 to 220 plain English words, covering evaporation, condensation, cloud formation, and precipitation in separate sentences. No heading."
    const val CASE_COUNT = 24
    fun cases(experimentId: String = "planned-cpu-experiment"): List<BenchmarkCase> = buildList {
        for (threads in listOf(2, 4, 6, 8)) {
            repeat(3) { repetition ->
                for (cache in listOf("cold", "cache_hot")) {
                    val coldId = "cpu-$threads-$repetition-cold"
                    add(
                        BenchmarkCase(
                            "cpu-$threads-$repetition-$cache", "$threads threads · ${if (cache == "cold") "cold" else "cache hot"} · repeat ${repetition + 1}",
                            "speed", PROMPT, workload = "cpu_experiment", seed = 0,
                            validatorVersion = "benchmark-validator-v4",
                            cpuExperiment = CpuExperimentTrial(experimentId, benchmarkHash(PROMPT), threads, cache, repetition),
                            dependsOn = if (cache == "cache_hot") setOf(coldId) else emptySet(), repeatIndex = repetition
                        )
                    )
                }
            }
        }
    }
}
