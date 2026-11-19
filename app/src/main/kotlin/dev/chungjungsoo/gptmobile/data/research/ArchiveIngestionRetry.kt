package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The outbox retains the source while failed indexing backs off across progress saves and restarts. */
private val terminalIngestionStates = setOf("IDENTITY_REVIEW_REQUIRED", "OWNER_UNAVAILABLE", "ARCHIVE_SUPERSEDED", "REQUIRES_REVIEW")

internal fun ResearchOutbox.awaitsAutomaticRetry(): Boolean =
    Json.parseToJsonElement(operation).jsonObject.text("state") !in terminalIngestionStates

internal fun ResearchOutbox.retryDue(now: Long): Boolean =
    Json.parseToJsonElement(operation).jsonObject.let { payload ->
        payload.text("state") !in terminalIngestionStates &&
            (payload.text("retryAfterMs").toLongOrNull()?.let { now >= it } ?: true)
    }

internal fun ResearchOutbox.failedIngestion(now: Long, reason: String): ResearchOutbox {
    val payload = Json.parseToJsonElement(operation).jsonObject
    val attempt = ((payload.text("retryAttempt").toIntOrNull() ?: 0).coerceIn(0, 16) + 1).coerceAtMost(16)
    val delay = (30_000L * (1L shl (attempt - 1).coerceAtMost(7))).coerceAtMost(60 * 60_000L)
    val state = when {
        reason == "IDENTITY_CONFLICT_REQUIRES_REVIEW" -> "IDENTITY_REVIEW_REQUIRED"
        reason == "ARCHIVE_OWNER_UNAVAILABLE" -> "OWNER_UNAVAILABLE"
        reason == "ARCHIVE_SUPERSEDED" -> "ARCHIVE_SUPERSEDED"
        attempt >= 16 -> "REQUIRES_REVIEW"
        else -> "RETRY_PENDING"
    }
    return copy(
        operation = JsonObject(
            payload + mapOf(
                "retryAttempt" to JsonPrimitive(attempt),
                "retryAfterMs" to JsonPrimitive(now + delay),
                "lastFailure" to JsonPrimitive(reason),
                "state" to JsonPrimitive(state)
            )
        ).toString()
    )
}

internal fun archiveIngestionFailure(error: Exception): String = when {
    error.message == "Conversation was deleted" || error.message == "Examination owner is unavailable" -> "ARCHIVE_OWNER_UNAVAILABLE"
    error.message == "Archive was cleared; start a new import" || error.message == "This work was removed from the archive" -> "ARCHIVE_SUPERSEDED"
    error.message?.contains("Conflicting strong research identifiers") == true -> "IDENTITY_CONFLICT_REQUIRES_REVIEW"
    error.message?.contains("integrity", ignoreCase = true) == true -> "BODY_INTEGRITY_FAILED"
    error is java.io.FileNotFoundException || error.message == "Missing retained source body" -> "RETAINED_BODY_UNAVAILABLE"
    error is android.database.sqlite.SQLiteFullException -> "DEVICE_STORAGE_FULL"
    error is android.database.sqlite.SQLiteException -> "ARCHIVE_DATABASE_ERROR"
    else -> "ARCHIVE_WRITE_FAILED"
}

/** Closed diagnostic codes: no raw exception message or source text is exported. */
internal fun archiveIngestionCauseCode(error: Exception): String = when (error.message) {
    "Conversation was deleted" -> "CHAT_DELETED"
    "Examination owner is unavailable" -> "EXAMINATION_OWNER_MISSING"
    "Archive was cleared; start a new import" -> "ARCHIVE_GENERATION_CHANGED"
    "This work was removed from the archive" -> "WORK_TOMBSTONED"
    else -> when (error) {
        is android.database.sqlite.SQLiteFullException -> "DATABASE_FULL"
        is android.database.sqlite.SQLiteException -> "DATABASE_FAILURE"
        is java.io.FileNotFoundException -> "RETAINED_FILE_MISSING"
        is IllegalStateException -> "UNCLASSIFIED_ILLEGAL_STATE"
        else -> "UNCLASSIFIED_EXCEPTION"
    }
}

/** Closed phase and cause survive a restart without leaking a stack trace. */
internal fun archiveIngestionReceiptReason(error: Exception, phase: String): String =
    "${archiveIngestionFailure(error)}:${error.javaClass.simpleName}:$phase:${archiveIngestionCauseCode(error)}"

internal fun archiveIngestionFailureSite(error: Exception): String =
    error.stackTrace.firstOrNull { it.className.startsWith("dev.chungjungsoo.gptmobile.data.research.") }?.let { frame ->
        val owner = frame.className.substringAfterLast('.').filter { it.isLetterOrDigit() || it == '_' }.take(64)
        val method = frame.methodName.filter { it.isLetterOrDigit() || it == '_' }.take(64)
        "$owner#$method"
    } ?: "NOT_RECORDED"
