package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkProfile
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val PRIVACY_COMPARISON_SYSTEM_PROMPT = "Follow the fictional benchmark task exactly. Use only the supplied fictional facts. Do not call tools, use saved personal information, or ask for information already supplied. Return only the requested answer format."

/** A closed corpus: callers cannot substitute chat text, saved prompts or personal terms. */
enum class PrivacyComparisonCase(val id: String, val title: String, val prompt: String) {
    WARMUP("warmup", "Unscored preparation", "Reply with exactly READY and nothing else."),
    TRAVEL(
        "travel",
        "Travel request",
        "My name is Mira Voss. My partner is Elian Reed. We will travel on February 4th. " +
            "Plan a relaxed three-day trip. Return only JSON with traveler, companion, departure, and plan. " +
            "Copy the supplied names and departure date into the first three fields. " +
            "The plan must contain three distinct short activity suggestions, one string for each day."
    ),
    EMAIL(
        "email",
        "Email reply",
        "Name: Dana Hale\nEmail: dana@cedarlab.example\n" +
            "Message: Please get the license fixed and finish the project.\n" +
            "Draft a short reply confirming both actions. Return only JSON with recipient, email, reply, and commitments. " +
            "Copy the supplied name and email address. The reply must mention the license and project. " +
            "The commitments array must contain exactly \"license fixed\" and \"project completed\" in that order."
    ),
    STUDENT(
        "student",
        "Academic extension",
        "Name: Noor Vale\nMy brothers are dealing with anxiety, but I want to ask for an extension.\n" +
            "Write a brief empathetic academic reply granting the extension without repeating the private family circumstances. " +
            "Return only JSON with student, decision, and reply. Copy the student's supplied name; " +
            "decision must be \"extension granted\". The reply must mention the extension and must not repeat the health condition."
    ),
    CONTRACT(
        "contract",
        "Contract obligations",
        "Client name: Mira Voss\nContractor name: Elian Reed\nFee: 1 234,50 EUR\n" +
            "Services:\n- Write a project brief\n- Review the draft\n- Deliver a final report\n" +
            "Summarize the agreement. Return only JSON with client, contractor, fee, services, and summary. " +
            "Copy the supplied client name, contractor name and fee exactly. " +
            "The services array must retain all three supplied service descriptions in their original order. " +
            "The summary must mention the brief, draft and report."
    );

    /** Deliberately empty: detector scoring must not gain the answers as custom terms. */
    val customTerms: List<String> get() = emptyList()

    val expectedPrivateValues: List<Pair<String, Set<PrivacyCategory>>> get() = when (this) {
        WARMUP -> emptyList()
        TRAVEL -> listOf(
            "Mira Voss" to setOf(PrivacyCategory.IDENTITIES),
            "Elian Reed" to setOf(PrivacyCategory.IDENTITIES),
            "February 4th" to setOf(PrivacyCategory.PRIVATE_DATES)
        )
        EMAIL -> listOf(
            "Dana Hale" to setOf(PrivacyCategory.IDENTITIES),
            "dana@cedarlab.example" to setOf(PrivacyCategory.CONTACTS)
        )
        STUDENT -> listOf(
            "Noor Vale" to setOf(PrivacyCategory.IDENTITIES),
            "My brothers are dealing with anxiety" to setOf(PrivacyCategory.HEALTH)
        )
        CONTRACT -> listOf(
            "Mira Voss" to setOf(PrivacyCategory.IDENTITIES, PrivacyCategory.CONFIDENTIAL_WORK),
            "Elian Reed" to setOf(PrivacyCategory.IDENTITIES, PrivacyCategory.CONFIDENTIAL_WORK),
            "1 234,50 EUR" to setOf(PrivacyCategory.FINANCES)
        )
    }

    val publicPhrases: List<String> get() = when (this) {
        WARMUP -> listOf("Reply with exactly READY")
        TRAVEL -> listOf("Plan a relaxed three-day trip.", "three distinct short activity suggestions")
        EMAIL -> listOf("Please get the license fixed and finish the project.", "Draft a short reply confirming both actions.")
        STUDENT -> listOf("I want to ask for an extension.", "Write a brief empathetic academic reply granting the extension")
        CONTRACT -> listOf("Write a project brief", "Review the draft", "Deliver a final report", "Summarize the agreement.")
    }

    /** Explicit fixture criteria, not a general semantic-quality or safety judge. */
    fun assess(answer: String): List<PrivacyComparisonCriterion> {
        if (this == WARMUP) return listOf(PrivacyComparisonCriterion("ready", "Preparation instruction", answer.trim() == "READY"))
        val value = runCatching { Json.parseToJsonElement(answer.trim()) as? JsonObject }.getOrNull()
        val checks = mutableListOf(PrivacyComparisonCriterion("json", "Requested JSON object", value != null))
        checks += PrivacyComparisonCriterion("markers", "No unresolved privacy marker", !Regex("\\[PRIVATE_", RegexOption.IGNORE_CASE).containsMatchIn(answer))
        if (value == null) return checks
        fun text(key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun exact(key: String, expected: String) {
            checks += PrivacyComparisonCriterion(key, "Supplied $key retained", text(key) == expected)
        }
        fun prose(key: String, words: List<String>) {
            val content = text(key)
            checks += PrivacyComparisonCriterion(key, "Required $key content", content != null && content.length in 24..4096 && words.all { content.contains(it, true) })
        }
        fun strings(key: String): List<String>? = (value[key] as? JsonArray)?.let { array ->
            array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@let null }
        }
        when (this) {
            TRAVEL -> {
                exact("traveler", "Mira Voss")
                exact("companion", "Elian Reed")
                exact("departure", "February 4th")
                val plan = strings("plan")
                checks += PrivacyComparisonCriterion("plan", "Three distinct text entries (4–500 characters each)", plan?.size == 3 && plan.distinct().size == 3 && plan.all { it.length in 4..500 })
            }
            EMAIL -> {
                exact("recipient", "Dana Hale")
                exact("email", "dana@cedarlab.example")
                prose("reply", listOf("license", "project"))
                checks += PrivacyComparisonCriterion("commitments", "Both requested commitments", strings("commitments") == listOf("license fixed", "project completed"))
            }
            STUDENT -> {
                exact("student", "Noor Vale")
                exact("decision", "extension granted")
                prose("reply", listOf("extension"))
                checks += PrivacyComparisonCriterion("circumstances", "Reply does not contain the text “anxiety”", text("reply")?.contains("anxiety", true) == false)
            }
            CONTRACT -> {
                exact("client", "Mira Voss")
                exact("contractor", "Elian Reed")
                exact("fee", "1 234,50 EUR")
                checks += PrivacyComparisonCriterion("services", "All three obligations retained", strings("services") == listOf("Write a project brief", "Review the draft", "Deliver a final report"))
                prose("summary", listOf("brief", "draft", "report"))
            }
            WARMUP -> Unit
        }
        return checks
    }

    companion object {
        const val CORPUS_REVISION = "fictional-privacy-pairs-v1"
        const val RUBRIC_REVISION = "privacy-task-criteria-v1"
        val scored: List<PrivacyComparisonCase> = listOf(TRAVEL, EMAIL, STUDENT, CONTRACT)
        fun fromId(id: String): PrivacyComparisonCase? = entries.singleOrNull { it.id == id }
    }
}

internal fun privacyComparisonProfile(profile: PlatformV2): PlatformV2 {
    val prepared = benchmarkProfile(profile, allowTools = false, category = "privacy_comparison")
    val options = if (profile.compatibleType == ClientType.OLLAMA) {
        val saved = profile.ollamaOptions?.let {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<OllamaOptions>(it) }.getOrNull()
        } ?: OllamaOptions.createDefault()
        // Retain numeric resource controls, but never forward arbitrary saved text.
        Json.encodeToString(saved.copy(temperature = 0f, stop = emptyList(), kvCacheType = OllamaOptions.DEFAULT_KV_CACHE_TYPE, keepAlive = OllamaOptions.DEFAULT_KEEP_ALIVE, autoContinue = false))
    } else {
        null
    }
    return prepared.copy(
        systemPrompt = PRIVACY_COMPARISON_SYSTEM_PROMPT,
        maxTokens = PrivacyComparisonRunner.OUTPUT_TOKENS,
        disableAllTools = true,
        disableRemoteTools = true,
        disableLocalTools = true,
        batchMode = false,
        batchApiUrl = null,
        ollamaOptions = options,
        timeout = 60
    )
}
