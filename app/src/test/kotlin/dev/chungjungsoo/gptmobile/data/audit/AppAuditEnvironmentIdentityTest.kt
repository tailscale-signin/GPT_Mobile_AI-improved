package dev.chungjungsoo.gptmobile.data.audit

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAuditEnvironmentIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allInstalledApksContributeToAStableRoleAwareDigest() = runBlocking {
        val base = apk("base.apk", "base")
        val first = apk("split_config.arm64.apk", "arm64")
        val second = apk("split_config.en.apk", "English")
        val forward = hashAppAuditApks(listOf(base, first, second))
        val reversed = hashAppAuditApks(listOf(base, second, first))
        assertEquals(appAuditHash("base"), forward.base)
        assertEquals(listOf(appAuditHash("arm64"), appAuditHash("English")).sorted(), forward.splits)
        assertEquals(forward.set, reversed.set)
        assertFalse(forward.set == hashAppAuditApks(listOf(base, first)).set)
        assertFalse(forward.set == hashAppAuditApks(listOf(first, base, second)).set)
    }

    @Test fun unreadableMissingEmptyOrOverBudgetSplitsNeverProduceAPartialIdentity() = runBlocking {
        val base = apk("base.apk", "base")
        val empty = apk("empty.apk", "")
        val missing = File(temporary.root, "missing.apk")
        for (files in listOf(listOf(base, missing), listOf(base, empty), listOf(base, base))) {
            assertTrue(runCatching { hashAppAuditApks(files) }.exceptionOrNull() is AppAuditApkIdentityException)
        }
        val failure = runCatching { hashAppAuditApks(listOf(base), maxTotalBytes = 3) }.exceptionOrNull()
        assertEquals(AppAuditApkIdentityFailure.APK_LIMIT_EXCEEDED, (failure as AppAuditApkIdentityException).reason)
    }

    @Test fun canceledHashingCannotReturnAnIdentity() = runBlocking {
        val canceled = Job().apply { cancel() }
        val failure = runCatching { withContext(canceled) { hashAppAuditApks(listOf(apk("base.apk", "base"))) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun incompleteDigestSetsAndPrivateDeviceValuesAreRejectedAtExport() {
        val valid = snapshot()
        assertEquals("OBSERVED", appAuditInstalledArtifactJson(valid)["status"]?.jsonPrimitive?.content)
        val invalid = valid.copy(splitApkSha256 = emptyList(), model = "https://private-device.invalid/name", manufacturer = "private\ncredential")
        val safe = checkNotNull(safeAppAuditEnvironment(invalid))
        assertNull(safe.baseApkSha256)
        assertNull(safe.apkSetSha256)
        assertTrue(safe.splitApkSha256.isEmpty())
        assertNull(safe.model)
        assertNull(safe.manufacturer)
        val artifact = appAuditInstalledArtifactJson(invalid)
        assertEquals("UNAVAILABLE", artifact["status"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, artifact["baseApkSha256"])
        assertFalse(appAuditDeviceIdentityJson(invalid).toString().contains("private"))
    }

    @Test fun legacyMissingSnapshotIsExplicitAndCannotBorrowTheExportingDevice() {
        val artifact = appAuditInstalledArtifactJson(null)
        val device = appAuditDeviceIdentityJson(null)
        assertEquals("LEGACY_RUN_NO_ENVIRONMENT_SNAPSHOT", artifact["reason"]?.jsonPrimitive?.content)
        assertEquals("LEGACY_RUN_NO_ENVIRONMENT_SNAPSHOT", device["reason"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, artifact["apkSetSha256"])
        assertEquals(JsonNull, device["model"])
    }

    @Test fun retainedSnapshotRoundTripsWithoutPathsOrUniqueDeviceIdentifiers() {
        val value = snapshot()
        assertEquals(value, Json.decodeFromString<AppAuditEnvironmentIdentity>(Json.encodeToString(value)))
        val device = appAuditDeviceIdentityJson(value)
        assertEquals("Pixel 9", device["model"]?.jsonPrimitive?.content)
        assertEquals("false", device["physicalDeviceVerified"]?.jsonPrimitive?.content)
        val encoded = Json.encodeToString(value)
        for (privateField in listOf("sourceDir", "serial", "fingerprint", "androidId", "deviceName")) assertFalse(encoded.contains(privateField))
    }

    private fun apk(name: String, body: String): File = temporary.newFile(name).apply { writeText(body) }

    private fun snapshot(): AppAuditEnvironmentIdentity {
        val base = appAuditHash("base")
        val splits = listOf(appAuditHash("split"))
        return AppAuditEnvironmentIdentity(1000, 96607, base, splits, appAuditApkSetDigest(base, splits), 2, manufacturer = "Google", model = "Pixel 9", androidApiLevel = 36, supportedAbis = listOf("arm64-v8a"), process64Bit = true)
    }
}
