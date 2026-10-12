package dev.chungjungsoo.gptmobile.data.agent

/** App-owned context: tool arguments cannot opt into this accounting or evidence retention. */
internal class ResearchAcquisitionContext(
    val budget: ResearchBudget,
    val retainBody: suspend (String) -> List<String>
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ResearchAcquisitionContext>
}
