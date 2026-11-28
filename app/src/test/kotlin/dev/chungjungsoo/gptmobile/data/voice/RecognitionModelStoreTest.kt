package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionModelStoreTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun tinyUsesExistingInstallAndLeaseOwnershipWithoutAnotherDownload() = runBlocking {
        withTimeout(4_000) {
            val bytes = "already installed weights".toByteArray()
            val asset = asset("weights.ort", bytes)
            val legacy = LocalVoiceModelStore(files.newFolder(), listOf(asset), sourceFactory = { source(bytes) })
            legacy.install()
            val catalog = RecognitionModelStore(files.newFolder(), VoiceRecognitionModel.MOONSHINE_TINY, legacyTinyStore = legacy)
            assertSame(legacy.state, catalog.state)
            assertEquals(legacy.modelDirectory(), catalog.modelDirectory())
            val first = requireNotNull(catalog.acquire())
            val second = requireNotNull(catalog.acquire())
            val removal = async(Dispatchers.IO) { legacy.remove() }
            delay(50)
            assertFalse(removal.isCompleted)
            first.close()
            first.close()
            delay(50)
            assertFalse(removal.isCompleted)
            assertTrue(File(first.directory, asset.name).isFile)
            second.close()
            removal.await()
            assertEquals(LocalVoiceModelStatus.NOT_INSTALLED, catalog.state.value.status)
            assertFalse(catalog.modelDirectory().exists())
        }
    }

    @Test
    fun missingExternalWeightsNeverPublishAnAcceleratorModelAsReady() = runBlocking {
        val graph = "graph references weights.data".toByteArray()
        val weights = "required weights".toByteArray()
        val graphAsset = asset("encoder.onnx", graph)
        val weightsAsset = asset("encoder.data", weights)
        val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU))
            .copy(assets = listOf(graphAsset, weightsAsset), expandedAssets = listOf(graphAsset, weightsAsset))
        val delegate = LocalVoiceModelStore(files.newFolder(), pack.assets, sourceFactory = {
            object : LocalVoiceAssetSource {
                override fun open(url: String): LocalVoiceAssetStream {
                    if (url == weightsAsset.urls.single()) throw IOException("Missing external weights")
                    return LocalVoiceAssetStream(ByteArrayInputStream(graph), graph.size.toLong())
                }
                override fun cancel() = Unit
            }
        })
        val store = RecognitionModelStore(pack, delegate)
        store.install()
        assertEquals(LocalVoiceModelStatus.FAILED, store.state.value.status)
        assertFalse(store.isReady())
        assertFalse(store.modelDirectory().exists())
        assertNull(store.acquire())
    }

    @Test
    fun acquiredModelRejectsChangedFilesAndDoesNotSilentlyUseAnotherRoute() = runBlocking {
        val bytes = "original".toByteArray()
        val asset = asset("encoder.onnx", bytes)
        val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN))
            .copy(assets = listOf(asset), expandedAssets = listOf(asset))
        val delegate = LocalVoiceModelStore(files.newFolder(), pack.assets, sourceFactory = { source(bytes) })
        val store = RecognitionModelStore(pack, delegate)
        store.install()
        val model = File(store.modelDirectory(), asset.name)
        model.writeText("tampered")
        model.setLastModified(model.lastModified() + 2_000)
        assertNull(store.acquire())
        assertFalse(store.isReady())
        assertEquals(VoiceRecognitionModel.ZIPFORMER_EN, store.model)
        assertEquals(VoiceRecognitionCompute.CPU, store.compute)
    }

    @Test
    fun separateExecutionRoutesCannotShareInstallationDirectories() {
        val directory = files.newFolder()
        val cpu = RecognitionModelStore(directory, VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.CPU)
        val gpu = RecognitionModelStore(directory, VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU)
        val npu = RecognitionModelStore(directory, VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.NPU)
        assertEquals(3, listOf(cpu, gpu, npu).map { it.modelDirectory() }.toSet().size)
        assertTrue(listOf(cpu, gpu, npu).all { !it.modelDirectory().exists() })
    }

    private fun asset(name: String, bytes: ByteArray) = LocalVoiceAsset(name, bytes.size.toLong(), hash(bytes), listOf("https://models.example/$name"))

    private fun source(bytes: ByteArray) = object : LocalVoiceAssetSource {
        override fun open(url: String) = LocalVoiceAssetStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        override fun cancel() = Unit
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
