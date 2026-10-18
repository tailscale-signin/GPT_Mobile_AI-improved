package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class IntelligenceScore(
    val score: Double?,
    val answered: Int,
    val planned: Int,
    val domains: Map<String, Double>,
    val version: Int = IntelligenceBenchmark.VERSION,
    val provisional: Boolean = false,
    val questionsPerDomain: Int = 6
)

/**
 * A small, original, offline text-capability screen, not an IQ test or a public
 * MMLU/LiveBench score. Every domain has six distinct, objectively checked tasks.
 * Expected answers stay in the evaluator, never in the provider prompt.
 */
internal object IntelligenceBenchmark {
    const val VERSION = 3
    const val CATEGORY = "intelligence"
    const val LIMITATION = "36 original text tasks, 6 per domain. A small capability screen, not full intelligence, IQ, or an official MMLU/LiveBench score. Excludes vision, creativity, long-form coding and real-world agency. Scores apply to this fixed prompt and generation policy; repeated runs are not new questions."

    private val cases: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "Math" to listOf(
            "Eight units cost 17 credits each. Add 12 credits shipping and subtract a 10-credit coupon. What is the final cost?" to "138",
            "A bag has 4 red and 6 blue balls. Draw two without replacement. What is the probability both are red? Give a reduced fraction." to "2/15",
            "Three workers pack 90 boxes in two hours. At the same constant per-worker rate, how many boxes do five workers pack in three hours?" to "225",
            "Solve 2*x + 3*y = 19 and x - y = 2. Give x+y." to "8",
            "A value starts at 240, increases by 25 percent, then decreases by 20 percent. What is the final value?" to "240",
            "What is the smallest positive integer with remainder 2 when divided by 5 and remainder 3 when divided by 7?" to "17"
        ),
        "Logic" to listOf(
            "All zorps are blue. No blue object is heavy. Can any zorp be heavy? Answer YES or NO." to "NO",
            "A is before C. C is before B. D is after B. List the four letters from first to last with no separators." to "ACBD",
            "Exactly one of P and Q is true. Q is false. Is P true? Answer TRUE or FALSE." to "TRUE",
            "If the alarm rings then the lamp lights. The lamp did not light. Must the alarm have been silent? Answer YES or NO." to "YES",
            "Some red objects are circles. All circles are flat. Must every red object be flat? Answer YES or NO." to "NO",
            "Exactly one of three boxes contains a coin. Label A says the coin is in B; label B says it is not in B; label C says it is not in C. Exactly one label is true. Which box contains the coin?" to "C"
        ),
        "Reading" to listOf(
            "Record: batch R was approved Monday, then approval was revoked Tuesday. No later decision exists. Is batch R currently approved? Answer YES or NO." to "NO",
            "Mira lent a key to Leon. Leon handed that key to Suri, who put it in a drawer. Who last held the key before it entered the drawer?" to "Suri",
            "Policy: refunds are allowed within 14 days only if unopened. A purchase is 10 days old and opened. Is a refund allowed? Answer YES or NO." to "NO",
            "Version 1 says the meeting is at 9. Version 2 replaces version 1 and says 11. Version 3 changes only the venue. What is the current meeting hour?" to "11",
            "A report says a package weighs 12 kg but gives no destination. What is its destination? Answer UNKNOWN if the text does not establish it." to "UNKNOWN",
            "Nia arrived before Omar but after Paz. Quinn arrived after Omar. Who arrived second?" to "Nia"
        ),
        "Planning" to listOf(
            "Tasks A and B take 3 and 5 minutes and can run in parallel. C takes 2 minutes and needs both finished. Starting at minute 0, what is the earliest finish time for C?" to "7",
            "There are directed edges S>A cost 2, S>B cost 5, A>B cost 1, A>T cost 8, B>T cost 3. What is the minimum cost from S to T?" to "6",
            "A bin holds 10 kg. Indivisible items weigh 6, 4, 5 and 5 kg. What is the minimum number of bins needed?" to "2",
            "You start with 2 tokens. Action X costs 1 token and adds 3 tokens, net +2; action Y costs 4 tokens and ends the task. What is the fewest actions needed to end the task?" to "2",
            "A robot starts at (0,0). It moves north 3, east 4, south 1, west 1. What is its Manhattan distance from the start?" to "5",
            "Jobs A, B, C take 2, 3, 4 minutes. B needs A finished; C is independent. Two workers each do one job at a time. What is the earliest time all jobs finish?" to "5"
        ),
        "Code reasoning" to listOf(
            "In Python, what is printed by: a=[1,2]; b=a; b.append(3); print(len(a)) ?" to "3",
            "In Python, what is printed by: print(sum(i*i for i in range(1,5) if i%2==0)) ?" to "20",
            "Start x=1. Repeat exactly three times: x=2*x+1. What is the final x?" to "15",
            "A stack starts empty. Push A, push B, pop once, push C, pop once. What is the last popped value?" to "C",
            "In Python, what is printed by: print(len(set([1,2,2,3,1]))) ?" to "3",
            "In Python, define f(n) as 1 when n<=1, otherwise n*f(n-1). What does f(5) return?" to "120"
        ),
        "Knowledge" to listOf(
            "Which chemical element has atomic number 8? Give its English name." to "oxygen",
            "What is the SI unit of electrical resistance? Give the unit name." to "ohm",
            "Which planet has the shortest orbital period around the Sun? Give its English name." to "Mercury",
            "What is the sum of interior angles of a Euclidean triangle, in degrees?" to "180",
            "What is the process in which plants use light energy to make sugars from carbon dioxide and water? Give its English name." to "photosynthesis",
            "Which data structure follows first-in-first-out ordering? Answer STACK or QUEUE." to "QUEUE"
        )
    )

    val responseSchemaJson: String by lazy {
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { cases.keys.forEach { key -> put(key, buildJsonObject { put("type", "string") }) } })
            put("required", kotlinx.serialization.json.JsonArray(cases.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }.toString()
    }

    val suite: List<BenchmarkCase> by lazy {
        cases.flatMap { (domain, questions) ->
            questions.mapIndexed { index, (question, expected) ->
                BenchmarkCase("intelligence-${domain.lowercase().replace(' ', '-')}-${index + 1}", "$domain ${index + 1}", CATEGORY, "$question\nReply with only the answer, without an explanation.", expected, workload = domain)
            }
        }
    }

    // Six requests, each with one question per domain. This preserves all 36
    // scored items while keeping Full at 50 tests and each prompt context-safe.
    val batches: List<BenchmarkCase> by lazy {
        (0 until 6).map { index ->
            val selected = cases.map { (domain, questions) -> domain to questions[index] }
            val expected = buildJsonObject { selected.forEach { (domain, question) -> put(domain, question.second) } }.toString()
            val prompt = selected.joinToString("\n") { (domain, question) -> "$domain: ${question.first}" } +
                "\nReturn only a raw JSON object, without Markdown or code fences, with these six exact keys: " + selected.joinToString { (domain, _) -> "\"$domain\"" } + ". Each value must be only the answer, without explanation."
            BenchmarkCase("intelligence-batch-$index", "Intelligence ${index + 1}", CATEGORY, prompt, expected, workload = "Six domains", seed = index)
        }
    }

    data class BatchGrade(
        val answers: Map<String, Boolean>,
        val formatCompliant: Boolean,
        val parseable: Boolean,
        val fixtureVersion: Int,
        val correction: String? = null
    )

    /** Only an entire object or one complete outer JSON fence is recoverable. */
    fun evaluateBatch(test: BenchmarkCase, answer: String, fixtureVersion: Int = VERSION): BatchGrade {
        val expected = Json.parseToJsonElement(checkNotNull(test.expected)) as JsonObject
        val trimmed = answer.trim()
        fun objectOrNull(value: String) = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
        val raw = objectOrNull(trimmed)
        val fenced = Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\z", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)?.groupValues?.get(1)?.let(::objectOrNull)
        val actual = raw ?: fenced
        val historicalAmbiguity = fixtureVersion <= 2 && test.id == "intelligence-batch-5"
        val answers = expected.mapValues { (domain, value) ->
            val response = actual?.get(domain) as? JsonPrimitive
            response != null &&
                (
                    matches(response.content, (value as JsonPrimitive).content) ||
                        historicalAmbiguity &&
                        domain == "Logic" &&
                        matches(response.content, "B")
                    )
        }
        return BatchGrade(
            answers,
            raw != null && raw.keys == expected.keys && raw.values.all { it is JsonPrimitive && it.isString },
            actual != null,
            fixtureVersion,
            if (historicalAmbiguity) "Historical ambiguous logic item: accept B or C; denominator retained." else null
        )
    }

    fun gradeBatch(test: BenchmarkCase, answer: String): Map<String, Boolean> = evaluateBatch(test, answer).answers

    internal fun validCoinLocations(legacy: Boolean): List<String> = listOf("A", "B", "C").filter { location ->
        listOf(location == "B", location != "B", if (legacy) location == "A" else location != "C").count { it } == 1
    }

    fun scoreBatches(samples: List<BenchmarkSample>, quick: Boolean, fixtureVersion: Int = VERSION): IntelligenceScore {
        val plan = if (quick) batches.take(1) else batches
        val byId = samples.filter { it.category == CATEGORY }.groupBy { it.testId }
        val completed = plan.mapNotNull { test ->
            byId[test.id]?.singleOrNull()?.takeIf { it.completed && it.workload == test.workload && it.intelligenceAnswers.keys == cases.keys }
        }
        val domains = cases.keys.mapNotNull { domain ->
            if (completed.size == plan.size) domain to (100.0 * completed.count { it.intelligenceAnswers[domain] == true } / plan.size) else null
        }.toMap()
        return IntelligenceScore(domains.values.takeIf { domains.size == cases.size }?.average(), completed.size * cases.size, plan.size * cases.size, domains, version = fixtureVersion, provisional = quick, questionsPerDomain = plan.size)
    }

    /** Tolerate a final answer label and case without searching prose for a lucky match. */
    fun matches(answer: String, expected: String): Boolean {
        val normalized = answer.trim().replace(Regex("^answer\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
        return normalized.equals(expected, ignoreCase = true) || normalized.equals("$expected.", ignoreCase = true)
    }

    fun score(samples: List<BenchmarkSample>): IntelligenceScore {
        val byId = samples.filter { it.category == CATEGORY }.groupBy { it.testId }
        val answered = suite.mapNotNull { test -> byId[test.id]?.singleOrNull()?.takeIf { it.completed && it.workload == test.workload } }
        val domains = cases.keys.mapNotNull { domain ->
            val results = answered.filter { it.workload == domain }
            if (results.size == cases.getValue(domain).size) domain to (100.0 * results.count { it.outcome == BenchmarkOutcome.PASSED } / results.size) else null
        }.toMap()
        // Missing/error/unsupported outcomes are not intelligence failures. Report
        // incomplete coverage instead of silently scoring only easier domains.
        return IntelligenceScore(domains.values.takeIf { domains.size == cases.size }?.average(), answered.size, suite.size, domains, version = 1)
    }
}
