package dev.chungjungsoo.gptmobile.data.agent.tool

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Retain a bounded, recognized error code, never the server's arbitrary message or body. */
internal fun mcpInitializationRejection(error: StreamableHttpError): McpProtocolConfigurationException {
    val declaredCode = mcpDeclaredRpcErrorCode(error.message)
    val detail = when (declaredCode) {
        -32700 -> "The server declared a JSON parse rejection."
        -32600 -> "The server declared an invalid JSON-RPC request."
        -32601 -> "The server declared that initialize is unsupported."
        -32602 -> "The server declared invalid initialize parameters."
        -32022 -> "The server declared a protocol-version rejection."
        else -> "The response did not establish a specific rejection cause."
    }
    return McpProtocolConfigurationException(
        "MCP_INITIALIZATION_SCHEMA_INVALID",
        "The server rejected the initialize handshake (HTTP ${error.code}). $detail Review the saved endpoint and the server's initialization contract. The app has not changed the endpoint or retried the rejected request.",
        error.code,
        "INITIALIZE_REQUEST_ACCEPTANCE_REQUIRED",
        "initialize",
        declaredCode
    )
}

/** SDK transport errors can include the response body in their message; arbitrary text is ignored. */
internal fun mcpDeclaredRpcErrorCode(message: String?): Int? {
    if (message == null || message.length > 16_384) return null
    val start = message.indexOf('{')
    val end = message.lastIndexOf('}')
    if (start < 0 || end < start) return null
    val response = runCatching { Json.parseToJsonElement(message.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
    if ((response["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") return null
    val error = response["error"] as? JsonObject ?: return null
    return (error["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in MCP_DECLARED_RPC_CODES }
}

internal val MCP_DECLARED_RPC_CODES = setOf(-32700, -32600, -32601, -32602, -32603, -32000, -32020, -32021, -32022)
