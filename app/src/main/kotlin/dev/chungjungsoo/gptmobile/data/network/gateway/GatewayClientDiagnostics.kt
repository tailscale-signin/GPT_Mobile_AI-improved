package dev.chungjungsoo.gptmobile.data.network.gateway

import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceUnavailableException
import dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class GatewayMetadataAcknowledgment { ACCEPTED, NOT_REQUESTED, REJECTED_BEFORE_PROCESSING, NOT_CONFIRMED, CAPTURE_DISABLED, INVALID_METADATA }

/** Opt-in allowlisted event metadata. Raw app logs, prompts, device identifiers and credentials are never uploaded. */
internal object GatewayClientDiagnostics {
    const val CLIENT_HEADER = "X-Gateway-Client-Diagnostics"
    private val lock = Any()
    private data class Instance(val id: String, val revision: Long)
    private val instances = linkedMapOf<String, Instance>()
    private data class ProfileBinding(val route: String, val revision: Long)
    private val profiles = linkedMapOf<String, ProfileBinding>()
    private var revisionClock = 0L
    private val instancePattern = Regex("[a-f0-9]{32}")
    private val roles = setOf("primary", "github", "review", "synthesis", "diagnostic")
    private val diagnosticHeaders = setOf(CLIENT_HEADER.lowercase(), "x-gateway-diagnostic-session", "x-gateway-diagnostic-started", "traceparent", "tracestate", "baggage")
    private data class Attempt(val started: Long, val route: String, val method: String, val trace: String, val request: String, val responseReceived: Boolean = false)
    private val dispatched = linkedMapOf<String, Attempt>()
    private val observed = linkedMapOf<String, Attempt>()
    private val tracePattern = Regex("00-([a-f0-9]{32})-([a-f0-9]{16})-[a-f0-9]{2}")
    val diagnosticStatus get() = GatewayDiagnosticBridge.status

    fun initialize(directory: java.io.File) = GatewayDiagnosticBridge.initialize(directory)
    fun appEvent(entry: dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry) = GatewayDiagnosticBridge.appEvent(entry)
    fun pause() {
        synchronized(lock) {
            dispatched.clear()
            observed.clear()
        }
        GatewayDiagnosticBridge.pause()
    }
    fun clear() = GatewayDiagnosticBridge.clear()
    fun refreshDiagnostics(config: ProviderRequestConfig) = GatewayDiagnosticBridge.refresh(config)
    suspend fun diagnosticStatusFor(config: ProviderRequestConfig) = GatewayDiagnosticBridge.statusFor(config)
    suspend fun diagnosticPage(config: ProviderRequestConfig? = null, offset: Int = 0, failuresOnly: Boolean = true, traceId: String? = null, stages: Set<String> = emptySet()) = GatewayDiagnosticBridge.page(config, offset, failuresOnly, traceId, stages)
    suspend fun exportDiagnostics(destination: java.io.File, config: ProviderRequestConfig? = null) = GatewayDiagnosticBridge.export(config, destination)

    /** Shared capability identity; two profiles on one authenticated route need only one probe. */
    fun routeIdentity(config: ProviderRequestConfig): String {
        val headers = config.extraHeaders.entries.filterNot { it.key.startsWith("X-Gateway-", true) || it.key.lowercase() in diagnosticHeaders || it.key.equals("X-Request-ID", true) }
            .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
        return researchHash("${config.apiUrl.trim().trimEnd('/')}|${researchHash(config.token.orEmpty())}|${researchHash(headers)}")
    }

    fun runtimeEpoch(config: ProviderRequestConfig): String = synchronized(lock) { instances[routeIdentity(config)]?.id ?: "unobserved" }

    fun capabilityEpoch(config: ProviderRequestConfig): Long = synchronized(lock) { instances[routeIdentity(config)]?.revision ?: revisionClock }

    /** A selected profile can observe its bound route's restart without resolving a stored credential. */
    fun profileCapabilityEpoch(profileUid: String, apiUrl: String): Long = synchronized(lock) {
        profiles[profileKey(profileUid, apiUrl)]?.let { binding ->
            maxOf(binding.revision, instances[binding.route]?.revision ?: revisionClock)
        } ?: revisionClock
    }

    private fun profileKey(profileUid: String, apiUrl: String) = researchHash("$profileUid|${apiUrl.trim().trimEnd('/')}")

    private fun rememberRoute(config: ProviderRequestConfig): String {
        val route = routeIdentity(config)
        config.profileUid?.takeIf(String::isNotBlank)?.let { profileUid ->
            synchronized(lock) {
                val key = profileKey(profileUid, config.apiUrl)
                val previous = profiles[key]
                if (previous?.route != route) {
                    if (profiles.size >= 128 && key !in profiles) {
                        profiles.remove(profiles.keys.first())
                        revisionClock++
                    }
                    if (previous != null) revisionClock++
                    profiles[key] = ProfileBinding(route, revisionClock)
                }
            }
        }
        return route
    }

    fun observeInstance(config: ProviderRequestConfig, instance: String?) {
        val route = rememberRoute(config)
        if (instance == null || !instancePattern.matches(instance)) return
        synchronized(lock) {
            if (instances.size >= 128 && route !in instances) {
                instances.remove(instances.keys.first())
                revisionClock++
            }
            val previous = instances[route]
            if (previous != null && previous.id != instance) revisionClock++
            val revision = if (previous?.id == instance) previous.revision else revisionClock
            instances[route] = Instance(instance, revision)
        }
    }

    fun withHeaders(config: ProviderRequestConfig, model: String, knownGateway: Boolean = false): ProviderRequestConfig {
        rememberRoute(config)
        val allowedRoute = knownGateway || config.providerType == "LLAMA" || config.extraHeaders.keys.any { it.startsWith("X-Gateway-", true) }
        val without = withoutDiagnosticHeaders(config.extraHeaders)
        if (!captureAllowed || !allowedRoute) return config.copy(extraHeaders = without)
        GatewayDiagnosticBridge.remember(config)
        val metadata = metadata(config, model, Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE, LocalServiceHealth.networkEpoch)
        val trace = java.util.UUID.randomUUID().toString().replace("-", "")
        return config.copy(extraHeaders = without + mapOf(CLIENT_HEADER to metadata, "traceparent" to "00-$trace-${trace.takeLast(16)}-01", "X-Request-ID" to trace))
    }

    private val captureAllowed: Boolean get() = LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive

    /** Recheck at each physical dispatch; a request prepared before Debug was disabled must not upload metadata. */
    fun requestHeaders(config: ProviderRequestConfig, route: String = "other", method: String = "GET"): Map<String, String> {
        if (!captureAllowed) return withoutDiagnosticHeaders(config.extraHeaders)
        if (config.extraHeaders.keys.none { it.equals(CLIENT_HEADER, true) }) return config.extraHeaders
        val prior = config.extraHeaders.entries.firstOrNull { it.key.equals("traceparent", true) }?.value
        val trace = tracePattern.matchEntire(prior.orEmpty())?.groupValues?.get(1)?.takeIf { it.any { char -> char != '0' } }
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
        val span = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val request = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Request-ID", true) }?.value ?: trace
        val attempt = Attempt(System.nanoTime(), route.takeIf { it in GatewayDiagnosticEvent.routes } ?: "other", method.takeIf { it in GatewayDiagnosticEvent.methods } ?: "GET", trace, researchHash(request))
        synchronized(lock) {
            if (dispatched.size >= 256) dispatched.remove(dispatched.keys.first())
            dispatched[span] = attempt
        }
        GatewayDiagnosticBridge.event(
            config,
            buildJsonObject {
                put("stage", "request")
                put("direction", "client_to_gateway")
                put("severity", "info")
                put("trace_id", trace)
                put("span_id", span)
                put("request_id", attempt.request)
                put("dispatch_state", "dispatched")
                put("category", "transport")
                put("retry_class", "unknown")
                put("bounded_attributes", attributes(config, attempt))
            }
        )
        return config.extraHeaders.filterKeys { !it.equals("traceparent", true) && !it.equals("X-Request-ID", true) } + GatewayDiagnosticBridge.sessionHeaders(config) + mapOf("traceparent" to "00-$trace-$span-01", "X-Request-ID" to request)
    }

    private fun withoutDiagnosticHeaders(headers: Map<String, String>): Map<String, String> {
        val owned = headers.keys.any { it.equals(CLIENT_HEADER, true) }
        return headers.filterKeys { it.lowercase() !in diagnosticHeaders && !(owned && it.equals("X-Request-ID", true)) }
    }

    private fun attributes(config: ProviderRequestConfig, attempt: Attempt): JsonObject = buildJsonObject {
        put("endpoint_digest", routeIdentity(config))
        put("route", attempt.route)
        put("method", attempt.method)
        put("response_headers_received", attempt.responseReceived)
    }

    /** One terminal client-side record per physical failed attempt, including failures before headers. */
    fun failed(config: ProviderRequestConfig, requestHeaders: Map<String, String>, failure: Throwable, route: String = "other", safeRead: Boolean = false) {
        val fields = failureFields(config, requestHeaders, failure, route, safeRead) ?: return
        GatewayDiagnosticBridge.event(config, fields)
        AppLogRecorder.record("Gateway", "CLIENT_EXCHANGE · route=${routeIdentity(config)} · transport=${fields.diagnosticText("error_code")} · requestDigest=${fields.diagnosticText("request_id")} · responseHeadersReceived=${(fields["bounded_attributes"] as? JsonObject)?.get("response_headers_received")}")
    }

    internal fun failureFields(config: ProviderRequestConfig, requestHeaders: Map<String, String>, failure: Throwable, route: String = "other", safeRead: Boolean = false): JsonObject? {
        val trace = tracePattern.matchEntire(requestHeaders.entries.firstOrNull { it.key.equals("traceparent", true) }?.value.orEmpty()) ?: return null
        val span = trace.groupValues[2]
        val attempt = synchronized(lock) { dispatched.remove(span) ?: observed.remove(span) } ?: return null
        if (!captureAllowed || attempt.trace != trace.groupValues[1]) return null
        val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
        val cancelled = failure is CancellationException
        if (!cancelled && causes.any { it is ProviderHttpFailure || it is io.ktor.client.plugins.ResponseException || it is LocalServiceUnavailableException }) return null
        val timeout = causes.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException || it.javaClass.simpleName in setOf("ConnectTimeoutException", "HttpRequestTimeoutException", "SocketTimeoutException") }
        val transport = timeout || causes.any { it is IOException }
        val securityFailure = causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }
        // HTTP rejection and malformed responses keep their existing response/validation
        // evidence. They are not a new socket failure or a reason to retry a write.
        if (!cancelled && !transport) return null
        val actual = if (attempt.route == "other" && route in GatewayDiagnosticEvent.routes) attempt.copy(route = route) else attempt
        return GatewayDiagnosticEvent.fields(
            buildJsonObject {
                put("stage", if (cancelled) "cancellation" else "transport")
                put("direction", "client_to_gateway")
                put("severity", if (cancelled) "info" else "error")
                put(
                    "error_code",
                    if (cancelled) {
                        "CANCELLED"
                    } else if (timeout) {
                        "TIMEOUT"
                    } else {
                        "TRANSPORT_FAILURE"
                    }
                )
                put("category", if (cancelled) "cancellation" else "transport")
                put("trace_id", actual.trace)
                put("parent_span_id", span)
                put("span_id", java.util.UUID.randomUUID().toString().replace("-", "").take(16))
                put("request_id", actual.request)
                put("dispatch_state", "unknown")
                put(
                    "retry_class",
                    if (cancelled || securityFailure) {
                        "none"
                    } else if (safeRead) {
                        "safe_read"
                    } else {
                        "reconcile"
                    }
                )
                put("local_duration_ms", ((System.nanoTime() - actual.started) / 1_000_000).coerceIn(0, GatewayDiagnosticEvent.CAPTURE_DURATION_MILLIS))
                put("bounded_attributes", attributes(config, actual))
            }
        )
    }

    fun finished(requestHeaders: Map<String, String>) {
        val span = tracePattern.matchEntire(requestHeaders.entries.firstOrNull { it.key.equals("traceparent", true) }?.value.orEmpty())?.groupValues?.get(2) ?: return
        synchronized(lock) {
            dispatched.remove(span)
            observed.remove(span)
        }
    }

    private fun canonicalRole(role: String): String = when {
        role in roles -> role
        role.startsWith("github-review") -> "review"
        role.startsWith("github-") -> "github"
        else -> "other"
    }

    internal fun metadata(config: ProviderRequestConfig, model: String, androidSdk: Int, appVersionCode: Int, networkEpoch: Long): String = buildJsonObject {
        put("schemaVersion", 1)
        put("androidSdk", androidSdk.coerceAtLeast(0))
        put("appVersionCode", appVersionCode.coerceAtLeast(0))
        put("networkEpoch", networkEpoch.coerceAtLeast(0))
        put("profileDigest", researchHash(config.profileUid.orEmpty()))
        if (model.isNotBlank()) put("modelDigest", researchHash(model))
        put("requestRole", canonicalRole(config.requestRole))
    }.toString()

    /** Runs before HTTP error handling as restart/auth failures are useful evidence too. */
    fun observe(config: ProviderRequestConfig, status: Int, header: (String) -> String?) {
        val instance = header("X-Gateway-Instance-ID")
        observeInstance(config, instance)
        val protocol = header("X-Gateway-Debug-Protocol")?.toIntOrNull()
        if (!captureAllowed || protocol !in setOf(1, 2)) return
        val debugEnabled = header("X-Gateway-Debug-Enabled") == "true"
        GatewayDiagnosticBridge.remember(config, protocol, debugEnabled)
        val fields = responseFields(config, status, header)
        GatewayDiagnosticBridge.event(config, fields)
        val attributes = fields["bounded_attributes"] as JsonObject
        AppLogRecorder.record("Gateway", "CLIENT_EXCHANGE · route=${routeIdentity(config)} · instance=${runtimeEpoch(config)} · http=$status · gatewayDebug=$debugEnabled · clientMetadataAcknowledgment=${attributes.diagnosticText("client_metadata_acknowledgment")} · admissionCode=${attributes.diagnosticText("gateway_admission_code") ?: "not_reported"} · requestDigest=${fields.diagnosticText("operation_id") ?: fields.diagnosticText("request_id") ?: "unavailable"} · httpRequestId=${header("X-Gateway-HTTP-Request-ID")?.takeIf(instancePattern::matches) ?: "unavailable"}")
    }

    /** A missing acknowledgement is unknown; HTTP rejection alone proves no metadata-processing outcome. */
    internal fun responseFields(config: ProviderRequestConfig, status: Int, header: (String) -> String?): JsonObject {
        val requestDigest = header("X-Gateway-Request-ID")?.takeIf { it.length <= 128 }?.let(::researchHash) ?: "unavailable"
        val physicalRequestId = header("X-Gateway-HTTP-Request-ID")?.takeIf(instancePattern::matches)
        val trace = tracePattern.matchEntire(header("traceparent").orEmpty())
        val attempt = header("X-Gateway-Client-Span-ID")?.let { span ->
            synchronized(lock) {
                dispatched.remove(span)?.copy(responseReceived = true)?.also {
                    if (observed.size >= 256) observed.remove(observed.keys.first())
                    observed[span] = it
                }
            }
        }
        val admissionCode = header("X-Gateway-Admission-Error")?.takeIf { it in GatewayDiagnosticEvent.admissionCodes }
        val requested = config.extraHeaders.keys.any { it.equals(CLIENT_HEADER, true) }
        val admissionRejected = when (admissionCode) {
            "duplicate_admission_header" -> status == 400
            "transport_identity_required" -> status == 401
            "unknown_job" -> status == 404
            "remote_surface_not_admitted", "scope_not_granted", "engineering_scope_required", "tool_scope_required_for_runtime", "local_admin_required", "invalid_host", "invalid_origin", "browser_csrf_required" -> status == 403
            else -> false
        }
        val acknowledgment = when {
            header("X-Gateway-Client-Diagnostics-Accepted") == "1" -> GatewayMetadataAcknowledgment.ACCEPTED
            !requested -> GatewayMetadataAcknowledgment.NOT_REQUESTED
            admissionRejected -> GatewayMetadataAcknowledgment.REJECTED_BEFORE_PROCESSING
            header("X-Gateway-Client-Diagnostics-Status") == "capture_disabled" -> GatewayMetadataAcknowledgment.CAPTURE_DISABLED
            header("X-Gateway-Client-Diagnostics-Status") == "invalid_metadata" -> GatewayMetadataAcknowledgment.INVALID_METADATA
            else -> GatewayMetadataAcknowledgment.NOT_CONFIRMED
        }
        return GatewayDiagnosticEvent.fields(
            buildJsonObject {
                put("stage", "response")
                put("direction", "gateway_to_client")
                put("severity", if (status >= 400) "error" else "info")
                put("error_code", if (status >= 400) "HTTP_ERROR" else "NONE")
                put("category", "transport")
                put("dispatch_state", "response_received")
                put("retry_class", "unknown")
                put("request_id", physicalRequestId ?: requestDigest)
                if (physicalRequestId != null) put("operation_id", requestDigest)
                if (trace != null) {
                    put("trace_id", trace.groupValues[1])
                    put("parent_span_id", trace.groupValues[2])
                    put("span_id", java.util.UUID.randomUUID().toString().replace("-", "").take(16))
                }
                if (attempt != null) put("local_duration_ms", ((System.nanoTime() - attempt.started) / 1_000_000).coerceIn(0, GatewayDiagnosticEvent.CAPTURE_DURATION_MILLIS))
                put(
                    "bounded_attributes",
                    buildJsonObject {
                        attempt?.let { attributes(config, it).forEach { (name, value) -> put(name, value) } }
                        put("status", status)
                        put("response_headers_received", true)
                        put("client_metadata_acknowledgment", acknowledgment.name)
                        admissionCode?.let { put("gateway_admission_code", it) }
                    }
                )
            }
        )
    }
}
