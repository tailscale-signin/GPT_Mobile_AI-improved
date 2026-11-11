package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticExportGate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Audit-only support evidence. Caller-owned streams remain open on success and failure. */
suspend fun writeAppAuditExport(output: OutputStream, batch: BenchmarkBatch, sourceInventory: InputStream? = null) = withContext(Dispatchers.IO) {
    DiagnosticExportGate.run {
        require(batch.mode == BenchmarkMode.APP_AUDIT) { "Select an app audit to export." }
        val run = requireNotNull(batch.audit) { "App audit evidence is unavailable." }
        val retainedPlan = run.plan.take(MAX_EXPORT_CHECKS).distinctBy { it.id }
        val ids = retainedPlan.mapIndexed { index, entry -> entry.id to "check-${index + 1}" }.toMap()
        val targets = appAuditTargetAliases(retainedPlan)
        val retainedResults = run.results.asSequence().filter { it.entryId in ids }.distinctBy { it.entryId }.take(MAX_EXPORT_CHECKS).toList()
        val omittedPlan = run.plan.size - retainedPlan.size
        val omittedResults = run.results.size - retainedResults.size
        val boundedRun = run.copy(plan = retainedPlan, results = retainedResults)
        val summary = appAuditSummary(boundedRun)
        val score = appAuditScore(boundedRun)
        val completeEvidence = omittedPlan == 0 && omittedResults == 0
        val inventory = readAppAuditInventory(sourceInventory, run)
        val coroutine = currentCoroutineContext()
        val writer = AppAuditJsonWriter(output) { coroutine.ensureActive() }
        writer.field("format", JsonPrimitive("gptmobile.app-audit.v1"))
        writer.field("schemaVersion", JsonPrimitive(1))
        writer.field("privacyPolicy", JsonPrimitive("app-audit-allowlist-v2"))
        writer.field("recordScope", JsonPrimitive("Selected app audit only; no model answers, chat history, credentials, tool bodies or uncorrelated application logs."))
        writer.field(
            "run",
            buildJsonObject {
                put("id", safeRunId(run.id))
                put("state", batch.state.name)
                put("startedAtEpochMs", run.startedAtEpochMs.nonnegative())
                put("updatedAtEpochMs", run.updatedAtEpochMs.nonnegative())
                put("finishedAtEpochMs", run.finishedAtEpochMs?.nonnegative() ?: JsonNull)
                put("activeCheckId", run.activeProbeId?.let(ids::get))
                put("activeDispatchEvidence", run.activeDispatchEvidence.name)
                put("stopReasonCode", run.stopReasonCode?.let(::safeAuditReason))
                put("catalogVersion", if (run.catalogVersion == "app-audit-v1") run.catalogVersion else "UNKNOWN_VERSION")
                put("catalogHash", safeDigest(run.catalogHash))
                put("selectedProfileCount", run.options.selectedProfileIds.distinct().size)
                put("networkReadsSelected", run.options.allowNetworkReads)
                put("executedProbes", run.executedProbes.coerceAtLeast(0))
                put("networkAccountingVersion", 2)
                put("networkProbesAttempted", run.networkAttemptBudgetUsed.coerceAtLeast(0))
                put("networkProbesDispatched", run.networkProbesConfirmed)
                put("networkProbesConfirmed", run.networkProbesConfirmed)
                put("networkProbesDispatchUnknown", run.networkProbesDispatchUnknown)
                put("networkAccountingMeaning", "Attempts are durable logical client admissions and consume budget even when later rejected locally. Confirmed and unknown dispatches come from fresh receipts; cached evidence sends no request.")
                put("maxExecutedProbes", run.limits.maxExecutedProbes)
                put("maxNetworkProbes", run.limits.maxNetworkProbes)
                put("perProbeTimeoutMs", run.limits.perProbeTimeoutMs)
                put("totalTimeoutMs", run.limits.totalTimeoutMs)
            }
        )
        writer.field(
            "build",
            buildJsonObject {
                put("sourceCommit", run.sourceCommit.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) })
                put("sourceIdentityMeaning", "Build-time app commit declaration; remote service source is not inferred from app or catalogue metadata.")
                put("version", run.buildVersion.takeIf { it.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,5}){1,4}")) })
                put("versionCode", safeAppAuditEnvironment(run.environment)?.versionCode?.let(::JsonPrimitive) ?: JsonNull)
                put("variant", run.buildVariant.takeIf { it in setOf("debug", "release", "benchmark") } ?: "unknown")
                put("installedArtifact", appAuditInstalledArtifactJson(run.environment))
            }
        )
        writer.field("device", appAuditDeviceIdentityJson(run.environment))
        writer.field(
            "sourceCoverage",
            buildJsonObject {
                put("sourceInventoryDigest", safeDigest(run.sourceInventoryDigest))
                put("indexedFiles", run.sourceFileCount.coerceAtLeast(0))
                put("indexedTextLines", run.sourceLineCount.coerceAtLeast(0))
                put("lineCoverageAvailable", false)
                put("lineCoveragePercent", JsonNull)
                put("lineCoverageReason", if (run.lineCoverageAvailable) "INVALID_COVERAGE_CLAIM" else "NO_EXECUTION_INSTRUMENTATION")
                put("meaning", "Indexed files and source-area associations are a component catalogue, not executed file, line or branch coverage.")
                put("sourceAreasPlanned", summary.sourceAreasPlanned)
                put("sourceAreasAssociatedWithExercisedChecks", summary.sourceAreasExercised)
                put("fileInventoryStatus", inventory.reason)
            }
        )
        writer.records(
            "sourceAreas",
            run.sourceAreas.asSequence().take(MAX_SOURCE_AREAS).map { area ->
                buildJsonObject {
                    put("id", safeArea(area.id))
                    put("indexedFiles", area.fileCount.coerceAtLeast(0))
                    put("indexedTextLines", area.lineCount.coerceAtLeast(0))
                }
            }
        )
        writer.field(
            "coverage",
            buildJsonObject {
                put("planned", run.plan.size)
                put("retainedPlanEntries", retainedPlan.size)
                put("accounted", retainedResults.size)
                put("passed", summary.passed)
                put("failed", summary.failed)
                put("skipped", summary.skipped)
                put("blocked", summary.blocked)
                put("unavailable", summary.unavailable)
                put("untested", summary.untested + omittedPlan)
                put("canceled", summary.canceled)
                put("exercised", summary.exercised)
                put("verified", summary.verified)
                put("exercisedDenominator", run.plan.size)
                put("verifiedDenominator", run.plan.size)
                put("completeRetainedEvidence", completeEvidence)
            }
        )
        writer.field(
            "score",
            buildJsonObject {
                put("policy", "app-audit-measured-pass-v1")
                put("value", score.score?.takeIf { completeEvidence }?.let(::JsonPrimitive) ?: JsonNull)
                put("passedWeight", score.passedWeight)
                put("measuredWeight", score.measuredWeight)
                put(
                    "reason",
                    when {
                        !completeEvidence -> "EXPORT_EVIDENCE_TRUNCATED"
                        score.score == null -> "NO_MEASURED_ASSERTIONS"
                        else -> "MEASURED_ASSERTIONS_ONLY"
                    }
                )
                put("meaning", "Passed weight / measured pass-or-fail weight. Configuration, cache, unavailable, blocked, skipped and untested rows do not grant success. No general intelligence, hardware or whole-app quality score is claimed.")
            }
        )
        writer.records(
            "checks",
            retainedPlan.asSequence().map { entry ->
                buildJsonObject {
                    put("id", ids.getValue(entry.id))
                    put("sourceId", safeSource(entry.sourceId))
                    put("probeId", safeProbe(entry.probeId))
                    put("label", safeAuditLabel(entry.probeId, entry.componentId))
                    put("componentId", safeComponent(entry.componentId))
                    put("targetAlias", entry.targetId?.let(targets::get))
                    put("sourceAreaIds", JsonArray(entry.sourceAreaIds.take(MAX_SOURCE_AREAS).map { JsonPrimitive(safeArea(it)) }))
                    put("evidenceKind", entry.evidenceKind.name)
                    put("safetyClass", entry.safetyClass.name)
                    put("selected", entry.selected)
                    put("prerequisite", entry.prerequisite.name)
                    put("reasonCode", entry.reasonCode?.let(::safeAuditReason))
                    put("weight", entry.weight)
                    put("fixtureHash", safeDigest(entry.fixtureHash))
                    put("configurationRevision", safeAuditConfigurationRevision(entry.configurationRevision))
                }
            }
        )
        writer.records(
            "results",
            retainedResults.asSequence().map { result ->
                val observation = result.observation
                buildJsonObject {
                    put("checkId", ids.getValue(result.entryId))
                    put("outcome", observation.outcome.name)
                    put("reasonCode", safeAuditReason(observation.reasonCode))
                    put("stage", observation.stage.takeIf { it in AUDIT_STAGES } ?: "UNKNOWN_STAGE")
                    put("evidenceKind", observation.evidenceKind.name)
                    put("observationSource", observation.observationSource.name)
                    put("dispatchEvidence", observation.dispatchEvidence.name)
                    put("attempted", result.attempted)
                    put("startedAtEpochMs", result.startedAtEpochMs.nonnegative())
                    put("finishedAtEpochMs", result.finishedAtEpochMs.nonnegative())
                    put("durationMs", result.durationMs?.nonnegative() ?: JsonNull)
                    put("retryable", observation.retryable?.let(::JsonPrimitive) ?: JsonNull)
                    put("httpStatus", observation.httpStatus?.takeIf { it in 100..599 }?.let(::JsonPrimitive) ?: JsonNull)
                    put("configurationChanged", observation.configChanged)
                    put("failurePhase", safeAuditFailurePhase(observation.failurePhase))
                    put("failureCategory", safeAuditFailureCategory(observation.failureCategory))
                    put("validationRule", safeAuditValidationRule(observation.validationRule))
                    put("validationField", safeAuditDiagnosticField(observation.validationField))
                    put("observedAtEpochMs", observation.observedAtEpochMs?.nonnegative() ?: JsonNull)
                    put("configurationRevision", safeAuditConfigurationRevision(observation.configurationRevision))
                    put("remoteServiceIdentity", appAuditRemoteIdentityJson(observation.remoteIdentity) ?: JsonNull)
                    put("remoteServiceIdentityStatus", appAuditRemoteIdentityStatus(observation.remoteIdentity))
                    observation.gatewayReadiness?.let { gateway ->
                        put(
                            "engineeringGatewayReadiness",
                            buildJsonObject {
                                put("savedBinding", gateway.savedBinding)
                                put("engineeringReady", gateway.engineeringReady?.let(::JsonPrimitive) ?: JsonNull)
                                put("hostGitHubConfigured", gateway.hostGitHubConfigured?.let(::JsonPrimitive) ?: JsonNull)
                                put("selectedModelReady", gateway.selectedModelReady?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        )
                    }
                    put("remoteSourceIndependentlyVerified", false)
                    put(
                        "metrics",
                        JsonArray(
                            observation.metrics.take(run.limits.maxMetricsPerProbe).map { metric ->
                                buildJsonObject {
                                    put("name", metric.name.name)
                                    put("value", metric.value.takeIf { it.isFinite() && it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
                                    put("unit", metric.unit.name)
                                }
                            }
                        )
                    )
                }
            }
        )
        writer.records(
            "evidenceByKind",
            AppAuditEvidenceKind.entries.asSequence().map { kind ->
                val scoped = boundedRun.copy(plan = retainedPlan.filter { it.evidenceKind == kind })
                val counts = appAuditSummary(scoped)
                val measured = appAuditScore(scoped)
                buildJsonObject {
                    put("evidenceKind", kind.name)
                    put("planned", counts.planned)
                    put("passed", counts.passed)
                    put("failed", counts.failed)
                    put("exercised", counts.exercised)
                    put("verified", counts.verified)
                    put("passedWeight", measured.passedWeight)
                    put("measuredWeight", measured.measuredWeight)
                    put("score", measured.score?.takeIf { completeEvidence }?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        )
        writer.field("qualificationPlan", appAuditQualificationPlan(boundedRun, ids, targets, completeEvidence))
        writer.records("sourceFiles", inventory.files.asSequence())
        writer.field(
            "diagnostics",
            buildJsonObject {
                put("scope", "Probe receipts and typed failures above; no raw exception, provider response, argument or private map key is copied.")
                put("checkIdMapping", "Frozen plan order; aliases are stable for this run and keep all result references joined.")
                put("omittedPlanEntries", omittedPlan)
                put("omittedResults", omittedResults)
                put("omittedSourceAreas", (run.sourceAreas.size - MAX_SOURCE_AREAS).coerceAtLeast(0))
                put("omittedMetrics", retainedResults.sumOf { (it.observation.metrics.size - run.limits.maxMetricsPerProbe).coerceAtLeast(0) })
                put("unknownReasonCodes", retainedResults.count { safeAuditReason(it.reasonCode) == "UNKNOWN_REASON" })
                put("maxExportBytes", MAX_EXPORT_BYTES)
                put("maxSourceInventoryBytes", MAX_INVENTORY_BYTES)
                put("maxRetainedChecks", MAX_EXPORT_CHECKS)
            }
        )
        writer.finish()
    }
}

private data class AuditInventoryExport(val reason: String, val files: List<JsonObject> = emptyList())

private suspend fun readAppAuditInventory(input: InputStream?, run: AppAuditRun): AuditInventoryExport {
    if (input == null) return AuditInventoryExport("SOURCE_INVENTORY_NOT_ATTACHED")
    if (run.sourceInventoryDigest != AppAuditSourceInventory.sourceDigest) return AuditInventoryExport("SOURCE_INVENTORY_BUILD_MISMATCH")
    return try {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var noProgressReads = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++noProgressReads >= MAX_NO_PROGRESS_READS) return AuditInventoryExport("SOURCE_INVENTORY_NO_PROGRESS")
                continue
            }
            noProgressReads = 0
            if (bytes.size() + count > MAX_INVENTORY_BYTES) return AuditInventoryExport("SOURCE_INVENTORY_BYTE_LIMIT")
            bytes.write(buffer, 0, count)
        }
        val root = Json.parseToJsonElement(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
        val files = (root["files"] as? JsonArray)?.map { it as? JsonObject ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID") }
            ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
        if (files.size > MAX_SOURCE_FILES ||
            files.size != run.sourceFileCount ||
            root.text("source_digest") != run.sourceInventoryDigest ||
            (root["schema_version"] as? JsonPrimitive)?.intOrNull != 1 ||
            (root["line_coverage_available"] as? JsonPrimitive)?.booleanOrNull != false
        ) {
            return AuditInventoryExport("SOURCE_INVENTORY_MISMATCH")
        }
        val paths = hashSetOf<String>()
        var lines = 0L
        val frozenAreas = run.sourceAreas.associateBy { it.id }
        val areaCounts = mutableMapOf<String, Pair<Int, Long>>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val path = file.text("path") ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
            val area = file.text("area_id") ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
            val count = (file["indexed_lines"] as? JsonPrimitive)?.longOrNull ?: return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
            if (file.keys != setOf("area_id", "indexed_lines", "kind", "path", "sha256") ||
                !paths.add(path) ||
                !safeInventoryPath(path) ||
                area !in frozenAreas ||
                safeArea(area) != area ||
                count !in 0..100_000_000L ||
                file.text("kind") !in setOf("source", "ui_resource") ||
                safeDigest(file.text("sha256")) == null
            ) {
                return AuditInventoryExport("SOURCE_INVENTORY_INVALID")
            }
            lines += count
            val old = areaCounts[area] ?: (0 to 0L)
            areaCounts[area] = old.first + 1 to old.second + count
        }
        if (lines != run.sourceLineCount.toLong() ||
            frozenAreas.values.any { area ->
                val count = areaCounts[area.id] ?: (0 to 0L)
                count.first != area.fileCount || count.second != area.lineCount.toLong()
            } ||
            appAuditInventoryDigest(files) != run.sourceInventoryDigest
        ) {
            return AuditInventoryExport("SOURCE_INVENTORY_MISMATCH")
        }
        AuditInventoryExport("MATCHED_BUILD_INVENTORY", files)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AuditInventoryExport("SOURCE_INVENTORY_UNAVAILABLE")
    }
}

/** Matches the generator's sorted-key, ASCII JSON digest without building another full document. */
internal fun appAuditInventoryDigest(files: List<JsonObject>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) = digest.update(value.toByteArray(Charsets.UTF_8))
    add("[")
    files.forEachIndexed { index, file ->
        if (index > 0) add(",")
        add("{")
        listOf("area_id", "indexed_lines", "kind", "path", "sha256").forEachIndexed { fieldIndex, key ->
            if (fieldIndex > 0) add(",")
            add(asciiJsonString(key))
            add(":")
            add(if (key == "indexed_lines") (file[key] as JsonPrimitive).longOrNull.toString() else asciiJsonString(file.text(key).orEmpty()))
        }
        add("}")
    }
    add("]")
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun asciiJsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        append(
            when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\u000c' -> "\\f"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code !in 32..126) "\\u${character.code.toString(16).padStart(4, '0')}" else character.toString()
            }
        )
    }
    append('"')
}

private fun safeInventoryPath(path: String): Boolean = path.length in 1..1024 &&
    path.none(Char::isISOControl) &&
    '\\' !in path &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
    listOf("app/src/main/", "local-gguf-runtime/src/main/", "core-domain/src/commonMain/", "core-domain/src/jvmMain/", "gateway/", "authentication/", "mcp/").any(path::startsWith)

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun safeDigest(value: String?): String? = value?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
private fun Long.nonnegative(): JsonElement = takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull
private fun safeRunId(value: String): String = value.takeIf { it.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) } ?: "audit-${appAuditTargetDigest(value)}"
private fun safeArea(value: String): String = value.takeIf { id -> AppAuditSourceInventory.areas.any { it.id == id } } ?: "unknown_source_area"
private fun safeSource(value: String): String = value.takeIf { it in setOf("local-contracts", "plugins", "source-inventory", "behavior", "appearance", "research_contract", "memory_contract", "selected_models") } ?: "unknown_source"
private fun safeProbe(value: String): String = value.takeIf { it in AUDIT_LABELS } ?: "unknown_probe"
private fun safeAuditLabel(probe: String, component: String): String =
    AppAuditPluginProbeSource.label(probe, component) ?: AUDIT_LABELS[probe] ?: "Unrecognized audit check"
private fun safeComponent(value: String): String = value.takeIf {
    it in setOf("mcp_connection", "configured_connection", "gateway_connection", AppAuditPluginProbeSource.ENGINEERING_GATEWAY_COMPONENT, "selected_model", "plugins", "local-contracts") ||
        safeArea(it) == it ||
        dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog.integrated.any { service -> service.id == it } ||
        dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.packages.any { entry -> "marketplace:${entry.id}" == it }
} ?: "unknown_component"

internal fun safeAuditReason(value: String): String = value.takeIf { it in AUDIT_REASONS } ?: "UNKNOWN_REASON"

private val AUDIT_LABELS = AppAuditPluginProbeSource.labels + AppAuditSelectedModelProbeSource.labels + mapOf(
    "calculator" to "Calculator arithmetic and argument validation", "date-clock" to "Date tool with a fixed leap-day clock",
    "text-slice" to "Bounded text slices and invalid ranges", "selected-routing" to "Selected-profile routing and pressure refusal",
    "usage-accounting" to "Token counter and exact cost arithmetic", "dispatch-receipts" to "Unknown outcomes remain distinct from rejection",
    "diagnostic-redaction" to "Diagnostic credential redaction", "research-identity" to "Research URL and DOI identity normalization",
    "gateway-feature-types" to "Gateway feature flags accept only JSON true", "github-ref-admission" to "GitHub exact-ref rejection and recovery",
    "audit-result-codec" to "Audit evidence JSON round trip", "external-coverage" to "External execution coverage required",
    "inventory-unavailable" to "Component inventory unavailable", "device-performance" to "Device rendering and resource measurements",
    "budget_precheck_v1" to "Argument admission and shared tool budget",
    "authorization_denial_v1" to "Denied tool authority dispatches no work",
    "unknown_receipt_v1" to "Unknown tool outcome requires reconciliation",
    "grant_scope_v1" to "Permission identity binds schema, chat and resource",
    "runner_isolation_v1" to "Agent collections retain independent execution budgets",
    "response_origin_v1" to "Gateway colour requires verified response origin",
    "theme_contrast_v1" to "Gateway theme foreground contrast calculation",
    "authority_projection_v1" to "Public research transfer excludes private context",
    "memory_eligibility_v1" to "Memory recall privacy, review and conversation gates",
    "memory_validity_v1" to "Memory validity boundaries and decay arithmetic",
    "memory_graph_v1" to "Knowledge graph extraction and bounded recall"
)
private val AUDIT_STAGES = AppAuditPluginProbeSource.stages + setOf("admission", "execution", "interruption", "validation", "fixture")
private val AUDIT_REASONS = AppAuditPluginProbeSource.reasonCodes + AppAuditSelectedModelProbeSource.reasonCodes + setOf(
    "AUDIT_VERSION_CHANGED", "INVALID_COVERAGE_CLAIM", "AUDIT_SOURCE_CHANGED", "AUDIT_MANIFEST_CHANGED", "INVALID_AUDIT_MANIFEST",
    "INVALID_AUDIT_RECEIPTS", "INVALID_AUDIT_BUDGET", "RUN_BUDGET_EXHAUSTED", "RUN_DEADLINE_EXCEEDED", "NOT_SELECTED", "NOT_CONFIGURED",
    "UNSUPPORTED", "PERMISSION_REQUIRED", "EXTERNAL_HARNESS_REQUIRED", "MODEL_EXECUTION_NOT_SELECTED", "NETWORK_READS_DISABLED",
    "NETWORK_BUDGET_EXHAUSTED", "PROBE_SOURCE_UNAVAILABLE", "INVENTORY_UNAVAILABLE", "DISPATCH_NOT_ALLOWED", "PROBE_TIMEOUT",
    "CHILD_OPERATION_ABORTED", "PROBE_FAILED", "PROBE_EVIDENCE_MISMATCH", "DISPATCH_RECEIPT_MISSING", "DISPATCH_CONFIRMATION_MISSING", "CONFIGURATION_CHANGED", "USER_STOP",
    "SERVICE_INTERRUPTION", "PROCESS_INTERRUPTION", "STORAGE_CHECKPOINT_FAILED", "FIXTURE_PASSED", "FIXTURE_FAILED", "FIXTURE_CHANGED", "UNKNOWN_LOCAL_FIXTURE",
    "PROBE_CONTRACT_INVALID", "DISPATCH_POLICY_CHANGED", "POLICY_STALE", "AUDIT_PROBE_CHANGED", "LOCAL_CONTRACT_PASSED", "LOCAL_CONTRACT_FAILED", "DEVICE_MEASUREMENT_REQUIRED"
)

private const val MAX_EXPORT_CHECKS = 4096
private const val MAX_SOURCE_AREAS = 64
private const val MAX_SOURCE_FILES = 10_000
private const val MAX_INVENTORY_BYTES = 2 * 1024 * 1024
private const val MAX_NO_PROGRESS_READS = 8
private const val MAX_EXPORT_BYTES = 8L * 1024 * 1024

@OptIn(ExperimentalSerializationApi::class)
private class AppAuditJsonWriter(output: OutputStream, private val checkActive: () -> Unit) {
    private var bytes = 0L
    private var first = true
    private val bounded = object : OutputStream() {
        override fun write(value: Int) {
            checkActive()
            if (bytes >= MAX_EXPORT_BYTES) throw IOException("AUDIT_EXPORT_BYTE_LIMIT")
            output.write(value)
            bytes++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkActive()
            if (length > MAX_EXPORT_BYTES - bytes) throw IOException("AUDIT_EXPORT_BYTE_LIMIT")
            output.write(buffer, offset, length)
            bytes += length
        }
        override fun flush() = output.flush()
    }

    init {
        bounded.write('{'.code)
    }

    private fun writeName(value: String) {
        checkActive()
        if (!first) bounded.write(','.code)
        first = false
        Json.encodeToStream(JsonElement.serializer(), JsonPrimitive(value), bounded)
        bounded.write(':'.code)
    }

    fun field(name: String, value: JsonElement) {
        writeName(name)
        Json.encodeToStream(JsonElement.serializer(), value, bounded)
    }

    fun records(name: String, values: Sequence<JsonElement>) {
        writeName(name)
        bounded.write('['.code)
        var firstValue = true
        values.forEach { value ->
            checkActive()
            if (!firstValue) bounded.write(','.code)
            firstValue = false
            Json.encodeToStream(JsonElement.serializer(), value, bounded)
        }
        bounded.write(']'.code)
    }

    fun finish() {
        checkActive()
        bounded.write('}'.code)
        bounded.flush()
    }
}
