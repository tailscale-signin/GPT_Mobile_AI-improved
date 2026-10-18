package dev.chungjungsoo.gptmobile.data.benchmark

/** Offline diagnostics have a separate cohort and never raise a Full timing rating. */
internal object BenchmarkCapabilitySuite {
    const val VERSION = 1
    val cases: List<BenchmarkCase> by lazy {
        IntelligenceBenchmark.suite + listOf(17, 18).flatMap { number ->
            val expression = "$number * 23 + 9"
            val expected = (number * 23 + 9).toString()
            listOf(
                BenchmarkCase("diagnostic-raw-$number", "Arithmetic $number · raw model", "task", "Compute $expression. Reply only with the integer.", expected, workload = "arithmetic_raw"),
                BenchmarkCase("diagnostic-assisted-$number", "Arithmetic $number · calculator assisted", "tools", "Use calculate_expression to evaluate $expression, then reply only with the returned integer.", expected, workload = "calculator_assisted", calculatorExpression = expression)
            )
        } + listOf(
            BenchmarkCase("diagnostic-selection", "Tool selection · offline fixture", "tools", "Find the parcel code with the parcel lookup tool, not the weather or calendar tool. Use key=parcel, then reply only with its returned code.", workload = "tool_selection"),
            BenchmarkCase("diagnostic-arguments", "Typed arguments · offline fixture", "tools", "Call benchmark_lookup with key=parcel, count=2 (integer), and includeArchived=false (boolean). Reply only with its returned code.", workload = "typed_arguments"),
            BenchmarkCase("diagnostic-reading", "Long tool-result reading · offline fixture", "tools", "Call benchmark_lookup with key=parcel. Read the complete returned record and reply only with the delivery code labelled FINAL_PARCEL_CODE.", workload = "tool_result_reading"),
            BenchmarkCase("diagnostic-research", "Search → read → answer · offline fixture", "tools", "Search the parcel record using benchmark_search with query=parcel, read the returned URL using benchmark_read, then reply only with its code. Do not guess or skip reading.", workload = "research_fixture"),
            BenchmarkCase("diagnostic-recovery", "Tool-error recovery · offline fixture", "tools", "Look up key=parcel using benchmark_lookup. A temporary error may require one retry. Reply only with the successful returned code.", workload = "tool_error_recovery"),
            BenchmarkCase("diagnostic-image", "Image reading · attached pixels", "vision", "Read the four-digit code shown in the attached image. Reply only with the digits. Do not infer it from the filename.", "5739", workload = "image_reading", imageCode = "5739")
        )
    }
}
