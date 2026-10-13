package dev.chungjungsoo.gptmobile.data.telemetry.tool

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Correlation travels in the admitted coroutine; UI collection cannot produce executions. */
class ToolStatisticsContext(val repository: ToolStatisticsRepository, val invocationId: String, val subject: String = "", val profile: String = "unattributed", val root: String = "unattributed") : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolStatisticsContext>
}
