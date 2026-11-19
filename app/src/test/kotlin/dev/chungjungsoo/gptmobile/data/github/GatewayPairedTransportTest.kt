package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlBinding
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairedRequestSource
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySignedRequest
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayControlRoute
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.content.TextContent
import java.io.EOFException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPairedTransportTest {
    private val principal = "paired:" + "d".repeat(32)
    private val binding = GatewayControlBinding("gateway", "http://192.168.1.9:8090/v1", "https://192.168.1.9:29444", "a".repeat(64), "pc", principal, "d".repeat(32), "owner", "paired-device-token", "device-key", engineeringBindingConfirmed = true)
    private val config = ProviderRequestConfig(binding.originalEndpoint, "ordinary-inference-token", profileUid = binding.profileUid, gatewayOwnerControl = true)

    @Test fun pairingInvalidatesSharedCapabilityCacheAndRetainedSharedJobsKeepTheirOriginalRoute() = runTest {
        var ordinaryCalls = 0
        var ownerCalls = 0
        HttpClient(
            MockEngine { request ->
                ordinaryCalls++
                assertEquals("Bearer ordinary-inference-token", request.headers["Authorization"])
                assertEquals(8090, request.url.port)
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"network:shared"}""")
            }
        ) { install(HttpTimeout) }.use { ordinary ->
            HttpClient(
                MockEngine { request ->
                    ownerCalls++
                    assertEquals("Bearer paired-device-token", request.headers["Authorization"])
                    assertEquals("/gateway/engineering/capabilities", request.url.encodedPath)
                    assertEquals("pc", request.headers["X-Gateway-Installation-ID"])
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"$principal"}""")
                }
            ) { install(HttpTimeout) }.use { owner ->
                val source = Source(owner)
                val client = GatewayEngineeringClient(ordinary, pairedRequests = source)
                assertEquals("network:shared", client.capabilities(config).principalId)
                source.binding = binding
                assertEquals(principal, client.capabilities(config).principalId)
                assertEquals("network:shared", client.capabilities(config.withGatewayIdentity(GatewayScopeIdentity("pc", "network:shared"))).principalId)
                assertEquals(1, ownerCalls)
                assertTrue(ordinaryCalls >= 1)
            }
        }
    }

    @Test fun savedOwnerIdentityReadsUseFreshProofsForEachSafeRetryAndInstructionsSignTheExactBodyOnce() = runTest {
        val nonces = mutableListOf<String>()
        var writes = 0
        var submittedBody: String? = null
        HttpClient(MockEngine { error("A saved owner request must never fall back to the ordinary inference connection") }) { install(HttpTimeout) }.use { ordinary ->
            HttpClient(
                MockEngine { request ->
                    assertEquals("Bearer paired-device-token", request.headers["Authorization"])
                    assertEquals(29444, request.url.port)
                    if (request.method.value == "GET") {
                        nonces += requireNotNull(request.headers["X-Gateway-Device-Nonce"])
                        if (nonces.size == 1) throw EOFException("stale connection")
                        respond("""{"schema_version":1,"protocol_version":1,"gateway_id":"pc","principal_id":"$principal"}""")
                    } else {
                        writes++
                        submittedBody = (request.body as TextContent).text
                        throw EOFException("lost mutation reply")
                    }
                }
            ) { install(HttpTimeout) }.use { owner ->
                val source = Source(owner).also { it.binding = binding }
                val retained = config.copy(gatewayOwnerControl = false).withGatewayIdentity(GatewayScopeIdentity("pc", principal))
                val identity = GatewayJobClient(ordinary, pairedRequests = source).identity(retained)
                assertEquals(principal, identity.principalId)
                assertEquals(2, nonces.size)
                assertEquals(2, nonces.distinct().size)
                val result = runCatching { GatewayConversationCommandClient(ordinary, source).instruction(retained, "saved", GatewayInstructionCommand("stable-command", "Review λ without restarting", 4)) }
                assertTrue(result.isFailure)
                assertEquals(1, writes)
                assertEquals(submittedBody, source.bodies.last())
                assertEquals("/gateway/conversations/saved/messages", source.paths.last())
            }
        }
    }

    private class Source(private val http: HttpClient) : GatewayPairedRequestSource {
        var binding: GatewayControlBinding? = null
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()
        override suspend fun identity(config: ProviderRequestConfig): String? = gatewayControlRoute(config, binding)?.let { "${it.installationId}/${it.principalId}" }
        override suspend fun prepare(config: ProviderRequestConfig, method: String, path: String, body: String): GatewaySignedRequest? {
            val selected = gatewayControlRoute(config, binding) ?: return null
            bodies += body
            paths += path
            return GatewaySignedRequest(http, selected.endpoint + path, selected.token, mapOf("X-Gateway-Installation-ID" to selected.installationId, "X-Gateway-Device-Nonce" to bodies.size.toString(), "X-Gateway-Device-Signature" to "test-signature"))
        }
    }
}
