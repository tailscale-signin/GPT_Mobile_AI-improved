package dev.chungjungsoo.gptmobile.data.research.academic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.tool.SpecialUseAddress
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchDomainAllowed
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class AcademicSearchRequest(
    val query: String,
    val depth: Int,
    val maxResults: Int = 25,
    val page: Int = 1,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val reviewStatus: String = "any",
    val maxProviderCalls: Int = 3,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList()
)

internal data class AcademicAccessPolicy(val included: List<String> = emptyList(), val excluded: List<String> = emptyList(), val providers: Set<String> = emptySet()) {
    fun allows(provider: String) = providers.isEmpty() || provider in providers
}

private class AcademicHttpFailure(val status: Int, val retryAtMs: Long? = null) : Exception("Academic HTTP $status")

/** Fixed public origins, DNS-pinned connections, bounded bodies, durable quotas and no credentials. */
@Singleton
class AcademicNativeClient @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("academic-quotas-v1", Context.MODE_PRIVATE)
    private val evidenceDirectory = File(context.filesDir, "academic-evidence-v1")
    private val cursorDirectory = File(context.filesDir, "research-history")
    private val downloadDirectory = File(context.cacheDir, "academic-downloads")
    private val permits = CORE.associateWith { Semaphore(1) } + mapOf("opencitations" to Semaphore(1))
    private val global = Semaphore(8)
    private val sourcePermits = Array(64) { Semaphore(2) }
    private val snapshotMutex = Mutex()
    private val quotaLocks = ConcurrentHashMap<String, Mutex>()
    private val statuses = ConcurrentHashMap<String, AcademicProviderStatus>()
    private val telemetry = dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchTelemetryStore(File(context.filesDir, "research-telemetry"))
    private val cache = dev.chungjungsoo.gptmobile.data.research.ResearchResultCache<String, List<AcademicPaper>>(30 * 60_000L, 64)
    private val connectivity = ResearchConnectivityGate.shared
    private val searchRotation = java.util.concurrent.atomic.AtomicInteger()
    private val papers = ConcurrentHashMap<String, AcademicPaper>()
    private val transientEvidence = ConcurrentHashMap<String, AcademicEvidence>()
    private val cursors = ConcurrentHashMap<String, String>()
    private fun remember(paper: AcademicPaper): AcademicPaper {
        if (papers.size >= 1200) papers.keys.firstOrNull()?.let(papers::remove)
        papers[paper.paperId] = paper
        return paper
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val http = HttpClient(OkHttp) {
        install(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsHttpPlugin)
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 8000
            socketTimeoutMillis = 12000
            requestTimeoutMillis = 20000
        }
        engine {
            dns = Dns { host ->
                Dns.SYSTEM.lookup(host).also { addresses ->
                    require(addresses.isNotEmpty() && addresses.none(SpecialUseAddress::isSpecialUse)) { "Academic endpoint resolved to a non-public address" }
                }
            }
            config {
                proxy(Proxy.NO_PROXY)
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }

    fun status(): List<AcademicProviderStatus> = (CORE + "opencitations").map { id ->
        val stats = telemetry.rollup(id)
        (statuses[id] ?: AcademicProviderStatus(id, "unknown", limitation = if (id == "arxiv") "Preprints; PDF reading requires an authorized PDF reader" else "Public metadata; provider limits and local policy are separate")).copy(requests = stats.attempts, successes = stats.successes, failures = stats.failures)
    }

    internal suspend fun search(request: AcademicSearchRequest, selection: MultiSearchResearchSelection, allowed: suspend () -> Boolean): AcademicSearchResult = coroutineScope {
        require(request.query.length in 1..500 && request.maxResults in 1..50 && request.page > 0)
        require(request.fromYear == null || request.toYear == null || request.fromYear <= request.toYear)
        val query = request.query.lowercase()
        val routed = when {
            Regex("cancer|clinical|patient|medicine|health|biolog").containsMatchIn(query) -> listOf("europepmc", "openalex", "crossref")
            Regex("dataset|software|research data").containsMatchIn(query) -> listOf("datacite", "openalex", "crossref")
            Regex("gpu|npu|android|transformer|physics|learning|cryptograph|algorithm").containsMatchIn(query) -> listOf("arxiv", "openalex", "crossref")
            else -> listOf("openalex", "crossref", "datacite")
        }
        val rotation = Math.floorMod(searchRotation.getAndIncrement(), CORE.size)
        val rotated = CORE.drop(rotation) + CORE.take(rotation)
        val planned = (routed.take(1) + rotated + routed).distinct()
            .filter { selection.enabledProviders.isEmpty() || it in selection.enabledProviders }.take(if (selection.depth == 100) CORE.size else request.maxProviderCalls.coerceIn(1, CORE.size))
        val results = planned.map { provider ->
            async(Dispatchers.IO) {
                val receipt = UUID.randomUUID().toString()
                val key = researchHash("$provider|${request.copy(query = request.query.trim().replace(Regex("\\s+"), " "), maxProviderCalls = 1)}")
                var cacheHit = false
                var dispatched = false
                val started = System.nanoTime()
                try {
                    check(allowed()) { "permission_denied" }
                    currentCoroutineContext().ensureActive()
                    val saved = cache[key]
                    cacheHit = saved != null
                    val papers = saved ?: permits.getValue(provider).withPermit {
                        cache[key]?.let {
                            cacheHit = true
                            return@withPermit it
                        }
                        run {
                            reserve(provider, allowed)
                            check(allowed()) { "permission_denied" }
                            dispatched = true
                            val body = fetch(searchUrl(provider, request), request.includeDomains, request.excludeDomains, allowed, provider).first
                            if (provider in setOf("europepmc", "pubmed")) {
                                val cursor = json.parseToJsonElement(body).jsonObject.string("nextCursorMark")
                                if (cursor.isNotBlank()) {
                                    if (cursors.size >= 192) cursors.keys.firstOrNull()?.let(cursors::remove)
                                    saveCursor(request, request.page + 1, cursor)
                                }
                            }
                            parseSearch(provider, body).also { cache[key] = it }
                        }
                    }
                    val filtered = papers.filter { paper ->
                        researchDomainAllowed(paper.openAccessUrl?.takeIf(String::isNotBlank) ?: paper.canonicalUrl, request.includeDomains, request.excludeDomains) &&
                            (request.fromYear == null || paper.year?.let { it >= request.fromYear } == true) &&
                            (request.toYear == null || paper.year?.let { it <= request.toYear } == true) &&
                            (request.reviewStatus == "any" || paper.reviewStatus == request.reviewStatus)
                    }.map { remember(it.copy(sourceProviders = listOf(provider), providerReceipts = listOf(receipt))) }
                    currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.research.AcademicDiscoveryObserver]?.accept?.invoke(
                        filtered.map { json.encodeToJsonElement(AcademicPaper.serializer(), it).jsonObject }
                    )
                    val state = AcademicProviderStatus(provider, if (filtered.isEmpty()) "empty" else "success", filtered.size, cacheHit, receiptId = receipt)
                    statuses[provider] = state
                    telemetry.record(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(receipt, provider, "search", System.currentTimeMillis(), outcome = if (filtered.isEmpty()) "EMPTY" else "SUCCESS", cacheHit = cacheHit, dispatched = dispatched, durationMs = (System.nanoTime() - started) / 1_000_000))
                    filtered to state
                } catch (cancelled: CancellationException) {
                    telemetry.record(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(receipt, provider, "search", System.currentTimeMillis(), outcome = "CANCELLED", dispatched = dispatched, cacheHit = cacheHit, durationMs = (System.nanoTime() - started) / 1_000_000))
                    throw cancelled
                } catch (failure: Exception) {
                    telemetry.record(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(receipt, provider, "search", System.currentTimeMillis(), outcome = "FAILED", dispatched = dispatched, cacheHit = cacheHit, durationMs = (System.nanoTime() - started) / 1_000_000))
                    val state = AcademicProviderStatus(
                        provider,
                        when {
                            failure is AcademicHttpFailure && failure.status == 429 -> "rate_limited"
                            failure is AcademicHttpFailure && failure.status in setOf(401, 403) -> "authentication_required"
                            failure.message == "local_daily_policy" -> "local_policy_limit"
                            failure.message == "network_recovery" -> "waiting_for_network"
                            failure.message == "permission_denied" -> "permission_denied"
                            else -> "unavailable"
                        },
                        retryAtMs = (failure as? AcademicHttpFailure)?.retryAtMs,
                        limitation = failure.message?.take(180),
                        receiptId = receipt
                    )
                    statuses[provider] = state
                    emptyList<AcademicPaper>() to state
                }
            }
        }.awaitAll()
        val merged = mergeAcademicPapers(results.flatMap { it.first }).map(::remember)
        val states = results.map { it.second }
        AcademicSearchResult(
            searchId = UUID.randomUUID().toString(),
            query = request.query,
            status = if (states.isNotEmpty() && states.all { it.state in setOf("success", "empty") }) "complete" else "partial",
            papers = merged.take(request.maxResults),
            providers = states,
            truncated = merged.size > request.maxResults || results.any { it.first.size >= minOf(request.maxResults, 20) },
            nextCursor = (request.page + 1).toString().takeIf { request.page < Int.MAX_VALUE && results.any { it.first.size >= minOf(request.maxResults, 20) } }
        )
    }

    internal suspend fun getPaper(identifier: String, allowed: suspend () -> Boolean, policy: AcademicAccessPolicy = AcademicAccessPolicy(), refresh: Boolean = false): AcademicPaper? = withContext(Dispatchers.IO) {
        check(allowed()) { "permission_denied" }
        val doi = SourceIdentity.normalizeDoi(identifier.removePrefix("doi:"))
        (
            (if (refresh) null else papers[if (doi.isNotBlank()) "doi:$doi" else identifier]) ?: papers.values.takeUnless { refresh }?.firstOrNull { cached ->
                cached.identifiers["pmcid"] == identifier.removePrefix("pmcid:").uppercase() ||
                    cached.identifiers["pmid"] == identifier.removePrefix("pmid:")
            }
            )?.let { saved ->
            require(researchDomainAllowed(saved.openAccessUrl?.takeIf(String::isNotBlank) ?: saved.canonicalUrl, policy.included, policy.excluded)) { "Paper domain is excluded" }
            require(saved.sourceProviders.any(policy::allows)) { "Academic provider disabled" }
            return@withContext saved
        }
        if (doi.isNotEmpty()) {
            val provider = "crossref"
            require(policy.allows(provider)) { "Academic provider disabled" }
            return@withContext permits.getValue(provider).withPermit {
                reserve(provider, allowed)
                val obj = json.parseToJsonElement(fetch("https://api.crossref.org/works/${encode(doi)}", policy.included, policy.excluded, allowed, provider).first).jsonObject
                remember(crossref(obj["message"]!!.jsonObject).copy(sourceProviders = listOf(provider), providerReceipts = listOf(UUID.randomUUID().toString())))
            }
        }
        val arxiv = identifier.removePrefix("arxiv:").substringAfter("arxiv.org/abs/", identifier.removePrefix("arxiv:")).substringAfter("arxiv.org/pdf/").removeSuffix(".pdf")
        if (arxiv.matches(Regex("(?:[0-9]{4}\\.[0-9]{4,5}|[a-z-]+/[0-9]{7})(?:v[0-9]+)?"))) {
            require(policy.allows("arxiv")) { "Academic provider disabled" }
            return@withContext permits.getValue("arxiv").withPermit {
                reserve("arxiv", allowed)
                parseSearch("arxiv", fetch("https://export.arxiv.org/api/query?id_list=${encode(arxiv)}", policy.included, policy.excluded, allowed, "arxiv").first).firstOrNull()?.copy(sourceProviders = listOf("arxiv"), providerReceipts = listOf(UUID.randomUUID().toString()))?.let(::remember)
            }
        }
        val pmcid = identifier.removePrefix("pmcid:").uppercase()
        val pmid = identifier.removePrefix("pmid:")
        require(pmcid.matches(Regex("PMC[0-9]+")) || pmid.matches(Regex("[0-9]{1,12}"))) { "Native lookup supports DOI, arXiv, PMID and PMCID. Use the gateway for other identifiers." }
        val query = if (pmcid.startsWith("PMC")) "EXT_ID:$pmcid" else "EXT_ID:$pmid AND SRC:MED"
        require(policy.allows("europepmc")) { "Academic provider disabled" }
        return@withContext search(AcademicSearchRequest(query, 20, 1, includeDomains = policy.included, excludeDomains = policy.excluded), MultiSearchResearchSelection(true, 20, enabledProviders = setOf("europepmc")), allowed).papers.firstOrNull()
    }

    internal suspend fun read(paper: AcademicPaper, allowed: suspend () -> Boolean, policy: AcademicAccessPolicy = AcademicAccessPolicy(), persistent: Boolean = true, maxTextCharacters: Int = 24000, targetedCrawl: Boolean = false): AcademicEvidence = withContext(Dispatchers.IO) {
        check(allowed()) { "permission_denied" }
        fun recorded(evidence: AcademicEvidence): AcademicEvidence {
            telemetry.record(
                dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(
                    UUID.randomUUID().toString(),
                    paper.sourceProviders.firstOrNull() ?: "academic-reader",
                    "read",
                    System.currentTimeMillis(),
                    outcome = if (evidence.retrievalStatus in setOf("read", "partially_read")) "SUCCESS" else "EMPTY"
                )
            )
            return evidence
        }
        try {
            val pmc = paper.identifiers["pmcid"].orEmpty()
            val arxiv = paper.identifiers["arxiv"].orEmpty()
            val url = when {
                pmc.matches(Regex("PMC[0-9]+")) -> "https://www.ebi.ac.uk/europepmc/webservices/rest/$pmc/fullTextXML"
                arxiv.isNotBlank() -> "https://arxiv.org/html/$arxiv"
                else -> paper.openAccessUrl.orEmpty().ifBlank { if (targetedCrawl) paper.canonicalUrl else "" }
            }
            if (url.isBlank()) return@withContext recorded(AcademicEvidence(paper.paperId, paper.versionId, paper.canonicalUrl, System.currentTimeMillis(), "", "", "metadata_only"))
            if (url.substringBefore('?').endsWith(".pdf", true)) return@withContext recorded(AcademicEvidence(paper.paperId, paper.versionId, url, System.currentTimeMillis(), "", "", "pdf_unsupported_without_gateway"))
            val provider = if (pmc.isNotBlank()) {
                "europepmc"
            } else if (arxiv.isNotBlank()) {
                "arxiv"
            } else {
                paper.sourceProviders.firstOrNull() ?: "crossref"
            }
            require(policy.allows(provider)) { "Academic provider disabled" }
            val readerIdentity = if (pmc.isNotBlank() || arxiv.isNotBlank()) provider else "source:${URI(url).host.orEmpty().lowercase()}"
            val readerPermit = permits[readerIdentity] ?: sourcePermits[(readerIdentity.hashCode() and Int.MAX_VALUE) % sourcePermits.size]
            var body = readerPermit.withPermit {
                reserve(readerIdentity, allowed)
                fetch(url, policy.included, policy.excluded, allowed, readerIdentity, sourceDocument = pmc.isBlank())
            }
            if (body.second.contains("pdf", true) || body.first.startsWith("%PDF")) return@withContext recorded(AcademicEvidence(paper.paperId, paper.versionId, url, System.currentTimeMillis(), "", "", "pdf_unsupported_without_gateway"))
            var sourceUrl = url
            if (targetedCrawl && pmc.isBlank() && arxiv.isBlank()) {
                for (candidate in targetedArticleLinks(body.first, url, paper.doi)) {
                    check(allowed()) { "permission_denied" }
                    val followed = readerPermit.withPermit {
                        reserve(readerIdentity, allowed)
                        fetch(candidate, policy.included, policy.excluded, allowed, readerIdentity, sourceDocument = true)
                    }
                    if (followed.second.contains("pdf", true) || followed.first.startsWith("%PDF")) continue
                    val doc = Jsoup.parse(followed.first)
                    val declaredDoi = doc.selectFirst("meta[name=citation_doi]")?.attr("content").orEmpty()
                    if (declaredDoi.isNotBlank() && paper.doi.isNotBlank() && SourceIdentity.normalizeDoi(declaredDoi) != SourceIdentity.normalizeDoi(paper.doi)) continue
                    if (Regex("(?i)verify you are human|access denied|captcha").containsMatchIn(doc.text().take(4000))) break
                    if ((doc.selectFirst("article") ?: doc.selectFirst("main"))?.text().orEmpty().length >= 500) {
                        body = followed
                        sourceUrl = candidate
                        break
                    }
                }
            }
            val document = Jsoup.parse(body.first, "", if (pmc.isNotBlank()) Parser.xmlParser() else Parser.htmlParser())
            document.select("script,style,nav,form,header,footer").remove()
            val fullText = (if (pmc.isNotBlank()) document.selectFirst("body")?.text() else (document.selectFirst("article") ?: document.selectFirst("main") ?: document.selectFirst(".ltx_document"))?.text()).orEmpty()
            val challenge = Regex("(?i)verify you are human|enable javascript and cookies|access denied|captcha").containsMatchIn(fullText.take(1000))
            val text = fullText.take(maxTextCharacters.coerceIn(24000, 240000))
            val evidence = AcademicEvidence(
                paper.paperId, paper.versionId + ":" + researchHash(body.first), sourceUrl, System.currentTimeMillis(), researchHash(text), text,
                if (challenge || text.length < 500) {
                    "unavailable"
                } else if (fullText.length > text.length) {
                    "partially_read"
                } else {
                    "read"
                },
                contentLicense = paper.contentLicense, providerReceipts = paper.providerReceipts, canonicalUrl = paper.canonicalUrl
            )
            if (evidence.retrievalStatus in setOf("read", "partially_read")) {
                if (persistent) {
                    save(evidence)
                } else {
                    val runtime = Runtime.getRuntime()
                    check(runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() >= 16L * 1024 * 1024) { "Paused for device resources: temporary memory pressure" }
                    transientEvidence[researchHash("${evidence.paperId}|${evidence.versionId}")] = evidence
                }
            }
            return@withContext recorded(evidence)
        } catch (cancelled: CancellationException) {
            telemetry.record(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(UUID.randomUUID().toString(), paper.sourceProviders.firstOrNull() ?: "academic-reader", "read", System.currentTimeMillis(), outcome = "CANCELLED"))
            throw cancelled
        } catch (failure: Exception) {
            telemetry.record(dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(UUID.randomUUID().toString(), paper.sourceProviders.firstOrNull() ?: "academic-reader", "read", System.currentTimeMillis(), outcome = "FAILED"))
            throw failure
        }
    }

    internal suspend fun citations(doi: String, direction: String, allowed: suspend () -> Boolean, policy: AcademicAccessPolicy = AcademicAccessPolicy()): JsonObject = withContext(Dispatchers.IO) {
        val normalized = SourceIdentity.normalizeDoi(doi.removePrefix("doi:"))
        require(normalized.isNotEmpty() && direction in setOf("references", "citations"))
        require(policy.allows("opencitations")) { "Academic provider disabled" }
        return@withContext permits.getValue("opencitations").withPermit {
            reserve("opencitations", allowed)
            val rows = json.parseToJsonElement(fetch("https://api.opencitations.net/index/v2/$direction/doi:${encode(normalized)}", policy.included, policy.excluded, allowed, "opencitations").first).jsonArray
            buildJsonObject {
                put("provider", "opencitations")
                put("direction", direction)
                put("edges", JsonArray(rows.take(30)))
                put("truncated", rows.size > 30)
                put("relation", "citation_edge_not_claim_support")
            }
        }
    }

    internal suspend fun evidence(paperId: String, versionId: String): AcademicEvidence? = withContext(Dispatchers.IO) {
        val identity = researchHash("$paperId|$versionId")
        transientEvidence[identity]?.let { return@withContext it }
        val file = File(evidenceDirectory, identity + ".json")
        if (!file.isFile || file.length() > 256 * 1024) null else runCatching { json.decodeFromString<AcademicEvidence>(file.readText()) }.getOrNull()
    }
    private suspend fun save(evidence: AcademicEvidence) = snapshotMutex.withLock {
        withContext(Dispatchers.IO) {
            evidenceDirectory.mkdirs()
            check(evidenceDirectory.usableSpace >= evidence.text.toByteArray().size + 8L * 1024 * 1024) { "Paused for device resources: insufficient storage" }
            val file = android.util.AtomicFile(File(evidenceDirectory, researchHash("${evidence.paperId}|${evidence.versionId}") + ".json"))
            val output = file.startWrite()
            try {
                output.write(json.encodeToString(evidence).toByteArray())
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }

    private suspend fun reserve(provider: String, allowed: suspend () -> Boolean) = quotaLocks.computeIfAbsent(provider) { Mutex() }.withLock {
        reserveLocked(provider, allowed)
    }

    private suspend fun reserveLocked(provider: String, allowed: suspend () -> Boolean) {
        check(allowed()) { "permission_denied" }
        val now = System.currentTimeMillis()
        check(!preferences.getBoolean("$provider:authBlocked", false)) { "authentication_blocked_retest_required" }
        val cooldown = preferences.getLong("$provider:cooldown", 0)
        if (cooldown > now) throw AcademicHttpFailure(429, cooldown)
        val spacing = when (provider) {
            "arxiv", "opencitations" -> 3000
            "datacite" -> 5000
            else -> 1000
        }
        delay((preferences.getLong("$provider:last", 0) + spacing - now).coerceIn(0, spacing.toLong()))
        check(allowed()) { "permission_denied" }
        currentCoroutineContext().ensureActive()
        val day = System.currentTimeMillis() / 86_400_000
        val count = if (preferences.getLong("$provider:day", -1) == day) preferences.getInt("$provider:count", 0) else 0
        val maximum = if (provider == "openalex") 50 else 500
        check(count < maximum) { "local_daily_policy" }
        withContext(Dispatchers.IO) { check(preferences.edit().putLong("$provider:day", day).putInt("$provider:count", count + 1).putLong("$provider:last", System.currentTimeMillis()).commit()) }
    }

    private suspend fun fetch(url: String, included: List<String> = emptyList(), excluded: List<String> = emptyList(), allowed: suspend () -> Boolean, provider: String, sourceDocument: Boolean = false): Pair<String, String> {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                check(connectivity.available()) { "network_recovery" }
                if (attempt > 0) reserve(provider, allowed)
                return fetchOnce(url, included, excluded, allowed, provider, sourceDocument)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                connectivity.failed(URI(url).host.orEmpty(), failure)
                if (!connectivity.available()) throw IllegalStateException("network_recovery", failure)
                val retryable = (failure is AcademicHttpFailure && failure.status in 500..599) || failure is java.io.IOException || failure is io.ktor.client.plugins.HttpRequestTimeoutException
                if (!retryable || attempt == 2) throw failure
                last = failure
                delay(250L * (attempt + 1) + kotlin.random.Random.nextLong(100))
            }
        }
        throw checkNotNull(last)
    }

    private suspend fun fetchOnce(url: String, included: List<String> = emptyList(), excluded: List<String> = emptyList(), allowed: suspend () -> Boolean, provider: String, sourceDocument: Boolean = false): Pair<String, String> = global.withPermit {
        var target = url
        repeat(4) {
            check(allowed()) { "permission_denied" }
            currentCoroutineContext().ensureActive()
            val uri = URI(target)
            require(uri.scheme == "https" && uri.host != null && (sourceDocument || uri.host in HOSTS) && uri.userInfo == null && uri.port in setOf(-1, 443) && (!sourceDocument || researchDomainAllowed(target, included, excluded))) { "Academic host is not permitted" }
            val boundBudget = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.tool.AcademicRequestBudgetContext]?.budget
            boundBudget?.reserve("http_request")
            var redirect: String? = null
            val started = System.nanoTime()
            val eventId = UUID.randomUUID().toString()
            var responseCode: Int? = null
            var successful = false
            val result = try {
                http.prepareGet(target) {
                    header("User-Agent", "GPTMobileAcademic/1 (keyless public research)")
                    timeout { requestTimeoutMillis = if (sourceDocument || uri.path.endsWith("fullTextXML")) 30_000 else 20_000 }
                }.execute { response ->
                    responseCode = response.status.value
                    if (response.status.value in 300..399) {
                        redirect = uri.resolve(response.headers["Location"] ?: error("Missing redirect target")).toString()
                        return@execute null
                    }
                    if (response.status.value == 429) {
                        val retry = response.headers["Retry-After"]
                        val duration = retry?.toLongOrNull()?.coerceIn(1, 86400)?.times(1000)
                            ?: runCatching { java.time.ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()?.coerceIn(1000, 86_400_000) ?: 60_000
                        val until = System.currentTimeMillis() + duration
                        withContext(Dispatchers.IO) { preferences.edit().putLong("$provider:cooldown", until).commit() }
                        throw AcademicHttpFailure(429, until)
                    }
                    if (response.status.value == 401) {
                        withContext(Dispatchers.IO) { preferences.edit().putBoolean("$provider:authBlocked", true).commit() }
                    }
                    if (response.status.value !in 200..299) throw AcademicHttpFailure(response.status.value)
                    val stream = response.bodyAsChannel()
                    check(downloadDirectory.mkdirs() || downloadDirectory.isDirectory)
                    check(downloadDirectory.usableSpace >= 8L * 1024 * 1024) { "Insufficient space for academic reading" }
                    val download = File.createTempFile("academic-", ".body", downloadDirectory)
                    try {
                        val buffer = ByteArray(8192)
                        var size = 0
                        download.outputStream().use { output ->
                            while (true) {
                                check(allowed()) { "permission_denied" }
                                val read = stream.readAvailable(buffer)
                                if (read < 0) break
                                require(size + read <= 2 * 1024 * 1024) { "Academic response exceeds 2 MiB" }
                                boundBudget?.chargeBytes(read)
                                output.write(buffer, 0, read)
                                size += read
                            }
                        }
                        successful = true
                        connectivity.success()
                        download.readText(Charsets.UTF_8) to response.headers["Content-Type"].orEmpty()
                    } finally {
                        download.delete()
                    }
                }
            } finally {
                telemetry.record(
                    dev.chungjungsoo.gptmobile.data.research.telemetry.ResearchAttempt(
                        eventId = eventId,
                        engineId = provider,
                        operation = if (sourceDocument) "transport_read" else "transport_lookup",
                        recordedAt = System.currentTimeMillis(),
                        durationMs = (System.nanoTime() - started) / 1_000_000,
                        outcome = if (!currentCoroutineContext().isActive) {
                            "TRANSPORT_CANCELLED"
                        } else if (responseCode?.let { it in 300..399 } == true) {
                            "REDIRECT"
                        } else if (successful) {
                            "TRANSPORT_SUCCESS"
                        } else {
                            "TRANSPORT_FAILED"
                        },
                        httpCode = responseCode
                    )
                )
            }
            if (result != null) return@withPermit result
            target = checkNotNull(redirect)
        }
        error("Too many academic redirects")
    }

    private suspend fun saveCursor(request: AcademicSearchRequest, page: Int, token: String) {
        val key = cursorKey(request, page)
        cursors[key] = token
        val scope = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.tool.AcademicRequestBudgetContext] ?: return
        if (!scope.persistent || scope.runKey.isBlank()) return
        withContext(Dispatchers.IO) {
            check(cursorDirectory.mkdirs() || cursorDirectory.isDirectory)
            val atomic = android.util.AtomicFile(File(cursorDirectory, researchHash("${scope.runKey}:$key") + ".cursor"))
            val output = atomic.startWrite()
            try {
                output.write(
                    buildJsonObject {
                        put("chatId", scope.runKey.substringBefore(':').toIntOrNull() ?: 0)
                        put("key", key)
                        put("token", token)
                    }.toString().toByteArray()
                )
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
    }

    private suspend fun loadCursor(request: AcademicSearchRequest): String? {
        if (request.page == 1) return "*"
        val key = cursorKey(request, request.page)
        cursors[key]?.let { return it }
        val scope = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.tool.AcademicRequestBudgetContext] ?: return null
        if (!scope.persistent || scope.runKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val file = File(cursorDirectory, researchHash("${scope.runKey}:$key") + ".cursor")
            if (!file.isFile || file.length() !in 1..64 * 1024) {
                null
            } else {
                val value = Json.parseToJsonElement(android.util.AtomicFile(file).openRead().use { it.readBytes() }.decodeToString()).jsonObject
                check(value.string("key") == key)
                value.string("token").takeIf { it.length in 1..4096 }
            }
        }
    }

    private fun cursorKey(request: AcademicSearchRequest, page: Int) = researchHash(request.copy(page = 1).toString()) + ":$page"
    private suspend fun searchUrl(provider: String, request: AcademicSearchRequest): String {
        val query = encode(request.query)
        val limit = minOf(request.maxResults, 20)
        val page = request.page
        val offset = Math.multiplyExact(page.toLong() - 1, limit.toLong())
        return when (provider) {
            "openalex" -> "https://api.openalex.org/works?search=$query&per-page=$limit&page=$page"
            "crossref" -> "https://api.crossref.org/works?query.bibliographic=$query&rows=$limit&offset=$offset"
            "arxiv" -> "https://export.arxiv.org/api/query?search_query=all:$query&start=$offset&max_results=$limit"
            "europepmc", "pubmed" -> {
                val cursor = loadCursor(request) ?: error("Cursor unavailable; resume requires the saved provider checkpoint")
                "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=${if (provider == "pubmed") encode("(${request.query}) AND SRC:MED") else query}&format=json&pageSize=$limit&cursorMark=${encode(cursor)}"
            }
            "datacite" -> "https://api.datacite.org/dois?query=$query&page%5Bsize%5D=$limit&page%5Bnumber%5D=$page"
            else -> error("Provider is not in the native allowlist")
        }
    }

    internal fun parseSearch(provider: String, body: String): List<AcademicPaper> {
        if (provider == "arxiv") {
            return Jsoup.parse(body, "", Parser.xmlParser()).select("entry").map { item ->
                val id = item.selectFirst("id")?.text().orEmpty().substringAfter("/abs/")
                val url = "https://arxiv.org/abs/$id"
                AcademicPaper(
                    "arxiv:$id", item.selectFirst("title")?.text().orEmpty(), url, identifiers = mapOf("arxiv" to id),
                    authors = item.select("author > name").map { it.text() }.take(30),
                    year = item.selectFirst("published")?.text()?.take(4)?.toIntOrNull(), publicationType = "preprint", reviewStatus = "preprint", isPreprint = true,
                    openAccessUrl = "https://arxiv.org/html/$id", versionGroup = "arxiv:${id.replace(Regex("v[0-9]+$"), "")}", abstract = item.selectFirst("summary")?.text().orEmpty().take(4000)
                )
            }
        }
        val obj = json.parseToJsonElement(body).jsonObject
        return when (provider) {
            "crossref" -> obj.obj("message").rows("items").map(::crossref)
            "openalex" -> obj.rows("results").map { row ->
                val doi = SourceIdentity.normalizeDoi(row.string("doi"))
                val url = if (doi.isNotEmpty()) "https://doi.org/$doi" else row.string("id")
                AcademicPaper(
                    academicPaperId(doi, url = url),
                    row.string("display_name"),
                    url,
                    doi,
                    year = row["publication_year"]?.jsonPrimitive?.intOrNull,
                    authors = row.rows("authorships").map { it.obj("author").string("display_name") }.filter(String::isNotBlank).take(30),
                    publicationType = row.string("type").ifBlank { "unknown" },
                    isRetracted = row["is_retracted"]?.jsonPrimitive?.booleanOrNull,
                    openAccessUrl = row.obj("open_access").string("oa_url").takeIf(String::isNotBlank)
                )
            }
            "datacite" -> obj.rows("data").map { row ->
                val attrs = row.obj("attributes")
                val doi = SourceIdentity.normalizeDoi(attrs.string("doi"))
                val url = "https://doi.org/$doi"
                AcademicPaper(
                    academicPaperId(doi, url = url),
                    attrs.rows("titles").firstOrNull()?.string("title").orEmpty(),
                    url,
                    doi,
                    year = attrs["publicationYear"]?.jsonPrimitive?.intOrNull,
                    publicationType = attrs.obj("types").string("resourceTypeGeneral").ifBlank { "unknown" }
                )
            }
            else -> obj.obj("resultList").rows("result").map { row ->
                val doi = SourceIdentity.normalizeDoi(row.string("doi"))
                val ids = mapOf("pmid" to row.string("id").takeIf { row.string("source") == "MED" }.orEmpty(), "pmcid" to row.string("pmcid")).filterValues(String::isNotBlank)
                val url = if (ids["pmcid"] != null) "https://pmc.ncbi.nlm.nih.gov/articles/${ids["pmcid"]}/" else "https://europepmc.org/article/${row.string("source")}/${row.string("id")}"
                AcademicPaper(academicPaperId(doi, ids, url), row.string("title"), url, doi, ids, year = row.string("pubYear").toIntOrNull(), openAccessUrl = url.takeIf { row.string("isOpenAccess") == "Y" })
            }
        }.filter { it.title.isNotBlank() && it.paperId != "doi:" }
    }
    private fun crossref(row: JsonObject): AcademicPaper {
        val doi = SourceIdentity.normalizeDoi(row.string("DOI"))
        val url = "https://doi.org/$doi"
        val year = ((row.obj("published")["date-parts"] as? JsonArray)?.firstOrNull() as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
        return AcademicPaper(
            academicPaperId(doi, url = url),
            (row["title"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content.orEmpty(),
            url,
            doi,
            year = year,
            publicationType = row.string("type").ifBlank { "unknown" },
            authors = row.rows("author").map { (it.string("given") + " " + it.string("family")).trim() }.filter(String::isNotBlank).take(30),
            reviewStatus = if (row.string("type") == "posted-content") "preprint" else "unknown",
            isPreprint = true.takeIf { row.string("type") == "posted-content" }
        )
    }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.rows(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object {
        val CORE = listOf("openalex", "crossref", "arxiv", "europepmc", "datacite")
        private val HOSTS = setOf("api.openalex.org", "api.crossref.org", "export.arxiv.org", "arxiv.org", "www.ebi.ac.uk", "api.datacite.org", "api.opencitations.net")
    }
}
