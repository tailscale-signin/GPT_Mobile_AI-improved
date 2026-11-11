package dev.chungjungsoo.gptmobile.data.pairing

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.net.URLEncoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerPairingTest {
    private fun link(endpoint: String = "http://192.168.1.4:8766/pair", expires: Long = 1200): String =
        "gptmobile://pair?endpoint=${URLEncoder.encode(endpoint, "UTF-8")}&code=${"A".repeat(43)}&expires=$expires"

    @Test fun onlyFreshCodesAndCleanPrivateOrHttpsDestinationsAreAccepted() {
        assertEquals("http://192.168.1.4:8766/pair", PairingLink.parse(link(), 1000).endpoint)
        assertTrue(runCatching { PairingLink.parse(link(expires = 999), 1000) }.isFailure)
        assertTrue(runCatching { PairingLink.parse(link(expires = 9999), 1000) }.isFailure)
        listOf("http://external.example/pair", "https://user:secret@example.com/pair", "file:///tmp/key", "https://example.com/pair?secret=key").forEach {
            assertTrue(runCatching { PairingLink.parse(link(it), 1000) }.isFailure)
        }
    }

    @Test fun importedConfigurationCreatesDisabledProfileWithoutCredentials() {
        val profile = PairedServer(1, "Computer", "LLAMA", "http://192.168.1.4:8080/v1", "model", 1200).profile(1000)
        assertFalse(profile.enabled)
        assertTrue(profile.token.isNullOrBlank())
        assertEquals(Int.MAX_VALUE, profile.maxToolCalls)
        assertEquals(Int.MAX_VALUE, Json.decodeFromString<PlatformV2>(Json.encodeToString(profile)).maxToolCalls)
    }

    @Test fun legacyAndExplicitProfileCapsSurviveImportWithoutBeingTreatedAsNewDefaults() {
        val legacy = Json.decodeFromString<PlatformV2>("""{"name":"Saved profile"}""")
        assertEquals(50, legacy.maxToolCalls)
        val explicit = legacy.copy(maxToolCalls = 100)
        assertEquals(100, Json.decodeFromString<PlatformV2>(Json.encodeToString(explicit)).maxToolCalls)
    }
}
