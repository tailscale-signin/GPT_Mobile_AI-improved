package dev.chungjungsoo.gptmobile.data.benchmark

/** Immutable suite eight fixtures and order, retained for historical grading. */
internal object BenchmarkSuite8 {
    const val VERSION = 8
    const val MIN_TIMING_REFERENCE_UNITS = 128
    val quick: List<BenchmarkCase> get() = BenchmarkSuite7.quick
    fun historical(mode: BenchmarkMode) = BenchmarkSuite7.historical(mode)
    fun legacyStandard(agent: Boolean) = BenchmarkSuite7.legacyStandard(agent)

    val full: List<BenchmarkCase> by lazy {
        BenchmarkSuite7.full.filter { it.category == "speed" } + buildList {
            repeat(2) { seed ->
                val tag = "READY_${742 + seed}"
                add(BenchmarkCase("instruction-$seed", "Exact instruction ${seed + 1}", "task", "Reply with exactly $tag and nothing else.", tag, workload = "instruction", seed = seed))
                add(BenchmarkCase("logic-$seed", "Arithmetic ${seed + 1}", "task", "Compute ${17 + seed} * 23 + 9. Reply only with the integer.", ((17 + seed) * 23 + 9).toString(), workload = "logic", seed = seed))
                add(BenchmarkCase("extract-$seed", "Grounded extraction ${seed + 1}", "task", "Record: parcel $tag is not approved and weighs ${12 + seed} kg. Reply only with its mass in kg.", "${12 + seed} kg", workload = "extraction", seed = seed))
                add(BenchmarkCase("recall-$seed", "Context recall ${seed + 1}", "task", "Parcel code: $tag. " + "Irrelevant record. ".repeat(160) + "Reply with only the parcel code.", tag, workload = "recall", seed = seed))
                add(BenchmarkCase("abstain-$seed", "Evidence boundary ${seed + 1}", "task", "A parcel exists. Its destination and date are unknown. State the ${if (seed == 0) "destination" else "arrival date"}. Reply UNKNOWN when not established.", "UNKNOWN", workload = "abstention", seed = seed))
            }
        } + listOf(
            "{\"status\":\"ready\",\"count\":3}",
            "{\"items\":[{\"id\":2,\"active\":false}],\"next\":null}",
            "{\"text\":\"a\\\"b\\nc\",\"path\":\"C:\\\\temp\"}",
            "{\"integer\":7,\"decimal\":1.5,\"flag\":true,\"empty\":[]}",
            "{\"outer\":{\"inner\":[1,2,3]},\"unit\":\"kg\"}",
            "{\"exact\":\"ONLY_THIS_OBJECT\",\"optional\":null}"
        ).mapIndexed { index, expected -> BenchmarkCase("json-$index", "JSON ${index + 1}", "json", "Return exactly this JSON value, without Markdown or explanation: $expected", expected, workload = "structured", seed = index) } +
            listOf("tools", "typed_arguments", "tool_error_recovery", "research_fixture", "tool_selection", "tool_result_reading", "tools", "typed_arguments", "research_fixture", "tool_error_recovery").mapIndexed { index, workload ->
                val prompt = when (workload) {
                    "typed_arguments" -> "Call benchmark_lookup with key=parcel, count=2 as an integer and includeArchived=false as a boolean. Return only its code."
                    "research_fixture" -> "Call benchmark_search with query=parcel, then benchmark_read on the returned fixture URL. Reply only with FINAL_PARCEL_CODE."
                    "tool_error_recovery" -> "Call benchmark_lookup with key=parcel. If it reports a temporary failure, retry exactly once with a new call ID. Reply only with its code."
                    else -> "Use benchmark_lookup with exactly key=parcel. Reply only with its returned parcel code; stop after success. Never guess."
                }
                BenchmarkCase("tool-$index", "Tool contract ${index + 1}", "tools", prompt, workload = workload, seed = index)
            } + IntelligenceBenchmark.batches
    }
}
