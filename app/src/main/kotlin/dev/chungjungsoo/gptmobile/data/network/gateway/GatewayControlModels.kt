package dev.chungjungsoo.gptmobile.data.network.gateway

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val GATEWAY_CONTROL_SCHEMA = "gptmobile.gateway.settings.v1"

internal fun gatewayControlDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun gatewayControlEndpoint(raw: String): String {
    val uri = URI(raw)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    require(uri.port == -1 || uri.port in 1..65535)
    return raw.trimEnd('/')
}

/** Bare addresses use the installer port. Explicit HTTP(S) URLs keep their original port. */
internal fun gatewayAddressEndpoint(input: String): URI {
    val raw = input.trim().substringBefore('#').trimEnd('/')
    require(raw.isNotBlank() && raw.length <= 2048 && raw.none { it.isWhitespace() || it == '\\' })
    val explicit = "://" in raw
    val authority = if (!explicit && !raw.startsWith("[") && raw.count { it == ':' } > 1) "[$raw]" else raw
    val uri = URI(if (explicit) raw else "http://$authority")
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null)
    require(uri.rawPath.orEmpty() in setOf("", "/v1") && (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(':'))
    return URI(uri.scheme, null, uri.host.lowercase(), if (!explicit && uri.port == -1) 8090 else uri.port, "/v1", null, null)
}

internal data class GatewayPairingLink(val endpoint: String, val installationId: String, val fingerprint: String, val code: String, val expiresAt: Long, val gatewayPort: Int? = null) {
    val profileEndpoint: String? get() = gatewayPort?.let { URI("http", null, URI(endpoint).host, it, "/v1", null, null).toString() }

    companion object {
        fun parse(raw: String, nowSeconds: Long): GatewayPairingLink {
            require(raw.length in 1..4096)
            val uri = URI(raw.trim())
            require(uri.scheme == "gptmobile" && uri.host == "gateway-control" && uri.fragment == null)
            require(uri.userInfo == null && uri.port == -1 && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"))
            val fields = uri.rawQuery.orEmpty().split('&').map { entry ->
                require(entry.contains('='))
                URLDecoder.decode(entry.substringBefore('='), "UTF-8") to URLDecoder.decode(entry.substringAfter('='), "UTF-8")
            }
            require(fields.map { it.first }.toSet().size == fields.size)
            val values = fields.toMap()
            require(values.keys - "gateway_port" == setOf("endpoint", "installation_id", "fingerprint", "code", "expires"))
            val installation = values.getValue("installation_id")
            require(installation.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
            val fingerprint = values.getValue("fingerprint").lowercase()
            require(fingerprint.matches(Regex("[a-f0-9]{64}")))
            val code = values.getValue("code")
            require(code.matches(Regex("[A-Za-z0-9_-]{43,128}")))
            val expires = requireNotNull(values.getValue("expires").toLongOrNull())
            require(expires > nowSeconds && expires - nowSeconds <= 3600)
            val gatewayPort = values["gateway_port"]?.let { requireNotNull(it.toIntOrNull()).also { port -> require(port in 1024..65535) } }
            return GatewayPairingLink(gatewayControlEndpoint(values.getValue("endpoint")), installation, fingerprint, code, expires, gatewayPort)
        }
    }
}

/** The fragment is consumed locally. Only the inference URL belongs in profiles or history. */
internal fun privateGatewayAddress(address: String, nowSeconds: Long): GatewayPairingLink? {
    val uri = URI(address)
    val fragment = uri.rawFragment ?: return null
    val target = address.substringBefore('#').trimEnd('/')
    val parsed = GatewayPairingLink.parse("gptmobile://gateway-control?$fragment", nowSeconds)
    require(parsed.profileEndpoint == target && parsed.gatewayPort != null)
    return parsed
}

internal enum class GatewayControlField(val wire: String, val label: String, val ownerOnly: Boolean = false, val minimum: Int? = null, val maximum: Int? = null, val choices: List<String> = emptyList()) {
    ENGINEERING("engineering_enabled", "Engineering", true),
    WORKERS("workers", "Worker limit", minimum = 1, maximum = 16),
    ACTIVE_CHATS("active_chats", "Active chat limit", minimum = 1, maximum = 64),
    BATCH("batch_mode", "Batch mode", choices = listOf("standard", "smart", "full")),
    VALIDATION_WORKERS("local_validation_workers", "Validation workers", minimum = 1, maximum = 4),
    VALIDATION_PROFILE("validation_profile", "Approved repository validation"),
    BACKEND("backend", "Approved model backend"),
    MODEL("model", "Selected Gateway model"),
    MAPS("public_repository_maps", "Public repository maps"),
    STARTUP("start_at_login", "Start Gateway at sign-in"),
    UPDATE_MODE("update_mode", "Gateway updates", choices = listOf("off", "notify", "auto")),
    UPDATE_INTERVAL("update_interval_hours", "Update interval (hours)", minimum = 1, maximum = 168),
    DIAGNOSTICS("debug_mode", "Diagnostic capture"),
    HOST_REQUEST("host_request", "PC review request", choices = listOf("Review phone network access", "Open engineering setup", "Review pending restart"));

    val boolean: Boolean get() = this in setOf(ENGINEERING, MAPS, STARTUP, DIAGNOSTICS)

    fun accepts(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        return when {
            this == VALIDATION_PROFILE -> primitive.isString && (primitive.content in setOf("disabled", "invalid") || primitive.content.matches(Regex("[a-f0-9]{64}")))
            boolean -> !primitive.isString && primitive.booleanOrNull != null
            minimum != null -> !primitive.isString && primitive.intOrNull?.let { it in minimum..requireNotNull(maximum) } == true
            choices.isNotEmpty() -> primitive.isString && primitive.contentOrNull in choices
            else -> primitive.isString && primitive.content.length in (if (this == MODEL) 0 else 1)..512 && primitive.content.none(Char::isISOControl)
        }
    }

    companion object {
        fun fromWire(value: String): GatewayControlField? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
internal data class GatewayControlPending(val field: String, val reason: String, @SerialName("blocking_jobs") val blockingJobs: List<String> = emptyList())

@Serializable
internal data class GatewayControlHostRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("principal_id") val principalId: String,
    val request: String,
    val status: String,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("reviewed_at") val reviewedAt: Double? = null
)

@Serializable
internal data class GatewayUpdateStatus(val installed: String, val state: String, val available: String? = null)

@Serializable
internal data class GatewayControlSnapshot(
    val schema: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("principal_id") val principalId: String,
    val revision: Long,
    @SerialName("observed_at") val observedAt: Double,
    val desired: JsonObject,
    val effective: JsonObject,
    val pending: List<GatewayControlPending> = emptyList(),
    @SerialName("writable_fields") val writableFields: List<String> = emptyList(),
    val role: String,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    @SerialName("validation_profiles") val validationProfiles: Map<String, String> = emptyMap(),
    @SerialName("host_requests") val hostRequests: List<GatewayControlHostRequest> = emptyList(),
    @SerialName("approved_backends") val approvedBackends: List<String> = emptyList(),
    val updates: GatewayUpdateStatus? = null
) {
    fun validate(installation: String, principal: String): GatewayControlSnapshot {
        require(schema == GATEWAY_CONTROL_SCHEMA && installationId == installation && principalId == principal)
        updates?.let { update ->
            val version = Regex("""[0-9]{1,6}(?:\.[0-9]{1,6}){2}""")
            require(update.installed.matches(version) && (update.available == null || update.available.matches(version)))
            require(update.state.matches(Regex("[a-z-]{1,40}")))
        }
        require(revision >= 0 && observedAt.isFinite() && observedAt >= 0 && role in setOf("owner", "viewer"))
        require(capabilities["controlSettings.v1"] == JsonPrimitive(true))
        require(desired.size <= 32 && effective.size <= 32 && pending.size <= 32 && writableFields.size <= 32)
        require("host_request" !in desired && "host_request" !in effective)
        require(writableFields.distinct().size == writableFields.size && (role == "owner" || writableFields.isEmpty()))
        require(validationProfiles.size <= 16 && validationProfiles.all { (hash, label) -> hash.matches(Regex("[a-f0-9]{64}")) && label.length in 1..100 && label.none(Char::isISOControl) })
        require(approvedBackends.size <= 16 && approvedBackends.distinct().size == approvedBackends.size && approvedBackends.all { GatewayControlField.BACKEND.accepts(JsonPrimitive(it)) })
        require(hostRequests.size <= 16 && hostRequests.map { it.commandId }.distinct().size == hostRequests.size)
        require(hostRequests.all { it.commandId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.principalId == principal && it.request in GatewayControlField.HOST_REQUEST.choices && it.status in setOf("waiting_pc", "reviewed") && it.createdAt.isFinite() && it.createdAt >= 0 && (it.reviewedAt == null || it.reviewedAt.isFinite() && it.reviewedAt >= it.createdAt) })
        (desired.entries + effective.entries).forEach { (key, value) -> GatewayControlField.fromWire(key)?.let { require(it.accepts(value)) } }
        require(pending.all { it.field.length <= 80 && it.reason.length <= 500 && it.reason.none(Char::isISOControl) && it.blockingJobs.size <= 128 && it.blockingJobs.all { id -> id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) } })
        return this
    }

    fun validateChanges(changes: JsonObject) {
        require(role == "owner" && changes.isNotEmpty() && changes.size <= GatewayControlField.entries.size)
        changes.forEach { (key, value) -> require(key in writableFields && GatewayControlField.fromWire(key)?.accepts(value) == true) }
        changes["validation_profile"]?.let { require((it as JsonPrimitive).content in validationProfiles) }
        changes["backend"]?.let { require((it as JsonPrimitive).content in approvedBackends) }
        if ("host_request" in changes) require(changes.size == 1)
    }
}

@Serializable
internal data class GatewayControlCommand(
    @SerialName("installation_id") val installationId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("command_id") val commandId: String,
    val changes: JsonObject
)

@Serializable
internal data class GatewayControlReceipt(
    val schema: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("principal_id") val principalId: String,
    val revision: Long,
    val status: String,
    val snapshot: GatewayControlSnapshot,
    @SerialName("dispatch_state") val dispatchState: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
    val error: JsonObject? = null,
    val requested: JsonObject? = null
) {
    fun validate(command: GatewayControlCommand, principal: String): GatewayControlReceipt {
        require(schema == "gptmobile.gateway.command.v1" && commandId == command.commandId && installationId == command.installationId && principalId == principal)
        require(revision >= 0 && snapshot.revision >= revision)
        require(status in setOf("applied", "waiting_for_jobs", "setup_required", "superseded", "rejected", "waiting_pc", "reviewed"))
        if (status in setOf("waiting_pc", "reviewed")) require(command.changes.keys == setOf("host_request") && GatewayControlField.HOST_REQUEST.accepts(command.changes.getValue("host_request")))
        if (status == "rejected") {
            require(dispatchState == "not_dispatched" && httpStatus in setOf(400, 403, 409, 422))
        } else {
            require(revision == command.expectedRevision + 1)
        }
        if (status == "superseded") require(requested == command.changes)
        snapshot.validate(installationId, principalId)
        return this
    }
}

/** Route selection is explicit; reconnecting cannot lend a saved shared job new owner authority. */
internal fun gatewayControlRoute(config: dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig, binding: GatewayControlBinding?): GatewayControlBinding? {
    val retainedPrincipal = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Principal-ID", true) }?.value
    val retainedInstallation = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Installation-ID", true) }?.value
    val exact = binding?.takeIf { it.profileUid == config.profileUid && it.originalEndpoint == config.apiUrl.trim().trimEnd('/') }
    if (config.gatewayOwnerControl && binding != null && binding.profileUid == config.profileUid) {
        check(exact != null) { "The selected Gateway address changed. Pair its explicit control target again before starting new owner work." }
    }
    if (retainedPrincipal?.startsWith("paired:") == true) {
        check(exact != null && retainedPrincipal == exact.principalId && retainedInstallation == exact.installationId) {
            "Restore the original paired Gateway grant to continue this saved work."
        }
    }
    if (exact == null || retainedPrincipal != null && retainedPrincipal != exact.principalId) return null
    if (!config.gatewayOwnerControl && retainedPrincipal != exact.principalId) return null
    check(!exact.revoked && !exact.revocationPending && exact.engineeringBindingConfirmed) {
        "Confirm Engineering for this paired PC in Gateway settings before continuing."
    }
    require(retainedInstallation == null || retainedInstallation == exact.installationId)
    return exact.takeUnless { it.connectedAccount }
}

@Serializable
internal data class GatewayControlBinding(
    val profileUid: String,
    val originalEndpoint: String,
    val endpoint: String,
    val fingerprint: String,
    val installationId: String,
    val principalId: String,
    val deviceId: String,
    val role: String,
    val token: String,
    val keyAlias: String,
    val engineeringBindingConfirmed: Boolean = false,
    val revoked: Boolean = false,
    val revocationPending: Boolean = false,
    val pending: GatewayControlCommand? = null,
    val snapshot: GatewayControlSnapshot? = null,
    val lastConfirmedAtMillis: Long? = null,
    val connectedAccount: Boolean = false
)

@Serializable
internal data class GatewayControlPairing(
    val profileUid: String,
    val originalEndpoint: String,
    val endpoint: String,
    val fingerprint: String,
    val installationId: String,
    val code: String,
    val expiresAt: Long,
    val keyAlias: String,
    val publicKey: String,
    val pairingId: String? = null,
    val gatewayPort: Int? = null,
    val privateAddress: Boolean = false
)

internal enum class GatewayControlPhase { UNPAIRED, PAIRING, LIVE, SAVING, RECONCILING, STALE, CONFLICT, REVOKED }

internal data class GatewayControlState(
    val phase: GatewayControlPhase = GatewayControlPhase.UNPAIRED,
    val snapshot: GatewayControlSnapshot? = null,
    val receivedAtMillis: Long? = null,
    val message: String? = null,
    val engineeringBindingConfirmed: Boolean = false,
    val targetName: String = "Gateway",
    val deviceFingerprint: String? = null,
    val targetEndpoint: String? = null,
    val revocationPending: Boolean = false,
    val pendingCommand: GatewayControlCommand? = null
) {
    val writable: Boolean get() = phase == GatewayControlPhase.LIVE && snapshot?.role == "owner" && !revocationPending
}
