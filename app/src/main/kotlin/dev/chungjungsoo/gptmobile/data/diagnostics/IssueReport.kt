package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val events: List<String> = emptyList()
) {
    companion object {
        const val TITLE_LIMIT = 120
        const val DESCRIPTION_LIMIT = 1200
        const val EVENT_LIMIT = 16
        private val versionCharacters = Regex("[^A-Za-z0-9._+\\-]")
        private val deviceCharacters = Regex("[^A-Za-z0-9 ._()+\\-]")
        private val commitPattern = Regex("[a-f0-9]{7,40}")
        private val framePattern = Regex("(?:dev\\.(?:chungjungsoo|melo)\\.gptmobile|android|com\\.android|java|kotlin)\\.[A-Za-z0-9_.$<>]{1,145}:[0-9]{1,6}")
        private val typePattern = Regex("[A-Za-z][A-Za-z0-9_$]{0,63}")
        internal val json = Json { ignoreUnknownKeys = true }

        fun manual(title: String, description: String, events: List<String>): IssueReport = base("manual", events).copy(
            title = diagnosticMessageForStorage(title.trim()).take(TITLE_LIMIT),
            description = diagnosticMessageForStorage(description.trim()).take(DESCRIPTION_LIMIT)
        )

        fun debug(feature: ReportFeature, code: String, events: List<String>, error: Throwable? = null): IssueReport = base("debug", events).copy(
            feature = feature.name.lowercase(),
            errorCode = code,
            errorType = error?.javaClass?.simpleName?.takeIf(typePattern::matches),
            frames = generateSequence(error) { it.cause }.take(4).flatMap { it.stackTrace.asSequence().take(48) }
                .map { "${it.className}.${it.methodName}:${it.lineNumber.coerceAtLeast(0)}" }
                .filter(framePattern::matches).distinct().take(8).toList()
        )

        private fun base(source: String, events: List<String>) = IssueReport(
            reportId = UUID.randomUUID().toString(),
            source = source,
            version = BuildConfig.VERSION_NAME.replace(versionCharacters, "_").take(48).ifBlank { "unknown" },
            build = BuildConfig.VERSION_CODE,
            commit = BuildConfig.APP_COMMIT.takeIf(commitPattern::matches) ?: "unknown",
            android = Build.VERSION.RELEASE.replace(versionCharacters, "_").take(48).ifBlank { "unknown" },
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".replace(deviceCharacters, "_").trim().take(80).ifBlank { "Unknown" },
            events = events.takeLast(EVENT_LIMIT)
        )
    }
}

internal enum class ReportFeature { APP, CHAT, VOICE, GATEWAY, LOCAL, TOOLS }
