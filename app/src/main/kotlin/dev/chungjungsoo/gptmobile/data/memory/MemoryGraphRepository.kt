package dev.chungjungsoo.gptmobile.data.memory

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class MemoryGraphRepository @Inject constructor(
    private val database: ChatDatabaseV2
) {
    private val dao get() = database.memoryGraphDao()
    private val mutex = Mutex()
    private var ftsAvailable: Boolean? = null
    private var fts4Index = false
    private var ftsNeedsRebuild = false
    private var lastVaultFingerprint: String? = null

    suspend fun createEntities(
        entities: List<MemoryGraphEntityInput>,
        chatId: Int,
        messageId: Int,
        scope: String = "personal"
    ): List<MemoryGraphEntityRecord> = mutex.withLock {
        require(scope == "personal" || scope.startsWith("project:") || scope.startsWith("personal:branch:"))
        val now = System.currentTimeMillis()
        val rows = entities.filter { it.name.isNotBlank() }.take(32).map { input ->
            val normalized = normalize(input.name)
            require(normalized.length in 1..120) { "Entity names must be 1–120 characters." }
            val existing = dao.entityByName(scope, normalized)
            MemoryGraphEntityRecord(
                id = existing?.id ?: stableId("entity", scope, normalized),
                name = input.name.trim().take(120),
                normalizedName = normalized,
                entityType = normalizeType(input.entityType),
                scope = scope,
                standalone = true,
                sourceChatId = existing?.sourceChatId?.takeIf { it != 0 } ?: chatId,
                sourceMessageId = existing?.sourceMessageId?.takeIf { it != 0 } ?: messageId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        }.distinctBy { it.id }
        if (rows.isNotEmpty()) {
            dao.upsertEntities(rows)
            rebuildFtsLocked(rows.map { it.id }.toSet())
        }
        rows
    }

    suspend fun findEntity(name: String, scope: String = "personal"): MemoryGraphEntityRecord? =
        mutex.withLock { dao.entityByName(scope, normalize(name)) }

    suspend fun releaseChatEntities(chatId: Int) = mutex.withLock {
        dao.releaseChatEntities(chatId)
        lastVaultFingerprint = null
    }

    suspend fun releaseScopeEntities(scope: String) = mutex.withLock {
        dao.releaseScopeEntities(scope, "$scope:branch:%")
        lastVaultFingerprint = null
    }

    suspend fun replaceFromVault(facts: List<VaultFact>) = mutex.withLock {
        val fingerprint = vaultFingerprint(facts)
        if (fingerprint == lastVaultFingerprint) return@withLock
        val existingEntities = dao.allEntities().associateBy { it.id }
        val now = System.currentTimeMillis()
        val entityRows = linkedMapOf<String, MemoryGraphEntityRecord>()
        val observations = mutableListOf<MemoryGraphObservationRecord>()
        val relations = mutableListOf<MemoryGraphRelationRecord>()

        facts.forEach { entry ->
            val scope = entry.scope
            val source = entityRecord(entry.fact.entity.name, entry.fact.entity.type, scope, entry.sourceChatId, entry.sourceMessageId, now, existingEntities)
            val target = entityRecord(entry.fact.target.name, entry.fact.target.type, scope, entry.sourceChatId, entry.sourceMessageId, now, existingEntities)
            entityRows[source.id] = mergeEntity(entityRows[source.id], source)
            entityRows[target.id] = mergeEntity(entityRows[target.id], target)
            relations += MemoryGraphRelationRecord(
                id = stableId("relation", scope, entry.id),
                fromEntityId = source.id,
                toEntityId = target.id,
                relationType = normalizeType(entry.fact.relation.relationType),
                scope = scope,
                sourceKind = SOURCE_VAULT,
                sourceChatId = entry.sourceChatId,
                sourceMessageId = entry.sourceMessageId,
                updatedAt = entry.lastSeenMillis
            )
            if (
                entry.fact.target.type.equals("OBSERVATION", true) ||
                entry.fact.target.type.equals("PREFERENCE", true) ||
                entry.fact.relation.relationType in setOf("REMEMBERS", "OBSERVATION", "PREFERS", "AVOIDS", "RESPONSE_LANGUAGE")
            ) {
                observations += MemoryGraphObservationRecord(
                    id = stableId("observation", scope, entry.id),
                    entityId = source.id,
                    observation = entry.fact.target.name.take(MAX_OBSERVATION_CHARS),
                    normalizedObservation = normalize(entry.fact.target.name).take(MAX_OBSERVATION_CHARS),
                    scope = scope,
                    sourceKind = SOURCE_VAULT,
                    sourceChatId = entry.sourceChatId,
                    sourceMessageId = entry.sourceMessageId,
                    updatedAt = entry.lastSeenMillis
                )
            }
        }

        val persisted = entityRows.values.map { row ->
            val existing = existingEntities[row.id]
            if (existing == null) {
                row
            } else {
                row.copy(
                    updatedAt = existing.updatedAt,
                    standalone = existing.standalone,
                    createdAt = existing.createdAt,
                    sourceChatId = existing.sourceChatId.takeIf { it != 0 } ?: row.sourceChatId,
                    sourceMessageId = existing.sourceMessageId.takeIf { it != 0 } ?: row.sourceMessageId,
                    entityType = existing.entityType.takeIf { existing.standalone && it != "ENTITY" } ?: row.entityType
                )
            }
        }
        val oldObservations = dao.observationsBySource(SOURCE_VAULT).associateBy { it.id }
        val oldRelations = dao.relationsBySource(SOURCE_VAULT).associateBy { it.id }
        val newObservationIds = observations.map { it.id }.toSet()
        val newRelationIds = relations.map { it.id }.toSet()
        val removedObservations = oldObservations.values.filter { it.id !in newObservationIds }
        val changedEntities = persisted.filter { it != existingEntities[it.id] }
        val changedObservations = observations.filter { it != oldObservations[it.id] }
        val changedRelations = relations.filter { it != oldRelations[it.id] }
        database.withTransaction {
            removedObservations.map { it.id }.chunked(400).forEach { dao.deleteObservationIds(it) }
            (oldRelations.keys - newRelationIds).chunked(400).forEach { dao.deleteRelationIds(it) }
            if (changedEntities.isNotEmpty()) dao.upsertEntities(changedEntities)
            if (changedObservations.isNotEmpty()) dao.upsertObservations(changedObservations)
            if (changedRelations.isNotEmpty()) dao.upsertRelations(changedRelations)
            dao.pruneUnreferencedEntities()
        }
        val removedEntities = existingEntities.keys - dao.allEntities().map { it.id }.toSet()
        rebuildFtsLocked((changedEntities.map { it.id } + changedObservations.map { it.entityId } + removedObservations.map { it.entityId } + removedEntities).toSet())
        lastVaultFingerprint = fingerprint
    }

    suspend fun searchNodes(query: String, chatId: Int?, scope: String = "personal", limit: Int = 20): List<MemoryGraphNode> =
        mutex.withLock {
            val bounded = limit.coerceIn(1, 64)
            val ids = searchFtsLocked(query, scope, bounded)
            val rows = if (ids.isNotEmpty()) {
                val byId = dao.entitiesByIds(ids).associateBy { it.id }
                ids.mapNotNull(byId::get)
            } else {
                dao.searchLike(scope, "%${normalize(query)}%", bounded)
            }
            loadNodesLocked(rows.filter { visible(it, chatId) }.take(bounded), chatId)
        }

    suspend fun openNodes(names: List<String>, chatId: Int?, scope: String = "personal"): List<MemoryGraphNode> =
        mutex.withLock {
            val rows = names.take(32).mapNotNull { dao.entityByName(scope, normalize(it)) }
                .filter { visible(it, chatId) }
                .distinctBy { it.id }
            loadNodesLocked(rows, chatId)
        }

    suspend fun readGraph(
        chatId: Int?,
        scope: String = "personal",
        offset: Int = 0,
        limit: Int = 32
    ): Pair<Int, List<MemoryGraphNode>> = mutex.withLock {
        val visibleRows = dao.entities(scope).filter { visible(it, chatId) }
        val page = visibleRows.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 64))
        visibleRows.size to loadNodesLocked(page, chatId)
    }

    suspend fun clear() = mutex.withLock {
        dao.clearRelations()
        dao.clearObservations()
        dao.clearEntities()
        lastVaultFingerprint = null
        runCatching { database.openHelper.writableDatabase.execSQL("DELETE FROM memory_graph_fts") }
    }

    private suspend fun entityRecord(
        name: String,
        type: String,
        scope: String,
        chatId: Int,
        messageId: Int,
        now: Long,
        existingEntities: Map<String, MemoryGraphEntityRecord>
    ): MemoryGraphEntityRecord {
        val normalized = normalize(name).take(120)
        val id = stableId("entity", scope, normalized)
        val existing = existingEntities[id]
        return MemoryGraphEntityRecord(
            id = id,
            name = name.trim().take(120),
            normalizedName = normalized,
            entityType = normalizeType(type),
            scope = scope,
            standalone = existing?.standalone ?: false,
            sourceChatId = existing?.sourceChatId?.takeIf { it != 0 } ?: chatId,
            sourceMessageId = existing?.sourceMessageId?.takeIf { it != 0 } ?: messageId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
    }

    private fun mergeEntity(current: MemoryGraphEntityRecord?, incoming: MemoryGraphEntityRecord): MemoryGraphEntityRecord =
        if (current == null) {
            incoming
        } else {
            incoming.copy(
                standalone = current.standalone || incoming.standalone,
                createdAt = minOf(current.createdAt, incoming.createdAt)
            )
        }

    private suspend fun loadNodesLocked(rows: List<MemoryGraphEntityRecord>, chatId: Int?): List<MemoryGraphNode> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val relations = dao.relationsFor(ids)
            .filter { chatId == null || it.sourceChatId == 0 || it.sourceChatId == chatId }
        val relatedIds = (ids + relations.flatMap { listOf(it.fromEntityId, it.toEntityId) }).distinct()
        val entityMap = relatedIds.chunked(400).flatMap { dao.entitiesByIds(it) }.associateBy { it.id }
        val observations = dao.observationsFor(ids)
            .filter { chatId == null || it.sourceChatId == 0 || it.sourceChatId == chatId }
            .groupBy { it.entityId }

        return rows.map { row ->
            MemoryGraphNode(
                id = row.id,
                name = row.name,
                entityType = row.entityType,
                observations = observations[row.id].orEmpty().map { it.observation }.distinct().take(24),
                relations = relations.filter { it.fromEntityId == row.id || it.toEntityId == row.id }.mapNotNull { relation ->
                    val from = entityMap[relation.fromEntityId]?.name ?: return@mapNotNull null
                    val to = entityMap[relation.toEntityId]?.name ?: return@mapNotNull null
                    MemoryGraphRelationView(from, to, relation.relationType)
                }.distinct().take(48)
            )
        }
    }

    private fun visible(row: MemoryGraphEntityRecord, chatId: Int?): Boolean =
        chatId == null || row.sourceChatId == 0 || row.sourceChatId == chatId

    private fun ensureFtsLocked(): Boolean {
        ftsAvailable?.let { return it }
        val readable = database.openHelper.readableDatabase
        val existingDefinition = readable.query("SELECT sql FROM sqlite_master WHERE name = 'memory_graph_fts'").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        ftsNeedsRebuild = existingDefinition == null
        if (existingDefinition != null) {
            val engine = Regex("(?i)USING\\s+(fts[45])\\b").find(existingDefinition)?.groupValues?.get(1)?.lowercase() ?: "LIKE"
            fts4Index = engine == "fts4"
            val usable = engine != "LIKE" && runCatching { readable.query("SELECT count(*) FROM memory_graph_fts").use { it.moveToFirst() } }.getOrDefault(false)
            ftsAvailable = usable
            AppLogRecorder.record("Memory", "SEARCH_INDEX_READY · engine=${if (usable) engine else "LIKE"} · rebuild=false")
            return usable
        }
        // Try actual capabilities; module_list itself is optional on vendor SQLite builds.
        val definitions = listOf(
            "fts5(entity_id UNINDEXED, scope UNINDEXED, text, tokenize='unicode61 remove_diacritics 2')",
            "fts4(entity_id, scope, text, notindexed=entity_id, notindexed=scope, tokenize=unicode61)"
        )
        var available = false
        for (definition in definitions) {
            if (runCatching { database.openHelper.writableDatabase.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_graph_fts USING $definition") }.isSuccess) {
                available = true
                fts4Index = definition.startsWith("fts4(")
                AppLogRecorder.record("Memory", "SEARCH_INDEX_READY · engine=${definition.substringBefore('(')} · rebuild=$ftsNeedsRebuild")
                break
            }
        }
        if (!available) AppLogRecorder.record("Memory", "SEARCH_INDEX_READY · engine=LIKE · fullTextUnavailable=true")
        ftsAvailable = available
        return available
    }

    private suspend fun rebuildFtsLocked(changed: Set<String>) {
        if (!ensureFtsLocked()) return
        // Read through Room before taking the raw SQLite transaction. Suspending
        // DAO calls inside a SupportSQLiteDatabase transaction can switch threads.
        if (changed.isEmpty() && !ftsNeedsRebuild) return
        val entities = if (ftsNeedsRebuild) dao.allEntities() else changed.chunked(400).flatMap { dao.entitiesByIds(it) }
        val observations = entities.map { it.id }.chunked(400).flatMap { dao.observationsFor(it) }
        val db = database.openHelper.writableDatabase
        runCatching {
            db.beginTransaction()
            try {
                if (ftsNeedsRebuild) {
                    db.execSQL("DELETE FROM memory_graph_fts")
                } else {
                    changed.forEach { db.execSQL("DELETE FROM memory_graph_fts WHERE entity_id = ?", arrayOf(it)) }
                }
                entities.forEach { entity ->
                    db.execSQL(
                        "INSERT INTO memory_graph_fts(entity_id, scope, text) VALUES (?, ?, ?)",
                        arrayOf(entity.id, entity.scope, "${entity.name} ${entity.entityType}")
                    )
                }
                observations.forEach { observation ->
                    db.execSQL(
                        "INSERT INTO memory_graph_fts(entity_id, scope, text) VALUES (?, ?, ?)",
                        arrayOf(observation.entityId, observation.scope, observation.observation)
                    )
                }
                db.setTransactionSuccessful()
                ftsNeedsRebuild = false
            } finally {
                db.endTransaction()
            }
        }.onFailure { ftsAvailable = false }
    }

    private fun searchFtsLocked(query: String, scope: String, limit: Int): List<String> {
        if (!ensureFtsLocked()) return emptyList()
        val match = ftsQuery(query, fts4Index) ?: return emptyList()
        return runCatching {
            val cursor = database.openHelper.readableDatabase.query(
                SimpleSQLiteQuery(
                    "SELECT entity_id FROM memory_graph_fts WHERE scope = ? AND memory_graph_fts MATCH ? GROUP BY entity_id LIMIT ?",
                    arrayOf<Any?>(scope, match, limit)
                )
            )
            cursor.use {
                buildList {
                    while (it.moveToNext()) add(it.getString(0))
                }
            }
        }.getOrElse {
            ftsAvailable = false
            emptyList()
        }
    }

    internal fun ftsQuery(query: String, fts4: Boolean = false): String? {
        val tokens = normalize(query)
            .split(Regex("[^\\p{L}\\p{N}_-]+"))
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { token ->
            val escaped = token.replace("\"", "\"\"")
            if (fts4) "\"$escaped*\"" else "\"$escaped\"*"
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun normalizeType(value: String): String =
        value.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]+"), "_").trim('_').take(64).ifBlank { "ENTITY" }

    private fun stableId(kind: String, scope: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$kind|$scope|$value".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$kind-${digest.take(32)}"
    }

    private fun vaultFingerprint(facts: List<VaultFact>): String {
        val stable = facts.asSequence()
            .sortedBy { it.id }
            .joinToString("|") { "${it.scope}:${it.id}:${it.sourceChatId}:${it.sourceMessageId}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SOURCE_VAULT = "vault"
        private const val MAX_OBSERVATION_CHARS = 1000
    }
}
