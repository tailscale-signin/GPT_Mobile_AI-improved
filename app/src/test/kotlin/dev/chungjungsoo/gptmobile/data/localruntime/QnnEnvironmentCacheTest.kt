package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Build
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QnnEnvironmentCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun reset() = QnnEnvironment.resetProbe()

    private fun fixture(): Pair<Context, File> {
        QnnEnvironment.resetProbe()
        ShadowVMRuntime.setIs64Bit(true)
        ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", "SM8750")
        ReflectionHelpers.setStaticField(Build::class.java, "SOC_MANUFACTURER", "QUALCOMM")
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val native = temporary.newFolder("native")
        val libraries = JSONObject()
        QualcommSocSupport.requiredLibraries("SM8750").forEach { name ->
            val file = File(native, name).apply { writeText("test-library-$name") }
            libraries.put("lib/arm64-v8a/$name", JSONObject().put("sha256", PackageDigest.sha256(file)).put("bytes", file.length()))
        }
        val manifest = JSONObject().put("libraries", libraries).toString().toByteArray()
        val assets = mockk<AssetManager>()
        every { assets.open("local-runtime-native-libraries.json") } answers { ByteArrayInputStream(manifest) }
        val app = RuntimeEnvironment.getApplication() as Application
        val info = ApplicationInfo(app.applicationInfo).apply { nativeLibraryDir = native.path }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = info
            override fun getAssets(): AssetManager = assets
        }
        return context to native
    }

    @Test fun warmLibraryPreparationUsesStrongIdentityWithoutRehashing() {
        val (context, _) = fixture()
        val first = QnnEnvironment.prepareForExecution(context)
        assertTrue(first.isReady)
        assertEquals("TRUSTED_HASH_PASS", first.libraryDigestStatus)
        assertTrue(first.libraryBytesHashed!! > 0)
        val warm = QnnEnvironment.prepareForExecution(context)
        assertTrue(warm.isReady)
        assertEquals("strong_identity_hit", warm.preparationCacheDisposition)
        assertEquals(0L, warm.libraryBytesHashed)
        assertEquals(0L, warm.digestValidationMs)
        assertEquals(first.libraryInventory, warm.libraryInventory)
    }

    @Test fun sameLengthAndMtimeLibraryMutationCannotReuseTrustedPreparation() {
        val (context, native) = fixture()
        assertTrue(QnnEnvironment.prepareForExecution(context).isReady)
        val file = File(native, "libLiteRtDispatch_Qualcomm.so")
        val modified = file.lastModified()
        RandomAccessFile(file, "rw").use { it.writeByte(1) }
        assertTrue(file.setLastModified(modified))
        val changed = QnnEnvironment.prepareForExecution(context)
        assertFalse(changed.isReady)
        assertEquals("TRUSTED_HASH_FAIL", changed.libraryDigestStatus)
        assertTrue(changed.libraryBytesHashed!! > 0)
    }
}
