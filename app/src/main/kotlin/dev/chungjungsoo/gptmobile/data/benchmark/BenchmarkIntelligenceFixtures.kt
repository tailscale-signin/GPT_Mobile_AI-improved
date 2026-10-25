package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Version four adds two domains and recorded, bounded rotations; v1-v3 stay frozen. */
internal object BenchmarkIntelligenceFixtures {
    const val VERSION = 4
    const val ROTATIONS = 3
    const val LIMITATION = "48 original text questions across eight domains, six per domain. Recorded rotations vary values and ordering within the same question families. Repeats and variants are not independent task families. This is a small capability screen, not an IQ or public benchmark score."

    fun questions(seed: Long): List<BenchmarkIntelligenceQuestion> {
        val rotation = Math.floorMod(seed, ROTATIONS.toLong()).toInt()
        val legacy = IntelligenceBenchmark.suite.groupBy { it.workload }
        val domains = linkedMapOf<String, List<Pair<String, String>>>()
        val units = 8 + rotation * 2
        val unitPrice = 17 + rotation
        val red = 4 + rotation
        val blue = 6
        val divisor = gcd(red * (red - 1), (red + blue) * (red + blue - 1))
        domains["Math"] = listOf(
            "$units units cost $unitPrice credits each. Add 12 credits shipping and subtract a 10-credit coupon. What is the final cost?" to (units * unitPrice + 2).toString(),
            "A bag has $red red and $blue blue balls. Draw two without replacement. What is the probability both are red? Give a reduced fraction." to "${red * (red - 1) / divisor}/${(red + blue) * (red + blue - 1) / divisor}",
            "Three workers pack ${90 + rotation * 30} boxes in two hours. At the same constant per-worker rate, how many boxes do five workers pack in three hours?" to ((90 + rotation * 30) * 5 / 2).toString(),
            "Solve 2*x + 3*y = ${19 + rotation * 5} and x - y = 2. Give x+y." to (8 + 2 * rotation).toString(),
            "A value starts at ${240 + rotation * 40}, increases by 25 percent, then decreases by 20 percent. What is the final value?" to (240 + rotation * 40).toString(),
            "What is the smallest positive integer with remainder ${2 + rotation} when divided by 5 and remainder 3 when divided by 7?" to (1..35).first { it % 5 == 2 + rotation && it % 7 == 3 }.toString()
        )
        for (domain in listOf("Logic", "Reading", "Planning", "Code reasoning", "Knowledge")) {
            domains[domain] = legacy.getValue(domain).map { it.prompt.substringBefore("\nReply with only") to requireNotNull(it.expected) }
        }
        domains["Multilingual"] = listOf(
            "Lis ce relevé français: le rendez-vous était à 14 h, puis a été déplacé à ${16 + rotation} h. À quelle heure est-il maintenant? Réponds uniquement avec les chiffres." to (16 + rotation).toString(),
            "Lee en español: hay ${7 + rotation} cajas rojas y 3 cajas azules. ¿Cuántas cajas hay en total? Responde solo con el número." to (10 + rotation).toString(),
            "Lies den deutschen Text: Paket A ist offen. Nur ungeöffnete Pakete dürfen zurückgegeben werden. Darf Paket A zurückgegeben werden? Antworte nur JA oder NEIN." to "NEIN",
            "Leia em português: a autorização foi concedida na segunda-feira e revogada na terça-feira. Não houve outra decisão. A autorização continua válida? Responda apenas SIM ou NÃO." to "NÃO",
            "Leggi in italiano: Ada arriva prima di Berto. Ciro arriva dopo Berto. Chi arriva per secondo? Rispondi solo con il nome." to "Berto",
            "日本語の記録を読んでください。「箱には赤い玉が${4 + rotation}個、青い玉が2個ある。」玉は全部で何個ですか。数字だけで答えてください。" to (6 + rotation).toString()
        )
        domains["Grounded reasoning"] = listOf(
            "Use only this record: parcel A weighs 12 kg; parcel B weighs 9 kg. The question concerns A. What is its mass? Include the unit." to "12 kg",
            "Use only these notices: notice 1 set the opening to 9; notice 2 replaces notice 1 and sets it to ${11 + rotation}; notice 3 changes only the address. What is the opening hour?" to (11 + rotation).toString(),
            "Use only this record: the trial enrolled 40 people and recorded 3 withdrawals. The record does not report treatment outcomes. How many improved? Reply UNKNOWN when unsupported." to "UNKNOWN",
            "Use only this history: the county was founded in 1096; the ruler proclaimed a kingdom in 1139; recognition followed in 1143. Which year is the proclamation event?" to "1139",
            "Use only this policy: permission requires both a signed form and a valid badge. R has a valid badge but no signed form. Is R permitted? Answer YES or NO." to "NO",
            "Use only this audit: record P was superseded by record Q. P says 24 units; Q says ${18 + rotation} units. A decorative example says 99. What is the currently accepted quantity?" to (18 + rotation).toString()
        )
        return domains.flatMap { (domain, questions) ->
            questions.indices.map { position ->
                val family = (position + rotation) % questions.size
                val question = questions[family]
                val familyId = "${domain.lowercase().replace(' ', '-')}-${family + 1}"
                BenchmarkIntelligenceQuestion("v$VERSION:$familyId:r$rotation", familyId, domain, question.first, question.second)
            }
        }
    }

    fun batches(seed: Long): List<BenchmarkCase> {
        val domains = questions(seed).groupBy { it.domain }
        return (0 until 6).map { index ->
            val selected = domains.values.map { it[index] }
            val expected = buildJsonObject { selected.forEach { put(it.domain, it.expected) } }.toString()
            val prompt = selected.joinToString("\n") { "${it.domain}: ${it.prompt}" } +
                "\nReturn only a raw JSON object, without Markdown or code fences, with these eight exact keys: " +
                selected.joinToString { "\"${it.domain}\"" } + ". Each value must be only the answer, without explanation."
            BenchmarkCase("intelligence-batch-$index", "Intelligence ${index + 1}", IntelligenceBenchmark.CATEGORY, prompt, expected, workload = "Eight domains", seed = index, validatorVersion = "benchmark-validator-v5", fixtureSetVersion = VERSION, fixtureRotationSeed = seed, intelligenceQuestions = selected, responseSchemaJson = schema(selected.map { it.domain }))
        }
    }

    fun individual(seed: Long): List<BenchmarkCase> = questions(seed).map { question ->
        BenchmarkCase("intelligence-${question.familyId}", question.familyId, IntelligenceBenchmark.CATEGORY, "${question.prompt}\nReply with only the answer, without an explanation.", question.expected, workload = question.domain, validatorVersion = "benchmark-validator-v5", fixtureSetVersion = VERSION, fixtureRotationSeed = seed, intelligenceQuestions = listOf(question))
    }

    private fun schema(keys: List<String>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { keys.forEach { key -> put(key, buildJsonObject { put("type", "string") }) } })
        put("required", kotlinx.serialization.json.JsonArray(keys.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }.toString()

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
