package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpInitializationRejectionTest {
    @Test fun onlyARecognizedNumericJsonRpcCodeIsRetained() {
        assertEquals(-32602, mcpDeclaredRpcErrorCode("HTTP 400: {\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"private-token\",\"data\":{\"credential\":\"private-value\"}},\"id\":1}"))
        assertEquals(-32601, mcpDeclaredRpcErrorCode("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601}}"))
        listOf(
            null,
            "private body HTTP 400",
            "{\"error\":{\"code\":-32602}}",
            "{\"jsonrpc\":\"1.0\",\"error\":{\"code\":-32602}}",
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":\"-32602\"}}",
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":123456}}",
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"${"x".repeat(16_384)}\"}}"
        ).forEach { assertNull(mcpDeclaredRpcErrorCode(it)) }
    }
}
