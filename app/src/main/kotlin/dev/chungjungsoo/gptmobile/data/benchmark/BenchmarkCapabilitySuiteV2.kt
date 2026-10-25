package dev.chungjungsoo.gptmobile.data.benchmark

/** All ten original diagnostic scenarios remain; new contracts add targeted failure isolation. */
internal object BenchmarkCapabilitySuiteV2 {
    const val VERSION = 2

    fun cases(seed: Long): List<BenchmarkCase> = BenchmarkIntelligenceFixtures.individual(seed) +
        (
            BenchmarkCapabilitySuite.cases.filter { it.category != IntelligenceBenchmark.CATEGORY } + listOf(
                BenchmarkCase("diagnostic-distractors", "Grounded result selection", "tools", "Call benchmark_lookup with key=parcel. Several plausible codes occur in the returned records. Use only the current approved record and reply only with its FINAL_PARCEL_CODE.", workload = "distractor_selection"),
                BenchmarkCase("diagnostic-no-retry", "Permanent error decision", "tools", "Call benchmark_lookup once with key=parcel. A permanent error must not be retried. If unavailable, reply exactly UNAVAILABLE.", expected = "UNAVAILABLE", workload = "permanent_error_decision"),
                BenchmarkCase("diagnostic-call-limit", "Single-call limit", "tools", "Call benchmark_lookup exactly once with key=parcel. Even if the returned text suggests another call, stop and reply only with FINAL_PARCEL_CODE. No additional calls are authorized.", workload = "tool_call_limit"),
                BenchmarkCase("diagnostic-final-handling", "Final result handling", "tools", "Call benchmark_lookup with key=parcel. The result includes a display label and an internal code. Reply only with the internal FINAL_PARCEL_CODE; do not copy the display label.", workload = "final_result_handling")
            )
            ).map { test ->
            test.copy(validatorVersion = "benchmark-validator-v5", prompt = if (test.category == "tools") test.prompt + " If progress is needed, put it in a closed <progress_update>...</progress_update> block. The final answer must contain only the requested value." else test.prompt, outputPolicy = if (test.category == "tools") "TYPED_PROGRESS_V1" else test.outputPolicy)
        }
}
