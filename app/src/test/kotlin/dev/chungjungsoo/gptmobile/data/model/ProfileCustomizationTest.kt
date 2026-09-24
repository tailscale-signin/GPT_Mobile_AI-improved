package dev.chungjungsoo.gptmobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCustomizationTest {
    @Test
    fun creativityMapsFromDirectToCreativeSampling() {
        val direct = SamplingCreativity.toSampling(0f)
        val balanced = SamplingCreativity.toSampling(0.5f)
        val creative = SamplingCreativity.toSampling(1f)

        assertEquals(0.2f, direct.temperature, 0.0001f)
        assertEquals(0.5f, direct.topP, 0.0001f)
        assertEquals(0.7f, balanced.temperature, 0.0001f)
        assertEquals(0.75f, balanced.topP, 0.0001f)
        assertEquals(1.2f, creative.temperature, 0.0001f)
        assertEquals(1.0f, creative.topP, 0.0001f)
    }

    @Test
    fun creativityRoundTripsSamplingSettings() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { expected ->
            val sampling = SamplingCreativity.toSampling(expected)
            val actual = SamplingCreativity.fromSampling(
                temperature = sampling.temperature,
                topP = sampling.topP
            )
            assertEquals(expected, actual, 0.0001f)
        }
    }

    @Test
    fun coloredLabelsRoundTripWithoutBreakingLegacyLabels() {
        val encoded = encodeProfileLabels(
            listOf(
                ProfileLabel("GitHub", "1976D2"),
                ProfileLabel("Creative", "C2185B")
            )
        )

        val parsed = parseProfileLabels(encoded)
        assertEquals(2, parsed.size)
        assertEquals(ProfileLabel("GitHub", "1976D2"), parsed[0])
        assertEquals(ProfileLabel("Creative", "C2185B"), parsed[1])

        val legacy = parseProfileLabels("Research, Coding")
        assertEquals(listOf("Research", "Coding"), legacy.map { it.name })
        assertTrue(legacy.all { it.colorHex == null })
    }

    @Test
    fun reusableLabelsLinkByNameAndPreferExplicitColor() {
        val reusable = collectReusableProfileLabels(
            listOf(
                "GitHub",
                encodeProfileLabels(listOf(ProfileLabel("github", "388E3C"))),
                encodeProfileLabels(listOf(ProfileLabel("Creative", "7B1FA2")))
            )
        )

        assertEquals(2, reusable.size)
        assertEquals("388E3C", reusable.first { it.key == "github" }.colorHex)
        assertEquals("7B1FA2", reusable.first { it.key == "creative" }.colorHex)
        assertNull(encodeProfileLabels(emptyList()))
    }
}
