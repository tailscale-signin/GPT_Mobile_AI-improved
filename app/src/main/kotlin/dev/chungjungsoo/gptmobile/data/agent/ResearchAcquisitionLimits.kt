package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings

/** Acquisition capacity is separate from model rounds and never grants extra write actions. */
internal fun researchAcquisitionLimits(parent: AgentRunLimits, settings: DeepResearchSettings): AgentRunLimits {
    val plan = settings.normalized()
    return parent.copy(
        maxToolCalls = plan.requestCeiling,
        maxObservedToolCalls = plan.requestCeiling * 3,
        maxToolOutputBytes = plan.corpusLimitMiB * 1024 * 1024,
        finalResponseToolCallReserve = 0
    )
}
