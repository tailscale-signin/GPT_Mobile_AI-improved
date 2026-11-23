package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class IssueReportAttachment(
    val name: String,
    val mediaType: String,
    val originalBytes: Long,
    val sha256: String,
    val text: String
) {
    fun valid(): Boolean = name.length in 1..100 &&
        name.none(Char::isISOControl) &&
        mediaType in MEDIA_TYPES &&
        originalBytes in 1..MAX_ORIGINAL_BYTES &&
        sha256.matches(Regex("[a-f0-9]{64}")) &&
        text.length in 1..MAX_TEXT_CHARS &&
        text.none { it.isISOControl() && it !in "\n\t" }

    companion object {
        const val MAX_ORIGINAL_BYTES = 4096L
        const val MAX_TEXT_CHARS = 1536
        val MEDIA_TYPES = setOf("text/plain", "text/markdown", "application/json")
    }
}

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
    val context: IssueReportContext? = null,
    val attachment: IssueReportAttachment? = null
) {
    /** Preserve safe catch-site frames when a Flow error crosses the UI boundary without its throwable. */
    fun withCapturedException(fields: Map<String, Any?>): IssueReport {
        fun text(value: Any?): String? = (value as? String) ?: (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text(fields["incident_capture_site"]) !in IssueReportIncidentCapsule.ExceptionCaptureSite.entries.map { it.name } ||
            text(fields["incident_phase_provenance"]) != "CAPTURE_BOUNDARY_ONLY"
        ) {
            return this
        }
        val capturedType = text(fields["exception_report_type"])?.takeIf(typePattern::matches)
        val capturedFrames = (fields["exception_application_frames"] as? JsonArray).orEmpty().take(8)
            .mapNotNull(::text).filter { frame ->
                framePattern.matches(frame) && (frame.startsWith("dev.chungjungsoo.gptmobile.") || frame.startsWith("dev.melo.gptmobile."))
            }.distinct()
        return copy(errorType = errorType ?: capturedType, frames = frames.ifEmpty { capturedFrames })
    }

    /** Validate the exact approved bytes before durable delivery; retries keep these bytes and this ID. */
    fun approvedPayload(): String = json.encodeToString(serializer(), this).also {
        require(attachment?.valid() != false) { "Invalid report attachment." }
        require(IssueReportContext.validDiagnosticsIn(json.parseToJsonElement(it).jsonObject["context"])) { "Invalid report diagnostics." }
        require(it.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "The report exceeds the supported size. Shorten the description before sending." }
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 8192
        const val TITLE_LIMIT = 120
        const val DESCRIPTION_LIMIT = 1200
        const val EVENT_LIMIT = 16
        internal const val FRAMEWORK_EXCEPTION_FRAMES_FEATURE = "FRAMEWORK_EXCEPTION_FRAMES_V1"
        private val versionCharacters = Regex("[^A-Za-z0-9._+\\-]")
        private val deviceCharacters = Regex("[^A-Za-z0-9 ._()+\\-]")
        private val commitPattern = Regex("[a-f0-9]{7,40}")

        // Kotlin value-class methods have mangled hyphen suffixes. Keep the total symbol bound,
        // and permit those suffixes only in the final method segment, never in a class/package.
        private val framePattern = Regex("(?:dev\\.(?:chungjungsoo|melo)\\.gptmobile|android|androidx|com\\.android|java|kotlin|kotlinx\\.coroutines)\\.(?=[A-Za-z0-9_.$<>-]{1,145}:[0-9]{1,6}$)[A-Za-z0-9_.$<>]+(?:-[A-Za-z0-9_$-]+)?:[0-9]{1,6}")
        private val typePattern = Regex("[A-Za-z][A-Za-z0-9_$]{0,63}")
        private val eventPattern = Regex("-[0-9]{1,5}ms [DIWEF] (?:app|chat|voice|gateway|local|tools)(?: [A-Z][A-Z0-9_]{0,63})?")
        internal val json = Json { ignoreUnknownKeys = true }

        internal fun requiresExtendedFrameSupport(frame: String): Boolean = framePattern.matches(frame) &&
            (frame.startsWith("androidx.") || frame.startsWith("kotlinx.coroutines.") || '-' in frame)

        fun manual(title: String, description: String, events: List<String>): IssueReport = base("manual", events).copy(
            title = diagnosticMessageForStorage(title.trim()).issueReportPrefix(TITLE_LIMIT),
            description = diagnosticMessageForStorage(description.trim()).issueReportPrefix(DESCRIPTION_LIMIT),
            context = IssueReportContext.fromFields("MANUAL_REPORT")
        )

        fun debug(feature: ReportFeature, code: String, events: List<String>, error: Throwable? = null, context: IssueReportContext? = null): IssueReport {
            val (type, frames) = exceptionMetadata(error)
            return base("debug", events).copy(
                feature = feature.name.lowercase(),
                errorCode = IssueReportCodes.select(code, fallbackCode(feature)),
                errorType = type,
                frames = frames,
                context = (context ?: IssueReportContext.fromFields(if (error == null) "CODE_ONLY" else "EXCEPTION")).withException(type, frames)
            )
        }

        /** Shared bounded metadata; never reads exception messages, file names or arbitrary stack text. */
        internal fun exceptionMetadata(error: Throwable?): Pair<String?, List<String>> {
            val type = error?.javaClass?.simpleName?.takeIf(typePattern::matches)
            val frames = generateSequence(error) { it.cause }.take(4).flatMap { it.stackTrace.asSequence().take(48) }
                .map { "${it.className}.${it.methodName}:${it.lineNumber.coerceAtLeast(0)}" }
                .filter(framePattern::matches).distinct().toList()
            val application = frames.filter { it.startsWith("dev.chungjungsoo.gptmobile.") || it.startsWith("dev.melo.gptmobile.") }
            // Reserve room for app frames when Android's draw stack is deep.
            val selected = (frames.take(4) + application).distinct().take(8)
            return type to (selected + frames).distinct().take(8)
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

internal object IssueReportAttachmentCapture {
    fun fromBytes(name: String, mediaType: String, declaredBytes: Long?, bytes: ByteArray): IssueReportAttachment {
        require(mediaType in IssueReportAttachment.MEDIA_TYPES && bytes.isNotEmpty() && bytes.size <= IssueReportAttachment.MAX_ORIGINAL_BYTES)
        require(declaredBytes == null || declaredBytes == bytes.size.toLong())
        val safeName = name.filterNot(Char::isISOControl).trim().take(100).ifBlank { "report-evidence.txt" }
        val decoded = bytes.toString(Charsets.UTF_8)
        require(decoded.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Attachment must be UTF-8 text." }
        val redacted = diagnosticMessageForStorage(decoded).trim().issueReportPrefix(IssueReportAttachment.MAX_TEXT_CHARS)
        require(redacted.isNotBlank())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return IssueReportAttachment(safeName, mediaType, bytes.size.toLong(), digest, redacted).also { require(it.valid()) }
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
