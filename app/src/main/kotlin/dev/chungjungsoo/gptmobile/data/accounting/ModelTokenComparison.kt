package dev.chungjungsoo.gptmobile.data.accounting

internal typealias ModelTokenComparison = dev.chungjungsoo.gptmobile.core.ModelTokenTotals

/** Room persistence stays in Android; comparison and accounting are platform-independent. */
internal fun compareModelTokens(invocations: List<ModelInvocation>): List<ModelTokenComparison> = dev.chungjungsoo.gptmobile.core.compareTokenObservations(invocations.map { dev.chungjungsoo.gptmobile.core.TokenObservation(it.id, it.provider, it.model, it.inputTokens, it.outputTokens, it.estimated, it.kind, it.startedAt) })
