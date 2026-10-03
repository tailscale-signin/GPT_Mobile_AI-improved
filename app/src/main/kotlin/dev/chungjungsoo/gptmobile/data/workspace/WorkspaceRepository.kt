package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
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
