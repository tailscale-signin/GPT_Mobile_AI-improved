package dev.melo.gptmobile.improved.data.model

enum class DynamicTheme {
    OFF,
    ON;

    companion object {
        fun getByValue(value: Int) = entries.firstOrNull { it.ordinal == value }
    }
}
