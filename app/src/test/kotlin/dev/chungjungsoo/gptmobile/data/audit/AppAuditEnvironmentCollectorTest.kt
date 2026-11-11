package dev.chungjungsoo.gptmobile.data.audit

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppAuditEnvironmentCollectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packages = mockk<PackageManager>()
    private val context = mockk<Context> {
        every { packageName } returns "dev.melo.gptmobile.improved"
        every { packageManager } returns packages
    }

    @Test fun capturesInstalledBytesAndVersionAwayFromTheCallingThread() = runBlocking {
        val caller = Thread.currentThread().id
        val base = apk("base.apk", "base")
        val split = apk("split.apk", "split")
        val installed = info(base, split)
        every { packages.getPackageInfo("dev.melo.gptmobile.improved", 0) } answers {
            assertNotEquals(caller, Thread.currentThread().id)
            installed
        }
        val observed = AppAuditEnvironmentCollector(context).capture()
        assertEquals(96607L, observed.versionCode)
        assertEquals(2, observed.installedApkCount)
        assertEquals(appAuditHash("base"), observed.baseApkSha256)
        assertEquals(listOf(appAuditHash("split")), observed.splitApkSha256)
        assertNull(observed.apkIdentityFailure)
        assertEquals(34, observed.androidApiLevel)
    }

    @Test fun anUnreadableSplitKeepsKnownVersionButCannotGrantPartialArtifactIdentity() = runBlocking {
        val installed = info(apk("base.apk", "base"), File(temporary.root, "missing.apk"))
        every { packages.getPackageInfo("dev.melo.gptmobile.improved", 0) } returns installed
        val observed = AppAuditEnvironmentCollector(context).capture()
        assertEquals(96607L, observed.versionCode)
        assertEquals(2, observed.installedApkCount)
        assertEquals(AppAuditApkIdentityFailure.APK_UNREADABLE, observed.apkIdentityFailure)
        assertNull(observed.baseApkSha256)
        assertNull(observed.apkSetSha256)
        assertTrue(observed.splitApkSha256.isEmpty())
    }

    @Test fun packageReplacementDuringHashingDiscardsTheEntireArtifactIdentity() = runBlocking {
        val base = apk("base.apk", "base")
        val split = apk("split.apk", "split")
        every { packages.getPackageInfo("dev.melo.gptmobile.improved", 0) } returnsMany listOf(info(base, split), info(base, split, 96608))
        val observed = AppAuditEnvironmentCollector(context).capture()
        assertEquals(AppAuditApkIdentityFailure.APK_CHANGED_DURING_CAPTURE, observed.apkIdentityFailure)
        assertNull(observed.baseApkSha256)
        assertNull(observed.apkSetSha256)
    }

    @Test fun unavailablePackageStillRetainsTheObservedApiWithoutInventingArtifactIdentity() = runBlocking {
        every { packages.getPackageInfo("dev.melo.gptmobile.improved", 0) } throws PackageManager.NameNotFoundException("private path or package message")
        val observed = AppAuditEnvironmentCollector(context).capture()
        assertEquals(AppAuditApkIdentityFailure.PACKAGE_UNAVAILABLE, observed.apkIdentityFailure)
        assertEquals(34, observed.androidApiLevel)
        assertNull(observed.versionCode)
        assertNull(observed.apkSetSha256)
    }

    private fun apk(name: String, body: String): File = temporary.newFile(name).apply { writeText(body) }

    private fun info(base: File, split: File, code: Long = 96607): PackageInfo = PackageInfo().apply {
        setLongVersionCode(code)
        lastUpdateTime = 1234
        applicationInfo = ApplicationInfo().apply {
            sourceDir = base.absolutePath
            splitSourceDirs = arrayOf(split.absolutePath)
        }
    }
}
