package dev.chungjungsoo.gptmobile.data.marketplace

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class NativeMarketplaceFailure(message: String, val status: Int? = null) : Exception(message)

/** Fixed provider origins and provider-specific authentication; never arbitrary model URLs. */
object NativeMarketplaceRequests {
    fun build(entry: GitHubMarketplacePackage, operation: String, args: JsonObject, config: NativeProviderConfiguration): Request {
        NativeMarketplaceCatalog.validate(entry.provider, operation, args)
        val key = config.apiKey
        require(!NativeMarketplaceCatalog.requiresKey(entry) || NativeMarketplaceCatalog.validKey(key)) { "API key required." }
        val count = config.installation.maxResults.coerceIn(1, 10).toString()
        fun text(name: String) = (args.getValue(name) as JsonPrimitive).content
        val query = args["query"]?.let { (it as JsonPrimitive).content }.orEmpty()
        val headers = linkedMapOf("Accept" to "application/json", "User-Agent" to "GPTMobile-AndroidMarketplace/1.0")
        var body: RequestBody? = null
        val parameters = linkedMapOf<String, String>()
        val endpoint = when (entry.provider) {
            "refuge" -> {
                parameters.putAll(mapOf("lat" to text("latitude"), "lng" to text("longitude"), "per_page" to count))
                "https://www.refugerestrooms.org/api/v1/restrooms/by_location.json"
            }
            "toronto" -> {
                if (operation == "datasets") {
                    parameters.putAll(mapOf("q" to query, "rows" to count))
                } else {
                    parameters.putAll(mapOf("resource_id" to text("resource_id"), "limit" to count))
                }
                "https://ckan0.cf.opendata.inter.prod-toronto.ca/api/3/action/" + if (operation == "datasets") "package_search" else "datastore_search"
            }
            "nominatim", "overpass" -> {
                require(NativeMarketplaceCatalog.validEndpoint(config.installation.endpoint)) { "Managed/self-hosted HTTPS endpoint required." }
                if (entry.provider == "nominatim") {
                    parameters.putAll(mapOf("q" to query, "format" to "jsonv2", "limit" to minOf(count.toInt(), 5).toString()))
                } else {
                    val lat = text("latitude")
                    val lon = text("longitude")
                    val overpass = "[out:json][timeout:15];(nwr(around:1500,$lat,$lon)[amenity=toilets];nwr(around:1500,$lat,$lon)[toilets=yes];);out center tags 50;"
                    body = FormBody.Builder().add("data", overpass).build()
                }
                config.installation.endpoint
            }
            "ticketmaster" -> {
                parameters.putAll(mapOf("apikey" to key, "keyword" to query, "city" to text("location"), "size" to count))
                "https://app.ticketmaster.com/discovery/v2/events.json"
            }
            "tomtom" -> {
                parameters.putAll(mapOf("key" to key, "limit" to count))
                "https://api.tomtom.com/search/2/search/".toHttpUrl().newBuilder().addPathSegment("$query.json").build().toString()
            }
            "yelp" -> {
                headers["Authorization"] = "Bearer $key"
                parameters.putAll(mapOf("term" to query, "location" to text("location"), "limit" to count))
                "https://api.yelp.com/v3/businesses/search"
            }
            "eventbrite" -> {
                headers["Authorization"] = "Bearer $key"
                "https://www.eventbriteapi.com/v3/organizations/${text("organization_id")}/events/"
            }
            "arcgis" -> {
                parameters.putAll(mapOf("SingleLine" to query, "f" to "json", "maxLocations" to minOf(count.toInt(), 5).toString(), "forStorage" to "false", "token" to key))
                "https://geocode-api.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates"
            }
            "openrouteservice" -> {
                headers["Authorization"] = key
                body = buildJsonObject {
                    put("coordinates", JsonArray(listOf(JsonArray(listOf(args.getValue("longitude"), args.getValue("latitude"))), JsonArray(listOf(args.getValue("end_longitude"), args.getValue("end_latitude"))))))
                }.toString().toRequestBody("application/json".toMediaType())
                "https://api.openrouteservice.org/v2/directions/foot-walking/geojson"
            }
            "google-places" -> {
                headers["X-Goog-Api-Key"] = key
                headers["X-Goog-FieldMask"] = "places.id,places.displayName,places.formattedAddress,places.location,places.googleMapsUri,places.attributions"
                body = buildJsonObject {
                    put("textQuery", query)
                    put("pageSize", count.toInt())
                }.toString().toRequestBody("application/json".toMediaType())
                "https://places.googleapis.com/v1/places:searchText"
            }
            "foursquare" -> {
                headers["Authorization"] = "Bearer $key"
                headers["X-Places-Api-Version"] = "2025-06-17"
                parameters.putAll(mapOf("query" to query, "near" to text("location"), "limit" to count))
                "https://places-api.foursquare.com/places/search"
            }
            else -> error("Unsupported provider.")
        }
        val url = endpoint.toHttpUrl().newBuilder().apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        return Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            body?.let { post(it) }
        }.build()
    }
}

/** Dedicated client has no diagnostic logger or app/provider credential interceptor. */
@Singleton
class NativeMarketplaceClient @Inject constructor() {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun fetch(request: Request): JsonElement = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(NativeMarketplaceFailure("Provider request failed. Check connectivity and plugin settings."))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) {
                            throw NativeMarketplaceFailure(
                                when (it.code) {
                                    401, 403 -> "Provider authentication or access failed. Check the plugin API key."
                                    402 -> "Provider payment or credits required. Check the account."
                                    429 -> "Provider rate limit reached. Wait before trying again."
                                    else -> "Provider request failed (HTTP ${it.code})."
                                },
                                it.code
                            )
                        }
                        val body = it.body ?: throw NativeMarketplaceFailure("Provider returned an empty response.")
                        if (body.contentLength() > MAX_BYTES) throw NativeMarketplaceFailure("Provider response exceeds the size limit.")
                        val bytes = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_BYTES) throw NativeMarketplaceFailure("Provider response exceeds the size limit.")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        Json.parseToJsonElement(bytes.decodeToString())
                    }
                    if (result is JsonObject && (result["error"] != null || result["errors"] != null || (result["success"] as? JsonPrimitive)?.booleanOrNull == false)) {
                        throw NativeMarketplaceFailure("Provider returned an error. Check credentials and service access.")
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error as? NativeMarketplaceFailure ?: NativeMarketplaceFailure("Provider returned an invalid response. Check plugin settings."))
                }
            }
        })
    }

    private companion object {
        const val MAX_BYTES = 2_000_000
    }
}
