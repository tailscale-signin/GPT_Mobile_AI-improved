package dev.chungjungsoo.gptmobile.data.memory.v2

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
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
        writeLocked(snapshot, null)
        val imported = requireNotNull(readLocked())
        check(canonical(imported) == canonical(snapshot)) { "Memory migration parity failed; original data is preserved." }
        imported
    }

    suspend fun commit(snapshot: FactVaultSnapshot, expected: FactVaultSnapshot): FactVaultSnapshot = database.withTransaction {
        val current = requireNotNull(readLocked())
        check(canonical(current) == canonical(expected)) { "This memory changed. Review the latest version." }
        if (canonical(current) == canonical(snapshot)) return@withTransaction current
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
            bytes.asList().chunked(48 * 1024).mapIndexed { index, part ->
                "memory-portable-${index.toString().padStart(6, '0')}" to java.util.Base64.getEncoder().encodeToString(part.toByteArray())
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
            parts.values.flatMap { java.util.Base64.getDecoder().decode(it).toList() }.toByteArray()
        } else {
            val legacy = java.util.Base64.getDecoder().decode(values.getValue("fact-vault-v1"))
            val root = json.parseToJsonElement(legacy.decodeToString()) as kotlinx.serialization.json.JsonObject
            if ("parts" !in root) {
                legacy
            } else {
                val refs = root.getValue("parts") as kotlinx.serialization.json.JsonArray
                legacy.fill(0)
                refs.flatMap { ref ->
                    val id = (ref as kotlinx.serialization.json.JsonPrimitive).content
                    require(id.startsWith("memory-part-"))
                    java.util.Base64.getDecoder().decode(requireNotNull(values[id]) { "Memory backup is incomplete." }).toList()
                }.toByteArray()
            }
        }
        try {
            require(bytes.size <= 24 * 1024 * 1024)
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
                facts = incoming.facts.filterNot { it.id in suppressed || "${it.scope}:${it.evidenceHash}" in evidence || "${it.scope}:${it.sourceChatId}:${it.sourceMessageId}" in messages }
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

    private fun canonical(snapshot: FactVaultSnapshot) = snapshot.copy(facts = snapshot.facts.sortedBy { it.id })
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
