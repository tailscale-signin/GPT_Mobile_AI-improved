package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.rag.VaultFact
import io.objectbox.BoxStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SemanticMemoryStatus(val indexed: Int = 0, val total: Int = 0, val available: Boolean = true, val detail: String = "Ready · on-device sentence encoder")

/** No network client. All tokenization, embedding and HNSW queries execute on-device. */
@Singleton
class LocalSemanticMemory @Inject constructor(@ApplicationContext private val context: Context) {
    // A limited-parallelism IO dispatcher does not guarantee thread affinity.
    // ObjectBox cached readers and native embedder lifecycle stay on this one owner thread.
    private val ownerDispatcher = Executors.newSingleThreadExecutor { work ->
        Thread(work, "semantic-memory-owner").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var store: BoxStore? = null
    private var embedder: TextEmbedder? = null
    private var lastUsed = 0L
    private var retryAfter = 0L
    private val _status = MutableStateFlow(SemanticMemoryStatus())
    val status = _status.asStateFlow()

    private fun box() = (
        store ?: MyObjectBox.builder()
            .androidContext(context)
            .directory(File(context.noBackupFilesDir, "memory-vectors-use-qa-v1"))
            .maxSizeInKByte(128 * 1024)
            .build().also { store = it }
        ).boxFor(MemoryVector::class.java)

    private fun embed(text: String): FloatArray {
        lastUsed = android.os.SystemClock.elapsedRealtime()
        val firstInput = embedder == null
        val engine = embedder ?: createEmbedder().also { embedder = it }
        if (firstInput) AppLogRecorder.checkpoint("Memory", "EMBEDDING_FIRST_INPUT_STARTED")
        val vector = engine.embed(text.take(4000)).embeddingResult().embeddings().first().floatEmbedding()
        require(vector.size == 100 && vector.all(Float::isFinite)) { "Embedding model dimensions changed." }
        if (firstInput) AppLogRecorder.checkpoint("Memory", "EMBEDDING_FIRST_INPUT_COMPLETED · dimensions=${vector.size}")
        return vector
    }

    private fun createEmbedder(): TextEmbedder {
        MediaPipeJniContract.verify()
        AppLogRecorder.checkpoint("Memory", "EMBEDDING_ENGINE_STARTING · jniBindings=verified")
        return TextEmbedder.createFromOptions(
            context,
            TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("memory/universal_sentence_encoder.tflite").build())
                .build()
        ).also {
            AppLogRecorder.checkpoint("Memory", "EMBEDDING_ENGINE_CREATED · instance=${System.identityHashCode(this)} · lazy=true · singleton=true")
        }
    }

    /** Incremental and bounded; a large restored vault is completed over successive turns or Rebuild. */
    suspend fun synchronize(facts: List<VaultFact>, batchSize: Int = 128) = withContext(ownerDispatcher) {
        mutex.withLock {
            if (retryDeferred()) return@withLock
            try {
                val box = box()
                val existing = box.all.filterNot { it.factId.startsWith("doc:") }.associateBy { it.factId }
                val desired = facts.associateBy { it.id }
                box.remove(*existing.values.filter { it.factId !in desired }.map { it.id }.toLongArray())
                val changed = facts.filter { existing[it.id]?.fingerprint != fingerprint(it) }
                val updated = changed.take(batchSize.coerceIn(1, 16384)).map { fact ->
                    currentCoroutineContext().ensureActive()
                    MemoryVector(existing[fact.id]?.id ?: 0, fact.id, fact.scope, fingerprint(fact), embed(text(fact)))
                }
                box.put(updated)
                val indexed = facts.size - changed.size + updated.size
                _status.value = SemanticMemoryStatus(indexed, facts.size, detail = if (indexed == facts.size) "Ready · 100-dimensional HNSW" else "Indexing · $indexed of ${facts.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                unavailable(failure)
            } catch (failure: LinkageError) {
                unavailable(failure)
            }
        }
    }

    suspend fun search(query: String, scope: String, limit: Int = 80, includePersonal: Boolean = true): Map<String, Double> = withContext(ownerDispatcher) {
        mutex.withLock {
            if (retryDeferred()) return@withLock emptyMap()
            try {
                if (query.isBlank()) return@withLock emptyMap()
                val allowedScope = if (includePersonal) MemoryVector_.scope.equal("personal").or(MemoryVector_.scope.equal(scope)) else MemoryVector_.scope.equal(scope)
                box().query(allowedScope.and(MemoryVector_.embedding.nearestNeighbors(embed(query), limit.coerceIn(1, 256))))
                    .build().use { search -> search.findWithScores().associate { it.get().factId to (1.0 - it.score).coerceIn(-1.0, 1.0) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                unavailable(failure)
                emptyMap()
            } catch (failure: LinkageError) {
                unavailable(failure)
                emptyMap()
            }
        }
    }

    suspend fun indexDocument(document: dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeDocument, chunks: List<dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeChunk>) = withContext(ownerDispatcher) {
        mutex.withLock {
            if (retryDeferred()) return@withLock
            try {
                val box = box()
                val prefix = "doc:${document.id}:"
                val existing = box.query(MemoryVector_.factId.startsWith(prefix)).build().use { it.find() }.associateBy { it.factId }
                val scope = document.projectId?.let { "document:project:$it" } ?: "document:chat:${document.chatId}"
                val desired = chunks.map { "doc:${it.id}" }.toSet()
                box.remove(*existing.values.filter { it.factId !in desired }.map { it.id }.toLongArray())
                // No suspension while native readers/cursors are in use; cancellation is checked per chunk.
                chunks.forEach { chunk ->
                    currentCoroutineContext().ensureActive()
                    val hash = MessageDigest.getInstance("SHA-256").digest(chunk.text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
                    val old = existing["doc:${chunk.id}"]
                    if (old?.fingerprint != hash || old.scope != scope) {
                        box.put(MemoryVector(old?.id ?: 0, "doc:${chunk.id}", scope, hash, embed(chunk.text)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                unavailable(failure)
            } catch (failure: LinkageError) {
                unavailable(failure)
            }
        }
    }

    suspend fun removeDocument(id: String) = withContext(ownerDispatcher) {
        mutex.withLock { box().query(MemoryVector_.factId.startsWith("doc:$id:")).build().use { it.remove() } }
    }

    suspend fun searchDocuments(query: String, chatId: Int, projectId: String?): Map<String, Double> {
        val scopes = listOfNotNull("document:chat:$chatId", projectId?.let { "document:project:$it" })
        return scopes.flatMap { search(query, it, 48, includePersonal = false).entries }
            .associate { it.key.removePrefix("doc:") to it.value }
    }

    suspend fun clear() = withContext(ownerDispatcher) {
        mutex.withLock {
            // Deletion failures must be visible; never report forgotten vectors as removed.
            if (store != null || File(context.noBackupFilesDir, "memory-vectors-use-qa-v1").exists()) box().removeAll()
            embedder?.close()
            embedder = null
            retryAfter = 0L
            _status.value = SemanticMemoryStatus()
        }
    }

    /** Shares the app's idle/pressure lifecycle without interrupting native inference. */
    suspend fun releaseWhenIdle(idleMillis: Long = 180_000L) = withContext(ownerDispatcher) {
        if (!mutex.tryLock()) return@withContext
        try {
            if (android.os.SystemClock.elapsedRealtime() - lastUsed >= idleMillis) {
                embedder?.close()
                embedder = null
                store?.closeThreadResources()
                store?.close()
                store = null
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun unavailable(failure: Throwable) {
        // Retrying every profile and recall cannot repair a broken release schema.
        // Other initialization errors get a cooldown; an explicit rebuild can retry.
        val schemaFailure = generateSequence(failure) { it.cause }.take(12).any {
            it is LinkageError || it.message.orEmpty().let { message -> "not found" in message && "Known fields" in message }
        }
        retryAfter = if (schemaFailure) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() + 60_000L
        runCatching { embedder?.close() }
        embedder = null
        AppLogRecorder.record("Memory", "SEMANTIC_ENGINE_FAILED · cause=${failure.javaClass.simpleName} · detail=${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message.orEmpty()).take(240)}", "E")
        _status.value = _status.value.copy(available = false, detail = "Semantic engine unavailable · exact and topic recall remain active")
    }

    private fun retryDeferred() = android.os.SystemClock.elapsedRealtime() < retryAfter

    private fun text(fact: VaultFact) = "${fact.fact.entity.name} ${fact.fact.relation.relationType.lowercase().replace('_', ' ')} ${fact.fact.target.name}"
    private fun fingerprint(fact: VaultFact) = MessageDigest.getInstance("SHA-256").digest(text(fact).encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
