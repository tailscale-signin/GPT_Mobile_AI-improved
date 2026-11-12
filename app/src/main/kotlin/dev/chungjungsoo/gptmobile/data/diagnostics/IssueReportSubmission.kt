package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Fixed-origin native delivery. An uncertain receipt is retried with the original report ID and bytes. */
internal class IssueReportSubmission(
    private val origin: String,
    private val client: OkHttpClient = defaultClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface Outcome
    data class Confirmed(val number: Int) : Outcome
    data class Failure(
        val code: String,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val retryNotBeforeMillis: Long? = null,
        val automaticRetry: Boolean = retryable,
        val creationRejected: Boolean = false
    ) : Outcome

    fun send(reportId: String, payload: String, beforeDispatch: () -> Unit = {}): Outcome {
        if (!configured(origin)) return Failure("REPORT_SERVICE_UNCONFIGURED", retryable = true, automaticRetry = false)
        if (payload.encodeToByteArray().size > IssueReport.MAX_PAYLOAD_BYTES) return Failure("REPORT_TOO_LARGE", retryable = false)
        val parsed = runCatching { IssueReport.json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return Failure("REPORT_PAYLOAD_INVALID", retryable = false)
        if (parsed["reportId"] != JsonPrimitive(reportId)) return Failure("REPORT_PAYLOAD_INVALID", retryable = false)
        val context = parsed["context"] as? JsonObject
        if (context != null) {
            val version = (context["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return Failure("INVALID_REPORT", retryable = false)
            contextSupport(version)?.let { return it }
        }
        val request = Request.Builder().url("${origin.trimEnd('/')}/v1/issue-reports")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
        beforeDispatch()
        return try {
            client.newCall(request).execute().use { response ->
                val transient = response.code in 500..599 || response.code in setOf(408, 425, 429)
                val retryAt = retryAfter(response.header("Retry-After"), now())
                val body = response.body
                val bounded = body != null && !body.source().request(MAX_RECEIPT_BYTES + 1)
                val text = if (bounded) body!!.string() else null
                if (!response.isSuccessful) {
                    val code = text?.let { raw ->
                        runCatching { ((IssueReport.json.parseToJsonElement(raw) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull }.getOrNull()
                    }?.takeIf { it in ERROR_CODES } ?: "REPORT_HTTP_${response.code}"
                    return@use Failure(code, transient, response.code, retryAt)
                }
                if (text == null) return@use Failure("REPORT_RECEIPT_UNAVAILABLE", retryable = true, httpStatus = response.code)
                val receipt = IssueReport.json.parseToJsonElement(text) as? JsonObject ?: return@use Failure("REPORT_RECEIPT_INVALID", retryable = true, httpStatus = response.code)
                val identity = receipt["reportId"] as? JsonPrimitive
                val number = (receipt["number"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                val requiresBoundReceipt = context != null || "reportId" in receipt
                when {
                    requiresBoundReceipt && (identity?.isString != true || identity.content != reportId) -> Failure("REPORT_RECEIPT_MISMATCH", retryable = true, httpStatus = response.code)
                    number == null || number <= 0 -> Failure("REPORT_RECEIPT_INVALID", retryable = true, httpStatus = response.code)
                    else -> Confirmed(number)
                }
            }
        } catch (_: IOException) {
            Failure("REPORT_CONNECTION_FAILED", retryable = true)
        } catch (_: SerializationException) {
            Failure("REPORT_RECEIPT_INVALID", retryable = true)
        }
    }

    private fun contextSupport(version: Int): Failure? = try {
        client.newCall(Request.Builder().url("${origin.trimEnd('/')}/health/ready").get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                val retry = response.code in 500..599 || response.code in setOf(408, 425, 429)
                return@use Failure(if (retry) "REPORT_SERVICE_UNAVAILABLE" else "REPORT_SERVICE_UPDATE_REQUIRED", retryable = true, httpStatus = response.code, retryNotBeforeMillis = retryAfter(response.header("Retry-After"), now()), automaticRetry = retry)
            }
            val body = response.body
            if (body == null || body.source().request(MAX_RECEIPT_BYTES + 1)) return@use Failure("REPORT_SERVICE_UNAVAILABLE", retryable = true)
            val ready = IssueReport.json.parseToJsonElement(body.string()) as? JsonObject ?: return@use Failure("REPORT_SERVICE_UNAVAILABLE", retryable = true)
            if (ready["service"] != JsonPrimitive("gpt-mobile-issue-reporter")) return@use Failure("REPORT_SERVICE_UNAVAILABLE", retryable = true)
            val versions = (ready["reportContextVersions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { version -> version.isString }?.intOrNull }
            if (version in versions) null else Failure("REPORT_SERVICE_UPDATE_REQUIRED", retryable = true, automaticRetry = false)
        }
    } catch (_: IOException) {
        Failure("REPORT_SERVICE_UNAVAILABLE", retryable = true)
    } catch (_: SerializationException) {
        Failure("REPORT_SERVICE_UNAVAILABLE", retryable = true)
    }

    companion object {
        private const val MAX_RECEIPT_BYTES = 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(40, TimeUnit.SECONDS).build()
        private val ERROR_CODES = setOf("HTTPS_ORIGIN_REQUIRED", "JSON_REQUIRED", "REPORT_TOO_LARGE", "INVALID_REPORT", "GITHUB_REJECTED", "REPORT_ID_CONFLICT", "RATE_LIMITED", "GITHUB_UNAVAILABLE", "RETRY_LATER", "AWAITING_GITHUB_RECEIPT", "REPORT_STORE_UNAVAILABLE")

        fun configured(origin: String): Boolean {
            val url = origin.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null
        }

        internal fun retryAfter(value: String?, now: Long): Long? {
            value ?: return null
            val seconds = value.trim().toLongOrNull()
            if (seconds != null && seconds >= 0) return runCatching { Math.addExact(now, Math.multiplyExact(seconds, 1000L)) }.getOrElse { Long.MAX_VALUE }
            return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrNull()
        }
    }
}

/** Durable state transitions are shared by the worker and its restart/retry regressions. */
internal object IssueReportDelivery {
    const val MAX_AUTOMATIC_ATTEMPTS = 5
    enum class Result { CONFIRMED, RETRY, FAILED }

    suspend fun attempt(outbox: IssueReportOutbox, reportId: String, send: suspend (String, String) -> IssueReportSubmission.Outcome, now: Long = System.currentTimeMillis(), onSending: suspend () -> Unit = {}): Result {
        val approved = checkNotNull(outbox.load(reportId)) { "Approved report is unavailable" }
        if (approved.state == IssueReportOutbox.State.SENT) return Result.CONFIRMED
        if (approved.state == IssueReportOutbox.State.FAILED) return Result.FAILED
        if (approved.automaticAttempts >= MAX_AUTOMATIC_ATTEMPTS) {
            outbox.failed(reportId, IssueReportSubmission.Failure(approved.failureCode ?: "REPORT_CONFIRMATION_PENDING", retryable = true, httpStatus = approved.httpStatus, retryNotBeforeMillis = approved.retryNotBeforeMillis, automaticRetry = false), automaticRetry = false)
            return Result.FAILED
        }
        if (approved.retryNotBeforeMillis > now) return Result.RETRY
        val sending = outbox.sending(reportId)
        onSending()
        return when (val outcome = send(reportId, sending.payload)) {
            is IssueReportSubmission.Confirmed -> {
                outbox.sent(reportId, outcome.number)
                Result.CONFIRMED
            }
            is IssueReportSubmission.Failure -> {
                val retry = outcome.retryable && outcome.automaticRetry && sending.automaticAttempts < MAX_AUTOMATIC_ATTEMPTS
                outbox.failed(reportId, outcome, retry)
                if (retry) Result.RETRY else Result.FAILED
            }
        }
    }
}
