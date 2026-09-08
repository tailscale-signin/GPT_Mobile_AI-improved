package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.util.Locale

/**
 * Locale-aware Char uppercasing for Kotlin versions where Char.uppercase(Locale)
 * is not provided by the standard library.
 */
internal fun Char.uppercase(locale: Locale): String = toString().uppercase(locale)
