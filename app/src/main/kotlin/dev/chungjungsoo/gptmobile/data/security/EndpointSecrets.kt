package dev.chungjungsoo.gptmobile.data.security

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Query values belong in the vault, not in connection rows or diagnostic output. */
object EndpointSecrets {
    private const val MARKER = "__vault__"
    fun reference(uid: String) = "endpoint_$uid"
    fun masked(url: String): String = runCatching {
        val uri = URI(url)
        if (uri.rawQuery == null) return url
        url.substringBefore('?') + "?" + uri.rawQuery.split('&').joinToString("&") { it.substringBefore('=') + "=" + MARKER }
    }.getOrDefault("[invalid endpoint]")

    suspend fun resolve(connection: ToolConnection, vault: SecretVault): String {
        val visible = connection.endpointUrl.orEmpty()
        if (!visible.contains(MARKER)) return visible
        val bytes = vault.read(reference(connection.connectionUid)) ?: error("Re-enter the saved endpoint credentials.")
        return try {
            bytes.decodeToString().also { require(masked(it) == visible) { "Saved endpoint does not match this connection." } }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun protect(connection: ToolConnection, vault: SecretVault): ToolConnection {
        val endpoint = connection.endpointUrl ?: return connection
        if (endpoint.contains(MARKER)) {
            return connection
        }
        val uri = URI(endpoint)
        require(uri.userInfo == null) { "Use the credential field instead of credentials in the URL authority." }
        if (uri.rawQuery == null) return connection
        val bytes = endpoint.encodeToByteArray()
        try {
            vault.put(reference(connection.connectionUid), bytes)
            val verified = vault.read(reference(connection.connectionUid)) ?: error("Endpoint credential storage failed.")
            try {
                check(verified.contentEquals(bytes)) { "Endpoint credential verification failed." }
            } finally {
                verified.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
        return connection.copy(endpointUrl = masked(endpoint))
    }
}

object DiagnosticRedactor {
    private val queryValue = Regex("([?&][^=\\s&#]+)=([^&#\\s]+)")
    private val userInfo = Regex("(https?://)[^/\\s@]+@", RegexOption.IGNORE_CASE)
    private val sensitiveKey = Regex("password|secret|token|authorization|cookie|api.?key|credential", RegexOption.IGNORE_CASE)
    fun arguments(value: JsonObject): String {
        fun clean(element: JsonElement, depth: Int = 0): JsonElement {
            if (depth > 8) return JsonPrimitive("[nested content omitted]")
            return when (element) {
                is JsonObject -> JsonObject(
                    element.entries.take(64).associate { (key, child) ->
                        key to if (sensitiveKey.containsMatchIn(key)) JsonPrimitive("[redacted]") else clean(child, depth + 1)
                    }
                )
                is JsonArray -> JsonArray(element.take(64).map { clean(it, depth + 1) })
                is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content).take(2000)) else element
            }
        }
        return clean(value).toString().take(8000)
    }
    fun redact(text: String): String = userInfo.replace(queryValue.replace(text, "$1=[redacted]"), "$1[redacted]@")
}
