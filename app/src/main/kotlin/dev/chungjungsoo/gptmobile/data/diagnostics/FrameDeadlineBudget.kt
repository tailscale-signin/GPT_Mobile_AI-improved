package dev.chungjungsoo.gptmobile.data.diagnostics

/** FrameMetrics.DEADLINE is a duration; Android's unknown timestamp can become MAX_VALUE - vsync. */
internal object FrameDeadlineBudget {
    private const val MAXIMUM_FRAME_BUDGET_NS = 1_000_000_000L
    private const val DEFAULT_FRAME_BUDGET_NS = 16_666_666L

    fun observed(deadlineNs: Long): Long? = deadlineNs.takeIf { it in 1..MAXIMUM_FRAME_BUDGET_NS }

    fun fromRefreshRate(refreshRate: Float?): Long {
        val rate = refreshRate?.takeIf { it.isFinite() && it > 0f } ?: return DEFAULT_FRAME_BUDGET_NS
        return observed((1_000_000_000.0 / rate).toLong()) ?: DEFAULT_FRAME_BUDGET_NS
    }
}
