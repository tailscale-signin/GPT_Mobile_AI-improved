package dev.chungjungsoo.gptmobile.data.agent.tool

import android.text.Html
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ReadUrlTool(
    private val dns: Dns = Dns.SYSTEM,
    private val allowAddress: (InetAddress) -> Boolean = { false },
    private val htmlToText: (String) -> String = ::androidHtmlToText,
    private val outputLimitBytes: Int = MAX_OUTPUT_BYTES,
    private val deniedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    private val documentContext: android.content.Context? = null,
    private val unavailableUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
) : AgentTool {

    fun withOutputLimit(bytes: Int): ReadUrlTool = ReadUrlTool(dns, allowAddress, htmlToText, bytes.coerceIn(0, MAX_OUTPUT_BYTES), deniedHosts, documentContext, unavailableUrls)

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "read_url",
        description = "Read a public HTTP or HTTPS URL and return bounded plain text.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    for (name in listOf("includeDomains", "excludeDomains")) {
                        put(
                            name,
                            buildJsonObject {
                                put("type", "array")
                                put("maxItems", 20)
                                put("items", buildJsonObject { put("type", "string") })
                            }
                        )
                    }
                    put(
                        "extractDocument",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Follow a document extraction handoff. Downloads a complete document to temporary disk, at most 16 MiB; reads at most 100 PDF pages or 80,000 characters. Partial binary files are never parsed.")
                        }
                    )
                    put(
                        "includeLinks",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Return structured text and page links for bounded crawling.")
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("url"))))
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val url = parseUrl(arguments)?.toASCIIString() ?: return error(callId, "Read URL failed: url must be a valid HTTP(S) URL without userinfo or fragment.")
        // Bound coordination memory; serialize identical URLs across parallel profiles.
        return requestLocks[(url.hashCode() and Int.MAX_VALUE) % requestLocks.size].withLock {
            val until = missingUntil[url]
            if (until != null && until > System.currentTimeMillis()) {
                error(callId, "Read URL failed: this exact source recently returned 404/405/410. Search for another URL; repeating it will not recover the page.")
            } else {
                missingUntil.remove(url)
                executeOnce(callId, arguments)
            }
        }
    }

    private suspend fun executeOnce(callId: String, arguments: JsonObject): AgentToolResult {
        val start = parseUrl(arguments) ?: return error(callId, "Read URL failed: url must be a valid HTTP(S) URL without userinfo or fragment.")
        if (start.toASCIIString() in unavailableUrls) return error(callId, "Read URL failed: this source was already unavailable in this turn. Choose another source.")
        if (!dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.available(start.host)) return error(callId, "DNS_RECOVERY: waiting for network recovery after DNS failures on multiple services.")
        return try {
            kotlinx.coroutines.withTimeoutOrNull(25_000) {
                dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkGate.run("read_url") {
                    read(
                        callId,
                        start,
                        (arguments["includeLinks"] as? JsonPrimitive)?.booleanOrNull == true,
                        (arguments["includeDomains"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() },
                        (arguments["excludeDomains"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() },
                        (arguments["extractDocument"] as? JsonPrimitive)?.booleanOrNull == true
                    )
                }
            } ?: error(callId, "Read URL failed: page deadline reached. Choose another source.")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred) {
            error(callId, "JAVA_HEAP_PRESSURE: waiting for Java heap capacity.")
        } catch (exception: ReadUrlException) {
            if (exception.message.orEmpty().let { it.contains("redirect") || it.contains("binary") || it.contains("HTTP 404") || it.contains("HTTP 405") }) unavailableUrls += start.toASCIIString()
            if (exception.message in setOf("HTTP 404", "HTTP 405", "HTTP 410")) {
                val now = System.currentTimeMillis()
                if (missingUntil.size >= 256) missingUntil.entries.removeAll { it.value <= now }
                if (missingUntil.size < 256) missingUntil[start.toASCIIString()] = now + 300_000
            }
            recordFailure(start, "protocol", exception.javaClass.simpleName, exception.message)
            error(callId, "Read URL failed: ${exception.message}.")
        } catch (exception: UnknownHostException) {
            recordFailure(start, "dns", exception.javaClass.simpleName, null)
            error(callId, "Read URL failed: DNS lookup could not resolve the hostname. Check the URL and network connection.")
        } catch (exception: java.net.SocketTimeoutException) {
            recordFailure(start, "timeout", exception.javaClass.simpleName, null)
            error(callId, "Read URL failed: the website timed out. Try another source.")
        } catch (exception: Exception) {
            recordFailure(start, "transport", exception.javaClass.simpleName, null)
            error(callId, "Read URL failed: request failed (${exception.javaClass.simpleName}).")
        }
    }

    private suspend fun read(callId: String, start: URI, includeLinks: Boolean, includeDomains: List<String>, excludeDomains: List<String>, extractDocument: Boolean): AgentToolResult {
        val outputCap = minOf(outputLimitBytes, kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ToolOutputAllowance]?.bytes ?: outputLimitBytes)
        var current = start
        var redirects = 0
        val seen = mutableSetOf(current.toASCIIString())
        while (true) {
            if (!dev.chungjungsoo.gptmobile.data.research.researchDomainAllowed(current.toString(), includeDomains, excludeDomains)) throw ReadUrlException("redirect or source outside allowed domain scope")
            val authority = current.host.lowercase(Locale.ROOT).removePrefix("www.") + ":" + current.port
            if (authority in deniedHosts) return error(callId, "Read URL failed: this host denied access earlier in this turn. Choose another source.")
            val now = System.currentTimeMillis()
            rateLimitUntil[authority]?.let { until ->
                if (until > now) return error(callId, "Read URL failed: this host is rate-limited for another ${(until - now + 999) / 1000} seconds. Choose another source.")
                rateLimitUntil.remove(authority, until)
            }
            val research = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ResearchAcquisitionContext]
            check(research?.budget?.tryChargeRequests(1) != false) { "User request limit reached" }
            val request = request(current)
            try {
                val response = request.response
                val status = response.status.value
                if (status in REDIRECT_STATUSES) {
                    if (redirects >= MAX_REDIRECTS) throw ReadUrlException("too many redirects")
                    val location = response.headers[HttpHeaders.Location]?.trim().orEmpty()
                    if (location.isBlank()) throw ReadUrlException("missing Location header")
                    val next = parseResolvedRedirect(current, location)
                    if (!seen.add(next.toASCIIString())) throw ReadUrlException("redirect loop")
                    current = next
                    redirects += 1
                    continue
                }
                if (status == 401 || status == 403) deniedHosts += authority
                if (status == 429) {
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]
                    val seconds = retryAfter?.toLongOrNull()?.coerceIn(1, 86400)
                    val date = runCatching { java.time.ZonedDateTime.parse(retryAfter, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    if (rateLimitUntil.size >= 128) rateLimitUntil.entries.removeAll { it.value <= now }
                    if (rateLimitUntil.size < 128) rateLimitUntil[authority] = date?.coerceIn(now + 1000, now + 86_400_000) ?: (now + (seconds ?: 60) * 1000)
                }
                if (!response.status.isSuccess()) throw ReadUrlException("HTTP $status")
                val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
                val documentExtension = when (contentType.substringBefore(';').trim().lowercase()) {
                    "application/pdf" -> "pdf"
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                    else -> null
                }
                if (!isTextContent(contentType) && (documentExtension == null || documentContext == null)) throw ReadUrlException("unsupported binary content ($contentType); use an enabled document reader or attach the document")
                val boundedBody = if (extractDocument && documentExtension != null && documentContext != null) BoundedBody(byteArrayOf(), false) else readBounded(response, current.host.orEmpty(), MAX_BODY_BYTES)
                val rawText = if (documentExtension == null) boundedBody.bytes.toString(contentType.charsetOrUtf8()) else ""
                val document = if (documentExtension != null && documentContext != null) {
                    if (boundedBody.truncated) return documentExtractionHandoff(callId, current, includeDomains, excludeDomains)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val file = java.io.File.createTempFile("research-", ".$documentExtension", documentContext.cacheDir)
                        try {
                            if (extractDocument) {
                                val channel = response.bodyAsChannel()
                                file.outputStream().use { output ->
                                    copyCompleteDocument(
                                        read = { buffer -> channel.readAvailable(buffer, 0, buffer.size) },
                                        write = { buffer, size ->
                                            check(research?.budget?.tryChargeDownloadedBytes(size) != false) { "User download limit reached" }
                                            output.write(buffer, 0, size)
                                        }
                                    )
                                }
                            } else {
                                file.writeBytes(boundedBody.bytes)
                            }
                            kotlinx.coroutines.runInterruptible {
                                dev.chungjungsoo.gptmobile.util.DocumentTextExtractor.extract(documentContext, file, contentType.substringBefore(';'))
                            }
                        } finally {
                            file.delete()
                        }
                    }
                } else {
                    null
                }
                val text = document?.text ?: if (isHtmlContent(contentType)) htmlToText(rawText) else rawText
                val normalizedText = normalizeWhitespace(text)
                val chunks = research?.retainBody?.invoke(normalizedText).orEmpty()
                val plainText = truncateUtf8(normalizedText, outputCap)
                val documentResult = dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult(
                    url = current.toString(),
                    content = plainText,
                    truncated = document?.note != null || boundedBody.truncated || plainText != normalizedText,
                    bodyChunks = chunks,
                    links = if (includeLinks) researchLinks(rawText, current.toString()).filter { it.length <= 2048 }.take(32) else emptyList(),
                    status = if (document?.note != null || boundedBody.truncated || plainText != normalizedText) "partially_read" else "read",
                    citationMetadata = if (isHtmlContent(contentType)) capturedCitationMetadata(rawText) else emptyMap()
                )
                val content = if (includeLinks || extractDocument) {
                    ToolResultContent.Json(documentResult.toJson())
                } else {
                    val partial = boundedBody.truncated || document?.note != null || plainText != normalizedText
                    val note = "\n[Source excerpt truncated; the full document was not examined.]"
                    ToolResultContent.Text(if (partial) truncateUtf8(plainText, (outputCap - note.toByteArray().size).coerceAtLeast(0)) + note.takeIf { outputCap >= it.toByteArray().size }.orEmpty() else plainText)
                }
                return AgentToolResult(
                    callId = callId,
                    content = content,
                    isError = false,
                    retainedContent = ToolResultContent.Json(documentResult.toJson()).takeUnless { content is ToolResultContent.Json }
                )
            } finally {
                request.response.bodyAsChannel().cancel(null)
                request.client.close()
            }
        }
    }

    private suspend fun request(uri: URI): ReadUrlRequest {
        val gate = dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared
        val lease = gate.acquire(uri.host) ?: throw ReadUrlException("DNS_RECOVERY: waiting for network recovery")
        try {
            val pinnedAddresses = resolveSafe(uri.host)
            val pinnedDns = Dns { hostname ->
                if (!hostname.equals(uri.host, ignoreCase = true)) throw UnknownHostException(hostname)
                pinnedAddresses
            }
            // ponytail: per-call client isolates DNS pins; pool per agent run only if profiling shows setup cost matters.
            val client = HttpClient(OkHttp) {
                install(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsHttpPlugin)
                followRedirects = false
                install(io.ktor.client.plugins.HttpTimeout) {
                    connectTimeoutMillis = 8_000
                    socketTimeoutMillis = 12_000
                    requestTimeoutMillis = 20_000
                }
                engine {
                    dns = pinnedDns
                    clientCacheSize = 0
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                        proxy(Proxy.NO_PROXY)
                    }
                }
            }
            return try {
                ReadUrlRequest(client, client.get(uri.toASCIIString()).also { gate.success(uri.host) })
            } catch (exception: Exception) {
                client.close()
                throw exception
            }
        } catch (failure: Exception) {
            gate.failed(uri.host, failure)
            throw failure
        } finally {
            gate.release(lease)
        }
    }

    private fun resolveSafe(host: String): List<InetAddress> {
        val addresses = try {
            dns.lookup(host)
        } catch (exception: UnknownHostException) {
            throw UnknownHostException("DNS lookup failed")
        }
        if (addresses.isEmpty()) throw UnknownHostException("DNS lookup failed")
        if (addresses.any { !allowAddress(it) && SpecialUseAddress.isSpecialUse(it) }) {
            throw ReadUrlException("unsafe DNS address rejected")
        }
        return addresses
    }

    private suspend fun readBounded(response: HttpResponse, host: String, bodyLimit: Int = MAX_BODY_BYTES): BoundedBody {
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        var truncated = contentLength != null && contentLength > bodyLimit
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < bodyLimit) {
            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, bodyLimit - output.size()))
            if (read == -1) break
            if (read == 0) {
                yield()
                continue
            }
            check(kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.ResearchAcquisitionContext]?.budget?.tryChargeDownloadedBytes(read) != false) { "User download limit reached" }
            output.write(buffer, 0, read)
        }
        if (output.size() >= bodyLimit) truncated = true
        if (truncated) {
            AppLogRecorder.record(
                "ReadUrl",
                "Read bounded oversized source · host=${host.take(160)} · retainedBytes=${output.size()} · maxBytes=$bodyLimit",
                "W"
            )
        }
        return BoundedBody(output.toByteArray(), truncated)
    }

    private fun parseUrl(arguments: JsonObject): URI? {
        if (arguments.keys.any { it !in setOf("url", "includeLinks", "includeDomains", "excludeDomains", "extractDocument") }) return null
        if ("extractDocument" in arguments && (arguments["extractDocument"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) return null
        if ("includeLinks" in arguments && (arguments["includeLinks"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) return null
        for (name in listOf("includeDomains", "excludeDomains")) {
            if (name !in arguments) continue
            val domains = arguments[name] as? JsonArray ?: return null
            if (domains.size > 20 || domains.any { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content?.matches(Regex("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")) != true }) return null
        }
        val value = (arguments["url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { URI(value) }.getOrNull()?.takeIf { it.isAllowedUrl() }
    }

    private fun String.charsetOrUtf8(): Charset = runCatching { ContentType.parse(this).charset() }
        .getOrNull()
        ?: StandardCharsets.UTF_8

    private fun parseResolvedRedirect(base: URI, location: String): URI {
        val next = base.toString().toHttpUrlOrNull()?.resolve(location)?.newBuilder()?.fragment(null)?.build()?.toUri()
            ?: throw ReadUrlException("malformed redirect URL")
        if (!next.isAllowedUrl()) throw ReadUrlException("malformed redirect URL")
        return next
    }

    private fun URI.isAllowedUrl(): Boolean {
        val scheme = scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (host.isNullOrBlank()) return false
        if (rawUserInfo != null || rawFragment != null) return false
        return try {
            toURL()
            true
        } catch (ignored: Exception) {
            false
        }
    }

    private fun recordFailure(uri: URI, category: String, exceptionType: String, detail: String?) {
        AppLogRecorder.record(
            "ReadUrl",
            "Read failed · host=${uri.host.orEmpty().take(160)} · category=$category · exception=$exceptionType" +
                detail?.takeIf { it.isNotBlank() }?.let { " · detail=${it.take(240)}" }.orEmpty(),
            "W"
        )
    }

    private fun error(callId: String, message: String): AgentToolResult = AgentToolResult(
        callId = callId,
        content = ToolResultContent.Text(message.take(ERROR_BYTES)),
        isError = true,
        errorCode = readerFailureCode(message),
        retainedContent = ToolResultContent.Json(
            dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult(
                url = "",
                status = readerFailureStatus(message),
                failureCode = readerFailureCode(message),
                failureReason = message.take(ERROR_BYTES)
            ).toJson()
        )
    )

    private fun readerFailureCode(message: String): String = when {
        "JAVA_HEAP_PRESSURE" in message -> "JAVA_HEAP_PRESSURE"
        "DNS_RECOVERY" in message -> "DNS_RECOVERY"
        "DNS" in message -> "DNS_FAILED"
        "redirect" in message.lowercase() -> "REDIRECT_FAILED"
        "403" in message || "401" in message || "denied access" in message -> "SOURCE_ACCESS_BLOCKED"
        "rate-limit" in message || "429" in message -> "RATE_LIMITED"
        "timed out" in message || "deadline" in message -> "TIMEOUT"
        else -> "READER_FAILED"
    }

    private fun readerFailureStatus(message: String): String = when (readerFailureCode(message)) {
        "JAVA_HEAP_PRESSURE" -> "waiting_capacity"
        "DNS_RECOVERY", "DNS_FAILED" -> "offline"
        "SOURCE_ACCESS_BLOCKED" -> "blocked"
        "RATE_LIMITED" -> "rate_limited"
        "REDIRECT_FAILED" -> "redirect_failed"
        "TIMEOUT" -> "timeout"
        else -> "unavailable"
    }

    private companion object {
        private val requestLocks = Array(32) { Mutex() }
        private val missingUntil = ConcurrentHashMap<String, Long>()
        val rateLimitUntil = ConcurrentHashMap<String, Long>()
        const val MAX_BODY_BYTES = 1024 * 1024 // 1 MB bounded buffer to prevent OOM
        const val MAX_OUTPUT_BYTES = 64 * 1024 // 64 KB output limit aligned with tests
        const val ERROR_BYTES = 2000
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

internal object SpecialUseAddress {
    fun isSpecialUse(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isSpecialUseIpv4(address.address)
            is Inet6Address -> isSpecialUseIpv6(address.address)
            else -> false
        }
    }

    private fun isSpecialUseIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            (a == 192 && b == 0 && c == 0) ||
            (a == 192 && b == 0 && c == 2) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 224
    }

    private fun isSpecialUseIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0xfc ||
            first == 0xfd ||
            (first == 0x20 && second == 0x01 && bytes[2].toInt() == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8) ||
            isTeredo(bytes) ||
            isLocalNat64(bytes) ||
            nat64Ipv4(bytes)?.let(::isSpecialUseIpv4) == true ||
            sixToFourIpv4(bytes)?.let(::isSpecialUseIpv4) == true ||
            ipv4Mapped(bytes)?.let(::isSpecialUseIpv4) == true ||
            ipv4Compatible(bytes)?.let(::isSpecialUseIpv4) == true
    }

    private fun isTeredo(bytes: ByteArray): Boolean = bytes.matchesPrefix(0x20, 0x01, 0x00, 0x00)

    private fun isLocalNat64(bytes: ByteArray): Boolean = bytes.matchesPrefix(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01)

    private fun nat64Ipv4(bytes: ByteArray): ByteArray? {
        if (!bytes.matchesPrefix(0x00, 0x64, 0xff, 0x9b) || bytes.sliceArray(4..11).any { it.toInt() != 0 }) return null
        return bytes.copyOfRange(12, 16)
    }

    private fun sixToFourIpv4(bytes: ByteArray): ByteArray? {
        if (!bytes.matchesPrefix(0x20, 0x02)) return null
        return bytes.copyOfRange(2, 6)
    }

    private fun ipv4Mapped(bytes: ByteArray): ByteArray? {
        if (bytes.size != 16) return null
        if (bytes.take(10).any { it.toInt() != 0 }) return null
        if ((bytes[10].toInt() and 0xff) != 0xff || (bytes[11].toInt() and 0xff) != 0xff) return null
        return bytes.copyOfRange(12, 16)
    }

    private fun ipv4Compatible(bytes: ByteArray): ByteArray? {
        if (bytes.size != 16 || bytes.take(12).any { it.toInt() != 0 }) return null
        return bytes.copyOfRange(12, 16)
    }

    private fun ByteArray.matchesPrefix(vararg prefix: Int): Boolean = prefix.indices.all { index ->
        (this[index].toInt() and 0xff) == prefix[index]
    }
}

private class ReadUrlException(message: String) : Exception(message)

private data class ReadUrlRequest(
    val client: HttpClient,
    val response: HttpResponse
)

private data class BoundedBody(
    val bytes: ByteArray,
    val truncated: Boolean
)

/** Head metadata belongs to this permitted response, never a separately fetched provider. */
private fun capturedCitationMetadata(html: String): Map<String, String> {
    val prefix = truncateUtf8(html, 64 * 1024)
    val end = Regex("(?i)</head\\s*>").find(prefix)?.range?.first ?: prefix.length
    val head = org.jsoup.Jsoup.parse(prefix.take(end)).head()
    val allowed = setOf("citation_doi", "citation_pmid", "citation_pmcid", "citation_article_type")
    return head.select("meta[name][content]").asSequence().take(128)
        .map { it.attr("name").lowercase(Locale.ROOT) to truncateUtf8(it.attr("content").trim(), 512) }
        .filter { it.first in allowed && it.second.isNotBlank() }.groupBy({ it.first }, { it.second })
        .mapNotNull { (key, values) -> values.distinct().singleOrNull()?.let { key to it } }.toMap()
}

private fun androidHtmlToText(html: String): String {
    val articleBodies = Regex(
        """"articleBody"\s*:\s*"((?:\\.|[^"\\])*)"""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { match ->
        match.groupValues[1]
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
    }.filter { it.isNotBlank() }.take(3).toList()

    // Strip non-visible blocks one tag at a time. A single regex with a numeric
    // back-reference was fragile under the Android/JVM regex implementations and
    // could leak consent-manager JavaScript into the extracted article text.
    var visibleHtml = html
    listOf("script", "style", "noscript", "template", "svg", "nav", "header", "footer", "aside").forEach { tag ->
        visibleHtml = Regex(
            """<$tag\b[^>]*>.*?</$tag\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).replace(visibleHtml, " ")
    }
    // Prefer article/main content so navigation and consent text cannot consume the output cap.
    val article = Regex("""<(?:article|main)\b[^>]*>(.*?)</(?:article|main)\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(visibleHtml)?.groupValues?.get(1)
    if (!article.isNullOrBlank()) visibleHtml = article
    val visible = runCatching {
        Html.fromHtml(visibleHtml, Html.FROM_HTML_MODE_LEGACY).toString()
    }.getOrElse {
        // Local JVM tests and non-Android execution paths do not provide the
        // android.text.Html logging/runtime stack. Keep URL reading functional
        // with a conservative tag-strip fallback after active blocks are removed.
        visibleHtml
            .replace(Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
    }
    return (articleBodies + visible).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
}

private fun isTextContent(contentType: String): Boolean {
    val type = contentType.substringBefore(";").trim().lowercase(Locale.US)
    return type.startsWith("text/") ||
        type == "application/json" ||
        type == "application/xml" ||
        type == "application/xhtml+xml" ||
        type.endsWith("+json") ||
        type.endsWith("+xml")
}

private fun isHtmlContent(contentType: String): Boolean {
    val type = contentType.substringBefore(";").trim().lowercase(Locale.US)
    return type == "text/html" || type == "application/xhtml+xml"
}

private fun normalizeWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

private fun truncateUtf8(value: String, maxBytes: Int): String {
    val result = StringBuilder()
    var index = 0
    var bytes = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val chunk = String(Character.toChars(codePoint))
        val chunkBytes = chunk.toByteArray(StandardCharsets.UTF_8).size
        if (bytes + chunkBytes > maxBytes) break
        result.append(chunk)
        bytes += chunkBytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

/** EOF is required: an oversized binary prefix is never handed to a document parser. */
internal suspend fun copyCompleteDocument(read: suspend (ByteArray) -> Int, write: (ByteArray, Int) -> Unit, maxBytes: Int = 16 * 1024 * 1024) {
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) return
        if (count == 0) {
            yield()
            continue
        }
        require(count <= maxBytes - total) { "Document exceeds the bounded 16 MiB extraction limit. Attach it or choose another source." }
        write(buffer, count)
        total += count
    }
}

private fun documentExtractionHandoff(callId: String, url: URI, includeDomains: List<String>, excludeDomains: List<String>) = AgentToolResult(
    callId,
    ToolResultContent.Json(
        buildJsonObject {
            put("outcome", "DOCUMENT_EXTRACTION_REQUIRED")
            put("coverage", "unread")
            put("message", "The document exceeds the inline reader limit. Resume with the bounded extraction arguments below; no full-text evidence has been verified.")
            put("nextTool", "read_url")
            put(
                "arguments",
                buildJsonObject {
                    put("url", url.toString())
                    put("extractDocument", true)
                    put("includeDomains", JsonArray(includeDomains.map(::JsonPrimitive)))
                    put("excludeDomains", JsonArray(excludeDomains.map(::JsonPrimitive)))
                }
            )
        }
    ),
    isError = true
)
