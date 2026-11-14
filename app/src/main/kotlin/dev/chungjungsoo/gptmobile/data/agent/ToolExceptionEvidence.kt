package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Actual caught exception evidence only; no message, arguments, dispatch or replay authority. */
internal object ToolExceptionEvidence {
    fun fields(error: Throwable) = buildJsonObject {
        put("cause_types", JsonArray(sanitizedCauseTypes(error).map(::JsonPrimitive)))
        IssueReportContext.exceptionFingerprint(error)?.let { put("exception_fingerprint", it) }
    }
}
