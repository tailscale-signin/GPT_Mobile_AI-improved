package dev.chungjungsoo.gptmobile.data.crawl

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One persistent frontier, alternative readers and two workers. Restart never starts network work. */
internal class MultiCrawlerTool(
    private val store: CrawlStore,
    private val scope: CrawlScope,
    private val engines: List<CrawlEngine>,
    private val robotsReader: AgentTool,
    private val pageLimit: Int,
    private val depthLimit: Int,
    private val allowed: suspend () -> Boolean,
    private val budgetBound: Boolean = false
) : AgentTool {
    override val managesExecutionBudget = true
    override val coordinatesChildWork = true
    override fun withChildBudget(bind: (AgentTool) -> AgentTool): AgentTool = MultiCrawlerTool(store, scope, engines.map { it.copy(tool = bind(it.tool)) }, bind(robotsReader), pageLimit, depthLimit, allowed, true)
    override val definition = AgentToolDefinition(
        "crawler",
        "Managed multi-page reading with selected free or connected readers. Use fetch for one page, start for multiple seeds, deep mode to follow same-host links. status/results never fetch. resume continues a paused job explicitly. Committed evidence survives interruption. Unsupported JavaScript/OCR requires a qualified connected reader.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put("default", "fetch")
                            put("enum", JsonArray(listOf("fetch", "start", "status", "results", "resume", "cancel").map(::JsonPrimitive)))
                        }
                    )
                    put("url", buildJsonObject { put("type", "string") })
                    put(
                        "seeds",
                        buildJsonObject {
                            put("type", "array")
                            put("maxItems", 100)
                            put("items", buildJsonObject { put("type", "string") })
                        }
                    )
                    put("jobId", buildJsonObject { put("type", "string") })
                    put(
                        "mode",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf("quick", "smart", "deep").map(::JsonPrimitive)))
                        }
                    )
                    put(
                        "maxPages",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", pageLimit.coerceIn(1, 100))
                        }
                    )
                    put(
                        "maxDepth",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", depthLimit.coerceIn(0, 5))
                        }
                    )
                    put(
                        "cursor",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                        }
                    )
                }
            )
            put("additionalProperties", false)
        }
    )
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        var workStarted = false
        try {
            check(allowed()) { "Crawler permission changed; no work dispatched" }
            require(engines.isNotEmpty()) { "Select at least one crawler reader in profile tool settings" }
            val action = (arguments["action"] as? JsonPrimitive)?.content ?: "fetch"
            val job = if (action in setOf("fetch", "start")) {
                create(arguments, action == "fetch")
            } else {
                val id = (arguments["jobId"] as? JsonPrimitive)?.content.orEmpty()
                requireNotNull(store.read(id)) { "Crawler job does not exist" }.also { require(it.scope.evidenceScope == scope.evidenceScope) { "Crawler job belongs to a different conversation or permission revision" } }
            }
            val state = when (action) {
                "fetch", "start", "resume" -> {
                    check(budgetBound) { "Crawler readers require the shared execution budget" }
                    workStarted = true
                    run(job, callId)
                }
                "cancel" -> {
                    cancel(store, job.id)
                }
                "status", "results" -> job.copy(state = if (job.state == "RUNNING" && job.id !in active) "PAUSED" else job.state)
                else -> error("Unsupported crawler action")
            }
            val cursor = (arguments["cursor"] as? JsonPrimitive)?.intOrNull ?: 0
            require(cursor in 0..state.pageIds.size) { "Invalid results cursor" }
            val pages = if (action == "status" || action == "cancel") emptyList() else state.pageIds.drop(cursor).take(5).mapNotNull { store.page(scope, it) }
            val payload = result(state, pages, cursor, false)
            return AgentToolResult(callId, ToolResultContent.Json(payload), false, retainedContent = ToolResultContent.Json(result(state, pages, cursor, true)), sharedResult = action in setOf("status", "results"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return AgentToolResult(callId, ToolResultContent.Text(error.message ?: "Crawler unavailable"), true, errorCode = "CRAWLER_UNAVAILABLE", dispatched = workStarted)
        }
    }
    private suspend fun create(args: JsonObject, single: Boolean): CrawlJob {
        val seeds = if (single) listOf((args["url"] as? JsonPrimitive)?.content.orEmpty()) else (args["seeds"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() }
        require(seeds.size in 1..100) { "Supply one URL or 1–100 seeds" }
        val urls = seeds.map(CrawlPolicy::url).distinct()
        val mode = if (single) "quick" else (args["mode"] as? JsonPrimitive)?.content ?: "smart"
        require(mode in setOf("quick", "smart", "deep"))
        val pages = if (single) 1 else ((args["maxPages"] as? JsonPrimitive)?.intOrNull ?: pageLimit).also { require(it in 1..pageLimit.coerceIn(1, 100)) }
        val depth = if (mode == "deep") ((args["maxDepth"] as? JsonPrimitive)?.intOrNull ?: depthLimit).also { require(it in 0..depthLimit.coerceIn(0, 5)) } else 0
        val id = crawlHash("${scope.evidenceScope}:${scope.turnId}:$mode:$pages:$depth:${urls.joinToString()}")
        return store.update(id) { it ?: CrawlJob(id, scope, engines.map { engine -> engine.id }, mode, pages, depth, urls.map { url -> URI(url).host }.toSet(), urls.take(pages).map(::CrawlTarget)) }
    }
    private suspend fun run(original: CrawlJob, callId: String): CrawlJob {
        if (original.state in setOf("COMPLETED", "CANCELLED")) return original
        require(original.engineIds == engines.map { it.id }) { "Selected readers changed; start a new job to use a different engine set" }
        if (!active.add(original.id)) return requireNotNull(store.read(original.id))
        val cancelled = cancellations.computeIfAbsent(original.id) { AtomicBoolean() }
        try {
            store.update(original.id) { requireNotNull(it).copy(state = "RUNNING", inFlight = emptySet(), notice = "") }
            val finished = try {
                withTimeoutOrNull(25_000) {
                    workers[original.id] = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
                    while (!cancelled.get() && allowed()) {
                        val job = requireNotNull(store.read(original.id))
                        if (job.state != "RUNNING") break
                        val next = job.frontier.filter { it.url !in job.visited }.take(minOf(2, job.maxPages - job.visited.size))
                        if (next.isEmpty()) break
                        store.update(job.id) { requireNotNull(it).copy(inFlight = next.map { target -> target.url }.toSet()) }
                        coroutineScope { next.mapIndexed { index, target -> async { acquire(job, target, "$callId:${job.visited.size + index}", cancelled) } }.awaitAll() }
                    }
                    true
                } == true
            } catch (cancelledError: CancellationException) {
                if (!cancelled.get()) throw cancelledError else false
            }
            return store.update(original.id) { current ->
                val job = requireNotNull(current)
                val terminal = job.frontier.none { it.url !in job.visited } || job.visited.size >= job.maxPages
                job.copy(
                    state = when {
                        cancelled.get() || job.state == "CANCELLED" -> "CANCELLED"
                        terminal && job.failures.isEmpty() -> "COMPLETED"
                        terminal -> "PARTIAL"
                        else -> "PAUSED"
                    },
                    inFlight = emptySet(),
                    notice = when {
                        !finished -> "Time slice finished; resume to continue from committed pages"
                        !allowedSnapshot(job) -> "Permission or selection changed"
                        else -> job.notice
                    }
                )
            }
        } finally {
            withContext(NonCancellable) { store.update(original.id) { requireNotNull(it).let { job -> if (job.state == "RUNNING") job.copy(state = "PAUSED", inFlight = emptySet(), notice = "Interrupted; resume explicitly from committed evidence") else job } } }
            active.remove(original.id)
            cancellations.remove(original.id)
            workers.remove(original.id)
        }
    }
    private fun allowedSnapshot(job: CrawlJob): Boolean = job.engineIds == engines.map { it.id }
    private suspend fun acquire(job: CrawlJob, target: CrawlTarget, callId: String, cancelled: AtomicBoolean) {
        var failure = "No selected crawler returned usable evidence"
        var page: CrawlPage? = store.cached(scope, target.url)
        if (page == null) {
            try {
                check(allowed() && !cancelled.get()) { "Crawler permission revoked or job cancelled" }
                check(robots(target.url, "$callId:robots")) { "ROBOTS_DENIED_OR_UNAVAILABLE" }
                val order = engines.indices.map { engines[(it + job.visited.size) % engines.size] }
                for (engine in order) {
                    check(allowed() && !cancelled.get()) { "Crawler permission revoked or job cancelled" }
                    try {
                        domainTurn(URI(target.url).host)
                        page = engine.read("$callId:${engine.id.take(32)}", target.url)
                        break
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        failure = "${engine.name}: ${error.message.orEmpty().take(200)}"
                        if (failure.contains("BUDGET_EXHAUSTED") || failure.contains("permission", true) || Regex("(?i)HTTP (401|403)|CHALLENGE|CAPTCHA|AUTH_REQUIRED|ROBOTS_DENIED|SOURCE_ACCESS_BLOCKED|unsafe DNS|outside allowed domain").containsMatchIn(failure)) break
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error.message.orEmpty().take(240)
            }
        }
        val captured = page
        withContext(NonCancellable) {
            store.commitPage(job.id, captured) { previous ->
                if (previous.state == "CANCELLED" || cancelled.get()) {
                    previous
                } else if (captured == null && failure.contains("BUDGET_EXHAUSTED")) {
                    previous.copy(state = "PAUSED", inFlight = previous.inFlight - target.url, notice = "Reader budget exhausted; committed evidence retained")
                } else {
                    val links = if (previous.mode == "deep" && target.depth < previous.maxDepth) captured?.links.orEmpty().mapNotNull { CrawlPolicy.inScope(it, previous.hosts) }.filter { it !in previous.visited }.map { CrawlTarget(it, target.depth + 1) } else emptyList()
                    previous.copy(visited = previous.visited + target.url, inFlight = previous.inFlight - target.url, pageIds = (previous.pageIds + listOfNotNull(captured?.sourceId)).distinct(), failures = if (captured == null) (previous.failures + CrawlFailure(target.url, "selected readers", failure)).takeLast(100) else previous.failures, frontier = (previous.frontier + links).distinctBy { it.url }.take(500))
                }
            }
        }
    }
    private suspend fun robots(url: String, callId: String): Boolean {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        val key = "${scope.evidenceScope}:$origin"
        val saved = robotsCache[key]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }
        val text = saved?.second ?: run {
            domainTurn(uri.host)
            val result = robotsReader.execute(callId, buildJsonObject { put("url", "$origin/robots.txt") })
            if (result.isError) {
                if (result.toolCallBudgetExhausted || result.outputBudgetExhausted || result.errorCode?.contains("BUDGET_EXHAUSTED") == true) error("BUDGET_EXHAUSTED")
                if (!result.content.toString().contains("HTTP 404")) return false
                ""
            } else {
                (result.content as? ToolResultContent.Text)?.text ?: return false
            }
        }.also { robotsCache[key] = System.currentTimeMillis() to it }
        return CrawlPolicy.robotsAllows(text, url)
    }
    private fun result(job: CrawlJob, pages: List<CrawlPage>, cursor: Int, full: Boolean): JsonObject = buildJsonObject {
        put("jobId", job.id)
        put("state", job.state)
        put("visitedPages", job.visited.size)
        put("capturedPages", job.pageIds.size)
        put("maxPages", job.maxPages)
        put("pendingPages", job.frontier.count { it.url !in job.visited })
        put("notice", job.notice)
        put(
            "results",
            JsonArray(
                pages.map { page ->
                    buildJsonObject {
                        put("citationMetadata", page.metadata)
                        put("sourceId", page.sourceId)
                        put("url", page.url)
                        put("finalUrl", page.finalUrl)
                        put("title", page.title)
                        put("content", if (full) page.content else page.content.take(2400))
                        put("reader", page.engine)
                        put("retrievedAt", page.retrievedAt)
                        put("truncated", page.truncated || !full && page.content.length > 2400)
                        put("links", JsonArray(page.links.map(::JsonPrimitive)))
                    }
                }
            )
        )
        put("failures", Json.parseToJsonElement(Json.encodeToString(job.failures)))
        if (cursor + pages.size < job.pageIds.size) put("nextCursor", cursor + pages.size)
        put("evidenceOutcome", if (job.state == "COMPLETED") "SUCCESS" else "PARTIAL")
    }
    companion object {
        private val active = ConcurrentHashMap.newKeySet<String>()
        private val workers = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        fun stop(id: String) {
            cancellations[id]?.set(true)
            workers[id]?.cancel()
        }
        suspend fun cancel(store: CrawlStore, id: String): CrawlJob {
            cancellations[id]?.set(true)
            val job = store.update(id) { requireNotNull(it).copy(state = "CANCELLED", inFlight = emptySet(), notice = "Cancelled; committed evidence retained") }
            workers[id]?.cancel()
            return job
        }
        fun isRunning(id: String): Boolean = id in active
        private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
        private val robotsCache = ConcurrentHashMap<String, Pair<Long, String>>()
        private val domainLocks = Array(64) { Mutex() }
        private val domains = ConcurrentHashMap<String, Long>()
        private suspend fun domainTurn(host: String) = domainLocks[(host.hashCode() and Int.MAX_VALUE) % domainLocks.size].withLock {
            val delayMs = (domains[host] ?: 0) + 1000 - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            domains[host] = System.currentTimeMillis()
        }
    }
}
