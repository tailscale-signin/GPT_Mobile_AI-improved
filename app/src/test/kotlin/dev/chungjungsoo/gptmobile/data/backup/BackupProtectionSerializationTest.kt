package dev.chungjungsoo.gptmobile.data.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupProtectionSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun passwordIsNeverSerializedEvenWhenDefaultsAreIncluded() {
        val raw = json.encodeToString(BackupProtection(true, "unique-sentinel-password"))
        assertFalse(raw.contains("unique-sentinel-password"))
        assertFalse(raw.contains("password"))
        assertEquals(BackupProtection(true), json.decodeFromString<BackupProtection>(raw))
    }

    @Test
    fun oldArchivePasswordsAreIgnoredDuringDecode() {
        assertEquals(BackupProtection(true), json.decodeFromString<BackupProtection>("""{"enabled":true,"password":"legacy-secret"}"""))
    }
}
