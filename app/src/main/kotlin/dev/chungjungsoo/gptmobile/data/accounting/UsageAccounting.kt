package dev.chungjungsoo.gptmobile.data.accounting

/** Provider snapshots/deltas share the portable accounting rules. */
internal fun mergeUsageCount(previous: Int?, incoming: Int?, cumulative: Boolean): Int? = dev.chungjungsoo.gptmobile.core.mergeTokenCount(previous, incoming, cumulative)
