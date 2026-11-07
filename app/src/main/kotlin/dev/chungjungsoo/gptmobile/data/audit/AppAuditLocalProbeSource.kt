package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.core.InferenceCandidate
import dev.chungjungsoo.gptmobile.core.InferenceRouting
import dev.chungjungsoo.gptmobile.core.mergeTokenCount
import dev.chungjungsoo.gptmobile.core.tokenCostMicros
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool
import dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadFileSliceTool
import dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
import dev.chungjungsoo.gptmobile.data.github.GitHubRefAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Small deterministic fixtures call production logic without reading personal content. */
class AppAuditLocalProbeSource @Inject constructor() : AppAuditProbeSource {
    override val sourceId: String = "local-contracts"

    override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> = fixtures.map { fixture ->
        AppAuditPlanEntry(
            id = "$sourceId:${fixture.id}",
            sourceId = sourceId,
            probeId = fixture.id,
            componentId = fixture.areas.first(),
            label = fixture.label,
            sourceAreaIds = fixture.areas,
            evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
            fixtureHash = appAuditHash("local-contract-v1:${fixture.id}:${fixture.input}")
        )
    }

    override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
        context.ensureActive()
        val fixture = fixtures.firstOrNull { it.id == entry.probeId } ?: return observation(false, "UNKNOWN_LOCAL_FIXTURE")
        if (entry.fixtureHash != appAuditHash("local-contract-v1:${fixture.id}:${fixture.input}")) return observation(false, "FIXTURE_CHANGED").copy(configChanged = true)
        val passed = when (fixture.id) {
            "calculator" -> {
                val tool = CalculatorTool()
                val result = tool.execute("audit-calculator", buildJsonObject { put("expression", fixture.input) })
                val invalid = tool.execute("audit-calculator-invalid", JsonObject(emptyMap()))
                !result.isError && (result.content as? ToolResultContent.Json)?.value?.jsonObject?.get("formatted") == JsonPrimitive("28") && invalid.isError
            }
            "date-clock" -> {
                val tool = CurrentDateTool(Clock.fixed(Instant.parse(fixture.input), ZoneOffset.UTC))
                val result = tool.execute("audit-date", JsonObject(emptyMap()))
                val data = (result.content as? ToolResultContent.Json)?.value?.jsonObject
                !result.isError && data?.get("date") == JsonPrimitive("2024-02-29") && data["time"] == JsonPrimitive("12:34:56") && data["zone"] == JsonPrimitive("Z")
            }
            "text-slice" -> {
                val tool = ReadFileSliceTool()
                val result = tool.execute(
                    "audit-slice",
                    buildJsonObject {
                        put("content", fixture.input)
                        put("start_line", 2)
                        put("end_line", 9)
                    }
                )
                val invalid = tool.execute(
                    "audit-slice-invalid",
                    buildJsonObject {
                        put("content", fixture.input)
                        put("start_line", 0)
                        put("end_line", 2)
                    }
                )
                !result.isError && (result.content as? ToolResultContent.Text)?.text == "Lines 2-3 of 3:\n2: beta\n3: gamma" && invalid.isError
            }
            "selected-routing" -> {
                val candidates = listOf(InferenceCandidate("selected", true), InferenceCandidate("other", false))
                val retained = InferenceRouting.choose(candidates, "selected", allowFallback = false, localPressure = false, complexTask = true)
                val blocked = InferenceRouting.choose(candidates, "selected", allowFallback = false, localPressure = true, complexTask = true)
                retained.profileId == "selected" && blocked.profileId == null && blocked.reason == "local_hardware_pressure"
            }
            "usage-accounting" -> mergeTokenCount(5, 3, cumulative = true) == 5 && mergeTokenCount(Int.MAX_VALUE, 2, cumulative = false) == Int.MAX_VALUE && tokenCostMicros(1_000_000, 500_000, 2, 4) == 4L
            "dispatch-receipts" -> {
                val unknown = ToolResultEnvelope.error("audit-unknown", "DISPATCH_OUTCOME_UNKNOWN", "Fixture only.", dispatched = true)
                val rejected = ToolResultEnvelope.error("audit-rejected", "PERMISSION_DENIED", "Fixture only.", dispatched = false)
                ToolResultEnvelope.executionState(unknown) == "UNKNOWN_OUTCOME" && ToolResultEnvelope.executionState(rejected) == "PRECHECK_REJECTED" && unknown.dispatched && !rejected.dispatched
            }
            "diagnostic-redaction" -> {
                val raw = buildJsonObject {
                    put("authorization", "fixture-secret")
                    put("url", fixture.input)
                }
                val sanitized = DiagnosticRedactor.arguments(raw)
                "fixture-secret" !in sanitized && "fixture-password" !in sanitized && "fixture-query" !in sanitized && "redacted" in sanitized
            }
            "research-identity" -> {
                val normalized = SourceIdentity.create(fixture.input)
                val credentialed = SourceIdentity.canonicalResearchUrl("https://fixture-user:fixture-password@example.org/paper")
                normalized.canonicalUrl == "https://example.org/paper?id=7" && credentialed.isEmpty() && SourceIdentity.normalizeDoi("https://doi.org/10.1000/EXAMPLE.") == "10.1000/example"
            }
            "gateway-feature-types" -> {
                val caps = Json.decodeFromString<GatewayCapabilities>(fixture.input)
                caps.supportsFeature("enabled") && listOf("string", "number", "disabled", "nothing", "list", "object").none(caps::supportsFeature)
            }
            "github-ref-admission" -> {
                val availability = GitHubRefAvailability(now = { 1_000 })
                availability.failed(fixture.input, 404, "Fixture ref unavailable.")
                val rejected = try {
                    availability.requireAvailable(fixture.input)
                    false
                } catch (failure: GitHubReadValidation) {
                    failure.code == "GITHUB_REF_UNAVAILABLE" && failure.details["dispatched"] == JsonPrimitive(false) && failure.details["automatic_branch_substitution"] == JsonPrimitive(false)
                }
                availability.requireAvailable("/repos/audit/fixture/commits/other-ref")
                availability.succeeded(fixture.input)
                availability.requireAvailable(fixture.input)
                rejected
            }
            "audit-result-codec" -> {
                val original = AppAuditResult("fixture", observation(true, "FIXTURE_PASSED"), 1, 2, 1, attempted = true)
                val encoded = Json.encodeToString(original)
                val decoded = Json.decodeFromString<AppAuditResult>(encoded)
                decoded == original && Json.parseToJsonElement(encoded).jsonObject["entryId"]?.jsonPrimitive?.content == "fixture"
            }
            else -> false
        }
        return observation(passed, if (passed) "FIXTURE_PASSED" else "FIXTURE_FAILED")
    }

    private fun observation(passed: Boolean, code: String) = AppAuditObservation(
        outcome = if (passed) AppAuditOutcome.PASS else AppAuditOutcome.FAIL,
        reasonCode = code,
        evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT,
        observationSource = AppAuditObservationSource.FIXTURE,
        metrics = listOf(AppAuditMetric(AppAuditMetricName.CHECK_COUNT, 1.0))
    )

    private data class Fixture(val id: String, val label: String, val areas: List<String>, val input: String)

    private val fixtures = listOf(
        Fixture("calculator", "Calculator arithmetic and argument validation", listOf("agent_tools"), "sqrt(144) + 2^4"),
        Fixture("date-clock", "Date tool with a fixed leap-day clock", listOf("agent_tools"), "2024-02-29T12:34:56Z"),
        Fixture("text-slice", "Bounded text slices and invalid ranges", listOf("agent_tools"), "alpha\nbeta\ngamma"),
        Fixture("selected-routing", "Selected-profile routing and pressure refusal", listOf("portable_domain", "models_and_profiles"), "selected-local;other-remote;fallback-disabled"),
        Fixture("usage-accounting", "Token counter and exact cost arithmetic", listOf("portable_domain"), "cumulative=5+3;overflow-clamp;cost=4"),
        Fixture("dispatch-receipts", "Unknown outcomes remain distinct from rejection", listOf("agent_execution", "auth_and_security"), "unknown-dispatched;permission-not-dispatched"),
        Fixture("diagnostic-redaction", "Diagnostic credential redaction", listOf("diagnostics_and_export", "auth_and_security"), "https://fixture-user:fixture-password@example.org/?key=fixture-query"),
        Fixture("research-identity", "Research URL and DOI identity normalization", listOf("research"), "https://www.example.org/paper?utm_source=fixture&id=7#section"),
        Fixture("gateway-feature-types", "Gateway feature flags accept only JSON true", listOf("gateway_client"), "{\"features\":{\"enabled\":true,\"string\":\"true\",\"number\":1,\"disabled\":false,\"nothing\":null,\"list\":[],\"object\":{}}}"),
        Fixture("github-ref-admission", "GitHub exact-ref rejection and recovery", listOf("github"), "/repos/audit/fixture/commits/selected-ref"),
        Fixture("audit-result-codec", "Audit evidence JSON round trip", listOf("benchmark", "persistence"), "app-audit-result-v1")
    )
}
