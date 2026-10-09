package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListing
import dev.chungjungsoo.gptmobile.data.airbnb.AirbnbListings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** Public origins only. No login/session state, remote proxy, or app credential interceptors. */
@Singleton
class PublicAirbnbClient internal constructor(private val http: HttpClient) {
    @Inject constructor() : this(
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout)
        }
    )
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    internal suspend fun listings(action: String, arguments: JsonObject): List<AirbnbListing> = mutex.withLock {
        delay((1500 - (System.currentTimeMillis() - lastRequestAt)).coerceIn(0, 1500))
        lastRequestAt = System.currentTimeMillis()
        parse(read(url(action, arguments)), arguments)
    }

    internal fun url(action: String, arguments: JsonObject): String {
        fun text(key: String) = (arguments[key] as? JsonPrimitive)?.content.orEmpty()
        val base = "https://www.airbnb.com".toHttpUrl().newBuilder()
        if (action == "details") {
            require(text("id").matches(Regex("[0-9]{1,30}")))
            base.addPathSegment("rooms").addPathSegment(text("id"))
        } else {
            require(text("location").isNotBlank() && text("location").length <= 200)
            val slug = text("location").replace(Regex(",\\s*"), "--").replace(Regex("\\s+"), "-")
            base.addPathSegment("s").addPathSegment(slug).addPathSegment("homes")
        }
        for (key in listOf("checkin", "checkout", "adults", "children", "infants", "pets")) {
            if (key in arguments) {
                val parameter = if (action == "details") {
                    when (key) {
                        "checkin" -> "check_in"
                        "checkout" -> "check_out"
                        else -> key
                    }
                } else {
                    key
                }
                base.addQueryParameter(parameter, text(key))
            }
        }
        return base.build().toString()
    }

    internal fun parse(html: String, arguments: JsonObject): List<AirbnbListing> {
        require(html.length <= MAX_BYTES)
        val document = Jsoup.parse(html)
        val states = document.select("script[id^=data-deferred-state], script[type=application/ld+json]").take(8).mapNotNull { script ->
            runCatching { Json.parseToJsonElement(script.data()) }.getOrNull()
        }
        var remaining = 10_000
        fun find(value: JsonElement, key: String, depth: Int = 0): JsonElement? {
            if (depth > 18 || --remaining < 0) return null
            return when (value) {
                is JsonObject -> value[key] ?: value.values.firstNotNullOfOrNull { find(it, key, depth + 1) }
                is JsonArray -> value.take(100).firstNotNullOfOrNull { find(it, key, depth + 1) }
                else -> null
            }
        }
        val search = states.firstNotNullOfOrNull { find(it, "searchResults") } as? JsonArray
        if (search != null) {
            val results = search.take(30).filterIsInstance<JsonObject>().mapNotNull { item ->
                val listing = item["demandStayListing"] as? JsonObject ?: item["listing"] as? JsonObject ?: item
                val raw = (listing["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val id = raw.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: runCatching {
                    Base64.getDecoder().decode(raw).decodeToString().substringAfter(':')
                }.getOrNull()?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: return@mapNotNull null
                JsonObject(item + mapOf("id" to JsonPrimitive(id), "url" to JsonPrimitive("https://www.airbnb.com/rooms/$id")))
            }
            return AirbnbListings.normalize(JsonObject(mapOf("searchResults" to JsonArray(results))), arguments)
        }
        val id = (arguments["id"] as? JsonPrimitive)?.content ?: error("Airbnb changed its search page or blocked public results.")
        remaining = 10_000
        val details = states.firstNotNullOfOrNull { find(it, "stayProductDetailPage") } as? JsonObject
        val structured = states.filterIsInstance<JsonObject>().firstOrNull { it["@type"] != null }
        check(details != null || structured != null) { "Airbnb listing details are unavailable." }
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: (structured?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: "Airbnb listing $id"
        val payload = JsonObject(
            (details ?: structured ?: JsonObject(emptyMap())) + buildJsonObject {
                put("id", id)
                put("title", title)
                document.selectFirst("meta[property=og:image]")?.attr("content")?.let { put("photos", JsonArray(listOf(JsonPrimitive(it)))) }
            }
        )
        return AirbnbListings.normalize(payload, arguments)
    }

    private suspend fun read(url: String): String = http.prepareGet(url) {
        header(HttpHeaders.Accept, "text/html")
        header(HttpHeaders.AcceptLanguage, "en-CA,en;q=0.9")
        header(HttpHeaders.UserAgent, "GPTMobile-PublicBrowsing/1.0")
        timeout {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 10_000
        }
    }.execute { response ->
        check(response.status.value == 200) { "Airbnb public page unavailable (${response.status.value})." }
        check((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= MAX_BYTES)
        val output = ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }

    private companion object {
        const val MAX_BYTES = 4 * 1_048_576
    }
}
