package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef
import dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class RunPolicySnapshotException(val code: String) :
    IllegalStateException(
        "$code: The saved request policy is missing or invalid. Restore its complete backup or start a new request; the saved answer is unchanged."
    )

/**
 * Normalizes only recognized immutable policy nodes, never user text or current settings.
 * Literal chunks preserve whitespace, escape spelling and key order: the hydrated snapshot
 * must have exactly its original SHA, including the existing synthesisKey seal.
 */
object RunPolicySnapshotCodec {
    private const val SCHEMA = "gptmobile.run-policy-ref.v1"
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Chunk(val literal: String? = null, val ref: String? = null)

    @Serializable
    private data class Payload(val schemaVersion: Int = 1, val chunks: List<Chunk>)

    @Serializable
    private data class Reference(
        val snapshotRefSchema: String = SCHEMA,
        val runId: String,
        val chatId: Int,
        val userMessageId: Int,
        val originalDigest: String,
        val policyRevision: String,
        val taskDigest: String,
        val authorityDigest: String,
        val chunks: List<Chunk>
    )

    data class Normalized(val snapshot: String, val policies: List<RunPolicySnapshot>)
    private data class Identity(val revision: String, val task: String, val authority: String)
    private data class Span(val start: Int, val end: Int, val kind: String, val identity: Identity)
    private data class Cached(val run: AgentRun, val rows: List<RunPolicySnapshot>, val links: Set<String>, val expanded: String)
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private var cacheCharacters = 0

    fun forgetChat(chatId: Int) = synchronized(cache) {
        val stale = cache.filterValues { it.run.chatId == chatId }.keys.toList()
        stale.forEach { key -> cache.remove(key)?.let { cacheCharacters -= it.expanded.length } }
        dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.forgetDecodedChat(chatId)
    }

    fun isReference(raw: String?): Boolean = raw?.let {
        "\"snapshotRefSchema\"" in it &&
            runCatching {
                (json.parseToJsonElement(it) as? JsonObject)?.containsKey("snapshotRefSchema") == true
            }.getOrDefault(it.trimStart().startsWith("{\"snapshotRefSchema\""))
    } ?: false

    fun normalize(run: AgentRun): Normalized? {
        val raw = run.synthesisSnapshot ?: return null
        if (isReference(raw)) throw RunPolicySnapshotException("POLICY_SNAPSHOT_UNRESOLVED_IMPORT")
        if (raw.length > MAX_BYTES) return null
        // Unknown/legacy records stay inline. A migration must never repair or reinterpret them.
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val identity = primaryIdentity(root) ?: return null
        if (run.synthesisKey != null && run.synthesisKey != researchHash(raw)) return null
        val spans = runCatching { Scanner(raw).scan() }.getOrNull() ?: return null
        if (spans.isEmpty() || spans.any { it.identity != identity }) return null
        val rows = linkedMapOf<String, RunPolicySnapshot>()
        fun chunks(start: Int, end: Int): List<Chunk> {
            val result = mutableListOf<Chunk>()
            var position = start
            for (span in spans.filter { it.start >= start && it.end <= end && (it.start != start || it.end != end) }.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })) {
                if (span.start < position) continue
                if (span.start > position) result += Chunk(literal = raw.substring(position, span.start))
                val digest = researchHash(raw.substring(span.start, span.end))
                val payload = if (span.kind == "REQUESTED_POLICY") listOf(Chunk(literal = raw.substring(span.start, span.end))) else chunks(span.start, span.end)
                rows.putIfAbsent(digest, RunPolicySnapshot(run.chatId, digest, 1, span.kind, identity.revision, identity.task, identity.authority, json.encodeToString(Payload(chunks = payload))))
                result += Chunk(ref = digest)
                position = span.end
            }
            if (position < end) result += Chunk(literal = raw.substring(position, end))
            return result
        }
        val body = chunks(0, raw.length)
        if (rows.isEmpty()) return null
        return Normalized(
            json.encodeToString(Reference(runId = run.runId, chatId = run.chatId, userMessageId = run.userMessageId, originalDigest = researchHash(raw), policyRevision = identity.revision, taskDigest = identity.task, authorityDigest = identity.authority, chunks = body)),
            rows.values.toList()
        )
    }

    /** The caller must fetch current database rows and links before every call, including cache hits. */
    fun hydrate(run: AgentRun, rows: List<RunPolicySnapshot>, refs: List<AgentRunPolicyRef>): AgentRun {
        val linked = refs.filter { it.runId == run.runId && it.chatId == run.chatId }.map { it.digest }.toSet()
        val raw = run.synthesisSnapshot
        if (!isReference(raw)) {
            if (linked.isNotEmpty()) throw RunPolicySnapshotException("POLICY_SNAPSHOT_INVALID")
            return run
        }
        try {
            requireNotNull(raw)
            val owned = rows.filter { it.chatId == run.chatId && it.digest in linked }.sortedBy { it.digest }
            if (owned.size != linked.size || linked.isEmpty()) throw RunPolicySnapshotException("POLICY_SNAPSHOT_MISSING")
            // Status/usage changes need not reparse immutable policy bytes. Ownership and the seal do.
            val cacheKey = "${run.chatId}:${run.runId}:${researchHash(raw)}:${run.synthesisKey.orEmpty()}"
            synchronized(cache) {
                cache[cacheKey]?.takeIf { it.run.userMessageId == run.userMessageId && it.rows == owned && it.links == linked }
                    ?.let { return run.copy(synthesisSnapshot = it.expanded) }
            }
            val reference = json.decodeFromString<Reference>(raw)
            require(reference.snapshotRefSchema == SCHEMA && reference.runId == run.runId && reference.chatId == run.chatId && reference.userMessageId == run.userMessageId)
            val identity = Identity(reference.policyRevision, reference.taskDigest, reference.authorityDigest)
            val byDigest = owned.associateBy { it.digest }
            val expanded = mutableMapOf<String, String>()
            val resolving = mutableSetOf<String>()
            val used = mutableSetOf<String>()
            fun expand(parts: List<Chunk>, depth: Int): String {
                require(depth <= 8 && parts.size <= 1024)
                val output = StringBuilder()
                for (chunk in parts) {
                    require((chunk.literal != null) xor (chunk.ref != null))
                    if (chunk.literal != null) {
                        output.append(chunk.literal)
                    } else {
                        val digest = requireNotNull(chunk.ref)
                        used += digest
                        val row = byDigest[digest] ?: throw RunPolicySnapshotException("POLICY_SNAPSHOT_MISSING")
                        require(row.schemaVersion == 1 && row.policyRevision == identity.revision && row.taskDigest == identity.task && row.authorityDigest == identity.authority)
                        val content = expanded[digest] ?: run {
                            require(resolving.add(digest))
                            val payload = json.decodeFromString<Payload>(row.payload)
                            require(payload.schemaVersion == 1)
                            val value = expand(payload.chunks, depth + 1)
                            require(researchHash(value) == digest)
                            val obj = json.parseToJsonElement(value) as? JsonObject ?: error("Policy object required")
                            require(fragmentIdentity(obj, row.kind) == identity)
                            resolving.remove(digest)
                            expanded[digest] = value
                            value
                        }
                        output.append(content)
                    }
                    require(output.length <= MAX_BYTES)
                }
                return output.toString()
            }
            val value = expand(reference.chunks, 0)
            require(used == linked && researchHash(value) == reference.originalDigest)
            require(run.synthesisKey == null || run.synthesisKey == reference.originalDigest)
            require(primaryIdentity(json.parseToJsonElement(value) as JsonObject) == identity)
            synchronized(cache) {
                if (value.length <= 1_000_000) {
                    cache.remove(cacheKey)?.let { cacheCharacters -= it.expanded.length }
                    cache[cacheKey] = Cached(run, owned, linked, value)
                    cacheCharacters += value.length
                    while (cache.size > 24 || cacheCharacters > 4_000_000) {
                        val key = cache.keys.first()
                        cacheCharacters -= requireNotNull(cache.remove(key)).expanded.length
                    }
                }
            }
            return run.copy(synthesisSnapshot = value)
        } catch (error: RunPolicySnapshotException) {
            throw error
        } catch (_: Exception) {
            throw RunPolicySnapshotException("POLICY_SNAPSHOT_INVALID")
        }
    }

    fun blockedProjection(run: AgentRun, error: RunPolicySnapshotException): AgentRun = run.copy(
        status = if (run.status in setOf("QUEUED", "RUNNING", "STOPPING")) "FAILED" else run.status,
        terminalError = error.message,
        synthesisSnapshot = null
    )

    private fun primaryIdentity(root: JsonObject): Identity? {
        val context = (root["context"] ?: root["combined"]) as? JsonObject ?: return null
        return (context["requested"] as? JsonObject)?.let(::requestedIdentity)
    }

    private fun fragmentIdentity(value: JsonObject, kind: String): Identity? = when (kind) {
        "REQUESTED_POLICY" -> requestedIdentity(value)
        "WORK_PLAN" -> if ((value["version"] as? JsonPrimitive)?.intOrNull in listOf(null, 1) && value["baselineByOwner"] is JsonObject && (value["effectivePlanDigest"] as? JsonPrimitive)?.content?.isNotBlank() == true) {
            (value["requested"] as? JsonObject)?.let(::requestedIdentity)
        } else {
            null
        }
        else -> null
    }

    private fun requestedIdentity(requested: JsonObject): Identity? {
        if ((requested["version"] as? JsonPrimitive)?.intOrNull !in listOf(null, 1)) return null
        val revision = (requested["requestedPolicyDigest"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val task = (requested["userPrompt"] as? JsonPrimitive)?.content ?: return null
        if (requested["participantUids"] !is JsonArray || requested["requestedModels"] !is JsonObject) return null
        // This hashes the captured authority, not today's settings or an execution permission.
        val authority = JsonObject(requested.filterKeys { it !in setOf("userPrompt", "acceptedAtMillis", "requestedPolicyDigest") })
        return Identity(revision, researchHash(task), researchHash(canonical(authority).toString()))
    }

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    /** A lexical walk records exact source ranges after Json has validated the document. */
    private class Scanner(private val source: String) {
        private var position = 0
        private val spans = mutableListOf<Span>()
        fun scan(): List<Span> {
            value(emptyList(), 0)
            whitespace()
            require(position == source.length)
            return spans
        }
        private fun whitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }
        private fun string(): String {
            val start = position++
            while (position < source.length) {
                when (source[position++]) {
                    '\\' -> position++
                    '"' -> return json.decodeFromString(source.substring(start, position))
                }
            }
            error("Unterminated string")
        }
        private fun value(path: List<String>, depth: Int) {
            require(depth < 128)
            whitespace()
            val start = position
            when (source[position]) {
                '{' -> {
                    position++
                    whitespace()
                    if (source[position] != '}') {
                        while (true) {
                            val key = string()
                            whitespace()
                            require(source[position++] == ':')
                            value(path + key, depth + 1)
                            whitespace()
                            if (source[position] != ',') break
                            position++
                            whitespace()
                        }
                    }
                    require(source[position++] == '}')
                    val contextPath = path.take(1) in listOf(listOf("context"), listOf("combined")) || path.take(2) in listOf(listOf("tools", "combined"), listOf("acceptedTools", "combined"))
                    val kind = when (path.lastOrNull()) {
                        "requested" -> "REQUESTED_POLICY"
                        "plan" -> "WORK_PLAN"
                        else -> null
                    }
                    if (contextPath && kind != null) {
                        val obj = json.parseToJsonElement(source.substring(start, position)) as JsonObject
                        fragmentIdentity(obj, kind)?.let { spans += Span(start, position, kind, it) }
                    }
                }
                '[' -> {
                    position++
                    whitespace()
                    if (source[position] != ']') {
                        while (true) {
                            value(path + "[]", depth + 1)
                            whitespace()
                            if (source[position] != ',') break
                            position++
                        }
                    }
                    require(source[position++] == ']')
                }
                '"' -> string()
                else -> while (position < source.length && source[position] !in charArrayOf(',', '}', ']') && !source[position].isWhitespace()) position++
            }
        }
    }
}
