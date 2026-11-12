package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class IssueReport(
    val reportId: String,
    val source: String,
    val version: String,
    val build: Int,
    val commit: String,
    val android: String,
    val sdk: Int,
    val device: String,
    val title: String? = null,
    val description: String? = null,
    val feature: String? = null,
    val errorCode: String? = null,
    val errorType: String? = null,
    val frames: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val context: IssueReportContext? = null
) {
    /** Validate the exact approved bytes before durable delivery; retries keep these bytes and this ID. */
    fun approvedPayload(): String = json.encodeToString(serializer(), this).also {
        require(IssueReportContext.validDiagnosticsIn(json.parseToJsonElement(it).jsonObject["context"])) { "Invalid report diagnostics." }
        require(it.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "The report exceeds the supported size. Shorten the description before sending." }
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 8192
        const val TITLE_LIMIT = 120
        const val DESCRIPTION_LIMIT = 1200
        const val EVENT_LIMIT = 16
        private val versionCharacters = Regex("[^A-Za-z0-9._+\\-]")
        private val deviceCharacters = Regex("[^A-Za-z0-9 ._()+\\-]")
        private val commitPattern = Regex("[a-f0-9]{7,40}")
        private val framePattern = Regex("(?:dev\\.(?:chungjungsoo|melo)\\.gptmobile|android|com\\.android|java|kotlin)\\.[A-Za-z0-9_.$<>]{1,145}:[0-9]{1,6}")
        private val typePattern = Regex("[A-Za-z][A-Za-z0-9_$]{0,63}")
        private val eventPattern = Regex("-[0-9]{1,5}ms [DIWEF] (?:app|chat|voice|gateway|local|tools)(?: [A-Z][A-Z0-9_]{0,63})?")
        internal val json = Json { ignoreUnknownKeys = true }

        fun manual(title: String, description: String, events: List<String>): IssueReport = base("manual", events).copy(
            title = diagnosticMessageForStorage(title.trim()).issueReportPrefix(TITLE_LIMIT),
            description = diagnosticMessageForStorage(description.trim()).issueReportPrefix(DESCRIPTION_LIMIT),
            context = IssueReportContext.fromFields("MANUAL_REPORT")
        )

        fun debug(feature: ReportFeature, code: String, events: List<String>, error: Throwable? = null, context: IssueReportContext? = null): IssueReport {
            val type = error?.javaClass?.simpleName?.takeIf(typePattern::matches)
            val frames = generateSequence(error) { it.cause }.take(4).flatMap { it.stackTrace.asSequence().take(48) }
                .map { "${it.className}.${it.methodName}:${it.lineNumber.coerceAtLeast(0)}" }
                .filter(framePattern::matches).distinct().take(8).toList()
            return base("debug", events).copy(
                feature = feature.name.lowercase(),
                errorCode = IssueReportCodes.select(code, fallbackCode(feature)),
                errorType = type,
                frames = frames,
                context = (context ?: IssueReportContext.fromFields(if (error == null) "CODE_ONLY" else "EXCEPTION")).withException(type, frames)
            )
        }

        private fun base(source: String, events: List<String>) = IssueReport(
            reportId = UUID.randomUUID().toString(),
            source = source,
            version = BuildConfig.VERSION_NAME.replace(versionCharacters, "_").take(48).ifBlank { "unknown" },
            build = BuildConfig.VERSION_CODE,
            commit = BuildConfig.APP_COMMIT.takeIf(commitPattern::matches) ?: "unknown",
            android = Build.VERSION.RELEASE.replace(versionCharacters, "_").take(48).ifBlank { "unknown" },
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".replace(deviceCharacters, "_").trim().take(80).ifBlank { "Unknown" },
            events = events.filter { event ->
                eventPattern.matches(event) && event.split(' ').getOrNull(3)?.let { it in IssueReportCodes.allowed } != false
            }.takeLast(EVENT_LIMIT)
        )
    }
}

internal enum class ReportFeature { APP, CHAT, VOICE, GATEWAY, LOCAL, TOOLS }

internal fun fallbackCode(feature: ReportFeature): String = when (feature) {
    ReportFeature.CHAT -> "CHAT_FAILED"
    ReportFeature.VOICE -> "VOICE_SESSION_FAILED"
    else -> "APP_ERROR"
}

internal fun String.issueReportPrefix(limit: Int): String {
    val end = limit.coerceIn(0, length)
    return take(if (end > 0 && this[end - 1].isHighSurrogate()) end - 1 else end)
}
