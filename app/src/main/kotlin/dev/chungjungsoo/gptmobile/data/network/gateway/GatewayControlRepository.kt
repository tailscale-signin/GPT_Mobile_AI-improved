package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Both Android entry points use this installation-bound store. No settings write is queued offline. */
@Singleton
internal class GatewayControlRepository @Inject constructor(
    private val vault: SecretVault,
    private val keys: GatewayControlDeviceKeys,
    private val transport: GatewayControlTransport
) : GatewayPairedRequestSource {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val locks = mutableMapOf<String, Mutex>()
    private val mutable = MutableStateFlow<Map<String, GatewayControlState>>(emptyMap())
    val states = mutable.asStateFlow()
    private fun mutex(uid: String) = synchronized(locks) { locks.getOrPut(uid) { Mutex() } }
    private fun reference(uid: String) = "gateway_scope_control_${gatewayControlDigest(uid)}"
    private fun pairingReference(uid: String) = "gateway_scope_control_pair_${gatewayControlDigest(uid)}"
    private fun original(profile: PlatformV2): String {
        require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile)) { "Choose an enabled private Gateway connection." }
        return profile.apiUrl.trim().trimEnd('/')
    }

    private suspend fun load(uid: String): GatewayControlBinding? = vault.read(reference(uid))?.let { bytes ->
        try {
            json.decodeFromString<GatewayControlBinding>(bytes.toString(Charsets.UTF_8)).also { require(it.profileUid == uid) }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun pairing(uid: String): GatewayControlPairing? = vault.read(pairingReference(uid))?.let { bytes ->
        try {
            json.decodeFromString<GatewayControlPairing>(bytes.toString(Charsets.UTF_8)).also { require(it.profileUid == uid) }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun save(binding: GatewayControlBinding) {
        val bytes = json.encodeToString(binding).toByteArray(Charsets.UTF_8)
        try {
            vault.put(reference(binding.profileUid), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun savePairing(value: GatewayControlPairing) {
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try {
            vault.put(pairingReference(value.profileUid), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun publish(profile: PlatformV2, phase: GatewayControlPhase, binding: GatewayControlBinding? = null, message: String? = null, fresh: Boolean = false, deviceFingerprint: String? = null) {
        mutable.update {
            val previous = it[profile.uid]
            it + (
                profile.uid to GatewayControlState(
                    phase,
                    binding?.snapshot ?: previous?.snapshot,
                    binding?.lastConfirmedAtMillis ?: if (fresh) System.currentTimeMillis() else previous?.receivedAtMillis,
                    message,
                    binding?.engineeringBindingConfirmed ?: false,
                    profile.name.take(120),
                    if (phase == GatewayControlPhase.PAIRING) deviceFingerprint ?: previous?.deviceFingerprint else null,
                    binding?.endpoint,
                    binding?.revocationPending == true,
                    binding?.pending
                )
                )
        }
    }

    private suspend fun exchange(binding: GatewayControlBinding, method: String, path: String, body: String = ""): GatewayControlResponse {
        check(!binding.revoked) { "This phone's Gateway access was revoked. Pair it again on the PC." }
        return transport.exchange(binding.endpoint, binding.fingerprint, binding.installationId, binding.token, binding.keyAlias, method, path, body)
    }

    private suspend fun snapshot(binding: GatewayControlBinding): GatewayControlSnapshot {
        val response = exchange(binding, "GET", "/gateway/settings")
        if (response.status in setOf(401, 403)) throw GatewayControlRevoked()
        check(response.status == 200) { "Gateway settings are unavailable. Reconnect to read their current state." }
        return json.decodeFromString<GatewayControlSnapshot>(response.body).validate(binding.installationId, binding.principalId)
    }

    suspend fun refresh(profile: PlatformV2) = mutex(profile.uid).withLock {
        val endpoint = original(profile)
        val binding = load(profile.uid)
        val pendingPair = pairing(profile.uid)
        val waiting = pendingPair?.originalEndpoint == endpoint && pendingPair.expiresAt > System.currentTimeMillis() / 1000
        if (waiting) {
            publish(
                profile,
                GatewayControlPhase.PAIRING,
                binding,
                message = "Compare this phone's key fingerprint on the PC before approval, then check confirmation.",
                deviceFingerprint = gatewayControlDigest(requireNotNull(pendingPair).publicKey)
            )
            return@withLock
        }
        if (binding == null || binding.originalEndpoint != endpoint) {
            publish(profile, GatewayControlPhase.UNPAIRED)
            return@withLock
        }
        if (binding.revoked) {
            publish(profile, GatewayControlPhase.REVOKED, binding, "Access revoked. Pair this phone again on the PC.")
            return@withLock
        }
        try {
            if (binding.pending != null) {
                reconcile(profile, binding)
            } else {
                val observed = snapshot(binding)
                check(observed.revision >= (binding.snapshot?.revision ?: 0))
                val updated = binding.copy(snapshot = observed, lastConfirmedAtMillis = System.currentTimeMillis())
                save(updated)
                publish(profile, GatewayControlPhase.LIVE, updated, if (binding.revocationPending) "The previous revoke request was not confirmed. Review and revoke again if needed." else null, fresh = true)
            }
        } catch (revoked: GatewayControlRevoked) {
            val cleared = binding.copy(token = "", revoked = true, revocationPending = false)
            save(cleared)
            publish(profile, GatewayControlPhase.REVOKED, cleared, "This phone no longer has access. Saved jobs remain on the Gateway.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(
                profile,
                if (binding.pending == null) GatewayControlPhase.STALE else GatewayControlPhase.RECONCILING,
                binding,
                if (binding.pending == null) "Offline or changed certificate. Showing the last confirmed setting; refresh before applying changes." else "The original change has no confirmed receipt yet. Refresh checks it without resending."
            )
        }
    }

    suspend fun pair(profile: PlatformV2, link: String) = mutex(profile.uid).withLock {
        val endpoint = original(profile)
        val parsed = GatewayPairingLink.parse(link, System.currentTimeMillis() / 1000)
        val alias = "gptmobile_gateway_control_${gatewayControlDigest(profile.uid + parsed.endpoint)}"
        val previous = load(profile.uid)
        check(previous?.pending == null) { "Reconcile the original Gateway change before replacing its pairing." }
        val pending = GatewayControlPairing(profile.uid, endpoint, parsed.endpoint, parsed.fingerprint, parsed.installationId, parsed.code, parsed.expiresAt, alias, keys.publicKey(alias))
        savePairing(pending)
        publish(profile, GatewayControlPhase.PAIRING, previous, "Requesting owner confirmation on the selected PC…", deviceFingerprint = gatewayControlDigest(pending.publicKey))
        savePairing(claim(pending))
        publish(profile, GatewayControlPhase.PAIRING, previous, "Compare this phone's key fingerprint on the PC before approval, then check confirmation.")
    }

    private suspend fun claim(pending: GatewayControlPairing): GatewayControlPairing {
        val body = buildJsonObject {
            put("installation_id", pending.installationId)
            put("device_name", "GPT Mobile Android")
            put("public_key", pending.publicKey)
        }.toString()
        val result = transport.exchange(pending.endpoint, pending.fingerprint, pending.installationId, pending.code, pending.keyAlias, "POST", "/gateway/pairing/claim", body)
        check(result.status in setOf(200, 201, 202)) { "The pairing code was not accepted. Create a new code on the PC." }
        val data = json.parseToJsonElement(result.body) as? JsonObject ?: error("Invalid pairing response.")
        val id = (data["pairing_id"] as? JsonPrimitive)?.contentOrNull
        require(id?.matches(Regex("[A-Za-z0-9_-]{1,160}")) == true)
        require(data["state"] in setOf(JsonPrimitive("awaiting_confirmation"), JsonPrimitive("approved")))
        require((data["expires_at"] as? JsonPrimitive)?.longOrNull?.let { it > System.currentTimeMillis() / 1000 && it <= pending.expiresAt } == true)
        return pending.copy(pairingId = id)
    }

    suspend fun finishPairing(profile: PlatformV2) = mutex(profile.uid).withLock {
        var pending = requireNotNull(pairing(profile.uid)) { "Paste the pairing link created on the PC." }
        require(pending.originalEndpoint == original(profile) && pending.expiresAt > System.currentTimeMillis() / 1000)
        // Claim is idempotent for this retained code and key, including a lost first response.
        if (pending.pairingId == null) pending = claim(pending).also { savePairing(it) }
        val body = buildJsonObject {
            put("pairing_id", pending.pairingId)
            put("public_key", pending.publicKey)
        }.toString()
        val response = transport.exchange(pending.endpoint, pending.fingerprint, pending.installationId, pending.code, pending.keyAlias, "POST", "/gateway/pairing/finish", body)
        if (response.status == 202) {
            publish(profile, GatewayControlPhase.PAIRING, message = "Waiting for confirmation on the PC.")
            return@withLock
        }
        check(response.status == 200) { "Pairing is unavailable or expired. Create a new link on the PC." }
        val data = json.parseToJsonElement(response.body) as? JsonObject ?: error("Invalid pairing response.")
        fun text(name: String) = requireNotNull((data[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull).also { require(it.length in 1..2048 && it.none(Char::isISOControl)) }
        require(text("installation_id") == pending.installationId && gatewayControlEndpoint(text("endpoint")) == pending.endpoint && text("fingerprint") == pending.fingerprint)
        val principal = text("principal_id")
        require(principal.matches(Regex("paired:[a-f0-9]{32}")) && text("role") in setOf("owner", "viewer"))
        val deviceId = text("device_id")
        require(principal == "paired:$deviceId")
        val previous = load(profile.uid)
        require(previous?.pending == null || previous.installationId == pending.installationId && previous.principalId == principal && previous.endpoint == pending.endpoint && previous.fingerprint == pending.fingerprint)
        var binding = GatewayControlBinding(
            profile.uid, pending.originalEndpoint, pending.endpoint, pending.fingerprint, pending.installationId, principal, deviceId, text("role"), text("token"), pending.keyAlias,
            engineeringBindingConfirmed = previous?.takeIf { it.installationId == pending.installationId && it.principalId == principal }?.engineeringBindingConfirmed == true,
            pending = previous?.pending
        )
        save(binding)
        vault.delete(pairingReference(profile.uid))
        binding = binding.copy(snapshot = snapshot(binding), lastConfirmedAtMillis = System.currentTimeMillis())
        save(binding)
        publish(profile, GatewayControlPhase.LIVE, binding, "Paired with this PC. Shared settings identify their scope below.", fresh = true)
    }

    suspend fun apply(profile: PlatformV2, expectedRevision: Long, changes: JsonObject, confirmEngineeringBinding: Boolean = false) = mutex(profile.uid).withLock {
        var binding = requireNotNull(load(profile.uid))
        require(binding.originalEndpoint == original(profile) && !binding.revoked && !binding.revocationPending)
        check(binding.pending == null) { "Read the original change receipt before requesting another change." }
        check(pairing(profile.uid)?.expiresAt?.let { it > System.currentTimeMillis() / 1000 } != true) { "Finish the requested pairing before changing Gateway settings." }
        val observed = snapshot(binding)
        if (observed.revision != expectedRevision) {
            binding = binding.copy(snapshot = observed, lastConfirmedAtMillis = System.currentTimeMillis())
            save(binding)
            publish(profile, GatewayControlPhase.CONFLICT, binding, "Changed on another device. Review the current values before applying your draft again.", fresh = true)
            return@withLock
        }
        observed.validateChanges(changes)
        val bindingOn = changes["engineering_enabled"] == JsonPrimitive(true)
        check(!bindingOn || binding.engineeringBindingConfirmed || confirmEngineeringBinding) { "Confirm the target PC and owner scope before enabling Engineering." }
        val command = GatewayControlCommand(binding.installationId, observed.revision, UUID.randomUUID().toString().replace("-", ""), changes)
        // Persist before dispatch. Only an explicit retry after a missing receipt may resend this exact command.
        binding = binding.copy(pending = command, snapshot = observed, lastConfirmedAtMillis = System.currentTimeMillis())
        save(binding)
        publish(profile, GatewayControlPhase.SAVING, binding, "Applying the requested setting on ${profile.name.take(80)}…")
        dispatchPrepared(profile, binding)
    }

    private suspend fun dispatchPrepared(profile: PlatformV2, prepared: GatewayControlBinding) {
        var binding = prepared
        val command = requireNotNull(binding.pending)
        try {
            val response = exchange(binding, "PATCH", "/gateway/settings", json.encodeToString(command))
            val receipt = runCatching { json.decodeFromString<GatewayControlReceipt>(response.body).validate(command, binding.principalId) }.getOrNull()
            when {
                receipt != null -> acceptReceipt(profile, binding, receipt)
                response.status in setOf(401, 403) -> {
                    binding = binding.copy(token = "", revoked = true)
                    save(binding)
                    publish(profile, GatewayControlPhase.REVOKED, binding, "Access revoked. This change was refused.")
                }
                else -> publish(profile, GatewayControlPhase.RECONCILING, binding, "Change outcome unknown. Refresh checks the original receipt without resending.")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { publish(profile, GatewayControlPhase.RECONCILING, binding, "The saved change needs its original receipt checked.") }
            throw cancelled
        } catch (_: Exception) {
            publish(profile, GatewayControlPhase.RECONCILING, binding, "Change outcome unknown. Refresh checks the original receipt without resending.")
        }
    }

    /** User action only. Refresh/reconnect never calls this method or repeats a mutation. */
    suspend fun retryPending(profile: PlatformV2, expected: GatewayControlCommand, expectedPrincipal: String, expectedEndpoint: String) = mutex(profile.uid).withLock {
        var binding = requireNotNull(load(profile.uid))
        require(binding.originalEndpoint == original(profile) && !binding.revoked && !binding.revocationPending)
        require(binding.pending == expected && binding.installationId == expected.installationId && binding.principalId == expectedPrincipal && binding.endpoint == expectedEndpoint) {
            "The original change or paired target changed. Review its retained receipt before retrying."
        }
        require(expected.commandId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        publish(profile, GatewayControlPhase.RECONCILING, binding, "Checking whether this original change already reached the PC…")
        try {
            val response = exchange(binding, "GET", "/gateway/settings/commands/${expected.commandId}")
            when (response.status) {
                200 -> acceptReceipt(profile, binding, json.decodeFromString<GatewayControlReceipt>(response.body))
                401, 403 -> throw GatewayControlRevoked()
                404 -> {
                    // Preserve the original revision and body even if a host edit now makes them stale.
                    // The host can return a durable rejection; a replacement command is never invented.
                    binding = binding.copy(snapshot = snapshot(binding), lastConfirmedAtMillis = System.currentTimeMillis())
                    save(binding)
                    publish(profile, GatewayControlPhase.SAVING, binding, "Retrying the original reviewed change on ${profile.name.take(80)}…", fresh = true)
                    dispatchPrepared(profile, binding)
                }
                else -> publish(profile, GatewayControlPhase.RECONCILING, binding, "The original receipt could not be checked. This change was not resent.")
            }
        } catch (revoked: GatewayControlRevoked) {
            binding = binding.copy(token = "", revoked = true)
            save(binding)
            publish(profile, GatewayControlPhase.REVOKED, binding, "This phone's access was revoked. The original change is retained.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(profile, GatewayControlPhase.RECONCILING, binding, "The original receipt remains unconfirmed. Refresh to check its saved outcome.")
        }
    }

    private suspend fun acceptReceipt(profile: PlatformV2, binding: GatewayControlBinding, receipt: GatewayControlReceipt) {
        val command = requireNotNull(binding.pending)
        receipt.validate(command, binding.principalId)
        val updated = binding.copy(
            snapshot = receipt.snapshot,
            lastConfirmedAtMillis = System.currentTimeMillis(),
            pending = null,
            engineeringBindingConfirmed = binding.engineeringBindingConfirmed || receipt.status != "rejected" && command.changes["engineering_enabled"] == JsonPrimitive(true)
        )
        save(updated)
        publish(
            profile,
            if (receipt.status == "rejected" && receipt.httpStatus == 409) GatewayControlPhase.CONFLICT else GatewayControlPhase.LIVE,
            updated,
            when (receipt.status) {
                "rejected" -> "The Gateway refused this change before applying it. Review its current values and supported settings."
                "superseded" -> "This change was saved earlier, then changed on another device. Showing the latest Gateway values."
                "waiting_for_jobs" -> "Saved. Waiting for the listed jobs before applying the remaining settings."
                "setup_required" -> "Saved. Complete the listed prerequisites on the PC."
                "waiting_pc" -> "Request saved for review on the PC. The requested host action has not been performed."
                "reviewed" -> "The PC marked this request as reviewed. This receipt does not confirm a host operation."
                else -> "Applied on ${profile.name.take(80)}."
            },
            fresh = true
        )
    }

    private suspend fun reconcile(profile: PlatformV2, binding: GatewayControlBinding) {
        val command = requireNotNull(binding.pending)
        val response = exchange(binding, "GET", "/gateway/settings/commands/${command.commandId}")
        if (response.status in setOf(401, 403)) throw GatewayControlRevoked()
        if (response.status == 200) {
            acceptReceipt(profile, binding, json.decodeFromString<GatewayControlReceipt>(response.body))
        } else {
            publish(profile, GatewayControlPhase.RECONCILING, binding, "The original change receipt is unavailable. No replacement change was sent.")
        }
    }

    suspend fun revoke(profile: PlatformV2) = mutex(profile.uid).withLock {
        var binding = requireNotNull(load(profile.uid))
        require(binding.originalEndpoint == original(profile) && binding.pending == null)
        binding = binding.copy(revocationPending = true)
        save(binding)
        val response = exchange(binding, "DELETE", "/gateway/devices/self")
        if (response.status in setOf(200, 204, 401, 403)) {
            binding = binding.copy(token = "", revoked = true, revocationPending = false)
            save(binding)
            publish(profile, GatewayControlPhase.REVOKED, binding, "This phone's control access is revoked. Saved work remains on the Gateway.")
        } else {
            publish(profile, GatewayControlPhase.STALE, binding, "Revocation is unconfirmed. Refresh to check this original grant.")
        }
    }

    override suspend fun prepare(config: ProviderRequestConfig, method: String, path: String, body: String): GatewaySignedRequest? {
        val binding = gatewayControlRoute(config, config.profileUid?.let { load(it) }) ?: return null
        return transport.signed(binding.endpoint, binding.fingerprint, binding.installationId, binding.token, binding.keyAlias, method, path, body)
    }

    override suspend fun identity(config: ProviderRequestConfig): String? = gatewayControlRoute(config, config.profileUid?.let { load(it) })?.let {
        gatewayControlDigest("${it.endpoint}|${it.fingerprint}|${it.installationId}|${it.principalId}|${it.deviceId}")
    }
}

private class GatewayControlRevoked : IllegalStateException()
