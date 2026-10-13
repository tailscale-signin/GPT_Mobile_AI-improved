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
    fun `every palette hue meets normal prose contrast on light dark and custom surfaces`() {
        val surfaces = listOf(Color.White, Color.Black, Color(0xFF141218), Color(0xFFFEF7FF), Color(0xFF808080), Color(0xFF236054), Color(0xFFFFD7BC))
        for (surface in surfaces) {
            for (hue in 0 until 360) {
                val color = ProfileAttributionPalette.foreground(ProfileColorIdentity(hue = hue, marker = 1), surface)
                assertTrue("hue $hue", ProfileAttributionPalette.contrast(color, surface) >= 4.5)
                assertEquals(1f, color.alpha)
            }
        }
    }
}
