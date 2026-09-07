package dev.melo.gptmobile.improved.data.agent.tool

import dev.melo.gptmobile.improved.data.network.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpConnectionConfig(
    val connectionUid: String,
    val endpointUrl: String,
    val allowCleartext: Boolean,
    val authorizationHeader: String? = null
)

class McpHttpException(val statusCode: Int, message: String) : Exception(message)

@Singleton
class McpClientManager internal constructor(
    private val httpClient: HttpClient
) {
    @Inject
    constructor(networkClient: NetworkClient) : this(networkClient())

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()
    private val inFlight = mutableMapOf<String, InFlight>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listTools(config: McpConnectionConfig): List<Tool> = withSession(config) { session ->
        val tools = mutableListOf<Tool>()
        var cursor: String? = null
        var pageCount = 0
        do {
            check(++pageCount <= MAX_TOOL_PAGES) { "MCP server returned too many tool pages." }
            val requestParams = buildJsonObject {
                cursor?.let { put("cursor", it) }
            }
            val response = session.sendRpc("tools/list", requestParams)
            val result = response["result"]?.jsonObject
                ?: throw McpHttpException(200, "MCP tools/list returned invalid response: $response")
            val toolList = result["tools"]?.jsonArray.orEmpty()
            for (element in toolList) {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val description = obj["description"]?.jsonPrimitive?.contentOrNull
                val inputSchemaObj = obj["inputSchema"]?.jsonObject
                val schemaType = inputSchemaObj?.get("type")?.jsonPrimitive?.contentOrNull ?: "object"
                val properties = inputSchemaObj?.get("properties")?.jsonObject
                val requiredList = inputSchemaObj?.get("required")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                tools += Tool(
                    name = name,
                    description = description,
                    inputSchema = ToolInputSchema(
                        type = schemaType,
                        properties = properties,
                        required = requiredList
                    )
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (!cursor.isNullOrBlank())
        tools
    }

    suspend fun callTool(
        config: McpConnectionConfig,
        toolName: String,
        arguments: JsonObject
    ): CallToolResult = withSession(config) { session ->
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val response = session.sendRpc("tools/call", params)
        val result = response["result"]?.jsonObject
            ?: throw McpHttpException(200, "MCP tools/call returned invalid response: $response")
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val contentArray = result["content"]?.jsonArray.orEmpty()
        val blocks = mutableListOf<ContentBlock>()
        for (item in contentArray) {
            val itemObj = item.jsonObject
            when (itemObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = itemObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    blocks += TextContent(text)
                }
                "resource" -> {
                    val resObj = itemObj["resource"]?.jsonObject
                    if (resObj != null) {
                        val uri = resObj["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val mimeType = resObj["mimeType"]?.jsonPrimitive?.contentOrNull
                        val text = resObj["text"]?.jsonPrimitive?.contentOrNull
                        if (text != null) {
                            blocks += EmbeddedResource(TextResourceContents(uri = uri, mimeType = mimeType, text = text))
                        } else {
                            val blob = resObj["blob"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            blocks += EmbeddedResource(BlobResourceContents(uri = uri, mimeType = mimeType, blob = blob))
                        }
                    }
                }
                "image" -> {
                    val data = itemObj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val mimeType = itemObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                    blocks += ImageContent(data = data, mimeType = mimeType)
                }
            }
        }
        val structured = result["structuredContent"]?.jsonObject
        CallToolResult(content = blocks, isError = isError, structuredContent = structured)
    }

    suspend fun close(connectionUid: String) {
        val session = takeSession(connectionUid) ?: return
        session.close()
    }

    suspend fun closeAll() {
        mutex.lock()
        val active = try {
            sessions.values.toList().also { sessions.clear() }
        } finally {
            mutex.unlock()
        }
        active.forEach { it.close() }
    }

    private suspend fun <T> withSession(config: McpConnectionConfig, block: suspend (Session) -> T): T {
        val session = session(config)
        return try {
            block(session)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { invalidate(config.connectionUid, session) }
            throw error
        } catch (error: Exception) {
            invalidate(config.connectionUid, session)
            throw error
        }
    }

    private suspend fun session(config: McpConnectionConfig): Session {
        val key = config.validatedKey()
        while (true) {
            val created = CompletableDeferred<Session>()
            var stale: Session? = null
            var awaiting: CompletableDeferred<Session>? = null
            mutex.lock()
            try {
                sessions[config.connectionUid]?.takeIf { it.key == key }?.let { return it }
                inFlight[config.connectionUid]?.let { existing ->
                    awaiting = existing.deferred
                } ?: run {
                    stale = sessions.remove(config.connectionUid)
                    inFlight[config.connectionUid] = InFlight(key, created)
                }
            } finally {
                mutex.unlock()
            }
            awaiting?.await()
            if (awaiting != null) continue
            withContext(NonCancellable) { stale?.close() }

            val result = runCatching {
                val session = Session(key, config, httpClient)
                session.initialize()
                session
            }
            withContext(NonCancellable) {
                mutex.lock()
                try {
                    if (inFlight[config.connectionUid]?.deferred === created) {
                        inFlight.remove(config.connectionUid)
                        result.getOrNull()?.let { sessions[config.connectionUid] = it }
                    }
                } finally {
                    mutex.unlock()
                }
                result.fold(created::complete, created::completeExceptionally)
            }
            return result.getOrThrow()
        }
    }

    private suspend fun invalidate(connectionUid: String, expected: Session) {
        mutex.lock()
        val removed = try {
            if (sessions[connectionUid] === expected) sessions.remove(connectionUid) else null
        } finally {
            mutex.unlock()
        }
        withContext(NonCancellable) { removed?.close() }
    }

    private suspend fun takeSession(connectionUid: String): Session? {
        mutex.lock()
        return try {
            sessions.remove(connectionUid)
        } finally {
            mutex.unlock()
        }
    }

    private fun McpConnectionConfig.validatedKey(): String {
        require(connectionUid.isNotBlank()) { "MCP connection ID is required." }
        require(endpointUrl.length <= MAX_ENDPOINT_LENGTH) { "MCP endpoint URL is too long." }
        val uri = runCatching { URI(endpointUrl) }.getOrNull()
            ?: throw IllegalArgumentException("MCP endpoint must be a valid URL.")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "MCP endpoint must use HTTP or HTTPS." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "MCP endpoint URL is invalid." }
        require(scheme != "http" || allowCleartext) { "Cleartext MCP requires explicit user approval." }
        require(authorizationHeader == null || authorizationHeader.isNotBlank()) { "MCP authorization header is required." }
        require(authorizationHeader?.contains('\r') != true && authorizationHeader?.contains('\n') != true) {
            "MCP authorization header is invalid."
        }
        require(authorizationHeader == null || authorizationHeader.length <= MAX_AUTHORIZATION_HEADER_LENGTH) {
            "MCP authorization header is too long."
        }
        return "$endpointUrl|${authorizationHeader.orEmpty().sha256()}"
    }

    private class Session(
        val key: String,
        private val config: McpConnectionConfig,
        private val httpClient: HttpClient
    ) {
        private var messageIdCounter = 0
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun initialize() {
            val initParams = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {})
                })
                put("clientInfo", buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                })
            }
            sendRpc("initialize", initParams)
        }

        suspend fun sendRpc(method: String, params: JsonObject): JsonObject {
            val id = ++messageIdCounter
            val rpcRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            val response: HttpResponse = httpClient.post(config.endpointUrl) {
                contentType(ContentType.Application.Json)
                config.authorizationHeader?.let { header(HttpHeaders.Authorization, it) }
                setBody(rpcRequest.toString())
            }
            if (response.status.value == 401) {
                throw McpHttpException(401, "MCP authentication failed (401 Unauthorized)")
            }
            if (!response.status.isSuccess()) {
                throw McpHttpException(response.status.value, "MCP request failed with HTTP ${response.status.value}")
            }
            val responseText = response.bodyAsText()
            val responseObj = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrElse {
                throw McpHttpException(response.status.value, "Malformed JSON-RPC response from MCP server: $responseText")
            }
            responseObj["error"]?.jsonObject?.let { err ->
                val code = err["code"]?.jsonPrimitive?.intOrNull ?: -1
                val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown MCP error"
                throw McpHttpException(code, msg)
            }
            return responseObj
        }

        fun close() {
            // Stateless HTTP JSON-RPC does not require explicit remote teardown
        }
    }

    private data class InFlight(val key: String, val deferred: CompletableDeferred<Session>)

    private companion object {
        const val CLIENT_NAME = "gpt-mobile"
        const val CLIENT_VERSION = "0.8.0"
        const val MAX_TOOL_PAGES = 50
        const val MAX_DISCOVERED_TOOLS = 200
        const val MAX_ENDPOINT_LENGTH = 32 * 1024
        const val MAX_AUTHORIZATION_HEADER_LENGTH = 128 * 1024
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
