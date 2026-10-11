package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicAccessPolicy
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicNativeClient
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicSearchRequest
import dev.chungjungsoo.gptmobile.data.research.researchDomainAllowed
import dev.chungjungsoo.gptmobile.data.research.researchDomains
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One six-tool facade. Provider tools never enter the model's tool catalogue. */
internal class DeepResearchTool(
    private val operation: String,
    private val client: AcademicNativeClient,
    private val selection: MultiSearchResearchSelection,
    private val options: DeepResearchSettings,
    private val permitted: suspend () -> Boolean,
    private val remote: AgentTool? = null,
    private val budget: AcademicRunBudget = AcademicRunBudget()
) : AgentTool {
    override val definition = AgentToolDefinition(
        "deep_research_$operation",
        when (operation) {
            "search_papers" -> "Discover academic metadata. Titles, abstracts and citation counts are leads, not verified full-text evidence."
            "get_paper" -> "Resolve a public paper identifier and return bibliographic metadata."
            "read_paper" -> "Read permitted open-access text. Native mode cannot parse PDFs; gateway reading is bounded."
            "citations" -> "Read bounded citation edges; a citation does not establish agreement or support."
            "verify_evidence" -> "Deterministically check an exact quote in a captured paper version. Does not validate scientific truth."
            else -> "Show academic provider availability, native/gateway ownership and quota limitations."
        },
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    fun string(name: String) = put(
                        name,
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "maxLength",
                                if (name == "quote") {
                                    4000
                                } else if (name == "versionId") {
                                    700
                                } else {
                                    500
                                }
                            )
                        }
                    )
                    when (operation) {
                        "search_papers" -> {
                            string("query")
                            string("reviewStatus")
                            string("cursor")
                            listOf("maxResults", "fromYear", "toYear", "maxProviderCalls").forEach { key -> put(key, buildJsonObject { put("type", "integer") }) }
                        }
                        "get_paper", "read_paper", "citations" -> {
                            string("identifier")
                            if (operation == "citations") string("direction")
                        }
                        "verify_evidence" -> {
                            string("paperId")
                            string("versionId")
                            string("quote")
                        }
                    }
                }
            )
            put(
                "required",
                JsonArray(
                    when (operation) {
                        "search_papers" -> listOf("query")
                        "get_paper", "read_paper", "citations" -> listOf("identifier")
                        "verify_evidence" -> listOf("paperId", "versionId", "quote")
                        else -> emptyList()
                    }.map(::JsonPrimitive)
                )
            )
        }
    )
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (!permitted()) return failure(callId, "permission_denied", "Deep Research is locked. Check the chat Research toggle, depth, Papers consent and profile permission.")
        try {
            val providerCalls = ((arguments["maxProviderCalls"] as? JsonPrimitive)?.intOrNull ?: 3).coerceIn(1, 3)
            budget.reserve(
                operation,
                if (operation == "search_papers") {
                    providerCalls
                } else if (operation == "read_paper") {
                    2
                } else {
                    1
                }
            )
            if (remote != null) {
                val bound = JsonObject(
                    arguments + mapOf(
                        "depth" to JsonPrimitive(selection.depth),
                        "maxProviderCalls" to JsonPrimitive(providerCalls),
                        "enabledProviderIds" to JsonArray(selection.enabledProviders.sorted().map(::JsonPrimitive)),
                        "includeDomains" to JsonArray(researchDomains(options.includeDomains).map(::JsonPrimitive)),
                        "excludeDomains" to JsonArray(researchDomains(options.excludeDomains).map(::JsonPrimitive))
                    )
                )
                val result = remote.execute(callId, bound)
                if (!permitted()) return failure(callId, "permission_denied", "Research permission changed during the request.")
                budget.chargeBytes(
                    when (val content = result.content) {
                        is ToolResultContent.Text -> content.text.toByteArray().size
                        is ToolResultContent.Json -> content.value.toString().toByteArray().size
                        is ToolResultContent.ResourceLinks -> content.links.toString().toByteArray().size
                    }
                )
                if (!result.isError || !selection.allowNativeFallback || operation == "verify_evidence") return result
                budget.reserve(
                    operation,
                    if (operation == "search_papers") {
                        providerCalls
                    } else if (operation == "read_paper") {
                        2
                    } else {
                        1
                    }
                )
                // Completed remote results return above. Only a failed read-only operation
                // may fall back to the explicitly consented native owner.
            } else if (selection.gatewayConnectionUid != null && !selection.allowNativeFallback) {
                return failure(callId, "gateway_unavailable", "Reconnect the selected academic gateway or explicitly allow native fallback.")
            }
            val payload = native(arguments)
            check(permitted()) { "permission_denied" }
            budget.chargeBytes(payload.toString().toByteArray().size)
            return AgentToolResult(callId, ToolResultContent.Json(if (selection.gatewayConnectionUid != null) JsonObject(payload + mapOf("fallbackOwner" to JsonPrimitive("native"), "coveragePartial" to JsonPrimitive(true))) else payload), false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(callId, "academic_unavailable", error.message?.take(300) ?: "Academic request failed")
        }
    }
    private suspend fun native(args: JsonObject): JsonObject {
        fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun number(key: String) = (args[key] as? JsonPrimitive)?.intOrNull
        val json = Json { encodeDefaults = true }
        val policy = AcademicAccessPolicy(researchDomains(options.includeDomains), researchDomains(options.excludeDomains), selection.enabledProviders)
        return when (operation) {
            "status" -> buildJsonObject {
                put("owner", "native")
                put("providers", json.encodeToJsonElement(client.status()))
                put("pdf", "pdf_unsupported_without_gateway")
                put("remainingToolCalls", budget.remaining())
            }
            "search_papers" -> json.encodeToJsonElement(
                client.search(
                    AcademicSearchRequest(
                        query = str("query"), depth = selection.depth, page = str("cursor").ifBlank { "1" }.toInt().also { require(it in 1..3) }, maxResults = (number("maxResults") ?: if (selection.depth < 35) 10 else 25).coerceIn(1, 50),
                        fromYear = number("fromYear"), toYear = number("toYear"), reviewStatus = str("reviewStatus").ifBlank { "any" },
                        includeDomains = researchDomains(options.includeDomains), excludeDomains = researchDomains(options.excludeDomains),
                        maxProviderCalls = (number("maxProviderCalls") ?: 3).coerceIn(1, 3)
                    ),
                    selection,
                    permitted
                )
            ).jsonObject
            "get_paper", "read_paper" -> {
                val paper = client.getPaper(str("identifier"), permitted, policy) ?: return buildJsonObject { put("status", "not_found") }
                require(researchDomainAllowed(paper.canonicalUrl, researchDomains(options.includeDomains), researchDomains(options.excludeDomains))) { "Paper domain is excluded" }
                if (operation == "get_paper") {
                    json.encodeToJsonElement(paper).jsonObject
                } else {
                    val evidence = client.read(paper, permitted, policy)
                    json.encodeToJsonElement(evidence).jsonObject
                }
            }
            "citations" -> client.citations(str("identifier"), str("direction").ifBlank { "references" }, permitted, policy)
            else -> {
                val evidence = client.evidence(str("paperId"), str("versionId"))
                if (evidence != null) require(researchDomainAllowed(evidence.sourceUrl, researchDomains(options.includeDomains), researchDomains(options.excludeDomains))) { "Evidence domain is excluded" }
                buildJsonObject {
                    put("verdict", evidence?.verify(str("quote"), str("versionId")) ?: "not_read")
                    put("paperId", str("paperId"))
                    put("versionId", str("versionId"))
                    if (evidence != null) {
                        put("offset", evidence.text.indexOf(str("quote")))
                        put("textHash", evidence.textHash)
                        put("section", evidence.section)
                        put("sourceUrl", evidence.sourceUrl)
                    }
                    put("limitation", "Quote existence only; scientific validity and causal claims require review")
                }
            }
        }
    }
    private fun failure(id: String, code: String, detail: String) = AgentToolResult(
        id,
        ToolResultContent.Json(
            buildJsonObject {
                put("status", code)
                put("message", detail)
            }
        ),
        true,
        errorCode = code,
        dispatched = code != "permission_denied"
    )
    companion object {
        val OPERATIONS = listOf("search_papers", "get_paper", "read_paper", "citations", "verify_evidence", "status")
    }
}

internal class AcademicRunBudget(private val limit: Int = 30, private val maxReads: Int = 12) {
    private val mutex = Mutex()
    private var calls = 0
    private var bytes = 0
    private var reads = 0
    suspend fun reserve(operation: String, cost: Int = 1) = mutex.withLock {
        check(cost in 1..3 && calls + (if (operation == "status") 0 else cost) <= limit && bytes < 256 * 1024) { "Academic budget exhausted; synthesize the retained evidence" }
        if (operation == "read_paper") {
            check(reads < maxReads) { "Academic reading budget exhausted" }
            reads++
        }
        if (operation != "status") calls += cost
    }
    suspend fun chargeBytes(count: Int) = mutex.withLock {
        bytes += count
        check(bytes <= 256 * 1024) { "Academic output budget exhausted" }
    }
    fun remaining() = (limit - calls).coerceAtLeast(0)
}
