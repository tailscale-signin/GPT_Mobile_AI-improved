package dev.chungjungsoo.gptmobile.data.crawl

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CrawlScope(val profileUid: String, val chatId: Int, val turnId: String, val revision: String, val persistent: Boolean) {
    val evidenceScope: String get() = crawlHash("$profileUid:$chatId:$revision:$persistent")
}

@Serializable
data class CrawlTarget(val url: String, val depth: Int = 0)

@Serializable
data class CrawlPage(val url: String, val finalUrl: String = url, val title: String = "", val content: String, val links: List<String> = emptyList(), val engine: String, val truncated: Boolean = false, val retrievedAt: Long = System.currentTimeMillis(), val metadata: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), val sourceId: String = crawlHash("$url\n$content"))

@Serializable
data class CrawlFailure(val url: String, val engine: String, val reason: String)

@Serializable
data class CrawlJob(
    val id: String,
    val scope: CrawlScope,
    val engineIds: List<String>,
    val mode: String,
    val maxPages: Int,
    val maxDepth: Int,
    val hosts: Set<String>,
    val frontier: List<CrawlTarget>,
    val visited: Set<String> = emptySet(),
    val inFlight: Set<String> = emptySet(),
    val pageIds: List<String> = emptyList(),
    val failures: List<CrawlFailure> = emptyList(),
    val state: String = "PAUSED",
    val updatedAt: Long = System.currentTimeMillis(),
    val notice: String = ""
)

/** Per-object commits on IO. A committed page is durable before its job receipt references it. */
class CrawlStore(private val root: File, private val maximumBytes: Long = 128L * 1024 * 1024) {
    private val maintenance = dev.chungjungsoo.gptmobile.data.research.ResearchStorageGate()
    private val quota = Mutex()
    private val usage = java.util.concurrent.atomic.AtomicLong(-1)
    private val revokedChats = ConcurrentHashMap<Int, Long>()
    private val locks = Array(64) { Mutex() }
    private val temporaryJobs = ConcurrentHashMap<String, CrawlJob>()
    private val temporaryPages = ConcurrentHashMap<String, CrawlPage>()
    private val mutable = MutableStateFlow<List<CrawlJob>>(emptyList())
    val jobs = mutable.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private fun lock(id: String) = locks[(id.hashCode() and Int.MAX_VALUE) % locks.size]
    private fun path(kind: String, id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid crawler record identity" }
        return File(root, "$kind/$id.json")
    }
    suspend fun read(id: String): CrawlJob? = withContext(Dispatchers.IO) {
        temporaryJobs[id] ?: lock(id).withLock { path("jobs", id).takeIf(File::exists)?.readText()?.let { json.decodeFromString<CrawlJob>(it) } }
    }
    suspend fun update(id: String, change: (CrawlJob?) -> CrawlJob): CrawlJob = withContext(Dispatchers.IO) {
        lock(id).withLock {
            val previous = temporaryJobs[id] ?: path("jobs", id).takeIf(File::exists)?.readText()?.let { json.decodeFromString<CrawlJob>(it) }
            val next = change(previous).copy(updatedAt = System.currentTimeMillis())
            require(next.id == id)
            check((next.scope.turnId.substringAfterLast(':').toLongOrNull() ?: Long.MIN_VALUE) > (revokedChats[next.scope.chatId] ?: Long.MIN_VALUE)) { "Crawler conversation was deleted or made temporary" }
            if (previous == null) check((File(root, "jobs").listFiles()?.size ?: 0) + temporaryJobs.size < 1000) { "Crawler job limit reached; delete old jobs to continue" }
            if (next.scope.persistent) write(path("jobs", id), json.encodeToString(next)) else temporaryJobs[id] = next
            mutable.update { current -> (current.filterNot { it.id == id } + next).sortedByDescending { it.updatedAt }.take(100) }
            next
        }
    }
    suspend fun page(scope: CrawlScope, id: String): CrawlPage? = withContext(Dispatchers.IO) {
        val key = crawlHash("${scope.evidenceScope}:$id")
        temporaryPages[key] ?: lock(key).withLock { path("pages", key).takeIf(File::exists)?.readText()?.let { json.decodeFromString<CrawlPage>(it) } }
    }
    private suspend fun capture(scope: CrawlScope, page: CrawlPage) = withContext(Dispatchers.IO) {
        require(page.content.toByteArray().size <= 256_000) { "Crawler page exceeds the evidence limit" }
        val key = crawlHash("${scope.evidenceScope}:${page.sourceId}")
        lock(key).withLock {
            if (!scope.persistent) {
                check(temporaryPages.size < 500 || temporaryPages.containsKey(key)) { "Temporary crawler evidence is full" }
                temporaryPages[key] = page
            } else if (!path("pages", key).exists()) {
                val encoded = json.encodeToString(page)
                val bytes = encoded.toByteArray().size.toLong()
                if (usage.get() < 0) quota.withLock { if (usage.get() < 0) usage.set(File(root, "pages").listFiles()?.sumOf { it.length() } ?: 0) }
                while (true) {
                    val previous = usage.get()
                    check(previous + bytes <= maximumBytes) { "Crawler storage is full; delete old jobs to continue" }
                    if (usage.compareAndSet(previous, previous + bytes)) break
                }
                try {
                    write(path("pages", key), encoded)
                } catch (error: Exception) {
                    usage.addAndGet(-bytes)
                    throw error
                }
            }
        }
    }
    suspend fun cached(scope: CrawlScope, url: String): CrawlPage? = withContext(Dispatchers.IO) {
        val candidates = list(scope).filter { it.scope.evidenceScope == scope.evidenceScope }.flatMap { it.pageIds }.distinct().takeLast(500)
        candidates.asReversed().firstNotNullOfOrNull { id -> page(scope, id)?.takeIf { it.url == url && System.currentTimeMillis() - it.retrievedAt < 300_000 } }
    }
    suspend fun list(scope: CrawlScope? = null): List<CrawlJob> = withContext(Dispatchers.IO) {
        val disk = File(root, "jobs").listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file -> runCatching { json.decodeFromString<CrawlJob>(file.readText()) }.getOrNull() }
        val all = (disk + temporaryJobs.values).sortedByDescending { it.updatedAt }
        mutable.value = all.take(100)
        all.filter { scope == null || it.scope.evidenceScope == scope.evidenceScope }
    }
    suspend fun commitPage(id: String, page: CrawlPage?, change: (CrawlJob) -> CrawlJob): CrawlJob = maintenance.withReadLock {
        val job = requireNotNull(read(id))
        if (job.state == "CANCELLED") return@withReadLock job
        if (page != null) capture(job.scope, page)
        update(id) { change(requireNotNull(it)) }
    }
    suspend fun delete(id: String) = maintenance.withLock {
        withContext(Dispatchers.IO) {
            lock(id).withLock {
                temporaryJobs.remove(id)
                path("jobs", id).delete()
            }
            val retained = list().flatMap { job -> job.pageIds.map { crawlHash("${job.scope.evidenceScope}:$it") } }.toSet()
            File(root, "pages").listFiles().orEmpty().filter { it.nameWithoutExtension !in retained }.forEach { file -> lock(file.nameWithoutExtension).withLock { file.delete() } }
            temporaryPages.keys.removeIf { it !in retained }
            usage.set(File(root, "pages").listFiles()?.sumOf { it.length() } ?: 0)
        }
    }
    suspend fun forgetChat(chatId: Int) {
        revokedChats[chatId] = System.currentTimeMillis()
        list().filter { it.scope.chatId == chatId }.forEach { job ->
            MultiCrawlerTool.stop(job.id)
            delete(job.id)
        }
    }
    private fun write(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.pending")
        java.io.FileOutputStream(temp).use {
            it.write(value.toByteArray())
            it.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, CrawlStore>()
        fun forDirectory(files: File): CrawlStore = instances.computeIfAbsent(files.absolutePath) { CrawlStore(File(files, "crawler-evidence-v1")) }
    }
}
fun crawlHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
