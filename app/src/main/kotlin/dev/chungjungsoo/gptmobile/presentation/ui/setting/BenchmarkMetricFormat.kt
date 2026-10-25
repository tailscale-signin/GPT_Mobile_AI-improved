package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.util.Locale

/** Unit labels are data. A percentage, placeholder, or localized unit cannot become a format directive. */
internal fun benchmarkMetricText(value: Double?, unit: String = "", decimals: Int = 2, locale: Locale = Locale.getDefault()): String {
    if (value == null) return "Not measured"
    if (!value.isFinite()) return "Invalid measurement"
    val pattern = when (decimals) {
        0 -> "%.0f"
        1 -> "%.1f"
        else -> "%.2f"
    }
    val number = String.format(locale, pattern, value)
    return when (unit) {
        "" -> number
        "%" -> number + "%"
        else -> number + " " + unit
    }
}

internal fun benchmarkMetricDeltaText(value: Double?, unit: String): String =
    (if (value != null && value.isFinite() && value > 0) "+" else "") + benchmarkMetricText(value, unit, 1)
