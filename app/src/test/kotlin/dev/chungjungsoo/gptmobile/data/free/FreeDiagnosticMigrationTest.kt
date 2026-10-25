package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.network.FreeCooldown
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FreeDiagnosticMigrationTest {
    @Test fun restoredDiagnosticRecordsCannotExportCredentialFingerprints() {
        val file = Files.createTempDirectory("diagnostic-redaction").resolve("failures.json").toFile()
        val digest = "a".repeat(64)
        try {
            val record = FreeProviderFailureRecord(time = 1, provider = "LLM7", endpoint = "https://api.llm7.io", model = "model", profileUid = "profile", chatId = 1, stage = "request", message = "rate limited", cooldownOrigin = FreeCooldown(1000, credentialIdentity = digest))
            file.writeText(Json.encodeToString(listOf(record)))
            FreeProviderDiagnostics.initialize(file)
            assertEquals("redacted", FreeProviderDiagnostics.snapshot().single().cooldownOrigin?.credentialIdentity)
            assertFalse(file.readText().contains(digest))
            FreeProviderDiagnostics.initialize(file)
            assertEquals("redacted", FreeProviderDiagnostics.snapshot().single().cooldownOrigin?.credentialIdentity)
        } finally {
            FreeProviderDiagnostics.clear()
            file.parentFile.deleteRecursively()
        }
    }
}
