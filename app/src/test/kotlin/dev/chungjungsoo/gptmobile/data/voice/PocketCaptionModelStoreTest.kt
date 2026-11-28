package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PocketCaptionModelStoreTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun publisherAssetManifestHasPinnedDigestSizeAndDeterministicIdentity() {
        val assets = PocketCaptionModelStore.MODEL_ASSETS
        val manifest = assets.sortedBy { it.name }.joinToString("") { "${it.name}:${it.bytes}:${it.sha256}\n" }
        assertEquals(9, assets.size)
        assertEquals(PocketCaptionModelStore.TOTAL_BYTES, assets.sumOf { it.bytes })
        assertEquals(PocketCaptionModelStore.MODEL_SHA256, hash(manifest.toByteArray()))
        assertEquals(32_515_016L, PocketCaptionModelStore.ATTENTION_ASSET.bytes)
        assertEquals("c1b41adca4ef75ad8dfdfc71875b5d0ae9be959865a14498da01cbddab8c02b8", PocketCaptionModelStore.ATTENTION_ASSET.sha256)
        assertTrue(PocketCaptionModelStore.ATTENTION_ASSET.urls.single().contains("/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/"))
        assertEquals(LocalVoiceModelStore.MODEL_ASSETS, assets.filterNot { it == PocketCaptionModelStore.ATTENTION_ASSET })
    }

    @Test
    fun derivedPackCopiesVerifiedAssetsUnderLeaseAndRemainsIndependentAfterRemoval() = runBlocking {
        withTimeout(4_000) {
            val baseBytes = "verified base weights".toByteArray()
            val extraBytes = "verified attention weights".toByteArray()
            val baseAsset = asset("base.ort", baseBytes)
            val extraAsset = asset("attention.ort", extraBytes)
            val base = LocalVoiceModelStore(files.newFolder("recognition"), listOf(baseAsset), revision = "base-revision", sourceFactory = { source(baseBytes) })
            base.install()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val downloads = mutableListOf<String>()
            val target = LocalVoiceModelStore(files.newFolder("captions"), listOf(baseAsset, extraAsset), revision = "caption-revision", sourceFactory = { error("Use the installation override") })
            val captions = PocketCaptionModelStore(target, base, listOf(baseAsset)) {
                object : LocalVoiceAssetSource {
                    override fun open(url: String): LocalVoiceAssetStream {
                        downloads += url
                        check(url == extraAsset.urls.single()) { "Verified base bytes must be copied locally" }
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        return LocalVoiceAssetStream(ByteArrayInputStream(extraBytes), extraBytes.size.toLong())
                    }
                    override fun cancel() {
                        release.countDown()
                    }
                }
            }
            val install = async(Dispatchers.IO) { captions.install() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val removal = async(Dispatchers.IO) { base.remove() }
            try {
                delay(50)
                assertTrue(File(base.modelDirectory(), baseAsset.name).isFile)
                assertFalse(removal.isCompleted)
            } finally {
                release.countDown()
            }
            install.await()
            removal.await()
            assertEquals(listOf(extraAsset.urls.single()), downloads)
            assertTrue(captions.isReady())
            assertFalse(base.modelDirectory().exists())
            assertEquals("caption-revision", File(captions.modelDirectory(), "verified").readText())
            assertEquals(hash(baseBytes), hash(File(captions.modelDirectory(), baseAsset.name).readBytes()))
            assertEquals(hash(extraBytes), hash(File(captions.modelDirectory(), extraAsset.name).readBytes()))
            requireNotNull(captions.acquire()).close()
        }
    }

    @Test
    fun corruptAttentionDownloadDoesNotPublishPackOrRemoveRecognitionModel() = runBlocking {
        val baseBytes = "base".toByteArray()
        val attentionBytes = "good".toByteArray()
        val baseAsset = asset("base.ort", baseBytes)
        val attentionAsset = asset("attention.ort", attentionBytes)
        val base = LocalVoiceModelStore(files.newFolder(), listOf(baseAsset), sourceFactory = { source(baseBytes) })
        base.install()
        val target = LocalVoiceModelStore(files.newFolder(), listOf(baseAsset, attentionAsset), sourceFactory = { error("Use override") })
        val captions = PocketCaptionModelStore(target, base, listOf(baseAsset)) { source("evil".toByteArray()) }
        captions.install()
        assertEquals(LocalVoiceModelStatus.FAILED, captions.state.value.status)
        assertEquals(VoiceAssetFailureKind.INTEGRITY, captions.state.value.failureKind)
        assertFalse(captions.modelDirectory().exists())
        assertTrue(base.isInstalled())
        assertTrue(base.verifyInstalledFiles())
        base.remove()
        assertFalse(base.modelDirectory().exists())
    }

    private fun asset(name: String, bytes: ByteArray) = LocalVoiceAsset(name, bytes.size.toLong(), hash(bytes), listOf("https://models.example/$name"))

    private fun source(bytes: ByteArray) = object : LocalVoiceAssetSource {
        override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        override fun cancel() = Unit
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
