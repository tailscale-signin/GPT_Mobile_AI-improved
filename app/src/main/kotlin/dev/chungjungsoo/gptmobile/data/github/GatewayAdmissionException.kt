package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class GatewayAdmissionReason {
    REMOTE_SURFACE_NOT_ADMITTED,
    MISSING_SCOPE,
    TRANSPORT_IDENTITY_REQUIRED,
    IDENTITY_MISMATCH,
    UNSUPPORTED_CONTRACT,
    INVALID_RESPONSE,
    INVALID_CONNECTION,
    STORAGE_UNAVAILABLE,
    CURSOR_EXPIRED,
    TRANSPORT_INTERRUPTED,
    SOURCE_MISMATCH,
    HTTP_REJECTED
}

enum class GatewayAdmissionSurface { IDENTITY, CONVERSATIONS, JOB_CATALOG, TRANSCRIPT, COMMAND, CAPABILITIES }
enum class GatewayAdmissionRetryability { SAFE_READ, AFTER_REPAIR, RECONCILE_ORIGINAL_COMMAND, NEVER }
enum class GatewayAdmissionPhase { PRECHECK, RECONCILIATION }

/** Dispatch of the dependent model/action, not dispatch of its read-only preflight. */
enum class GatewayAdmissionDispatchState { NOT_DISPATCHED, REJECTED, UNKNOWN }
enum class GatewayAdmissionRepair { UPDATE_GATEWAY, CHECK_SCOPE, CHECK_TRANSPORT_IDENTITY, RESTORE_ORIGINAL_CONNECTION, RESTORE_STORAGE, REFRESH_HISTORY, RECONNECT, VERIFY_SOURCE, CHECK_CONNECTION }

/** Only fixed categories and validated build identifiers may leave the response parser. */
data class GatewayAdmissionSource(val gatewayVersion: String?, val sourceCommit: String?) {
    init {
        require(gatewayVersion == null || gatewayVersion.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")))
        require(sourceCommit == null || sourceCommit.matches(Regex("[a-f0-9]{40}")))
    }
}

open class GatewayAdmissionException internal constructor(
    val reason: GatewayAdmissionReason,
    val httpStatus: Int?,
    val wireCode: String?,
    val surface: GatewayAdmissionSurface,
    val retryability: GatewayAdmissionRetryability = if (surface == GatewayAdmissionSurface.COMMAND) GatewayAdmissionRetryability.RECONCILE_ORIGINAL_COMMAND else retryability(reason),
    val dispatchState: GatewayAdmissionDispatchState = if (surface == GatewayAdmissionSurface.COMMAND) GatewayAdmissionDispatchState.UNKNOWN else GatewayAdmissionDispatchState.NOT_DISPATCHED,
    val repair: GatewayAdmissionRepair = repair(reason),
    val source: GatewayAdmissionSource? = null,
    val phase: GatewayAdmissionPhase = if (surface == GatewayAdmissionSurface.COMMAND) GatewayAdmissionPhase.RECONCILIATION else GatewayAdmissionPhase.PRECHECK
) : IllegalStateException(admissionMessage(reason, httpStatus, surface)) {
    internal fun withSource(source: GatewayAdmissionSource): GatewayAdmissionException = GatewayAdmissionException(reason, httpStatus, wireCode, surface, retryability, dispatchState, repair, source, phase)

    /** Safe terminal/export fields. Dispatch describes the dependent action, never the completed GET. */
    fun safeDetails(): Map<String, String> = buildMap {
        put("reason", reason.name)
        put("surface", surface.name)
        put("phase", phase.name)
        put("dependent_dispatch_state", dispatchState.name)
        put("retryability", retryability.name)
        put("repair", repair.name)
        httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it.toString()) }
        safeGatewayAdmissionCode(wireCode)?.let { put("admission_code", it) }
        source?.gatewayVersion?.let { put("gateway_version", it) }
        source?.sourceCommit?.let { put("gateway_source_commit", it) }
    }
}

private fun retryability(reason: GatewayAdmissionReason) = when (reason) {
    GatewayAdmissionReason.TRANSPORT_INTERRUPTED, GatewayAdmissionReason.STORAGE_UNAVAILABLE, GatewayAdmissionReason.CURSOR_EXPIRED -> GatewayAdmissionRetryability.SAFE_READ
    else -> GatewayAdmissionRetryability.AFTER_REPAIR
}

private fun repair(reason: GatewayAdmissionReason) = when (reason) {
    GatewayAdmissionReason.REMOTE_SURFACE_NOT_ADMITTED, GatewayAdmissionReason.UNSUPPORTED_CONTRACT, GatewayAdmissionReason.INVALID_RESPONSE -> GatewayAdmissionRepair.UPDATE_GATEWAY
    GatewayAdmissionReason.MISSING_SCOPE -> GatewayAdmissionRepair.CHECK_SCOPE
    GatewayAdmissionReason.TRANSPORT_IDENTITY_REQUIRED -> GatewayAdmissionRepair.CHECK_TRANSPORT_IDENTITY
    GatewayAdmissionReason.IDENTITY_MISMATCH -> GatewayAdmissionRepair.RESTORE_ORIGINAL_CONNECTION
    GatewayAdmissionReason.STORAGE_UNAVAILABLE -> GatewayAdmissionRepair.RESTORE_STORAGE
    GatewayAdmissionReason.CURSOR_EXPIRED -> GatewayAdmissionRepair.REFRESH_HISTORY
    GatewayAdmissionReason.TRANSPORT_INTERRUPTED -> GatewayAdmissionRepair.RECONNECT
    GatewayAdmissionReason.SOURCE_MISMATCH -> GatewayAdmissionRepair.VERIFY_SOURCE
    else -> GatewayAdmissionRepair.CHECK_CONNECTION
}

private fun admissionMessage(reason: GatewayAdmissionReason, status: Int?, surface: GatewayAdmissionSurface): String {
    val guidance = when (reason) {
        GatewayAdmissionReason.REMOTE_SURFACE_NOT_ADMITTED -> "This gateway build does not admit the saved-work route from this connection. Update the running PC gateway package, verify its source commit and saved-work routes, then retry. The version label alone does not verify compatibility."
        GatewayAdmissionReason.MISSING_SCOPE -> "The gateway identity is recognized but lacks permission for this saved-work route. Check the original account's jobs or engineering scope on the PC gateway."
        GatewayAdmissionReason.TRANSPORT_IDENTITY_REQUIRED -> "The gateway did not recognize this connection's transport identity. Check its configured access mode and original connection. Key-free LAN mode does not require adding an API token."
        GatewayAdmissionReason.IDENTITY_MISMATCH -> "The gateway installation or account changed. Restore the original connection and refresh its identity before sending the saved command."
        GatewayAdmissionReason.UNSUPPORTED_CONTRACT -> "This gateway does not expose the required saved-work contract. Update the running PC gateway package and verify its source commit and identity, conversation and Jobs routes."
        GatewayAdmissionReason.INVALID_RESPONSE -> "The gateway returned an incompatible saved-work document. Verify the selected address, installed source commit and saved-work contract."
        GatewayAdmissionReason.INVALID_CONNECTION -> "The gateway rejected this connection's host, origin or admission headers. Check the configured destination and access mode on the PC gateway."
        GatewayAdmissionReason.STORAGE_UNAVAILABLE -> "The gateway's saved-work storage is unavailable. Restore its durable journal on the PC, then reconnect to the original jobs."
        GatewayAdmissionReason.CURSOR_EXPIRED -> "The saved history cursor expired. Refresh the retained snapshot before continuing this read."
        GatewayAdmissionReason.TRANSPORT_INTERRUPTED -> if (surface == GatewayAdmissionSurface.COMMAND) "Reading the saved command receipt was interrupted. Reconnect to check that original command." else "The saved-work check was interrupted before the dependent action was sent. Reconnect and retry the bounded read."
        GatewayAdmissionReason.SOURCE_MISMATCH -> "The running gateway source does not match the selected package source. Update or restart the intended PC installation, then verify its source commit and saved-work routes again."
        GatewayAdmissionReason.HTTP_REJECTED -> "The gateway rejected this saved-work check. Check the original connection and its admission diagnostics before retrying."
    }
    val reconciliation = if (surface == GatewayAdmissionSurface.COMMAND) " The original command's outcome remains unconfirmed; retain its ID and reconcile it before another submission." else ""
    return "GATEWAY_${reason.name}" + status?.let { " (HTTP $it)" }.orEmpty() + ": $guidance$reconciliation"
}

private val admissionReasons = mapOf(
    "remote_surface_not_admitted" to GatewayAdmissionReason.REMOTE_SURFACE_NOT_ADMITTED,
    "scope_not_granted" to GatewayAdmissionReason.MISSING_SCOPE,
    "engineering_scope_required" to GatewayAdmissionReason.MISSING_SCOPE,
    "tool_scope_required_for_runtime" to GatewayAdmissionReason.MISSING_SCOPE,
    "local_admin_required" to GatewayAdmissionReason.MISSING_SCOPE,
    "transport_identity_required" to GatewayAdmissionReason.TRANSPORT_IDENTITY_REQUIRED,
    "CONTINUITY_IDENTITY_MISMATCH" to GatewayAdmissionReason.IDENTITY_MISMATCH,
    "CONTINUITY_IDENTITY_INVALID" to GatewayAdmissionReason.IDENTITY_MISMATCH,
    "invalid_host" to GatewayAdmissionReason.INVALID_CONNECTION,
    "invalid_origin" to GatewayAdmissionReason.INVALID_CONNECTION,
    "duplicate_admission_header" to GatewayAdmissionReason.INVALID_CONNECTION,
    "browser_csrf_required" to GatewayAdmissionReason.INVALID_CONNECTION,
    "conversation_storage_disabled" to GatewayAdmissionReason.STORAGE_UNAVAILABLE,
    "conversation_storage_unavailable" to GatewayAdmissionReason.STORAGE_UNAVAILABLE,
    "cursor_expired" to GatewayAdmissionReason.CURSOR_EXPIRED
)

internal fun safeGatewayAdmissionCode(value: String?): String? = value?.takeIf { it.length <= 80 && it in admissionReasons }

/** The header is authoritative when admitted; no arbitrary server message or raw cause is retained. */
internal fun gatewayAdmissionFailure(status: Int, surface: GatewayAdmissionSurface, headerCode: String?, boundedBody: String? = null): GatewayAdmissionException {
    val code = safeGatewayAdmissionCode(headerCode) ?: boundedBody?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 }?.let { body ->
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject ?: root?.get("detail") as? JsonObject ?: root
        safeGatewayAdmissionCode((error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
    }
    val reason = admissionReasons[code] ?: when (status) {
        404, 405, 501 -> GatewayAdmissionReason.UNSUPPORTED_CONTRACT
        410 -> GatewayAdmissionReason.CURSOR_EXPIRED
        else -> GatewayAdmissionReason.HTTP_REJECTED
    }
    return when (reason) {
        GatewayAdmissionReason.UNSUPPORTED_CONTRACT -> GatewayHistoryUnsupported(surface, status)
        GatewayAdmissionReason.CURSOR_EXPIRED -> GatewayCursorExpired(surface, status, code)
        else -> GatewayAdmissionException(reason, status, code, surface)
    }
}
