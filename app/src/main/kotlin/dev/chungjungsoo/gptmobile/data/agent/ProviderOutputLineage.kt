package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin

/** The ledger supplies identity, but only the provider decoder may declare text to be model output. */
internal fun ProviderEvent.withInvocationLineage(invocationId: String): ProviderEvent = when (this) {
    is ProviderEvent.TextDelta -> if (lineage.origin == OutputOrigin.MODEL_TEXT && lineage.invocationId == null) copy(lineage = lineage.copy(invocationId = invocationId)) else this
    is ProviderEvent.Failed -> if (this.invocationId == null) copy(invocationId = invocationId) else this
    is ProviderEvent.Usage -> if (this.invocationId == null) copy(invocationId = invocationId) else this
    else -> this
}
