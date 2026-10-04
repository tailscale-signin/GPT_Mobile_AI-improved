package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** MCP 2026-07-28: per-request metadata, request-scoped SSE and bounded MRTR. No session replay. */
internal class ModernMcpTransport(private val http: HttpClient, private val interactions: McpInteractions? = null, private val tasks: dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskStore? = null) {
    private val safeHttp = http.config {
        followRedirects = false
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true }
    private data class Endpoint(val identity: String, val discovery: JsonObject?, val checkedAt: Long)
    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val endpointDiscoveryCache = ConcurrentHashMap<String, Endpoint>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val taskLocks = Array(64) { Mutex() }
    private val schemas = ConcurrentHashMap<String, Map<String, JsonObject>>()

    suspend fun supports(config: McpConnectionConfig, refresh: Boolean = false): Boolean = locks.getOrPut(config.connectionUid) { Mutex() }.withLock {
        val identity = dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(
            buildJsonObject {
                put("endpoint", config.endpointUrl)
                put("authorization", config.authorizationHeader)
            }
        )
        val now = System.currentTimeMillis()
        endpoints[config.connectionUid]?.takeIf { endpoint ->
            val ttl = if (endpoint.discovery == null) LEGACY_DECISION_CACHE_MS else DISCOVERY_CACHE_MS
            !refresh && endpoint.identity == identity && now - endpoint.checkedAt < ttl
        }?.let {
            return@withLock it.discovery != null
        }
        endpointDiscoveryCache[identity]?.takeIf { shared ->
            val ttl = if (shared.discovery == null) LEGACY_DECISION_CACHE_MS else DISCOVERY_CACHE_MS
            !refresh && now - shared.checkedAt < ttl
        }?.let { shared ->
            endpoints[config.connectionUid] = shared
            return@withLock shared.discovery != null
        }
        dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.requireAvailable(config.endpointUrl)
        val discovered = try {
            rpc(config, "server/discover", JsonObject(emptyMap())).also {
                check(VERSION in (it["supportedVersions"] as? JsonArray).orEmpty().map { version -> version.jsonPrimitive.content }) { "Server has no mutually supported modern MCP version." }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModernMcpError) {
            if ((error.status in setOf(400, 404, 405) && error.code !in MODERN_ERRORS) || error.status == 200 && error.code == -32601) null else throw error
        } catch (error: Exception) {
            dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordFailure(config.endpointUrl, error)
            // Transport failure says nothing about protocol support. Never open a second
            // legacy connection to a host that did not answer discovery.
            throw error
        }
        if (endpoints.size >= 64) {
            endpoints.keys.firstOrNull()?.let {
                endpoints.remove(it)
                schemas.remove(it)
                locks.remove(it)
            }
        }
        if (endpointDiscoveryCache.size >= 64) endpointDiscoveryCache.keys.firstOrNull()?.let(endpointDiscoveryCache::remove)
        val endpoint = Endpoint(identity, discovered, now)
        endpoints[config.connectionUid] = endpoint
        endpointDiscoveryCache[identity] = endpoint
        discovered != null
    }

    fun close(uid: String) {
        endpoints.remove(uid)
        schemas.remove(uid)
    }
    fun closeAll() {
        endpoints.clear()
        schemas.clear()
    }
    private fun supportsTasks(uid: String) = ((endpoints[uid]?.discovery?.get("capabilities") as? JsonObject)?.get("extensions") as? JsonObject)?.containsKey("io.modelcontextprotocol/tasks") == true
    fun capabilities(uid: String): Set<String> = (endpoints[uid]?.discovery?.get("capabilities") as? JsonObject)?.keys.orEmpty()

    suspend fun listTools(config: McpConnectionConfig): List<Tool> {
        val definitions = pages(config, "tools/list", "tools").mapNotNull { element ->
            val definition = element as? JsonObject ?: return@mapNotNull null
            val schema = definition["inputSchema"] as? JsonObject ?: return@mapNotNull null
            if (runCatching { mirroredHeaders(schema, null) }.isFailure) return@mapNotNull null
            definition
        }
        schemas[config.connectionUid] = definitions.associate { it.getValue("name").jsonPrimitive.content to it.getValue("inputSchema").jsonObject }
        return definitions.map { json.decodeFromJsonElement<Tool>(it) }
    }

    suspend fun callTool(config: McpConnectionConfig, name: String, arguments: JsonObject, callId: String? = null): CallToolResult {
        if (schemas[config.connectionUid]?.containsKey(name) != true) listTools(config)
        val schema = requireNotNull(schemas[config.connectionUid]?.get(name)) { "Tool was removed or has an invalid HTTP header schema. Refresh the connection." }
        val result = request(
            config,
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            mirroredHeaders(schema, arguments)
        )
        if ((result["resultType"] as? JsonPrimitive)?.content == "task") {
            check(tasks != null && supportsTasks(config.connectionUid)) { "Server returned an unadvertised task." }
            var task = requireNotNull(result["task"] as? JsonObject)
            var record = tasks.save(config, task, callId)
            repeat(120) {
                record = refreshTask(config, record)
                task = json.decodeFromString<dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskHandle>(record.payload).task
                when (task["status"]?.jsonPrimitive?.content) {
                    "completed" -> return json.decodeFromJsonElement(requireNotNull(task["result"]))
                    "failed", "cancelled" -> error("Remote task ${task["status"]}. Inspect it in Workspaces; the original action was not replayed.")
                    "input_required" -> error("Remote task is waiting for input. Open Workspaces to continue the saved task.")
                }
                kotlinx.coroutines.delay((task["pollIntervalMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.coerceIn(1000, 300_000) ?: 2000)
            }
            error("Remote task is still running. Its handle is saved in Workspaces.")
        }
        return json.decodeFromJsonElement(result)
    }

    suspend fun refreshTask(config: McpConnectionConfig, record: dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord, cancel: Boolean = false, answerInputs: Boolean = false): dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord = taskLocks[(record.id.hashCode() and Int.MAX_VALUE) % taskLocks.size].withLock {
        val store = requireNotNull(tasks)
        val handle = json.decodeFromString<dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskHandle>((store.latest(record.id) ?: record).payload)
        check(handle.identity == dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskStore.identity(config)) { "Connection credentials or endpoint changed. Task recovery requires its original connection." }
        val id = handle.task.getValue("taskId")
        if (cancel) {
            rpc(config, "tasks/cancel", buildJsonObject { put("taskId", id) })
            return@withLock store.save(config, handle.task, null, handle.answered, true)
        }
        val result = rpc(config, "tasks/get", buildJsonObject { put("taskId", id) })
        check(result["taskId"] == id) { "Mismatched task response." }
        val answered = handle.answered.toMutableSet()
        if (answerInputs && result["status"]?.jsonPrimitive?.content == "input_required") {
            val inputs = (result["inputRequests"] as? JsonObject).orEmpty().filterKeys { it !in answered }
            check(inputs.size <= 8)
            for ((key, input) in inputs) {
                check(input.jsonObject["method"]?.jsonPrimitive?.content == "elicitation/create" && interactions != null) { "Unsupported task input capability." }
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { interactions.request(config.endpointUrl, json.decodeFromJsonElement<ElicitRequest>(input)) }
                // Store answered keys before the network boundary: ambiguous updates are never silently repeated.
                answered += key
                store.save(config, result, null, answered, handle.cancellationRequested)
                rpc(
                    config,
                    "tasks/update",
                    buildJsonObject {
                        put("taskId", id)
                        put("inputResponses", buildJsonObject { put(key, json.encodeToJsonElement(response)) })
                    }
                )
            }
        }
        store.save(config, result, null, answered, handle.cancellationRequested)
    }

    suspend fun browse(config: McpConnectionConfig): McpBrowserData {
        val discovery = endpoints[config.connectionUid]?.discovery
        val capabilities = capabilities(config.connectionUid)
        val resources = if ("resources" in capabilities) pages(config, "resources/list", "resources").map { json.decodeFromJsonElement<io.modelcontextprotocol.kotlin.sdk.types.Resource>(it) } else emptyList()
        val prompts = if ("prompts" in capabilities) pages(config, "prompts/list", "prompts").map { json.decodeFromJsonElement<io.modelcontextprotocol.kotlin.sdk.types.Prompt>(it) } else emptyList()
        val name = ((discovery?.get("_meta") as? JsonObject)?.get("io.modelcontextprotocol/serverInfo") as? JsonObject)?.get("name")?.jsonPrimitive?.content.orEmpty()
        return McpBrowserData(resources, prompts, name, System.currentTimeMillis(), VERSION, capabilities.toList())
    }

    suspend fun readResource(config: McpConnectionConfig, uri: String): String {
        val result = request(config, "resources/read", buildJsonObject { put("uri", uri) })
        return (result["contents"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("\n").take(64000)
    }

    suspend fun getPrompt(config: McpConnectionConfig, name: String, arguments: Map<String, String>): String {
        val result = request(
            config,
            "prompts/get",
            buildJsonObject {
                put("name", name)
                put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(it.value) }))
            }
        )
        return (result["messages"] as? JsonArray).orEmpty().mapNotNull { item ->
            val message = item as? JsonObject ?: return@mapNotNull null
            val text = (message["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: return@mapNotNull null
            "${message["role"]?.jsonPrimitive?.content}: $text"
        }.joinToString("\n\n").take(64000)
    }

    private suspend fun pages(config: McpConnectionConfig, method: String, field: String): List<JsonElement> {
        val values = mutableListOf<JsonElement>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(40) {
            val result = rpc(config, method, buildJsonObject { cursor?.let { put("cursor", it) } })
            values += (result[field] as? JsonArray).orEmpty()
            check(values.size <= 1000) { "MCP catalog exceeds 1,000 entries." }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.content
            if (cursor == null) return values
            check(seen.add(requireNotNull(cursor))) { "MCP catalog repeated a cursor." }
        }
        error("MCP catalog exceeds 40 pages.")
    }

    private suspend fun request(config: McpConnectionConfig, method: String, original: JsonObject, headers: Map<String, String> = emptyMap()): JsonObject {
        var parameters = original
        repeat(8) {
            val result = rpc(config, method, parameters, headers)
            if ((result["resultType"] as? JsonPrimitive)?.content != "input_required") return result
            val inputs = (result["inputRequests"] as? JsonObject).orEmpty()
            check(inputs.size <= 8 && (inputs.isNotEmpty() || result["requestState"] != null)) { "Invalid MCP input request." }
            val responses = inputs.mapValues { (_, input) ->
                val item = input.jsonObject
                check(item["method"]?.jsonPrimitive?.content == "elicitation/create" && interactions != null) { "The server requested a capability this client did not advertise." }
                val answer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { interactions.request(config.endpointUrl, json.decodeFromJsonElement<ElicitRequest>(item)) }
                json.encodeToJsonElement(answer)
            }
            parameters = JsonObject(
                original + buildMap {
                    if (inputs.isNotEmpty()) put("inputResponses", JsonObject(responses))
                    result["requestState"]?.let { put("requestState", it) }
                }
            )
        }
        error("MCP exceeded eight input rounds; the operation was not replayed.")
    }

    private suspend fun rpc(config: McpConnectionConfig, method: String, params: JsonObject, mirrored: Map<String, String> = emptyMap()): JsonObject = withTimeout(60_000) {
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put(
                "params",
                JsonObject(
                    params + (
                        "_meta" to buildJsonObject {
                            put("io.modelcontextprotocol/protocolVersion", VERSION)
                            put(
                                "io.modelcontextprotocol/clientInfo",
                                buildJsonObject {
                                    put("name", "gpt-mobile")
                                    put("version", "0.9.30.0")
                                }
                            )
                            put(
                                "io.modelcontextprotocol/clientCapabilities",
                                buildJsonObject {
                                    if (tasks != null && method == "tools/call" && supportsTasks(config.connectionUid)) put("extensions", buildJsonObject { put("io.modelcontextprotocol/tasks", JsonObject(emptyMap())) })
                                    if (interactions != null) put("elicitation", buildJsonObject { put("form", JsonObject(emptyMap())) })
                                }
                            )
                        }
                        )
                )
            )
        }
        safeHttp.preparePost(config.endpointUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("MCP-Protocol-Version", VERSION)
            header("Mcp-Method", method)
            (params["name"] ?: params["uri"])?.jsonPrimitive?.content?.let { header("Mcp-Name", headerValue(it)) }
            mirrored.forEach { (name, value) -> header(name, value) }
            config.authorizationHeader?.let { header("Authorization", it) }
            timeout {
                val discoveryTimeout = if (isLocalMcpEndpoint(config.endpointUrl)) LOCAL_DISCOVERY_TIMEOUT_MS else REMOTE_DISCOVERY_TIMEOUT_MS
                requestTimeoutMillis = if (method == "server/discover") discoveryTimeout else 60_000
                connectTimeoutMillis = if (method == "server/discover") minOf(discoveryTimeout, 8_000L) else 5_000
                socketTimeoutMillis = if (method == "server/discover") discoveryTimeout else 60_000
            }
            setBody(body.toString())
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val streaming = response.headers["Content-Type"].orEmpty().startsWith("text/event-stream")
            val buffer = StringBuilder()
            var bytes = 0
            var envelope: JsonObject? = null
            while (envelope == null) {
                val line = channel.readUTF8Line(1_048_576)
                if (line == null) {
                    if (buffer.isNotEmpty()) envelope = runCatching { json.parseToJsonElement(buffer.toString()).jsonObject }.getOrNull()
                    break
                }
                bytes += line.toByteArray().size
                check(bytes <= 4_194_304) { "MCP response exceeded 4 MB." }
                if (!streaming) {
                    buffer.append(line)
                    continue
                }
                if (line.isEmpty() && buffer.isNotEmpty()) {
                    val event = json.parseToJsonElement(buffer.toString()).jsonObject
                    buffer.clear()
                    if (event["id"] == JsonPrimitive(id)) {
                        envelope = event
                    } else {
                        check(event["id"] == null) { "Unexpected server request or mismatched MCP response." }
                    }
                } else if (line.startsWith("data:")) {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
            val error = envelope?.get("error") as? JsonObject
            if (response.status.value !in 200..299 || error != null) throw ModernMcpError(response.status.value, (error?.get("code") as? JsonPrimitive)?.intOrNull, "MCP request failed (${response.status.value}, ${error?.get("code") ?: "no protocol error"}).")
            check(envelope?.get("id") == JsonPrimitive(id)) { "MCP response ID did not match the request." }
            requireNotNull(envelope?.get("result") as? JsonObject) { "MCP result is missing." }
        }
    }

    private fun isLocalMcpEndpoint(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4) return false
        val a = parts[0]
        val b = parts[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
    companion object {
        const val VERSION = "2026-07-28"
        private const val DISCOVERY_CACHE_MS = 60 * 60 * 1000L
        private const val LEGACY_DECISION_CACHE_MS = 60_000L
        private const val LOCAL_DISCOVERY_TIMEOUT_MS = 12_000L
        private const val REMOTE_DISCOVERY_TIMEOUT_MS = 5_000L
        private val MODERN_ERRORS = (-32029..-32020).toSet() + -32601
        fun headerValue(value: String): String = if (value != value.trim() || value.any { it.code !in 0x20..0x7e && it != '\t' } || value.startsWith("=?base64?") && value.endsWith("?=")) {
            "=?base64?${Base64.getEncoder().encodeToString(value.encodeToByteArray())}?="
        } else {
            value
        }

        fun mirroredHeaders(schema: JsonObject, arguments: JsonObject?): Map<String, String> {
            val names = mutableSetOf<String>()
            val result = linkedMapOf<String, String>()
            fun walk(node: JsonObject, value: JsonElement?, reachable: Boolean, depth: Int) {
                require(depth <= 16) { "Tool schema is too deeply nested." }
                node["x-mcp-header"]?.let { annotation ->
                    val name = (annotation as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    val type = (node["type"] as? JsonPrimitive)?.content
                    require(reachable && name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && names.add(name.lowercase()) && type in setOf("string", "integer", "boolean")) { "Invalid or duplicate MCP header annotation." }
                    if (value != null && value != kotlinx.serialization.json.JsonNull) {
                        val primitive = value as? JsonPrimitive ?: error("MCP header value must be primitive.")
                        if (type == "string") require(primitive.isString)
                        if (type == "boolean") require(!primitive.isString && primitive.content in setOf("true", "false"))
                        if (type == "integer") require(!primitive.isString && primitive.content.toLongOrNull()?.let { it in -9_007_199_254_740_991L..9_007_199_254_740_991L } == true)
                        result["Mcp-Param-$name"] = headerValue(primitive.content)
                    }
                }
                node.forEach { (key, child) ->
                    if (key == "properties" && child is JsonObject) {
                        child.forEach { (property, definition) ->
                            if (definition is JsonObject) walk(definition, (value as? JsonObject)?.get(property), reachable, depth + 1)
                        }
                    } else if (key != "x-mcp-header") {
                        when (child) {
                            is JsonObject -> walk(child, null, false, depth + 1)
                            is JsonArray -> child.filterIsInstance<JsonObject>().forEach { walk(it, null, false, depth + 1) }
                            else -> Unit
                        }
                    }
                }
            }
            // The root is not a parameter; an annotation directly on it is invalid.
            require(schema["x-mcp-header"] == null)
            walk(schema, arguments, true, 0)
            return result
        }
    }
}

internal class ModernMcpError(val status: Int, val code: Int?, message: String) : IllegalStateException(message)
