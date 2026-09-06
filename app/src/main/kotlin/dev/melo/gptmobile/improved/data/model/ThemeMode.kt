package dev.melo.gptmobile.improved.data.model

enum class ThemeMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromInt(value: Int) = entries.first { it.value == value }
    }
}
