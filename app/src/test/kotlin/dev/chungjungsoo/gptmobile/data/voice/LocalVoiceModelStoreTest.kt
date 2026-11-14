package dev.chungjungsoo.gptmobile.data.voice

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

class LocalVoiceModelStoreTest {
    @get:Rule val files = TemporaryFolder()
    private val contents = "Verified test model".toByteArray()
    private val asset = LocalVoiceAsset("model.ort", contents.size.toLong(), hash(contents), listOf("https://models.example/model"))

    @Test fun httpsSourceUsesTheSuppliedConnectionForTheAssetAndItsRedirect() {
        val redirect = mockk<HttpURLConnection>(relaxed = true)
        every { redirect.responseCode } returns 302
        every { redirect.getHeaderField("Location") } returns "https://cdn.example/model"
        val assetConnection = mockk<HttpURLConnection>(relaxed = true)
        every { assetConnection.responseCode } returns 200
        every { assetConnection.inputStream } returns ByteArrayInputStream(contents)
        val hosts = mutableListOf<String>()
        val source = HttpsVoiceAssetSource { url ->
            hosts.add(url.host)
            if (hosts.size == 1) redirect else assetConnection
        }

        source.open("https://models.example/model").use { stream ->
            assertEquals("Verified test model", stream.input.readBytes().decodeToString())
        }
        assertEquals(listOf("models.example", "cdn.example"), hosts)
    }

    @Test fun installPublishesOnlyVerifiedCompleteFilesAndReusesTheInstallation() = runBlocking {
        val downloads = AtomicInteger()
        val store = store {
            downloads.incrementAndGet()
            ByteArrayInputStream(contents)
        }
        store.refresh()
        assertEquals(LocalVoiceModelStatus.NOT_INSTALLED, store.state.value.status)
        store.install()
        assertTrue(store.isInstalled())
        assertTrue(store.verifyInstalledFiles())
        store.install()
        assertEquals(1, downloads.get())
        assertFalse(File(store.modelDirectory().parentFile, "installing").exists())
    }

    @Test fun wrongHashAndOversizeNeverPublishAReadyModel() = runBlocking {
        for (data in listOf(ByteArray(contents.size), contents + byteArrayOf(1))) {
            val store = store { ByteArrayInputStream(data) }
            store.install()
            assertEquals(LocalVoiceModelStatus.FAILED, store.state.value.status)
            assertFalse(store.modelDirectory().exists())
            assertFalse(store.verifyInstalledFiles())
        }
    }

    @Test fun sameLengthCorruptionIsRejectedBeforeNativeLoading() = runBlocking {
        val store = store { ByteArrayInputStream(contents) }
        store.install()
        File(store.modelDirectory(), asset.name).writeBytes(ByteArray(contents.size))
        assertFalse(store.acquireUse())
        assertEquals(LocalVoiceModelStatus.NOT_INSTALLED, store.state.value.status)
    }

    @Test fun archiveCannotPublishUntilTheExpandedFilesVerify() = runBlocking {
        val expanded = asset.copy(name = "voice.onnx", urls = emptyList())
        for (correct in listOf(false, true)) {
            val store = LocalVoiceModelStore(
                files.newFolder(),
                listOf(asset),
                expandedAssets = listOf(expanded),
                expandArchive = { directory ->
                    File(directory, asset.name).delete()
                    File(directory, expanded.name).writeBytes(if (correct) contents else ByteArray(contents.size))
                }
            ) {
                object : LocalVoiceAssetSource {
                    override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(contents), asset.bytes)
                    override fun cancel() = Unit
                }
            }
            store.install()
            assertEquals(correct, store.isInstalled())
            assertEquals(correct, store.verifyInstalledFiles())
            assertFalse(File(store.modelDirectory(), asset.name).exists())
        }
    }

    @Test fun removalWaitsForMappedModelUseAndBlocksNewUseAfterRemoval() = runBlocking {
        val store = store { ByteArrayInputStream(contents) }
        store.install()
        assertTrue(store.acquireUse())
        val removal = async { store.remove() }
        withTimeout(1_000) {
            while (!store.state.value.message.orEmpty().contains("Waiting")) delay(10)
        }
        assertTrue(store.isInstalled())
        assertTrue(store.verifyInstalledFiles())
        assertFalse(store.acquireUse())
        store.releaseUse()
        removal.await()
        assertFalse(store.isInstalled())
        assertFalse(store.acquireUse())
    }

    @Test fun concurrentScreensShareOneDownloadAndCancellationCannotPublishPartialFiles() = runBlocking {
        val root = files.newFolder()
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val downloads = AtomicInteger()
        val source = object : LocalVoiceAssetSource {
            override fun open(url: String): LocalVoiceAssetStream {
                downloads.incrementAndGet()
                opened.countDown()
                return LocalVoiceAssetStream(
                    object : InputStream() {
                        override fun read(): Int {
                            cancelled.await(3, TimeUnit.SECONDS)
                            return -1
                        }
                    },
                    -1
                )
            }
            override fun cancel() {
                cancelled.countDown()
            }
        }
        val first = LocalVoiceModelStore(root, listOf(asset)) { source }
        val second = LocalVoiceModelStore(root, listOf(asset)) { source }
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { first.install() }
        assertTrue(opened.await(3, TimeUnit.SECONDS))
        assertEquals(LocalVoiceModelStatus.DOWNLOADING, second.state.value.status)
        second.install()
        assertEquals(1, downloads.get())
        second.cancelInstall()
        runCatching { pending.await() }
        assertFalse(first.isInstalled())
        assertFalse(first.modelDirectory().exists())
        assertFalse(File(root, "installing").exists())
    }

    @Test fun interruptedAssetResumesAtItsValidatedOffsetAndVerifiesTheWholeFile() = runBlocking {
        val root = files.newFolder()
        val offsets = mutableListOf<Long>()
        fun installer() = LocalVoiceModelStore(root, listOf(asset)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String) = error("Expected a resumable request")
                override fun open(url: String, offset: Long, validator: String?): LocalVoiceAssetStream {
                    offsets += offset
                    if (offset == 0L) {
                        return LocalVoiceAssetStream(ByteArrayInputStream(contents.copyOfRange(0, 5)), asset.bytes, validator = "\"test-etag\"")
                    }
                    assertEquals("\"test-etag\"", validator)
                    val remaining = contents.copyOfRange(offset.toInt(), contents.size)
                    return LocalVoiceAssetStream(ByteArrayInputStream(remaining), remaining.size.toLong(), offset, validator)
                }
                override fun cancel() = Unit
            }
        }
        val first = installer()
        first.install()
        assertFalse(first.isInstalled())
        val resumed = installer()
        resumed.refresh()
        resumed.install()
        assertTrue(resumed.verifyInstalledFiles())
        assertEquals(listOf(0L, 5L), offsets)
    }

    @Test fun exactEnglishLanguageSupportNeverSilentlyChangesAnotherLocale() {
        assertTrue(LocalVoiceModelStore.supportsLanguage("en-CA"))
        assertTrue(LocalVoiceModelStore.supportsLanguage("en-US"))
        assertFalse(LocalVoiceModelStore.supportsLanguage("pt-PT"))
        assertFalse(LocalVoiceModelStore.supportsLanguage("ar"))
        assertFalse(LocalVoiceModelStore.supportsLanguage(""))
        assertEquals(LocalVoiceModelStore.TOTAL_BYTES, LocalVoiceModelStore.MODEL_ASSETS.sumOf { it.bytes })
    }

    @Test fun dnsFailureRetainsTheConnectingStageWithoutAnInventedHttpResponse() = runBlocking {
        val store = store { throw UnknownHostException("private-host.example") }
        store.install()
        val observation = store.state.value
        assertEquals(LocalVoiceModelStatus.FAILED, observation.status)
        assertEquals(VoiceTransferStage.CONNECTING, observation.transferStage)
        assertEquals(VoiceAssetFailureKind.DNS, observation.failureKind)
        assertEquals(null, observation.httpStatus)
        assertEquals(0L, observation.downloadedBytes)
        assertFalse(observation.message.orEmpty().contains("private-host"))
    }

    @Test fun earlyTransferEndRetainsObservedBytesAndDoesNotPublishTheModel() = runBlocking {
        val store = store { ByteArrayInputStream(contents.copyOfRange(0, 5)) }
        store.install()
        assertEquals(LocalVoiceModelStatus.FAILED, store.state.value.status)
        assertEquals(5L, store.state.value.downloadedBytes)
        assertEquals(VoiceTransferStage.TRANSFERRING, store.state.value.transferStage)
        assertEquals(VoiceAssetFailureKind.NETWORK_IO, store.state.value.failureKind)
        assertFalse(store.isInstalled())
    }

    @Test fun unusableStorageDoesNotBecomeANetworkFailure() = runBlocking {
        val calls = AtomicInteger()
        val store = LocalVoiceModelStore(files.newFile(), listOf(asset)) {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    calls.incrementAndGet()
                    return LocalVoiceAssetStream(ByteArrayInputStream(contents), asset.bytes)
                }
                override fun cancel() = Unit
            }
        }
        store.install()
        assertEquals(VoiceAssetFailureKind.STORAGE, store.state.value.failureKind)
        assertEquals(null, store.state.value.httpStatus)
        assertEquals(0, calls.get())
    }

    private fun store(stream: () -> InputStream): LocalVoiceModelStore = LocalVoiceModelStore(files.newFolder(), listOf(asset)) {
        object : LocalVoiceAssetSource {
            override fun open(url: String) = LocalVoiceAssetStream(stream(), -1)
            override fun cancel() = Unit
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
