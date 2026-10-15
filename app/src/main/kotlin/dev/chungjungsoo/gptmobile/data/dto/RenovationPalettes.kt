package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.model.ThemeMode

/** Paired presets use the existing transactional editor and backup identities. */
object RenovationPalettes {
    val profiles: List<SavedThemeProfile> = listOf(
        Triple("Forest", 0xFF176B54L, 0xFF98D9B6L),
        Triple("Ocean", 0xFF245C9EL, 0xFFA4C5FFL),
        Triple("Iris", 0xFF6941A5L, 0xFFD3B9F4L),
        Triple("Amber", 0xFF7F571FL, 0xFFF0C69AL),
        Triple("Graphite", 0xFF4D5F57L, 0xFFD5DCD7L)
    ).flatMap { (name, light, dark) ->
        listOf(
            SavedThemeProfile("$name · Light", CustomThemePalette(light, 0xFF52635BL, 0xFFF6F8F7L, 0xFFFFFFFFL), ThemeMode.LIGHT, "renovation:${name.lowercase(java.util.Locale.ROOT)}:light"),
            SavedThemeProfile("$name · Dark", CustomThemePalette(dark, 0xFFB6C7BDL, 0xFF111A17L, 0xFF1A2621L), ThemeMode.DARK, "renovation:${name.lowercase(java.util.Locale.ROOT)}:dark")
        )
    }
}
