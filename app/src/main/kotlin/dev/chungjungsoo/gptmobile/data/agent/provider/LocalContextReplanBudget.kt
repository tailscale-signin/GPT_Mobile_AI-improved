package dev.chungjungsoo.gptmobile.data.agent.provider

/** Only a pre-load AUTO admission may restart planning; generation never enters this loop. */
internal class LocalContextReplanBudget(private val maximumReplans: Int = 3) {
    var attempts: Int = 0
        private set
    private var ceiling: Int? = null

    fun next(attemptedContext: Int, suggestedContext: Int): Int? {
        if (attempts >= maximumReplans || suggestedContext <= 0 || suggestedContext >= attemptedContext || ceiling?.let { attemptedContext > it } == true) return null
        attempts++
        ceiling = suggestedContext
        return suggestedContext
    }
}

/** Wrapped only at initial load, so a late runtime failure cannot replay completed tool actions. */
internal class PreloadContextReplan(val failure: dev.chungjungsoo.gptmobile.data.localruntime.LocalContextReplanRequired) : RuntimeException(failure)
