package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayControlRepositoryTest {
    private val json = Json { encodeDefaults = true }
    private val principal = "paired:" + "d".repeat(32)
    private val profile = PlatformV2(uid = "gateway", name = "Studio PC", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.9:8090/v1")
    private val key = "gateway_scope_control_${gatewayControlDigest(profile.uid)}"
    private val initial = GatewayControlBinding(profile.uid, profile.apiUrl, "https://192.168.1.9:29444", "a".repeat(64), "pc", principal, "d".repeat(32), "owner", "token", "keystore-alias", engineeringBindingConfirmed = true, snapshot = controlSnapshot(principal, 1))
    private val vault = Vault()
    private val keys = mockk<GatewayControlDeviceKeys>()
    private val transport = mockk<GatewayControlTransport>()
    private fun repository() = GatewayControlRepository(vault, keys, transport)
    private suspend fun seed(binding: GatewayControlBinding = initial) = vault.put(key, json.encodeToString(binding).toByteArray())
    private suspend fun saved(): GatewayControlBinding = json.decodeFromString(requireNotNull(vault.read(key)).toString(Charsets.UTF_8))

    @Test fun installRequiresTheVersionShownByThePairedPc() = runTest {
        seed()
        val snapshot = controlSnapshot(principal, 1).copy(writableFields = listOf("update_mode"), updates = GatewayUpdateStatus("18.0.0", "available", "18.0.2"))
        val posted = mutableListOf<String>()
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (arg<String>(5) == "GET") {
                GatewayControlResponse(200, json.encodeToString(snapshot))
            } else {
                assertEquals("/gateway/updates", arg<String>(6))
                posted.add(arg(7))
                GatewayControlResponse(200, """{"accepted":true}""")
            }
        }
        val repository = repository()
        assertTrue(runCatching { repository.updateGateway(profile, "18.0.1") }.isFailure)
        assertTrue(posted.isEmpty())
        repository.updateGateway(profile, "18.0.2")
        assertEquals(listOf("""{"action":"install","target":"18.0.2"}"""), posted)
    }

    @Test fun offlineRestartRetainsTheLastConfirmedValuesAndTimeWithoutRestoringWriteAuthority() = runTest {
        seed()
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } returns GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 2)))
        val live = repository()
        live.refresh(profile)
        val confirmed = requireNotNull(live.states.value[profile.uid])
        assertNotNull(confirmed.receivedAtMillis)
        assertTrue(confirmed.writable)

        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } throws IOException("offline")
        val restarted = repository()
        restarted.refresh(profile)

        val stale = requireNotNull(restarted.states.value[profile.uid])
        assertEquals(GatewayControlPhase.STALE, stale.phase)
        assertEquals(confirmed.snapshot, stale.snapshot)
        assertEquals(confirmed.receivedAtMillis, stale.receivedAtMillis)
        assertEquals(saved().lastConfirmedAtMillis, stale.receivedAtMillis)
        assertFalse(stale.writable)
        assertNull(saved().pending)
    }

    @Test fun acceptedPcReviewNoteResolvesTransportUncertaintyAndAllowsOtherSettingsChanges() = runTest {
        seed()
        var current = controlSnapshot(principal, 1).copy(writableFields = listOf("host_request", "workers"))
        val commands = mutableListOf<GatewayControlCommand>()
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (arg<String>(5) == "GET") {
                GatewayControlResponse(200, json.encodeToString(current))
            } else {
                assertEquals("PATCH", arg<String>(5))
                val command = json.decodeFromString<GatewayControlCommand>(arg<String>(7))
                assertEquals(current.revision, command.expectedRevision)
                commands += command
                val note = (command.changes["host_request"] as? JsonPrimitive)?.content
                current = if (note != null) {
                    current.copy(revision = current.revision + 1, hostRequests = listOf(GatewayControlHostRequest(command.commandId, principal, note, "waiting_pc", 1000.0)))
                } else {
                    current.copy(revision = current.revision + 1, desired = JsonObject(current.desired + command.changes), effective = JsonObject(current.effective + command.changes))
                }
                GatewayControlResponse(200, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, current.revision, if (note != null) "waiting_pc" else "applied", current)))
            }
        }
        val repository = repository()
        repository.apply(profile, 1, JsonObject(mapOf("host_request" to JsonPrimitive("Review pending restart"))))
        val accepted = requireNotNull(repository.states.value[profile.uid])
        assertTrue(accepted.writable)
        assertNull(accepted.pendingCommand)
        assertTrue(accepted.message.orEmpty().contains("not been performed"))
        assertEquals("waiting_pc", accepted.snapshot?.hostRequests?.single()?.status)

        repository.apply(profile, 2, JsonObject(mapOf("workers" to JsonPrimitive(4))))

        assertEquals(2, commands.size)
        assertFalse(commands.first().commandId == commands.last().commandId)
        assertEquals(JsonPrimitive(4), repository.states.value[profile.uid]?.snapshot?.effective?.get("workers"))
        assertEquals(commands.first().commandId, repository.states.value[profile.uid]?.snapshot?.hostRequests?.single()?.commandId)
        assertNull(saved().pending)
    }

    @Test fun lostPatchResponseIsReconciledAfterRestartWithoutRepeatingTheWrite() = runTest {
        seed()
        val methods = mutableListOf<String>()
        var original: GatewayControlCommand? = null
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val method = arg<String>(5)
            val path = arg<String>(6)
            methods += method
            when {
                method == "PATCH" -> {
                    original = json.decodeFromString<GatewayControlCommand>(arg<String>(7))
                    assertEquals(original, saved().pending)
                    throw IOException("lost response containing private diagnostic details")
                }
                path.contains("/commands/") -> {
                    val command = requireNotNull(original)
                    assertTrue(path.endsWith(command.commandId))
                    GatewayControlResponse(200, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 2, "waiting_for_jobs", controlSnapshot(principal, 2).copy(pending = listOf(GatewayControlPending("workers", "running jobs", listOf("eng_123")))))))
                }
                else -> GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 1)))
            }
        }
        val first = repository()
        first.apply(profile, 1, JsonObject(mapOf("workers" to JsonPrimitive(4))))
        assertEquals(GatewayControlPhase.RECONCILING, first.states.value[profile.uid]?.phase)
        assertNotNull(saved().pending)
        val restarted = repository()
        restarted.refresh(profile)
        assertEquals(GatewayControlPhase.LIVE, restarted.states.value[profile.uid]?.phase)
        assertNull(saved().pending)
        assertEquals(1, methods.count { it == "PATCH" })
        assertEquals(listOf("GET", "PATCH", "GET"), methods)
        assertTrue(restarted.states.value[profile.uid]?.snapshot?.pending?.single()?.blockingJobs == listOf("eng_123"))
    }

    @Test fun anotherDeviceRevisionChangeRefusesTheDraftBeforeAnyPatch() = runTest {
        seed()
        var calls = 0
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            assertEquals("GET", arg<String>(5))
            GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 2)))
        }
        val repository = repository()
        repository.apply(profile, 1, JsonObject(mapOf("workers" to JsonPrimitive(4))))
        assertEquals(1, calls)
        assertNull(saved().pending)
        assertEquals(GatewayControlPhase.CONFLICT, repository.states.value[profile.uid]?.phase)
        assertEquals(2L, repository.states.value[profile.uid]?.snapshot?.revision)
    }

    @Test fun aLostRejectedReceiptClearsOnlyItsMatchingCommandAndDoesNotRevokeThePhone() = runTest {
        val command = GatewayControlCommand("pc", 1, "original-command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        seed(initial.copy(pending = command))
        val receipt = GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 3, "rejected", controlSnapshot(principal, 3), "not_dispatched", 403)
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            assertEquals("GET", arg<String>(5))
            GatewayControlResponse(200, json.encodeToString(receipt))
        }
        val repository = repository()
        repository.refresh(profile)
        assertNull(saved().pending)
        assertFalse(saved().revoked)
        assertTrue(repository.states.value[profile.uid]?.message.orEmpty().contains("refused"))
    }

    @Test fun initialOwnerBindingRequiresExplicitTargetConfirmationEvenWhenHostGateIsAlreadyOn() = runTest {
        seed(initial.copy(engineeringBindingConfirmed = false, snapshot = controlSnapshot(principal, 1, true)))
        var patches = 0
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (arg<String>(5) == "PATCH") {
                patches++
                val command = json.decodeFromString<GatewayControlCommand>(arg<String>(7))
                GatewayControlResponse(200, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 2, "applied", controlSnapshot(principal, 2, true))))
            } else {
                GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 1, true)))
            }
        }
        val repository = repository()
        val enabled = JsonObject(mapOf("engineering_enabled" to JsonPrimitive(true)))
        assertTrue(runCatching { repository.apply(profile, 1, enabled) }.isFailure)
        assertEquals(0, patches)
        repository.apply(profile, 1, enabled, confirmEngineeringBinding = true)
        assertTrue(saved().engineeringBindingConfirmed)
        assertEquals(1, patches)
    }

    @Test fun aLostPairingClaimUsesTheRetainedCodeAndKeyAndShowsComparablePhoneFingerprint() = runTest {
        val publicKey = "phone-public-key-base64"
        val expires = System.currentTimeMillis() / 1000 + 300
        val code = "b".repeat(43)
        val link = "gptmobile://gateway-control?endpoint=https%3A%2F%2F192.168.1.9%3A29444&installation_id=pc&fingerprint=${"a".repeat(64)}&code=$code&expires=$expires"
        var claims = 0
        var claimBody: String? = null
        coEvery { keys.publicKey(any()) } returns publicKey
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when (arg<String>(6)) {
                "/gateway/pairing/claim" -> {
                    assertEquals(code, arg<String>(3))
                    val body = arg<String>(7)
                    if (claims++ == 0) {
                        claimBody = body
                        throw IOException("lost")
                    }
                    assertEquals(claimBody, body)
                    GatewayControlResponse(200, """{"pairing_id":"pairing-id","state":"awaiting_confirmation","expires_at":$expires}""")
                }
                "/gateway/pairing/finish" -> GatewayControlResponse(202, "{}")
                else -> error("Unexpected request")
            }
        }
        val first = repository()
        assertTrue(runCatching { first.pair(profile, link) }.isFailure)
        assertEquals(gatewayControlDigest(publicKey), first.states.value[profile.uid]?.deviceFingerprint)
        val restarted = repository()
        restarted.refresh(profile)
        restarted.finishPairing(profile)
        assertEquals(2, claims)
        assertEquals(GatewayControlPhase.PAIRING, restarted.states.value[profile.uid]?.phase)
        assertEquals(gatewayControlDigest(publicKey), restarted.states.value[profile.uid]?.deviceFingerprint)
        assertTrue(vault.references().all { it.startsWith("gateway_scope_") })
    }

    @Test fun explicitRetryAfterLostBeforeDispatchKeepsTheExactBodyAndRefreshNeverResends() = runTest {
        seed()
        val bodies = mutableListOf<String>()
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val method = arg<String>(5)
            val path = arg<String>(6)
            when {
                path.contains("/commands/") -> GatewayControlResponse(404, "{}")
                method == "PATCH" -> {
                    bodies += arg<String>(7)
                    if (bodies.size == 1) throw IOException("request never reached host")
                    val command = json.decodeFromString<GatewayControlCommand>(bodies.last())
                    GatewayControlResponse(200, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 2, "applied", controlSnapshot(principal, 2))))
                }
                else -> GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 1)))
            }
        }
        repository().apply(profile, 1, JsonObject(mapOf("workers" to JsonPrimitive(4))))
        val pending = requireNotNull(saved().pending)
        val restarted = repository()
        restarted.refresh(profile)
        assertEquals(1, bodies.size)
        assertEquals(pending, restarted.states.value[profile.uid]?.pendingCommand)
        restarted.retryPending(profile, pending, principal, initial.endpoint)
        assertEquals(2, bodies.size)
        assertEquals(bodies.first(), bodies.last())
        assertNull(saved().pending)
        assertEquals(GatewayControlPhase.LIVE, restarted.states.value[profile.uid]?.phase)
    }

    @Test fun explicitRetryAfterLostCommittedReplyReconcilesWithoutAnyPatch() = runTest {
        val command = GatewayControlCommand("pc", 1, "committed-command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        seed(initial.copy(pending = command))
        var reads = 0
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            assertEquals("GET", arg<String>(5))
            assertEquals("/gateway/settings/commands/${command.commandId}", arg<String>(6))
            reads++
            GatewayControlResponse(200, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 2, "applied", controlSnapshot(principal, 2))))
        }
        repository().retryPending(profile, command, principal, initial.endpoint)
        assertEquals(1, reads)
        assertNull(saved().pending)
    }

    @Test fun unavailableOrInvalidReceiptDoesNotAuthorizeAnExplicitResend() = runTest {
        val command = GatewayControlCommand("pc", 1, "unconfirmed-command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        for (status in listOf(503, 200)) {
            seed(initial.copy(pending = command))
            var reads = 0
            coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                assertEquals("GET", arg<String>(5))
                reads++
                GatewayControlResponse(status, "{}")
            }
            repository().retryPending(profile, command, principal, initial.endpoint)
            assertEquals(1, reads)
            assertEquals(command, saved().pending)
        }
    }

    @Test fun explicitRetryRefusesAChangedProfileTargetPrincipalOrCommandBeforeNetwork() = runTest {
        val command = GatewayControlCommand("pc", 1, "bound-command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        seed(initial.copy(pending = command))
        var calls = 0
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            error("Changed target must not receive any request")
        }
        val repository = repository()
        assertTrue(runCatching { repository.retryPending(profile.copy(apiUrl = "http://192.168.1.10:8090/v1"), command, principal, initial.endpoint) }.isFailure)
        assertTrue(runCatching { repository.retryPending(profile, command, "paired:" + "e".repeat(32), initial.endpoint) }.isFailure)
        assertTrue(runCatching { repository.retryPending(profile, command, principal, "https://192.168.1.10:29444") }.isFailure)
        assertTrue(runCatching { repository.retryPending(profile, command.copy(changes = JsonObject(mapOf("workers" to JsonPrimitive(8)))), principal, initial.endpoint) }.isFailure)
        seed(initial.copy(pending = command, endpoint = "https://192.168.1.10:29444"))
        assertTrue(runCatching { repository.retryPending(profile, command, principal, initial.endpoint) }.isFailure)
        assertEquals(0, calls)
        assertEquals(command, saved().pending)
    }

    @Test fun aHostEditDuringMissingReceiptRecoveryRejectsTheOriginalRevisionWithoutRewritingIt() = runTest {
        val command = GatewayControlCommand("pc", 1, "stale-command", JsonObject(mapOf("workers" to JsonPrimitive(4))))
        seed(initial.copy(pending = command))
        var patches = 0
        coEvery { transport.exchange(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            when {
                arg<String>(6).contains("/commands/") -> GatewayControlResponse(404, "{}")
                arg<String>(5) == "PATCH" -> {
                    patches++
                    assertEquals(command, json.decodeFromString<GatewayControlCommand>(arg<String>(7)))
                    GatewayControlResponse(409, json.encodeToString(GatewayControlReceipt("gptmobile.gateway.command.v1", command.commandId, "pc", principal, 3, "rejected", controlSnapshot(principal, 3), "not_dispatched", 409)))
                }
                else -> GatewayControlResponse(200, json.encodeToString(controlSnapshot(principal, 3)))
            }
        }
        val repository = repository()
        repository.retryPending(profile, command, principal, initial.endpoint)
        assertEquals(1, patches)
        assertNull(saved().pending)
        assertEquals(GatewayControlPhase.CONFLICT, repository.states.value[profile.uid]?.phase)
    }

    private class Vault : SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
        override suspend fun references(): Set<String> = values.keys.toSet()
    }
}
