package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate
import java.net.URI

/** Catalog hits bypass this helper; actual discovery pages share the host-aware recovery probe. */
internal suspend fun <T> withMcpConnectivityLease(config: McpConnectionConfig, block: suspend () -> T): T {
    val gate = ResearchConnectivityGate.shared
    val lease = gate.acquire(URI(config.endpointUrl).host) ?: error("DNS_RECOVERY: MCP discovery is waiting for the single connectivity probe.")
    return try {
        block()
    } finally {
        gate.release(lease)
    }
}
