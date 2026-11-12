package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Public GitHub is fixed. A durable dispatch marker permits only read-only reconciliation after an uncertain write. */
internal class IssueReportGitHubSubmission(
    private val client: OkHttpClient = defaultClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun send(entry: IssueReportOutbox.Entry, token: String, beforeDispatch: () -> Unit): IssueReportSubmission.Outcome {
        val route = entry.route?.takeIf { it.kind == IssueReportRoute.Kind.GITHUB && it.valid() }
            ?: return IssueReportSubmission.Failure("REPORT_DESTINATION_REQUIRED", retryable = true, automaticRetry = false)
        val report = runCatching { IssueReport.json.decodeFromString(IssueReport.serializer(), entry.payload) }.getOrNull()
            ?: return IssueReportSubmission.Failure("REPORT_PAYLOAD_INVALID", retryable = false)
        if (report.reportId != entry.reportId || entry.payload.encodeToByteArray().size > IssueReport.MAX_PAYLOAD_BYTES) return IssueReportSubmission.Failure("REPORT_PAYLOAD_INVALID", retryable = false)
        val expected = render(report, entry.payload)
        if (entry.mayHaveBeenSubmitted) return reconcile(entry.reportId, route, expected, token)
        val request = request("$API_REPOSITORY/issues", token).post(expected.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        // Persist first. A crash between this point and socket dispatch is deliberately
        // conservative: a later attempt searches, and never guesses that POST is safe.
        beforeDispatch()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val rejected = response.code in setOf(400, 401, 403, 404, 410, 422, 429)
                    return@use IssueReportSubmission.Failure(
                        code = when (response.code) {
                            401 -> "REPORT_GITHUB_SIGN_IN_REQUIRED"
                            403, 404 -> "REPORT_GITHUB_PERMISSION_REQUIRED"
                            429 -> "REPORT_GITHUB_RATE_LIMITED"
                            in 400..499 -> if (rejected) "REPORT_GITHUB_REJECTED" else "REPORT_GITHUB_CONFIRMATION_PENDING"
                            else -> "REPORT_GITHUB_CONFIRMATION_PENDING"
                        },
                        retryable = response.code !in setOf(400, 410, 422),
                        httpStatus = response.code,
                        retryNotBeforeMillis = IssueReportSubmission.retryAfter(response.header("Retry-After"), now()),
                        automaticRetry = !rejected || response.code == 429,
                        creationRejected = rejected
                    )
                }
                val value = readBounded(response.body) as? JsonObject
                receipt(value, route, expected)?.let { IssueReportSubmission.Confirmed(it) }
                    ?: IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true, httpStatus = response.code)
            }
        } catch (_: IOException) {
            IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true)
        } catch (_: SerializationException) {
            IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true)
        }
    }

    private fun reconcile(reportId: String, route: IssueReportRoute, expected: JsonObject, token: String): IssueReportSubmission.Outcome = try {
        val url = "https://api.github.com/search/issues".toHttpUrl().newBuilder()
            .addQueryParameter("q", "repo:$REPOSITORY is:issue in:body \"$reportId\"")
            .addQueryParameter("per_page", "100").build()
        client.newCall(request(url.toString(), token).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                return@use IssueReportSubmission.Failure(
                    if (response.code == 401) "REPORT_GITHUB_SIGN_IN_REQUIRED" else "REPORT_GITHUB_CONFIRMATION_PENDING",
                    retryable = true,
                    httpStatus = response.code,
                    retryNotBeforeMillis = IssueReportSubmission.retryAfter(response.header("Retry-After"), now()),
                    automaticRetry = response.code !in setOf(401, 403, 404)
                )
            }
            val result = readBounded(response.body) as? JsonObject
            val items = result?.get("items") as? JsonArray
            val matches = items.orEmpty().mapNotNull { receipt(it as? JsonObject, route, expected) }.distinct()
            when (matches.size) {
                1 -> IssueReportSubmission.Confirmed(matches.single())
                0 -> IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true)
                else -> IssueReportSubmission.Failure("REPORT_GITHUB_RECEIPT_CONFLICT", retryable = false)
            }
            // Search is eventually consistent. Empty, incomplete and failed results
            // never authorize another create request, including after manual Retry.
        }
    } catch (_: IOException) {
        IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true)
    } catch (_: SerializationException) {
        IssueReportSubmission.Failure("REPORT_GITHUB_CONFIRMATION_PENDING", retryable = true)
    }

    private fun request(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun readBounded(body: okhttp3.ResponseBody?): kotlinx.serialization.json.JsonElement? {
        if (body == null || body.source().request(MAX_RESPONSE_BYTES + 1)) return null
        return IssueReport.json.parseToJsonElement(body.string())
    }

    private fun receipt(value: JsonObject?, route: IssueReportRoute, expected: JsonObject): Int? {
        value ?: return null
        if ("pull_request" in value || value["body"] != expected["body"]) return null
        val author = value["user"] as? JsonObject ?: return null
        val authorId = (author["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull
        if (authorId != route.accountId) return null
        // Search cannot redirect confirmation to another repository or issue.
        val number = (value["number"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return null
        if (value["url"] != JsonPrimitive("$API_REPOSITORY/issues/$number")) return null
        return number
    }

    companion object {
        internal const val REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
        private const val API_REPOSITORY = "https://api.github.com/repos/$REPOSITORY"
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(40, TimeUnit.SECONDS).build()

        internal fun render(report: IssueReport, approvedPayload: String): JsonObject {
            val digest = MessageDigest.getInstance("SHA-256").digest(approvedPayload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val title = (report.title?.takeIf(String::isNotBlank) ?: "[Debug] ${report.feature} · ${report.errorCode}")
                .replace(Regex("\\s+"), " ").replace('@', '＠').take(160)
            // Indented JSON makes all user text, HTML, mentions and forged markers inert.
            // Its exact bytes are the approved report; the marker is added outside it.
            val body = "### Approved app report\n\n" + approvedPayload.lines().joinToString("\n") { "    $it" } +
                "\n\n<!-- gpt-mobile-report:${report.reportId} -->\n<!-- approved-payload-sha256:$digest -->"
            return buildJsonObject {
                put("title", title)
                put("body", body)
            }
        }
    }
}
