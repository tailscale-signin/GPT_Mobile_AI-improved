package dev.melo.gptmobile.improved.data.model

enum class DynamicTheme(val value: Int) {
    OFF(0),
    ON(1);

    companion object {
        fun fromInt(value: Int) = entries.first { it.value == value }
    }
}
