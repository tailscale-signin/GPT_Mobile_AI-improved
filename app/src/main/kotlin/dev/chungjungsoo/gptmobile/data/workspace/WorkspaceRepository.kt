package dev.chungjungsoo.gptmobile.data.workspace

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.rag.FactRecall
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class WorkspaceRepository @Inject constructor(val database: ChatDatabaseV2, private val vault: dev.chungjungsoo.gptmobile.data.security.SecretVault) {
    val dao = database.workspaceDao()
    val records = dao.observe()
    val json = Json { ignoreUnknownKeys = true }
    suspend fun exclusions(chatId: Int): ContextExclusions = dao.get("exclusions:$chatId")?.let { json.decodeFromString(it.payload) } ?: ContextExclusions()
    suspend fun exclude(chatId: Int, value: ContextExclusions) = dao.save(WorkspaceRecord("exclusions:$chatId", "exclusions", "Next request exclusions", json.encodeToString(value), chatId))
    suspend fun recordContext(chatId: Int, runId: String, platform: PlatformV2, plan: ContextPlan, turns: List<ConversationTurn>, facts: FactRecall, documents: String, reasoning: Boolean?, delegation: Boolean) {
        if (chatId <= 0) return
        val receipt = ContextReceipt(platform.uid, platform.model, turns.flatMap { listOfNotNull(it.userMessage.id, it.assistantMessage?.id) }, facts.facts.associate { it.id to listOf(it.fact.entity.name, it.fact.relation.relationType, it.fact.target.name).joinToString(" ").take(1000) }, DiagnosticRedactor.redact(documents).take(16000), plan.tools.map { it.name }, turns.flatMap { it.userMessage.attachments + it.assistantMessage?.attachments.orEmpty() }.map { it.filePathForDisplay }.distinct(), digest(platform.systemPrompt.orEmpty()), plan.notice, plan.outputTokens, reasoning, delegation, digest(platform.systemPrompt.orEmpty() + turns.joinToString { it.userMessage.toString() + it.assistantMessage.toString() } + plan.tools.joinToString()))
        val id = UUID.randomUUID().toString()
        val reference = "workspace-memory-$chatId-$id"
        val hasPrivateContext = receipt.facts.isNotEmpty() || receipt.documents.isNotBlank()
        if (hasPrivateContext) {
            val bytes = json.encodeToString(receipt).encodeToByteArray()
            try {
                vault.put(reference, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        val metadata = if (hasPrivateContext) receipt.copy(facts = receipt.facts.mapValues { "Encrypted memory · ${it.key}" }, documents = "Protected document context. Restore the Memory section to recover source excerpts.", protectedReference = reference) else receipt
        dao.save(WorkspaceRecord(id, "context", "${platform.name} · assembled request", json.encodeToString(metadata), chatId, runId))
    }

    /** Request inputs are retained only while Debug mode is enabled, excluding temporary chats. */
    suspend fun recordDebugRequest(
        chatId: Int,
        runId: String,
        kind: String,
        platform: PlatformV2,
        turns: List<ConversationTurn>,
        tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition>,
        constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
    ) {
        if (chatId <= 0 || !dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy.enabled) return
        if (database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return
        val body = kotlinx.serialization.json.buildJsonObject {
            put("runId", kotlinx.serialization.json.JsonPrimitive(runId))
            put("requestKind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("profileUid", kotlinx.serialization.json.JsonPrimitive(platform.uid))
            put("model", kotlinx.serialization.json.JsonPrimitive(platform.model))
            put("system", kotlinx.serialization.json.JsonPrimitive(platform.systemPrompt.orEmpty()))
            put("outputCap", constraints.outputLimit(platform.maxTokens)?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("allowTools", kotlinx.serialization.json.JsonPrimitive(constraints.allowTools))
            put("allowReasoning", kotlinx.serialization.json.JsonPrimitive(constraints.allowReasoning))
            put(
                "turns",
                kotlinx.serialization.json.JsonArray(
                    turns.map { turn ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("user", json.encodeToJsonElement(MessageV2.serializer(), turn.userMessage))
                            put("assistant", turn.assistantMessage?.let { json.encodeToJsonElement(MessageV2.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
                            put("current", kotlinx.serialization.json.JsonPrimitive(turn.isCurrentTurn))
                        }
                    }
                )
            )
            put(
                "tools",
                kotlinx.serialization.json.JsonArray(
                    tools.map { tool ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(tool.name))
                            put("description", kotlinx.serialization.json.JsonPrimitive(tool.description))
                            put("schema", tool.inputSchema)
                        }
                    }
                )
            )
        }
        val safe = dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(body, listOfNotNull(platform.token))
        dao.save(WorkspaceRecord(UUID.randomUUID().toString(), "debug_request", "Assembled request · $kind", safe.toString(), chatId, runId))
    }

    suspend fun synthesisCheckpoint(id: String): kotlinx.serialization.json.JsonObject? =
        dao.get(id)?.payload?.takeIf { it.length <= 1_500_000 }?.let { runCatching { Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }

    suspend fun saveSynthesisCheckpoint(id: String, chatId: Int, runId: String, payload: kotlinx.serialization.json.JsonObject) {
        if (chatId <= 0 || database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return
        require(payload.toString().length <= 1_500_000) { "Synthesis checkpoint exceeds retention limit" }
        dao.save(WorkspaceRecord(id, "synthesis_checkpoint", "Combined writing progress", payload.toString(), chatId, runId))
    }

    /** Each encrypted chunk fits the vault limit. Publish metadata only after all chunks exist. */
    suspend fun saveProtectedSynthesisCheckpoint(id: String, chatId: Int, runId: String, payload: kotlinx.serialization.json.JsonObject) {
        if (chatId <= 0 || database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return
        val bytes = payload.toString().encodeToByteArray()
        require(bytes.size <= 1_500_000) { "Synthesis checkpoint exceeds retention limit" }
        val references = mutableListOf<String>()
        var committed = false
        try {
            for (start in bytes.indices step 60_000) {
                val reference = "workspace-memory-$chatId-${UUID.randomUUID()}"
                references += reference
                val chunk = bytes.copyOfRange(start, minOf(start + 60_000, bytes.size))
                try {
                    vault.put(reference, chunk)
                } finally {
                    chunk.fill(0)
                }
            }
            val metadata = kotlinx.serialization.json.buildJsonObject {
                put("protectedChunks", kotlinx.serialization.json.JsonArray(references.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("digest", kotlinx.serialization.json.JsonPrimitive(digest(payload.toString())))
            }
            val previous = dao.get(id)?.payload?.let { Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }
            committed = database.withTransaction {
                if (database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return@withTransaction false
                dao.save(WorkspaceRecord(id, "synthesis_checkpoint", "Combined inspection progress", metadata.toString(), chatId, runId))
                true
            }
            if (!committed) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    references.forEach { reference -> runCatching { vault.delete(reference) } }
                }
                return
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                (previous?.get("protectedChunks") as? kotlinx.serialization.json.JsonArray).orEmpty().forEach {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { reference ->
                        if (reference.startsWith("workspace-memory-$chatId-")) runCatching { vault.delete(reference) }
                    }
                }
            }
        } catch (error: Exception) {
            // Unpublished chunks are safe to remove; a published record must remain readable.
            if (!committed) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    references.forEach { reference -> runCatching { vault.delete(reference) } }
                }
            }
            throw error
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun protectedSynthesisCheckpoint(id: String): kotlinx.serialization.json.JsonObject? {
        val record = dao.get(id) ?: return null
        val chatId = record.chatId ?: return null
        if (database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).singleOrNull()?.isTemporary != false) return null
        val metadata = Json.parseToJsonElement(record.payload) as? kotlinx.serialization.json.JsonObject ?: return null
        val references = metadata["protectedChunks"] as? kotlinx.serialization.json.JsonArray ?: return null
        if (references.size !in 1..25) return null
        val output = java.io.ByteArrayOutputStream()
        for (item in references) {
            val reference = (item as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            if (!reference.startsWith("workspace-memory-${record.chatId}-")) return null
            val bytes = vault.read(reference) ?: return null
            try {
                if (output.size() + bytes.size > 1_500_000) return null
                output.write(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        val bytes = output.toByteArray()
        return try {
            val text = bytes.decodeToString()
            if ((metadata["digest"] as? kotlinx.serialization.json.JsonPrimitive)?.content != digest(text)) {
                null
            } else {
                Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun readReceipt(entry: WorkspaceRecord): ContextReceipt {
        val metadata = json.decodeFromString<ContextReceipt>(entry.payload)
        val bytes = metadata.protectedReference?.let { vault.read(it) } ?: return metadata
        return try {
            json.decodeFromString(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }
    }
    suspend fun pin(chatId: Int, pin: ResearchPin) {
        require(pin.url.startsWith("https://") || pin.url.startsWith("http://"))
        require(pin.excerpt.length <= 16000 && pin.claim.length <= 2000)
        dao.save(WorkspaceRecord(UUID.randomUUID().toString(), "evidence", java.net.URI(pin.url).host.orEmpty(), json.encodeToString(pin), chatId))
    }
    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
