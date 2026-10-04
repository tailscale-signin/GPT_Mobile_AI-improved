package dev.chungjungsoo.gptmobile.data.agent

/**
 * Pure domain policy governing tool budgeting, wrap-up steering prompts, and limit notices.
 */
object ToolBudgetPolicy {

    fun executionLimit(limits: AgentRunLimits): Int =
        if (limits.maxToolCalls == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            (limits.maxToolCalls - limits.finalResponseToolCallReserve.coerceAtLeast(0)).coerceAtLeast(0)
        }

    fun wrapUpThreshold(limits: AgentRunLimits): Int =
        (limits.maxToolCalls / 5).coerceAtLeast(1)

    fun remainingAllowance(executionLimit: Int, toolCallCount: Int): Int =
        if (executionLimit == Int.MAX_VALUE) Int.MAX_VALUE else (executionLimit - toolCallCount)

    fun shouldEmitWrapUpNotice(
        executionLimit: Int,
        limits: AgentRunLimits,
        toolCallCount: Int,
        wrapUpNoticeEmitted: Boolean
    ): Boolean {
        if (executionLimit == Int.MAX_VALUE || limits.maxToolCalls <= 2 || wrapUpNoticeEmitted) {
            return false
        }
        val remaining = remainingAllowance(executionLimit, toolCallCount)
        return remaining in 1..wrapUpThreshold(limits)
    }

    fun shouldInjectWrapUpPrompt(
        executionLimit: Int,
        limits: AgentRunLimits,
        toolCallCount: Int
    ): Boolean {
        if (executionLimit == Int.MAX_VALUE || limits.maxToolCalls <= 2) {
            return false
        }
        val remaining = remainingAllowance(executionLimit, toolCallCount)
        return remaining in 1..wrapUpThreshold(limits)
    }

    fun buildWrapUpPrompt(remainingAllowance: Int): String =
        "You have $remainingAllowance tool call(s) remaining before your hard limit. " +
            "Prioritize only essential remaining calls and avoid starting broad new research. " +
            "If the available evidence is already sufficient, stop calling tools and synthesize the final answer now. " +
            "If essential evidence will not fit in the remaining allowance, state what is missing and ask whether to continue in a new response."
}
