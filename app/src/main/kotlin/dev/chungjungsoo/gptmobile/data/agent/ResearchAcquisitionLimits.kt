package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings

/** Acquisition capacity is separate from model rounds and never grants extra write actions. */
internal fun researchAcquisitionLimits(parent: AgentRunLimits, settings: DeepResearchSettings): AgentRunLimits {
    val plan = dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(settings)
    return parent.copy(
        researchAcquisition = plan.active,
        toolTimeoutMillis = plan.settings.pageTimeoutSeconds * 1000L,
        finalResponseToolCallReserve = 0
    )
}
