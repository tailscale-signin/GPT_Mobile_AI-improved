package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity
import dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAttributionPaletteTest {
    @Test
    fun `persisted identities survive profile reordering removal and additions`() {
        val initial = ProfileColorRegistry.ensure(emptyMap(), listOf("uid-a", "uid-b", "uid-c"))
        assertEquals(initial, ProfileColorRegistry.ensure(emptyMap(), listOf("uid-c", "uid-a", "uid-b")))
        val persisted = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(AppFeatureSettings(profileAttributionColors = initial)))
        val after = ProfileColorRegistry.ensure(persisted.profileAttributionColors, listOf("uid-c", "uid-new"))
        initial.forEach { (uid, identity) -> assertEquals(identity, after[uid]) }
        assertEquals(after.size, after.values.map { it.marker }.toSet().size)
        assertEquals(after.size, after.values.map { it.hue }.toSet().size)
    }

    @Test
    fun `the first eight contributors have visibly separated font colours on dark and light themes`() {
        for (surface in listOf(Color.White, Color.Black, Color(0xFF141218), Color(0xFFFEF7FF))) {
            val identities = ProfileColorRegistry.ensure(emptyMap(), (1..8).map { "profile-$it" })
            val colours = identities.values.map { identity -> ProfileAttributionPalette.foreground(identity, surface) }
            colours.forEachIndexed { index, left ->
                colours.drop(index + 1).forEach { right ->
                    val distance = kotlin.math.abs(left.red - right.red) +
                        kotlin.math.abs(left.green - right.green) +
                        kotlin.math.abs(left.blue - right.blue)
                    assertTrue("Low distinction on $surface: $left / $right", distance >= 0.25f)
                }
            }
        }
    }

    @Test
    fun `persisted hue is the displayed identity regardless of marker`() {
        for (hue in listOf(16, 253, 301, 337)) {
            for (surface in listOf(Color.White, Color.Black, Color(0xFF141218))) {
                assertEquals(ProfileAttributionPalette.foreground(ProfileColorIdentity(hue = hue, marker = 1), surface), ProfileAttributionPalette.foreground(ProfileColorIdentity(hue = hue, marker = 27), surface))
            }
        }
    }

    @Test
    fun `late created profiles preserve their persisted identities after removal`() {
        val persisted = mapOf(
            "claude" to ProfileColorIdentity(hue = 88, marker = 37),
            "flash" to ProfileColorIdentity(hue = 90, marker = 86),
            "xiaomi" to ProfileColorIdentity(hue = 91, marker = 104)
        )
        val chosen = ProfileAttributionPalette.forSelection(listOf("xiaomi", "flash", "claude"), persisted)
        val reduced = ProfileAttributionPalette.forSelection(listOf("xiaomi", "flash"), persisted)
        assertEquals(persisted, chosen)
        assertEquals(persisted.filterKeys { it != "claude" }, reduced)
        for (surface in listOf(Color.Black, Color.White)) {
            reduced.forEach { (uid, identity) ->
                assertEquals(ProfileAttributionPalette.foreground(chosen.getValue(uid), surface), ProfileAttributionPalette.foreground(identity, surface))
            }
        }
    }

    @Test
    fun `every palette hue meets normal prose contrast on light dark and custom surfaces`() {
        val surfaces = listOf(Color.White, Color.Black, Color(0xFF141218), Color(0xFFFEF7FF), Color(0xFF808080), Color(0xFF236054), Color(0xFFFFD7BC))
        for (surface in surfaces) {
            for (hue in 0 until 360) {
                val color = ProfileAttributionPalette.foreground(ProfileColorIdentity(hue = hue, marker = 0), surface)
                assertTrue("hue $hue", ProfileAttributionPalette.contrast(color, surface) >= 4.5)
                assertEquals(1f, color.alpha)
            }
        }
    }
}
