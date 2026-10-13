package dev.chungjungsoo.gptmobile.data.memory.v2

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeEntity
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeFact
import dev.chungjungsoo.gptmobile.data.rag.KnowledgeRelation
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room is the sole authority after verified cutover. Index work never participates in a save. */
@Singleton
class MemoryRepository(
    private val database: ChatDatabaseV2,
    private val cipher: MemoryCipher
) {
    @Inject constructor(database: ChatDatabaseV2, cipher: AndroidMemoryCipher) : this(database, cipher as MemoryCipher)

    @kotlinx.serialization.Serializable
    private data class PortableRecord(val id: String, val kind: String, val generation: Long, val scope: String, val content: String)

    @kotlinx.serialization.Serializable
    private data class PortableArchive(val snapshot: FactVaultSnapshot, val records: List<PortableRecord> = emptyList())

    private val dao get() = database.memoryV2Dao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(): FactVaultSnapshot? = database.withTransaction { readLocked() }

    private suspend fun readLocked(): FactVaultSnapshot? {
        val state = dao.state() ?: return null
        check(state.cutoverStatus == "ACTIVE") { "Memory migration needs recovery; original data is preserved." }
        val metadata = java.io.ByteArrayOutputStream().apply {
            dao.records("METADATA").sortedBy { it.id }.forEach { record ->
                val part = decrypt(record)
                try {
                    write(part)
                } finally {
                    part.fill(0)
                }
            }
        }.toByteArray()
        try {
            check(state.formatVersion == 2 && digest(metadata).contentEquals(state.importDigest)) { "Memory integrity check failed; encrypted data is preserved." }
            val base = json.decodeFromString<FactVaultSnapshot>(metadata.decodeToString())
            val facts = dao.allFacts().map { row ->
                val bytes = cipher.decrypt(row.payload, MemoryCipherContext("fact", row.id, row.revision, row.scope))
                try {
                    json.decodeFromString<VaultFact>(bytes.decodeToString())
                } finally {
                    bytes.fill(0)
                }
            }
            check(facts.size == state.importedCount) { "Memory record count changed unexpectedly." }
            return base.copy(facts = facts.sortedBy { it.id })
        } finally {
            metadata.fill(0)
        }
    }

    /** Both import and the authority marker commit together; any parity failure rolls back. */
    suspend fun importLegacy(snapshot: FactVaultSnapshot): FactVaultSnapshot = database.withTransaction {
        readLocked()?.let { return@withTransaction it }
        val graph = database.memoryGraphDao()
        // Older versions allowed standalone graph entities with no vault fact. Preserve
        // their original identity, name, type and provenance before removing plaintext indexes.
        val standalone = graph.allEntities().filter { it.standalone }.map { row ->
            val entity = KnowledgeEntity(row.id, row.name, row.entityType, mapOf("legacyNormalizedName" to row.normalizedName))
            val target = KnowledgeEntity("type:${row.entityType}", row.entityType, "ENTITY_TYPE")
            VaultFact(
                id = "legacy-graph:${row.id}",
                fact = KnowledgeFact(entity, KnowledgeRelation(entity.id, "IS_A", target.id), target),
                source = "manual", sourceChatId = row.sourceChatId, sourceMessageId = row.sourceMessageId,
                savedAtMillis = row.createdAt, lastSeenMillis = row.updatedAt, scope = row.scope, memoryType = "NOTE"
            )
        }.filterNot { it.id in snapshot.suppressedIds || it.sourceChatId in snapshot.suppressedChats }
        val combined = snapshot.copy(facts = (snapshot.facts + standalone).distinctBy { it.id })
        writeLocked(combined, null)
        val imported = requireNotNull(readLocked())
        check(canonical(imported) == canonical(combined)) { "Memory migration parity failed; original data is preserved." }
        graph.clearRelations()
        graph.clearObservations()
        graph.clearEntities()
        val sql = database.openHelper.writableDatabase
        val hasIndex = sql.query("SELECT name FROM sqlite_master WHERE name='memory_graph_fts'").use { it.moveToFirst() }
        if (hasIndex) sql.execSQL("DELETE FROM memory_graph_fts")
        imported
    }

    suspend fun commit(snapshot: FactVaultSnapshot, expected: FactVaultSnapshot, discardHistory: Boolean = false): FactVaultSnapshot = database.withTransaction {
        val current = requireNotNull(readLocked())
        if (canonical(current) == canonical(snapshot) && !discardHistory) return@withTransaction current
        check(canonical(current) == canonical(expected)) { "This memory changed. Review the latest version." }
        if (discardHistory) {
            dao.deleteRecords("REVISION")
            dao.deleteRecords("TOMBSTONE")
        }
        writeLocked(snapshot, current)
        snapshot
    }

    private suspend fun writeLocked(snapshot: FactVaultSnapshot, previous: FactVaultSnapshot?) {
        require(snapshot.version == 1 && snapshot.facts.map { it.id }.distinct().size == snapshot.facts.size)
        val generation = (dao.state()?.generation ?: 0) + 1
        val old = previous?.facts.orEmpty().associateBy { it.id }
        val incoming = snapshot.facts.associateBy { it.id }
        old.values.filter { it.id !in incoming }.forEach { fact ->
            dao.deleteFact(fact.id)
            record("TOMBSTONE", fact.id, generation, fact.scope, fact.id.encodeToByteArray())
        }
        snapshot.facts.forEach { fact ->
            if (old[fact.id] != fact) {
                val bytes = json.encodeToString(fact).encodeToByteArray()
                try {
                    val state = when {
                        fact.awaitingReview -> "PENDING_REVIEW"
                        fact.supersededBy != null -> "SUPERSEDED"
                        !fact.enabled -> "ARCHIVED"
                        else -> "ACTIVE"
                    }
                    dao.upsertFact(
                        MemoryFactEntity(
                            fact.id, fact.revision.toLong(), digest(fact.id.encodeToByteArray()), fact.scope,
                            fact.enabled, state, fact.confidence.toDouble(), fact.pinned, fact.occurrences,
                            fact.lastSeenMillis, fact.savedAtMillis, fact.validFromMillis, fact.validUntilMillis,
                            cipher.encrypt(bytes, MemoryCipherContext("fact", fact.id, fact.revision.toLong(), fact.scope))
                        )
                    )
                    // Complete revision payloads include provenance and source identities, not only old text.
                    record("REVISION", "${fact.id}:$generation", generation, fact.scope, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }
        val metadata = json.encodeToString(snapshot.copy(facts = emptyList())).encodeToByteArray()
        try {
            require(metadata.size <= 24 * 1024 * 1024) { "Memory metadata capacity reached." }
            dao.deleteRecords("METADATA")
            metadata.asList().chunked(48 * 1024).forEachIndexed { index, part ->
                record("METADATA", index.toString().padStart(6, '0'), generation, "store", part.toByteArray())
            }
            dao.upsertState(MemoryStoreStateEntity(1, 2, generation, "ACTIVE", byteArrayOf(), digest(metadata), snapshot.facts.size))
            record("OUTBOX", "indexes", generation, "store", byteArrayOf())
        } finally {
            metadata.fill(0)
        }
    }

    private suspend fun record(kind: String, id: String, generation: Long, scope: String, bytes: ByteArray) {
        dao.upsertRecord(MemoryRecordEntity("$kind:$id", kind, generation, scope, cipher.encrypt(bytes, MemoryCipherContext(kind, "$kind:$id", generation, scope))))
    }

    private fun decrypt(row: MemoryRecordEntity) = cipher.decrypt(row.payload, MemoryCipherContext(row.kind, row.id, row.generation, row.scope))

    suspend fun acknowledgeIndexes(generation: Long) = dao.acknowledgeIndexes(generation)
    suspend fun generation(): Long = dao.state()?.generation ?: 0

    /** Portable plaintext exists only inside the password-protected backup's secret envelope. */
    suspend fun exportPortable(): Map<String, String> = database.withTransaction {
        val snapshot = readLocked() ?: return@withTransaction emptyMap()
        val records = (dao.records("REVISION") + dao.records("TOMBSTONE")).map { row ->
            val bytes = decrypt(row)
            try {
                PortableRecord(row.id, row.kind, row.generation, row.scope, java.util.Base64.getEncoder().encodeToString(bytes))
            } finally {
                bytes.fill(0)
            }
        }
        val bytes = json.encodeToString(PortableArchive(snapshot, records)).encodeToByteArray()
        try {
            require(bytes.size <= MAX_ARCHIVE_BYTES) { "Memory backup exceeds the supported archive size." }
            (bytes.indices step 48 * 1024).mapIndexed { index, offset ->
                val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + 48 * 1024))
                try {
                    "memory-portable-${index.toString().padStart(6, '0')}" to java.util.Base64.getEncoder().encodeToString(part)
                } finally {
                    part.fill(0)
                }
            }.toMap()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun restorePortable(values: Map<String, String>, protected: FactVaultSnapshot?) {
        val parts = values.filterKeys { it.startsWith("memory-portable-") }.toSortedMap()
        if (parts.isEmpty() && "fact-vault-v1" !in values) return
        require(parts.keys.withIndex().all { (index, key) -> key == "memory-portable-${index.toString().padStart(6, '0')}" }) { "Memory backup is incomplete." }
        val bytes = if (parts.isNotEmpty()) {
            decodeParts(parts.values)
        } else {
            val legacy = java.util.Base64.getDecoder().decode(values.getValue("fact-vault-v1"))
            val root = json.parseToJsonElement(legacy.decodeToString()) as kotlinx.serialization.json.JsonObject
            if ("parts" !in root) {
                legacy
            } else {
                val refs = root.getValue("parts") as kotlinx.serialization.json.JsonArray
                legacy.fill(0)
                decodeParts(
                    refs.map { ref ->
                        val id = (ref as kotlinx.serialization.json.JsonPrimitive).content
                        require(id.startsWith("memory-part-"))
                        requireNotNull(values[id]) { "Memory backup is incomplete." }
                    }
                )
            }
        }
        try {
            require(bytes.size <= MAX_ARCHIVE_BYTES)
            val root = json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject
            val archive = if ("snapshot" in root) json.decodeFromString<PortableArchive>(bytes.decodeToString()) else PortableArchive(json.decodeFromString(bytes.decodeToString()))
            val incoming = archive.snapshot
            val suppressed = incoming.suppressedIds + protected?.suppressedIds.orEmpty()
            val evidence = incoming.suppressedEvidence + protected?.suppressedEvidence.orEmpty()
            val messages = incoming.suppressedMessages + protected?.suppressedMessages.orEmpty()
            val restored = incoming.copy(
                suppressedIds = suppressed,
                suppressedEvidence = evidence,
                suppressedMessages = messages,
                suppressedChats = incoming.suppressedChats + protected?.suppressedChats.orEmpty(),
                facts = incoming.facts.filterNot { it.sourceChatId in protected?.suppressedChats.orEmpty() || it.id in suppressed || "${it.scope}:${it.evidenceHash}" in evidence || "${it.scope}:${it.sourceChatId}:${it.sourceMessageId}" in messages }
            )
            database.withTransaction {
                dao.clearFacts()
                dao.clearRecords()
                writeLocked(restored, null)
                archive.records.filter { it.kind in setOf("REVISION", "TOMBSTONE") }.forEach { row ->
                    require(row.id.startsWith("${row.kind}:"))
                    val content = java.util.Base64.getDecoder().decode(row.content)
                    try {
                        dao.upsertRecord(MemoryRecordEntity(row.id, row.kind, row.generation, row.scope, cipher.encrypt(content, MemoryCipherContext(row.kind, row.id, row.generation, row.scope))))
                    } finally {
                        content.fill(0)
                    }
                }
                check(canonical(requireNotNull(readLocked())) == canonical(restored))
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeParts(parts: Collection<String>): ByteArray {
        require(parts.size <= 512) { "Memory backup exceeds the supported archive size." }
        return java.io.ByteArrayOutputStream().apply {
            parts.forEach { encoded ->
                require(encoded.length <= 96 * 1024) { "Memory backup part is oversized." }
                val part = java.util.Base64.getDecoder().decode(encoded)
                try {
                    require(size() + part.size <= MAX_ARCHIVE_BYTES) { "Memory backup exceeds the supported archive size." }
                    write(part)
                } finally {
                    part.fill(0)
                }
            }
        }.toByteArray()
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 24 * 1024 * 1024
    }

    private fun canonical(snapshot: FactVaultSnapshot) = snapshot.copy(facts = snapshot.facts.sortedBy { it.id })
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
