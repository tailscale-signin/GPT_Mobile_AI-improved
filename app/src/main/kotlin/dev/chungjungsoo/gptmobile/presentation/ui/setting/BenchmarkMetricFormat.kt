package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.util.Locale

/** Unit labels are data. A percentage, placeholder, or localized unit cannot become a format directive. */
internal fun benchmarkMetricText(value: Double?, unit: String = "", decimals: Int = 2, locale: Locale = Locale.getDefault()): String {
    if (value == null) return "Not measured"
    if (!value.isFinite()) return "Invalid measurement"
    val pattern = when (decimals) {
        0 -> "%.0f"
        1 -> "%.1f"
        3 -> "%.3f"
        4 -> "%.4f"
        5 -> "%.5f"
        6 -> "%.6f"
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

/** Dividing a one-micro total among many successes must not make a positive cost look free. */
internal fun benchmarkApiCostText(micros: Double?, currency: String?, locale: Locale = Locale.getDefault()): String {
    if (micros == null) return "Not measured"
    if (!micros.isFinite() || micros < 0) return "Invalid measurement"
    if (micros == 0.0 && currency.isNullOrBlank()) return "No API charge"
    if (currency.isNullOrBlank()) return "Currency not recorded"
    if (micros > 0 && micros < 1) return "< " + benchmarkMetricText(.000001, currency, 6, locale)
    return benchmarkMetricText(micros / 1_000_000, currency, 6, locale)
}
