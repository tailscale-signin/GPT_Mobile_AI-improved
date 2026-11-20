package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.github.GatewayScopeIdentity
import dev.chungjungsoo.gptmobile.data.github.withGatewayIdentity
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayOwnerControlTest {
    private val owner = "paired:" + "d".repeat(32)
    private val binding = GatewayControlBinding("gateway", "http://192.168.1.9:8090/v1", "https://192.168.1.9:29444", "a".repeat(64), "pc", owner, "d".repeat(32), "owner", "device-token", "key-alias", engineeringBindingConfirmed = true)
    private val config = ProviderRequestConfig(binding.originalEndpoint, "inference-secret", profileUid = binding.profileUid)

    @Test fun ordinaryInferenceAndRetainedSharedJobsNeverBorrowPairedOwnerAuthority() {
        assertNull(gatewayControlRoute(config, binding))
        val explicit = config.copy(gatewayOwnerControl = true)
        assertSame(binding, gatewayControlRoute(explicit, binding))
        assertNull(gatewayControlRoute(explicit.withGatewayIdentity(GatewayScopeIdentity("pc", "network:shared")), binding))
        assertSame(binding, gatewayControlRoute(config.withGatewayIdentity(GatewayScopeIdentity("pc", owner)), binding))
        assertEquals("inference-secret", config.token)
        assertEquals(binding.originalEndpoint, config.apiUrl)
    }

    @Test fun savedPairedWorkFailsClosedAfterRestoreRevocationOrWrongInstallation() {
        val retained = config.withGatewayIdentity(GatewayScopeIdentity("pc", owner))
        for (grant in listOf(null, binding.copy(revoked = true), binding.copy(revocationPending = true), binding.copy(installationId = "other-pc"), binding.copy(principalId = "paired:" + "f".repeat(32)), binding.copy(originalEndpoint = "http://192.168.1.10:8090/v1"))) {
            assertTrue(runCatching { gatewayControlRoute(retained, grant) }.isFailure)
        }
        assertTrue(runCatching { gatewayControlRoute(config.copy(gatewayOwnerControl = true), binding.copy(engineeringBindingConfirmed = false)) }.isFailure)
        assertTrue(runCatching { gatewayControlRoute(config.copy(gatewayOwnerControl = true, apiUrl = "http://192.168.1.10:8090/v1"), binding) }.isFailure)
    }

    @Test fun pairingRequiresAnUnexpiredExactHttpsOriginAndUniqueFields() {
        val link = "gptmobile://gateway-control?endpoint=https%3A%2F%2F192.168.1.9%3A29444&installation_id=pc&fingerprint=${"a".repeat(64)}&code=${"b".repeat(43)}&expires=1300"
        assertEquals(binding.endpoint, GatewayPairingLink.parse(link, 1000).endpoint)
        assertEquals("http://192.168.1.9:8090/v1", GatewayPairingLink.parse(link + "&gateway_port=8090", 1000).profileEndpoint)
        for (invalid in listOf(link + "&gateway_port=65536", link + "&gateway_port=80", link + "&gateway_port=host.example", link + "&installation_id=other", link.replace("https%3A", "http%3A"), link.replace("29444&", "29444%2Fredirect&"), link + "#fragment", link.replace("expires=1300", "expires=1000"), link.replace("expires=1300", "expires=999999"))) {
            assertTrue(invalid, runCatching { GatewayPairingLink.parse(invalid, 1000) }.isFailure)
        }
    }

    @Test fun requestProofBindsExactMethodQueryTimeNonceAndRawBody() {
        val nonce = "1".repeat(32)
        assertEquals("gptmobile-control-v1\npc\nGET\n/gateway/settings\n1000\n$nonce\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", gatewayControlSigningText("pc", "GET", "/gateway/settings", 1000, nonce, ""))
        val original = gatewayControlSigningText("pc", "POST", "/gateway/engineering/jobs?cursor=a%2Fb", 1000, nonce, "{\"text\":\"λ\"}")
        assertFalse(original == gatewayControlSigningText("pc", "POST", "/gateway/engineering/jobs?cursor=a/b", 1000, nonce, "{\"text\":\"λ\"}"))
        assertFalse(original == gatewayControlSigningText("pc", "POST", "/gateway/engineering/jobs?cursor=a%2Fb", 1000, nonce, "{ \"text\":\"λ\"}"))
        assertTrue(runCatching { gatewayControlSigningText("pc\nother", "GET", "/gateway/settings", 1000, nonce, "") }.isFailure)
        assertEquals("/gateway/conversations/id%2Fpart/messages?cursor=a%2Bb&after=1", gatewayControlCanonicalPath(binding.endpoint, "/gateway/conversations/id%2Fpart/messages?cursor=a%2Bb&after=1"))
        assertTrue(gatewayControlSigningText("pc", "PUT", "/gateway/conversations/id/bookmarks/message", 1000, nonce, "{}").contains("\nPUT\n"))
    }

    @Test fun pinnedCertificateRejectsAnyDifferentLeafAndExpiredCertificates() {
        val cert = mockk<X509Certificate>()
        val encoded = byteArrayOf(1, 2, 3, 4)
        every { cert.encoded } returns encoded
        every { cert.checkValidity() } returns Unit
        val pin = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        GatewayCertificatePin(pin).checkServerTrusted(arrayOf(cert), "EC")
        assertTrue(runCatching { GatewayCertificatePin("0".repeat(64)).checkServerTrusted(arrayOf(cert), "EC") }.exceptionOrNull() is CertificateException)
        every { cert.checkValidity() } throws CertificateException("expired")
        assertTrue(runCatching { GatewayCertificatePin(pin).checkServerTrusted(arrayOf(cert), "EC") }.exceptionOrNull() is CertificateException)
    }

    @Test fun receiptValidationRejectsWrongPrincipalWrongCommandAndFalseAppliedRevisions() {
        val command = GatewayControlCommand("pc", 3, "command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        val snapshot = controlSnapshot(owner, 4)
        val receipt = GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", owner, 4, "applied", snapshot)
        receipt.validate(command, owner)
        assertTrue(runCatching { receipt.copy(principalId = "network:shared").validate(command, owner) }.isFailure)
        assertTrue(runCatching { receipt.copy(commandId = "other").validate(command, owner) }.isFailure)
        assertTrue(runCatching { receipt.copy(revision = 3).validate(command, owner) }.isFailure)
        receipt.copy(status = "rejected", revision = 3, dispatchState = "not_dispatched", httpStatus = 409).validate(command, owner)
        receipt.copy(status = "superseded", requested = command.changes, snapshot = snapshot.copy(revision = 5)).validate(command, owner)
        assertTrue(runCatching { receipt.copy(status = "superseded").validate(command, owner) }.isFailure)
    }

    @Test fun rangesAndWritableFieldsNeverExpandAViewerOrLimitedOwnerGrant() {
        val snapshot = controlSnapshot(owner, 1).copy(writableFields = listOf("workers"))
        snapshot.validateChanges(JsonObject(mapOf("workers" to JsonPrimitive(16))))
        assertTrue(runCatching { snapshot.validateChanges(JsonObject(mapOf("workers" to JsonPrimitive(17)))) }.isFailure)
        assertTrue(runCatching { snapshot.validateChanges(JsonObject(mapOf("engineering_enabled" to JsonPrimitive(true)))) }.isFailure)
        assertTrue(runCatching { snapshot.copy(role = "viewer").validateChanges(JsonObject(mapOf("workers" to JsonPrimitive(2)))) }.isFailure)
        assertFalse(GatewayControlField.WORKERS.accepts(JsonPrimitive("2")))
        assertFalse(GatewayControlField.ENGINEERING.accepts(JsonPrimitive("true")))
    }

    @Test fun validationSelectionIsRestrictedToTheCurrentHostApprovedRepositoryChoices() {
        val approved = "a".repeat(64)
        val other = "b".repeat(64)
        val snapshot = controlSnapshot(owner, 1).copy(
            desired = JsonObject(mapOf("validation_profile" to JsonPrimitive("disabled"))),
            effective = JsonObject(mapOf("validation_profile" to JsonPrimitive("invalid"))),
            writableFields = listOf("validation_profile"),
            validationProfiles = mapOf(approved to "owner/repository")
        ).validate("pc", owner)
        snapshot.validateChanges(JsonObject(mapOf("validation_profile" to JsonPrimitive(approved))))
        for (value in listOf(other, "disabled", "invalid", "run arbitrary command")) {
            assertTrue(runCatching { snapshot.validateChanges(JsonObject(mapOf("validation_profile" to JsonPrimitive(value)))) }.isFailure)
        }
        assertTrue(runCatching { snapshot.copy(validationProfiles = emptyMap()).validateChanges(JsonObject(mapOf("validation_profile" to JsonPrimitive(approved)))) }.isFailure)
        assertTrue(runCatching { snapshot.copy(validationProfiles = mapOf("bad" to "Unexpected profile")).validate("pc", owner) }.isFailure)
    }

    @Test fun backendSelectionRequiresTheCurrentPcApprovedCatalogAndCannotAuthorizeANewOrigin() {
        val current = "http://127.0.0.1:11434"
        val approved = "http://192.168.1.20:1234"
        val changes = JsonObject(mapOf("backend" to JsonPrimitive(approved), "model" to JsonPrimitive("exact-model")))
        val snapshot = controlSnapshot(owner, 1).copy(writableFields = listOf("backend", "model"), approvedBackends = listOf(current, approved)).validate("pc", owner)
        snapshot.validateChanges(changes)
        assertTrue(runCatching { snapshot.copy(approvedBackends = listOf(current)).validateChanges(changes) }.isFailure)
        assertTrue(runCatching { snapshot.copy(writableFields = listOf("model")).validateChanges(changes) }.isFailure)
        assertTrue(runCatching { snapshot.validateChanges(JsonObject(mapOf("backend" to JsonPrimitive("https://unapproved.invalid")))) }.isFailure)
        assertTrue(runCatching { snapshot.copy(approvedBackends = listOf(current, current)).validate("pc", owner) }.isFailure)
    }

    @Test fun hostReviewNotesHaveABoundedVocabularyAndDistinctAcknowledgementReceipts() {
        val request = "Review pending restart"
        val changes = JsonObject(mapOf("host_request" to JsonPrimitive(request)))
        val command = GatewayControlCommand("pc", 1, "request-original", changes)
        val record = GatewayControlHostRequest(command.commandId, owner, request, "waiting_pc", 1000.0)
        val snapshot = controlSnapshot(owner, 2).copy(writableFields = listOf("host_request", "workers"), hostRequests = listOf(record)).validate("pc", owner)
        for (note in GatewayControlField.HOST_REQUEST.choices) snapshot.validateChanges(JsonObject(mapOf("host_request" to JsonPrimitive(note))))
        assertTrue(runCatching { snapshot.validateChanges(JsonObject(changes + ("workers" to JsonPrimitive(4)))) }.isFailure)
        assertTrue(runCatching { snapshot.validateChanges(JsonObject(mapOf("host_request" to JsonPrimitive("Restart now")))) }.isFailure)
        assertTrue(runCatching { snapshot.copy(hostRequests = listOf(record.copy(principalId = "network:shared"))).validate("pc", owner) }.isFailure)
        val receipt = GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", owner, 2, "waiting_pc", snapshot)
        receipt.validate(command, owner)
        receipt.copy(status = "reviewed", snapshot = snapshot.copy(hostRequests = listOf(record.copy(status = "reviewed", reviewedAt = 1001.0)))).validate(command, owner)
        assertTrue(runCatching { receipt.validate(command.copy(changes = JsonObject(mapOf("workers" to JsonPrimitive(4)))), owner) }.isFailure)
    }
}

internal fun controlSnapshot(principal: String, revision: Long, engineering: Boolean = false): GatewayControlSnapshot = GatewayControlSnapshot(
    GATEWAY_CONTROL_SCHEMA, "pc", principal, revision, 1000.0,
    JsonObject(mapOf("engineering_enabled" to JsonPrimitive(engineering), "workers" to JsonPrimitive(2), "model" to JsonPrimitive(""))),
    JsonObject(mapOf("engineering_enabled" to JsonPrimitive(engineering), "workers" to JsonPrimitive(2), "model" to JsonPrimitive(""))),
    writableFields = listOf("engineering_enabled", "workers"), role = "owner", capabilities = JsonObject(mapOf("controlSettings.v1" to JsonPrimitive(true)))
)
