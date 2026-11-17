package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.AtomicFile
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.voice.VoiceDiagnosticTrace
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reporting may lose evidence, but must never replace an operation's outcome. */
internal inline fun <T> captureIssueReport(capture: () -> T): T? = try {
    capture()
} catch (failure: Throwable) {
    if (failure is CancellationException || failure !is Exception && failure !is LinkageError) throw failure
    AppLogRecorder.operationCaptureEpoch()?.let { epoch ->
        AppLogRecorder.recordCaptured(epoch, "Diagnostics", "ISSUE_REPORT_CAPTURE_UNAVAILABLE", "W")
    }
    null
}

/** Local projection for public reports. Raw messages, identifiers and OS logcat never enter it. */
internal object IssueReportIncidents {
    private const val RECENT_MILLIS = 60_000L
    private const val DUPLICATE_MILLIS = 3_000L
    private const val PENDING_LIMIT = 24
    private val lock = Any()
    private data class RecentEvent(val timeMillis: Long, val line: String, val codedWarning: Boolean)

    private val events = ArrayDeque<RecentEvent>()
    private data class FailureKey(
        val feature: ReportFeature,
        val code: String,
        val runId: String?,
        val invocationId: String?,
        val callId: String?,
        val jobId: String?,
        val activeJobId: String?,
        val stage: String?,
        val exceptionFingerprint: String?
    )
    private val lastFailures = mutableMapOf<FailureKey, Long>()
    private val lastCauses = mutableMapOf<IssueReportIncidentCapsule.CausalIdentity, Long>()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCorrelations = Channel<IssueReport>(PENDING_LIMIT)
    private val mutablePending = MutableStateFlow<List<IssueReport>>(emptyList())
    private val mutablePrivate = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()
    val privateSession = mutablePrivate.asStateFlow()

    @Volatile private var context: Context? = null

    @Volatile private var processState: SharedPreferences? = null

    @Volatile private var correlations: IssueReportCorrelationIndex? = null
    private val isPrivate: Boolean get() = mutablePrivate.value || AppLogRecorder.privateSessionActive

    init {
        persistenceScope.launch {
            for (report in pendingCorrelations) {
                try {
                    retainCorrelation(report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Send and export retry this write and expose failure explicitly.
                }
            }
        }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        correlations = null
        clearDiagnosticSnapshots()
    }

    private suspend fun correlationIndex(): IssueReportCorrelationIndex = withContext(Dispatchers.IO) {
        correlations ?: run {
            val owner = checkNotNull(context) { "Issue reporting is not initialized." }
            IssueReportCorrelationIndex(File(owner.noBackupFilesDir, "issue-report-correlations.json")).also {
                if (context === owner) correlations = it
            }
        }
    }

    /** Approved Send waits for this join before delivery; retries retain the same public UUID. */
    suspend fun retainCorrelation(report: IssueReport) {
        correlationIndex().retain(report)
    }

    suspend fun correlationSnapshot(): List<JsonObject> {
        if (isPrivate) return emptyList()
        val index = correlationIndex()
        pending.value.forEach { index.retain(it) }
        return index.snapshot().takeUnless { isPrivate }.orEmpty()
    }

    fun privacyChanged(active: Boolean) = synchronized(lock) {
        mutablePrivate.value = active
        if (active) clearRecent()
        processState?.edit()?.putBoolean("private", active)?.apply()
    }

    fun clearRecent() = synchronized(lock) {
        events.clear()
        lastFailures.clear()
        lastCauses.clear()
        clearDiagnosticSnapshots()
    }

    private fun clearDiagnosticSnapshots() {
        ReportDiagnosticSnapshots.clear()
        VoiceDiagnosticTrace.clearAll()
    }

    /** Explicit manual selection only; automatic failures use their own producer fields. */
    fun diagnosticCaptureEnabled(): Boolean = LocalDiagnosticsPolicy.enabled && !isPrivate

    fun diagnosticSnapshot(area: String): IssueReportDiagnostics? = synchronized(lock) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate || area !in IssueReportDiagnostics.areas) return null
        if (area == "VOICE") VoiceDiagnosticTrace.manualSnapshot()?.let(ReportDiagnosticSnapshots::retain)
        ReportDiagnosticSnapshots.latest(area)
    }

    /** A later scheduler observation is available for an explicit follow-up, never appended to approved bytes. */
    fun providerRecoveryObservation(fields: Map<String, Any?>) = synchronized(lock) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate) return@synchronized
        fun hash(key: String): String? {
            val text = when (val value = fields[key]) {
                is String -> value
                is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
                else -> null
            }
            return text?.takeIf { it.isNotBlank() && it.length <= 256 }?.let(::researchHash)
        }
        val evidence = IssueReportContext.fromFields(
            "STRUCTURED_EVENT",
            IssueReportIncidentCapsule.fields(
                ReportFeature.CHAT,
                "STRUCTURED_EVENT",
                "PROVIDER_RATE_LIMITED",
                fields + mapOf("diagnostic_area" to "PROVIDER", "profile_hash" to hash("profile_id"), "model_hash" to hash("model"))
            )
        )
        if (evidence.runIdHash != null) evidence.diagnostics?.let(ReportDiagnosticSnapshots::retain)
    }

    fun recentEvents(): List<String> = synchronized(lock) {
        if (isPrivate) return emptyList()
        val now = System.nanoTime() / 1_000_000L
        events.filter { it.timeMillis >= now - RECENT_MILLIS }.map { "-${(now - it.timeMillis).coerceAtLeast(0)}ms ${it.line}" }
    }

    fun log(tag: String, level: String, tracking: Boolean) = log(tag, level, tracking, null)

    fun log(tag: String, level: String, tracking: Boolean, code: String?, error: Throwable? = null, fields: Map<String, Any?> = emptyMap(), source: String? = null) {
        if (!LocalDiagnosticsPolicy.enabled && !tracking || isPrivate) return
        val severity = level.firstOrNull()?.takeIf { it in "DIWEF" } ?: return
        // Only application-owned tags with known meaning. Ignore raw logcat and the reporting pipeline.
        val feature = when (tag) {
            "Application", "Startup", "Database", "Download", "PluginSettings", "Rendering" -> ReportFeature.APP
            "Send", "Combined", "CombinedRetry", "Streaming", "Provider", "Network", "Recovery" -> ReportFeature.CHAT
            "VoiceEngine", "VoiceTiming", "VoiceRecognition", "VoicePlayback" -> ReportFeature.VOICE
            "Gateway", "GatewayQueue", "GatewayObserver", "GitHub", "GitHubExecution" -> ReportFeature.GATEWAY
            "LocalModel", "LocalImport", "LocalConnection", "LocalAssist" -> ReportFeature.LOCAL
            "Tools", "Tool", "Agent", "ToolCatalog", "MCP", "Research", "ResearchExamine" -> ReportFeature.TOOLS
            else -> return
        }
        if (severity == 'E' && LocalDiagnosticsPolicy.enabled && feature != ReportFeature.CHAT && feature != ReportFeature.VOICE) {
            val evidence = if (tag == "MCP") fields + ("operation" to "mcp") else fields
            val reportSource = source?.takeIf { it in IssueReportContext.sources } ?: "STRUCTURED_EVENT".takeIf { evidence.isNotEmpty() }
            val toolContext = if (reportSource == "TOOL_RESULT") IssueReportContext.fromFields(reportSource, evidence) else null
            val toolPrecheck = toolContext?.let { it.stage == "tool_precheck" && it.dispatched == false } == true
            val precheck = if (feature == ReportFeature.TOOLS && toolContext != null) {
                IssueReportCodes.nativeGitHubPrecheck(code, toolContext)
            } else {
                null
            }
            val reportEvidence = if (precheck?.recoveryAction != null &&
                evidence.keys.none { it in setOf("recovery_action", "next_action", "recoveryAction", "nextAction") }
            ) {
                evidence + ("recovery_action" to precheck.recoveryAction)
            } else {
                evidence
            }
            val capturedBudgetKind = toolContext?.diagnostics?.values?.get("tool_budget_kind") != null
            val sourceBudgetKind = reportSource == "TOOL_RESULT" && evidence["tool_budget_kind"] in setOf("CALLS", "RESULT_BYTES", "BOTH")
            val toolBudget = toolPrecheck && (capturedBudgetKind || sourceBudgetKind)
            // Exhaustion can accompany the last admitted call's specific failure; it is not its cause.
            val fallbackCode = if (toolBudget) {
                "TOOL_BUDGET_EXHAUSTED"
            } else if (toolPrecheck) {
                "TOOL_FAILURE"
            } else {
                "APP_ERROR"
            }
            // A trusted local dependency admission can reject a tool before
            // dispatch because no call allowance remains. Generic TOOL_FAILURE
            // must not hide this distinct, actionable budget condition.
            val validatedAdmissionReasons = (evidence["dependency_admission_reasons"] as? List<*>)
                ?.takeIf { reasons ->
                    reasons.isNotEmpty() &&
                        reasons.size <= 16 &&
                        reasons.all { reason ->
                            reason is String && dev.chungjungsoo.gptmobile.data.agent.ToolDependencyDiagnostics.Admission.entries.any { it.name == reason }
                        }
                }
            val capturedCallAllowance = (toolContext?.diagnostics?.values?.get("dependency_admission_reasons") as? JsonArray)
                ?.contains(JsonPrimitive("CALL_ALLOWANCE")) == true
            val trustedSourceCallAllowance = reportSource == "TOOL_RESULT" && validatedAdmissionReasons?.contains("CALL_ALLOWANCE") == true
            val callAllowanceExhausted = toolPrecheck && (capturedCallAllowance || trustedSourceCallAllowance)
            val publicCode = if (callAllowanceExhausted && code in setOf(null, "TOOL_FAILURE", "DEPENDENCY_FAILED", "DEPENDENCY_DEFERRED")) {
                "TOOL_BUDGET_EXHAUSTED"
            } else {
                precheck?.code ?: IssueReportCodes.select(code, fallbackCode)
            }
            failure(feature, publicCode, error, reportEvidence, reportSource)
        } else {
            synchronized(lock) {
                if (!isPrivate) addEvent(severity, feature, IssueReportCodes.known(code))
            }
        }
    }

    fun failure(feature: ReportFeature, code: String) = failure(feature, code, null)

    fun failure(feature: ReportFeature, code: String, error: Throwable?, fields: Map<String, Any?> = emptyMap(), source: String? = null) =
        failureFromOrigin(feature, code, error, fields, source) { true }

    /** Recheck asynchronous producer authority under the same lock as privacy and publication. */
    internal fun failureFromOrigin(feature: ReportFeature, code: String, error: Throwable?, fields: Map<String, Any?>, source: String?, originAllowed: () -> Boolean) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate || context == null) return
        synchronized(lock) {
            if (!LocalDiagnosticsPolicy.enabled || isPrivate || !originAllowed()) return
            val now = System.nanoTime() / 1_000_000L
            val publicCode = IssueReportCodes.select(code, fallbackCode(feature))
            val reportSource = source ?: if (fields.isNotEmpty()) {
                "STRUCTURED_EVENT"
            } else if (error == null) {
                "CODE_ONLY"
            } else {
                "EXCEPTION"
            }
            // A trusted TOOL_RESULT failure can carry a dispatched result phase
            // even when the producer did not supply a broad failed_stage. Only
            // synthesize the constant execution boundary for source-owned phases:
            // never turn a precheck refusal or an unknown outcome into a remote failure.
            val toolDispatchPhase = when (val value = fields["tool_dispatch_phase"]) {
                is String -> value
                is JsonPrimitive -> value.contentOrNull
                else -> null
            }
            val reportFields = if (
                feature == ReportFeature.TOOLS &&
                reportSource == "TOOL_RESULT" &&
                toolDispatchPhase in setOf("READ_FAILED", "OBSERVED", "FAILED") &&
                listOf("failed_stage", "failedStage", "failurePhase", "phase", "stage").none { it in fields }
            ) {
                fields + ("failed_stage" to "tool_execution")
            } else {
                fields
            }
            // Producer-owned exceptions are typed evidence even when the public
            // error code is generic. Retain only allowlisted cause categories as
            // hashes; do not copy messages, stack strings or a nearby run's state.
            val exceptionFields = if (error != null && "cause_types" !in reportFields) {
                reportFields + ("cause_types" to dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes(error).take(4))
            } else {
                reportFields
            }
            val evidence = IssueReportContext.fromFields(
                reportSource,
                IssueReportIncidentCapsule.fields(feature, reportSource, publicCode, if (feature == ReportFeature.APP) exceptionFields + appDiagnosticFields(code) else exceptionFields)
            )
            // Distinct failures may share a public code and run within the debounce window.
            // Retain observed stage and a sanitized exception fingerprint in the private key;
            // neither raw exception text nor stack details are stored in this index.
            val fingerprint = evidence.exceptionFingerprint ?: error?.let(IssueReportContext::exceptionFingerprint)
            val key = FailureKey(feature, publicCode, evidence.runIdHash, evidence.invocationIdHash, evidence.callIdHash, evidence.jobIdHash, evidence.activeJobIdHash, evidence.stage, fingerprint)
            evidence.diagnostics?.let(ReportDiagnosticSnapshots::retain)
            addEvent('E', feature, publicCode)
            val cause = IssueReportIncidentCapsule.identity(evidence, publicCode)
            if (cause == null && (lastFailures[key] ?: Long.MIN_VALUE / 2) + DUPLICATE_MILLIS > now) return
            if (cause != null && (lastCauses[cause] ?: Long.MIN_VALUE / 2) + DUPLICATE_MILLIS > now) return
            lastFailures.entries.removeAll { it.value < now - DUPLICATE_MILLIS }
            lastCauses.entries.removeAll { it.value < now - DUPLICATE_MILLIS }
            if (lastFailures.size >= 128) lastFailures.remove(lastFailures.minBy { it.value }.key)
            if (cause == null) lastFailures[key] = now
            if (cause != null) {
                if (lastCauses.size >= 128) lastCauses.remove(lastCauses.minBy { it.value }.key)
                lastCauses[cause] = now
            }
            val report = IssueReport.debug(feature, publicCode, recentEvents(), error, evidence)
            addPending(if (error == null) report.withCapturedException(fields) else report)
        }
    }

    fun chatFailure(code: String?, rateLimited: Boolean) = chatFailure(code, rateLimited, null)

    fun chatFailure(code: String?, rateLimited: Boolean, error: Throwable?, fields: Map<String, Any?> = emptyMap(), invocationId: String? = null) {
        val input = IssueReportContext.fromFields("CHAT_FAILURE", fields)
        if (code == "REVIEW_INPUT_REQUIRED" &&
            input.stage == "review_comparison" &&
            input.dispatched == false &&
            input.recoveryAction == "SELECT_REVIEW_BASELINE" &&
            input.requiresReconciliation != true
        ) {
            // A prerequisite question is already delivered in the task. It is not a provider incident.
            return
        }
        val localRecovery = IssueReportContext.localRecoveryCode(fields) != null
        val rejection = when (val category = fields["provider_rejection_category"]) {
            is String -> category
            is kotlinx.serialization.json.JsonPrimitive -> category.takeIf { it.isString }?.content
            else -> null
        }
        val specificCode = when {
            code == "BILLING_UNAVAILABLE" || rejection == "BILLING_UNAVAILABLE" && input.httpStatus in setOf(400, 402) -> "SOURCE_PAYMENT_REQUIRED"
            code == "OUTPUT_CONTINUATION_PENDING_TOOLS" && input.stage == "tool_reconciliation" && input.dispatched == false -> "RECONCILIATION_REQUIRED"
            code == "RATE_LIMIT" && input.provider != null -> "PROVIDER_RATE_LIMITED"
            code == "TOOL_PROTOCOL_INVALID_FRAME" -> "TOOL_PROTOCOL_INVALID_FRAME"
            code == "GITHUB_REQUIRED_TOOL_CONTEXT_BUDGET" -> "GITHUB_TOOL_CONTEXT_BUDGET"
            code in dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringPrerequisites.codes -> code
            code == "tool_use_failed" && input.provider != null -> "PROVIDER_PAYLOAD_ERROR"
            else -> IssueReportCodes.known(code)?.takeUnless { it == "CHAT_FAILED" }
        } ?: "SOURCE_UNAVAILABLE".takeIf { input.provider != null && input.httpStatus == 410 }
            ?: "GITHUB_TASK_INCOMPLETE".takeIf {
                code in setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED")
            }
        val observedRateLimit = if (!localRecovery && specificCode == null && !rateLimited) {
            input.provider != null &&
                input.stage in setOf("model_invocation", "provider_generation") &&
                input.httpStatus == 429 &&
                input.remoteAcceptance == "NO" &&
                input.requiresReconciliation != true
        } else {
            false
        }
        // A rejected provider attempt may lack a resumable checkpoint. Retain its
        // observed cause without treating the report as authorization to retry.
        val publicCode = if (localRecovery) "CHAT_FAILED" else specificCode ?: if (rateLimited || observedRateLimit) "PROVIDER_RATE_LIMITED" else "CHAT_FAILED"
        val diagnosticFields = if (publicCode in dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringPrerequisites.codes) {
            fields + mapOf(
                "diagnostic_area" to "GATEWAY",
                "gateway_admission_ready" to false,
                "gateway_preflight_reason" to publicCode,
                "gateway_preflight_blocker_count" to fields["prerequisite_blocker_count"],
                "gateway_validation_policy_state" to fields["validation_policy_state"],
                "gateway_http_status" to input.httpStatus,
                "gateway_failure_phase" to input.stage.takeIf { it in setOf("provider_admission", "connection_preflight") }
            )
        } else if (publicCode == "TOOL_PROTOCOL_INVALID_FRAME") {
            fields + mapOf(
                "diagnostic_area" to "PROVIDER",
                "failed_stage" to (input.stage ?: "provider_generation"),
                "provider_failure_stage" to "GENERATION",
                "provider_failure_code" to publicCode
            )
        } else if (publicCode in setOf("GITHUB_TOOL_CONTEXT_BUDGET", "GITHUB_PROFILE_LOCKED", "GITHUB_COOLDOWN_ACTIVE")) {
            fields + mapOf(
                "diagnostic_area" to "PROVIDER",
                "diagnostic_capture_mode" to "INCIDENT",
                "provider_failure_stage" to "ADMISSION",
                "provider_failure_code" to publicCode,
                "failure_origin" to "APPLICATION"
            )
        } else if (publicCode == "CHAT_FAILED" && "diagnostic_area" !in fields) {
            // This is a typed source observation, not proof of dispatch or HTTP
            // acceptance. Avoid presenting a code-less provider error as an
            // unexplained application exception.
            val codeAbsent = fields["provider_error_code_state"] in setOf("ABSENT", kotlinx.serialization.json.JsonPrimitive("ABSENT"))
            val sourceObserved = fields["provider_error_origin"] in setOf("OPENAI_COMPAT_STREAM_ERROR_VALUE", kotlinx.serialization.json.JsonPrimitive("OPENAI_COMPAT_STREAM_ERROR_VALUE"))
            fields + mapOf(
                "failure_origin" to (if (codeAbsent && sourceObserved) "PROVIDER" else fields["failure_origin"]),
                "diagnostic_area" to "INCIDENT",
                "diagnostic_capture_mode" to "INCIDENT",
                "chat_failure_code_state" to when {
                    code.isNullOrBlank() -> "ABSENT"
                    IssueReportCodes.known(code) != null -> "KNOWN"
                    else -> "UNRECOGNIZED"
                },
                "original_error_code_hash" to IssueReportContext.identifierHash("chat-error-code", code),
                "run_id_hash" to input.runIdHash,
                "invocation_id_hash" to (IssueReportContext.identifierHash("invocation", invocationId) ?: input.invocationIdHash)
            )
        } else {
            fields
        }
        failure(ReportFeature.CHAT, publicCode, error, diagnosticFields + listOfNotNull(invocationId?.let { "invocation_id" to it }).toMap(), "CHAT_FAILURE")
    }

    /** Called by the existing uncaught exception handler before it delegates to Android. */
    fun crash(error: Throwable) {
        if (!LocalDiagnosticsPolicy.enabled || isPrivate) return
        val crashEvidence = buildMap<String, Any?> {
            putAll(appDiagnosticFields("JVM_CRASH"))
            FrameTimingRecorder.activeReportSurface()?.let { put("crash_surface", it) }
            val frames = error.stackTrace.take(48)
            if (frames.any { it.className == "android.view.ThreadedRenderer" || it.className == "android.view.View" && it.methodName == "draw" }) {
                put("crash_boundary", "ANDROID_VIEW_DRAW")
            }
        }
        val evidence = IssueReportContext.fromFields("EXCEPTION", crashEvidence)
        evidence.diagnostics?.let(ReportDiagnosticSnapshots::retain)
        persist(IssueReport.debug(ReportFeature.APP, "JVM_CRASH", recentEvents(), error, evidence))
    }

    private fun appDiagnosticFields(code: String): Map<String, Any?> = mapOf(
        "diagnostic_area" to "APP",
        "original_error_code_hash" to IssueReportContext.identifierHash("error_code", code),
        "app_exit_kind" to code.takeIf { it in setOf("JVM_CRASH", "NATIVE_CRASH", "APP_NOT_RESPONDING", "STARTUP_FAILED") }
    )

    /** Called on IO as soon as the persisted Debug Mode value is available. */
    fun debugPreferenceChanged(enabled: Boolean) {
        val owner = context ?: return
        val current = processState ?: owner.getSharedPreferences("issue_report_process", Context.MODE_PRIVATE).also { saved ->
            if (enabled) {
                restoreFiles().forEach { report -> synchronized(lock) { addPending(report) } }
                runCatching { recoverProcessExit(owner, saved) }
            }
            processState = saved
        }
        if (!enabled) {
            synchronized(lock) {
                mutablePending.value = emptyList()
                clearDiagnosticSnapshots()
                if (!AppLogRecorder.enabled.value) clearRecent()
            }
            crashDirectory()?.listFiles()?.forEach { it.delete() }
        }
        val previousStart = current.getLong("debugSince", 0L)
        val since = if (enabled && current.getInt("pid", -1) == Process.myPid() && previousStart > 0) previousStart else System.currentTimeMillis()
        current.edit().putInt("pid", Process.myPid()).putLong("debugSince", if (enabled) since else 0L)
            .putBoolean("private", mutablePrivate.value).apply()
    }

    suspend fun dismiss(reportId: String) {
        synchronized(lock) { mutablePending.value = mutablePending.value.filterNot { it.reportId == reportId } }
        withContext(Dispatchers.IO) { crashFile(reportId)?.delete() }
    }

    private fun addEvent(level: Char, feature: ReportFeature, code: String? = null) {
        val line = "$level ${feature.name.lowercase()}" + (code?.let { " $it" } ?: "")
        val now = System.nanoTime() / 1_000_000L
        while (events.firstOrNull()?.let { it.timeMillis < now - RECENT_MILLIS } == true) events.removeFirst()
        if (events.size == IssueReport.EVENT_LIMIT) {
            // Keep recent activity and one instance of each recent coded warning or error.
            // A busy tool batch otherwise evicts the entire failure history with info events.
            val retained = events.indices.reversed().asSequence()
                .filter { events[it].codedWarning }
                .distinctBy { events[it].line }
                .take(IssueReport.EVENT_LIMIT / 2)
                .toSet()
            events.removeAt(events.indices.first { it !in retained })
        }
        events.addLast(RecentEvent(now, line, code != null && level in "WEF"))
    }

    private fun addPending(report: IssueReport) {
        if (mutablePending.value.size < PENDING_LIMIT && mutablePending.value.none { it.reportId == report.reportId }) {
            mutablePending.value = (mutablePending.value + report).take(PENDING_LIMIT)
            // The queue is bounded; Send/export also retain every still-pending report synchronously.
            pendingCorrelations.trySend(report)
        }
    }

    private fun crashDirectory() = context?.let { File(it.noBackupFilesDir, "pending_issue_crashes") }
    private fun crashFile(id: String) = crashDirectory()?.let { AtomicFile(File(it, "$id.json")) }

    private fun persist(report: IssueReport) {
        val file = crashFile(report.reportId) ?: return
        val bytes = IssueReport.json.encodeToString(IssueReport.serializer(), report).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun restoreFiles(): List<IssueReport> {
        val files = crashDirectory()?.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
        files.drop(8).forEach { AtomicFile(it).delete() }
        return files.take(8).mapNotNull { file ->
            runCatching {
                AtomicFile(file).openRead().use { input ->
                    if (input.available() > 8192) return@use null
                    IssueReport.json.decodeFromString(IssueReport.serializer(), input.bufferedReader().readText())
                }
            }.getOrNull()?.takeIf { it.reportId == file.nameWithoutExtension && it.source == "debug" }
        }
    }

    private fun recoverProcessExit(owner: Context, saved: SharedPreferences) {
        val oldPid = saved.getInt("pid", -1)
        val since = saved.getLong("debugSince", 0L)
        if (oldPid <= 0 || oldPid == Process.myPid() || since <= 0 || saved.getBoolean("private", false)) return
        val manager = owner.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(owner.packageName, oldPid, 4).firstOrNull {
            it.timestamp >= since && it.processName == owner.packageName
        } ?: return
        val code = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_ANR -> "APP_NOT_RESPONDING"
            else -> return
        }
        val evidence = IssueReportContext.fromFields("PROCESS_EXIT", appDiagnosticFields(code), occurredAtMillis = exit.timestamp)
        evidence.diagnostics?.let(ReportDiagnosticSnapshots::retain)
        val report = IssueReport.debug(ReportFeature.APP, code, emptyList(), context = evidence)
        persist(report)
        synchronized(lock) { addPending(report) }
    }
}
