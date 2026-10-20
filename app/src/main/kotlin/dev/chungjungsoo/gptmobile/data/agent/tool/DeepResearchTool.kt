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
import kotlinx.coroutines.withContext
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
    private val budget: AcademicRunBudget = AcademicRunBudget(goalDriven = true, shared = dev.chungjungsoo.gptmobile.data.agent.ResearchBudget(1, ledger = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(options).fingerprint, options.userLimits))),
    private val persistentEvidence: Boolean = false,
    private val remoteCapability: suspend () -> Boolean = { false },
    private val runKey: String = "",
    private val peerReviewedOnly: Boolean = false
) : AgentTool {
    override val coordinatesChildWork = operation !in setOf("status", "verify_evidence")
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
        },
        capabilities = if (operation == "search_papers") setOf(dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.WEB_SEARCH) else emptySet()
    )
    private val enabledSelection: MultiSearchResearchSelection get() {
        val requested = selection.enabledProviders.ifEmpty { dev.chungjungsoo.gptmobile.data.research.engine.ResearchEngineRegistry.searchableIds }
        return selection.copy(enabledProviders = (requested - options.disabledEngineIds).ifEmpty { setOf("disabled_by_user") })
    }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        if (!permitted()) return failure(callId, "permission_denied", "Deep Research is locked. Check the chat Research toggle, depth, Papers consent and profile permission.")
        try {
            val providerCalls = ((arguments["maxProviderCalls"] as? JsonPrimitive)?.intOrNull ?: if (selection.depth == 100) AcademicNativeClient.CORE.size else 3).coerceIn(1, AcademicNativeClient.CORE.size)
            budget.reserve(
                operation,
                if (remote == null || budget.isGoalDriven) {
                    0
                } else if (operation == "search_papers") {
                    providerCalls
                } else if (operation == "read_paper") {
                    2
                } else {
                    1
                }
            )
            val combinedContext = dev.chungjungsoo.gptmobile.data.agent.CombinedAcquisitionBoundary.context()
            val remoteCompatible = (remote == null || !budget.isGoalDriven || operation == "status" || remoteCapability()) &&
                (combinedContext == null || remote == null || operation in setOf("status", "verify_evidence") || remoteCapability())
            if (!remoteCompatible && !selection.allowNativeFallback) return failure(callId, "unsupported_research_policy", "Gateway does not support this research policy. Update it or explicitly allow native fallback.")
            if (remote != null && remoteCompatible) {
                val counters = budget.sharedState()
                val combinedAllowance = combinedContext?.let { context ->
                    requireNotNull(dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore.installedOrNull()).open(context).availableToolOperations(context)
                }
                val requestAllowance = minOf((minOf(counters?.limits?.maxRequests ?: 2400L, 2400L) - (counters?.physical ?: 0)).coerceAtLeast(0), combinedAllowance ?: Long.MAX_VALUE, budget.gatewayAvailable(operation), if (operation == "search_papers") providerCalls * 4L else 8L)
                if (operation !in setOf("status", "verify_evidence")) check(requestAllowance > 0) { "Combined academic request allowance is exhausted; no gateway request was sent." }
                val remoteLimits = buildJsonObject {
                    put("researchRequestAllowance", requestAllowance)
                    counters?.let { put("researchDownloadAllowance", (minOf(it.limits.maxDownloadedBytes ?: 536870912L, 536870912L) - it.downloaded).coerceAtLeast(0)) }
                }
                val bound = JsonObject(
                    arguments + remoteLimits + (if (peerReviewedOnly && operation == "search_papers") mapOf("reviewStatus" to JsonPrimitive("peer_reviewed")) else emptyMap()) + mapOf(
                        "depth" to JsonPrimitive(selection.depth),
                        "maxProviderCalls" to JsonPrimitive(providerCalls),
                        "enabledProviderIds" to JsonArray(enabledSelection.enabledProviders.sorted().map(::JsonPrimitive)),
                        "includeDomains" to JsonArray(researchDomains(options.includeDomains).map(::JsonPrimitive)),
                        "excludeDomains" to JsonArray(researchDomains(options.excludeDomains).map(::JsonPrimitive)),
                        "ephemeral" to JsonPrimitive(!persistentEvidence),
                        "researchPolicyVersion" to JsonPrimitive(3),
                        "researchSourceGoal" to JsonPrimitive(dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(options).sourceGoal),
                        "researchRunId" to JsonPrimitive(runKey),
                        "researchPolicyFingerprint" to JsonPrimitive(dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(options).fingerprint)
                    )
                )
                val combinedAttempt = if (operation in setOf("status", "verify_evidence")) null else dev.chungjungsoo.gptmobile.data.agent.CombinedAcquisitionBoundary.reserve("academic-gateway:${java.util.UUID.randomUUID()}", requestAllowance)
                val gatewayReservation = try {
                    if (operation !in setOf("status", "verify_evidence") && budget.isGoalDriven) budget.reserveGateway(operation, requestAllowance) else null
                } catch (failure: Throwable) {
                    combinedAttempt?.finish(0, false)
                    throw failure
                }
                var gatewayOperations: Long? = null
                var gatewayDispatched = true
                val result = try {
                    remote.execute(callId, bound).also { result ->
                        gatewayDispatched = result.dispatched
                        val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
                            ?: (result.content as? ToolResultContent.Text)?.text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                        gatewayOperations = ((payload?.get("researchAccounting") as? JsonObject)?.get("physicalRequests") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    }
                } finally {
                    combinedAttempt?.finish(gatewayOperations, gatewayDispatched)
                    gatewayReservation?.let { budget.settleGateway(it, if (!gatewayDispatched) 0 else gatewayOperations) }
                }
                if (combinedContext != null && operation !in setOf("status", "verify_evidence") && (gatewayOperations == null || gatewayOperations < 0)) {
                    return failure(callId, "accounting_unavailable", "Gateway physical-request accounting is unavailable; its upper reservation is retained and the outcome requires reconciliation.")
                }
                if (!permitted()) return failure(callId, "permission_denied", "Research permission changed during the request.")
                if (budget.isGoalDriven && operation != "status") {
                    val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
                        ?: (result.content as? ToolResultContent.Text)?.text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    val receipt = payload?.get("researchAccounting") as? JsonObject
                    if (receipt == null) return failure(callId, "accounting_unavailable", "Gateway accounting is unavailable; dispatch outcome must be reconciled before continuing.")
                    budget.recordRemote(
                        (receipt["physicalRequests"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: error("Invalid gateway accounting"),
                        (receipt["downloadedBytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: error("Invalid gateway accounting"),
                        requestsAlreadyReserved = gatewayReservation != null
                    )
                } else if (!budget.isGoalDriven) {
                    budget.chargeBytes(result.content.toString().toByteArray().size)
                }
                if (!result.isError || !selection.allowNativeFallback || operation == "verify_evidence") return result
                budget.reserve(
                    operation,
                    0
                )
                // Completed remote results return above. Only a failed read-only operation
                // may fall back to the explicitly consented native owner.
            } else if (selection.gatewayConnectionUid != null && !selection.allowNativeFallback) {
                return failure(callId, "gateway_unavailable", "Reconnect the selected academic gateway or explicitly allow native fallback.")
            }
            val payload = withContext(AcademicRequestBudgetContext(budget, persistentEvidence, runKey, operation)) { native(arguments) }
            check(permitted()) { "permission_denied" }
            // Native downloaded bytes are charged in the bounded response stream.
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
        val policy = AcademicAccessPolicy(researchDomains(options.includeDomains), researchDomains(options.excludeDomains), enabledSelection.enabledProviders)
        return when (operation) {
            "status" -> buildJsonObject {
                put("owner", "native")
                put("providers", json.encodeToJsonElement(client.status()))
                put("pdf", "pdf_unsupported_without_gateway")
                put("accountingMode", if (budget.isGoalDriven) "goal_driven" else "legacy_bounded")
                if (!budget.isGoalDriven) put("remainingToolCalls", budget.remaining())
            }
            "search_papers" -> json.encodeToJsonElement(
                client.search(
                    AcademicSearchRequest(
                        query = str("query"), depth = selection.depth, page = str("cursor").ifBlank { "1" }.toInt().also { require(it > 0) }, maxResults = (number("maxResults") ?: if (selection.depth < 35) 10 else 25).coerceIn(1, 50),
                        fromYear = number("fromYear"), toYear = number("toYear"), reviewStatus = if (peerReviewedOnly) "peer_reviewed" else str("reviewStatus").ifBlank { "any" },
                        includeDomains = researchDomains(options.includeDomains), excludeDomains = researchDomains(options.excludeDomains),
                        maxProviderCalls = (number("maxProviderCalls") ?: if (selection.depth == 100) AcademicNativeClient.CORE.size else 3).coerceIn(1, AcademicNativeClient.CORE.size)
                    ),
                    enabledSelection,
                    permitted
                )
            ).jsonObject
            "get_paper", "read_paper" -> {
                val paper = client.getPaper(str("identifier"), permitted, policy) ?: return buildJsonObject { put("status", "not_found") }
                require(researchDomainAllowed(paper.openAccessUrl?.takeIf(String::isNotBlank) ?: paper.canonicalUrl, researchDomains(options.includeDomains), researchDomains(options.excludeDomains))) { "Paper domain is excluded" }
                if (operation == "get_paper") {
                    json.encodeToJsonElement(paper).jsonObject
                } else {
                    val evidence = client.read(paper, permitted, policy, persistent = persistentEvidence)
                    json.encodeToJsonElement(evidence).jsonObject
                }
            }
            "citations" -> client.citations(str("identifier"), str("direction").ifBlank { "references" }, permitted, policy)
            else -> {
                val evidence = client.evidence(str("paperId"), str("versionId"))
                if (evidence != null) {
                    val connectorXml = runCatching { java.net.URI(evidence.sourceUrl).let { it.host == "www.ebi.ac.uk" && it.path.endsWith("fullTextXML") } }.getOrDefault(false)
                    val documentUrl = if (connectorXml) evidence.canonicalUrl else evidence.sourceUrl
                    require(researchDomainAllowed(documentUrl, researchDomains(options.includeDomains), researchDomains(options.excludeDomains))) { "Evidence domain is excluded" }
                }
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

internal class AcademicRunBudget(private val limit: Int = 30, private val maxReads: Int = 12, private val maxBytes: Int = 256 * 1024, private val shared: dev.chungjungsoo.gptmobile.data.agent.ResearchBudget? = null, private val goalDriven: Boolean = false) {
    private val mutex = Mutex()
    private var calls = 0L
    private var bytes = 0L
    private var reads = 0L
    suspend fun reserve(operation: String, cost: Int = 1, lane: dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.AcquisitionLane = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.AcquisitionLane.READ) = mutex.withLock {
        if (operation in setOf("status", "verify_evidence")) return@withLock
        check(cost in 0..64 && (goalDriven || calls + cost <= limit && bytes < maxBytes)) { "Academic budget exhausted; synthesize the retained evidence" }
        if (operation == "read_paper") {
            check(goalDriven || reads < maxReads) { "Academic reading budget exhausted" }
            reads++
        }
        if (operation != "status") {
            if (cost > 0) check(shared?.tryChargeRequests(cost, lane) != false) { "Shared research request budget exhausted" }
            calls += cost
        }
    }
    suspend fun chargeBytes(count: Int) = mutex.withLock {
        require(count >= 0)
        bytes = Math.addExact(bytes, count.toLong())
        check(shared?.tryChargeDownloadedBytes(count) != false) { "Shared research download budget exhausted" }
        check(goalDriven || bytes <= maxBytes) { "Academic output budget exhausted" }
    }
    fun sharedState() = shared?.ledger?.snapshot()
    suspend fun recordRemote(requests: Long, downloaded: Long, requestsAlreadyReserved: Boolean = false) = mutex.withLock {
        require(requests >= 0 && downloaded >= 0)
        check(shared?.ledger?.charge(physical = if (requestsAlreadyReserved) 0 else requests, downloaded = downloaded) != false) { "User research limit reached" }
        calls = Math.addExact(calls, requests)
        bytes = Math.addExact(bytes, downloaded)
    }
    private fun lane(operation: String) = when (operation) {
        "search_papers" -> dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.AcquisitionLane.SEARCH
        "read_paper" -> dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.AcquisitionLane.READ
        else -> dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.AcquisitionLane.METADATA
    }
    fun gatewayAvailable(operation: String): Long = shared?.ledger?.acquisitionAvailable(lane(operation)) ?: Long.MAX_VALUE
    suspend fun reserveGateway(operation: String, upper: Long): String? = mutex.withLock {
        val ledger = shared?.ledger ?: return@withLock null
        val id = java.util.UUID.randomUUID().toString()
        check(ledger.reserveAcquisition(id, lane(operation), upper)) { "Shared acquisition lane is reserved; gateway was not dispatched" }
        id
    }
    suspend fun settleGateway(id: String, actual: Long?) = mutex.withLock { shared?.ledger?.settleAcquisition(id, actual) }
    val isGoalDriven get() = goalDriven
    fun remaining(): Int = (limit.toLong() - calls).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

internal class AcademicRequestBudgetContext(val budget: AcademicRunBudget, val persistent: Boolean = false, val runKey: String = "", val operation: String = "get_paper") : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AcademicRequestBudgetContext>
}
