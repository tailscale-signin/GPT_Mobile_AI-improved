package dev.chungjungsoo.gptmobile.data.localruntime

/** Model allocation plus the user's reserve, without overflowing a signed byte count. */
internal data class LocalMemoryBudget(
    val requiredBytes: Long,
    val reserveBytes: Long,
    val availableBytes: Long?
) {
    private val required = requiredBytes.coerceAtLeast(0)
    private val reserve = reserveBytes.coerceAtLeast(0)
    private val available = availableBytes?.coerceAtLeast(0)

    val totalBytes: Long
        get() = if (required > Long.MAX_VALUE - reserve) Long.MAX_VALUE else required + reserve

    val hasShortfall: Boolean
        get() = available?.let { reserve > it || required > it - reserve } ?: false

    /** Saturated diagnostic value; admission uses subtraction rather than a saturated sum. */
    val shortfallBytes: Long?
        get() = available?.let {
            if (required <= it) {
                (reserve - (it - required)).coerceAtLeast(0)
            } else {
                val missing = required - it
                if (missing > Long.MAX_VALUE - reserve) Long.MAX_VALUE else missing + reserve
            }
        }

    fun fitsWithReserve(bytes: Long): Boolean = available != null &&
        !copy(reserveBytes = bytes).hasShortfall
}
