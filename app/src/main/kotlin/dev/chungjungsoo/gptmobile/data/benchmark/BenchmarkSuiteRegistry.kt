package dev.chungjungsoo.gptmobile.data.benchmark

/** Versioned isolated fixtures. Validation labels are never included in model context. */
internal object BenchmarkSuiteRegistry {
    const val VERSION = 3
    private val textSuite by lazy { createStandard(false) }
    private val agentSuite by lazy { createStandard(true) }
    fun standard(agent: Boolean): List<BenchmarkCase> = if (agent) agentSuite else textSuite
    private fun createStandard(agent: Boolean): List<BenchmarkCase> = buildList {
        for ((band, target) in listOf("short" to 512, "medium" to 2048, "long" to 4096)) {
            val suffix = "\nExplain how rain forms in about 100 plain English words. No heading. Ignore the irrelevant record padding above."
            var padding = "Synthetic weather record. "
            while (ReferenceTextTokenizer.starts(padding + suffix).size < target) padding += "Synthetic weather record. "
            repeat(6) { trial ->
                add(BenchmarkCase("speed-$band-$trial", "$band delivery ${if (trial == 0) "warm-up" else trial}", "speed", padding + suffix, warmup = trial == 0, workload = band, seed = trial))
            }
        }
        repeat(3) { seed ->
            val tag = "READY_${742 + seed}"
            add(BenchmarkCase("instruction-$seed", "Instruction ${seed + 1}", "task", "Reply with exactly $tag and nothing else.", tag, workload = "instruction", seed = seed))
            val json = "{\"ready\":true,\"count\":${3 + seed},\"unit\":\"kg\"}"
            add(BenchmarkCase("json-$seed", "JSON ${seed + 1}", "json", "Return only this JSON object, without markdown: $json", json, workload = "structured", seed = seed))
            add(BenchmarkCase("logic-$seed", "Logic ${seed + 1}", "task", "Compute ${17 + seed} * 23 + 9. Reply only with the integer.", ((17 + seed) * 23 + 9).toString(), workload = "logic", seed = seed))
            add(BenchmarkCase(if (seed == 0) "context" else "recall-$seed", "Context ${seed + 1}", "task", if (seed == 0) "What was the parcel code I gave you? Reply with only the code." else "Read this record: parcel $tag weighs ${12 + seed} kg. " + "Irrelevant record. ".repeat(160) + "Reply only with the parcel code.", if (seed == 0) "ORCHID-742" else tag, workload = "recall", seed = seed))
            add(BenchmarkCase("extract-$seed", "Grounded extraction ${seed + 1}", "task", "Source A: shipment $tag is NOT approved. Measured mass is ${12 + seed} kg on 2026-10-10. Reply with exactly: $tag|not approved|${12 + seed} kg|2026-10-10", "$tag|not approved|${12 + seed} kg|2026-10-10", workload = "extraction", seed = seed))
            add(BenchmarkCase("abstain-$seed", "Insufficient evidence ${seed + 1}", "task", "A shipment exists. No destination or delivery date is known. What is its delivery date? Reply only UNKNOWN.", "UNKNOWN", workload = "abstention", seed = seed))
        }
        if (agent) {
            repeat(12) { index ->
                add(BenchmarkCase("tool-$index", "Tool contract ${index + 1}", "tools", "Only the benchmark fixture is allowed. Call benchmark_lookup with exactly key=\"parcel\", then reply with only its returned code. Never guess, use old codes, or invoke a different tool.", workload = "tools", seed = index))
            }
        }
    }
}
