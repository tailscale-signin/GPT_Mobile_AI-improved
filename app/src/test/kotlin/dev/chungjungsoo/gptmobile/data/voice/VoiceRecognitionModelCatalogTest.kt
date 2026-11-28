package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionModelCatalogTest {
    @Test
    fun legacyTinyAndIndependentPocketCaptionPackKeepTheirOriginalIdentity() {
        val tiny = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_TINY))
        assertEquals(LocalVoiceModelStore.MODEL_ASSETS, tiny.assets)
        assertEquals(LocalVoiceModelStore.MODEL_REVISION, tiny.revision)
        assertEquals(45_233_659L, tiny.downloadBytes)
        assertEquals(8, tiny.assets.size)
        assertEquals(VoiceRecognitionTimingCapability.SEGMENT, tiny.model.timingCapability)
        assertEquals(77_748_675L, PocketCaptionModelStore.TOTAL_BYTES)
        assertEquals(9, PocketCaptionModelStore.MODEL_ASSETS.size)
        assertFalse(tiny.assets.any { it.name == "decoder_kv_with_attention.ort" })
    }

    @Test
    fun moonshineWordTimingPacksContainBothMatchingDecoders() {
        for (model in listOf(VoiceRecognitionModel.MOONSHINE_SMALL, VoiceRecognitionModel.MOONSHINE_MEDIUM)) {
            val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(model))
            assertEquals(9, pack.assets.size)
            assertTrue(pack.assets.any { it.name == "decoder_kv_with_attention.ort" })
            assertTrue(pack.assets.any { it.name == "decoder_kv.ort" })
            assertTrue(pack.assets.all { it.urls.single().contains("/e318117203f245fda2ca0142db6b8c057d86671c/model/") })
            assertTrue(pack.assets.all { it.urls.single().contains("/quantized_26_08_21/") })
            assertEquals(VoiceRecognitionTimingCapability.WORD, model.timingCapability)
            assertNotNull(model.nativeArchitecture)
            assertFalse(model.supportsLanguage("fr-CA"))
        }
    }

    @Test
    fun nemotronExecutionRoutesHaveCompleteAndDistinctGraphPacks() {
        val cpu = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.CPU))
        val gpu = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU))
        val npu = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.NPU))
        assertEquals(560, cpu.chunkMillis)
        assertEquals(560, gpu.chunkMillis)
        assertEquals(320, npu.chunkMillis)
        assertTrue(cpu.encoderFile!!.endsWith(".int8.onnx"))
        assertTrue(gpu.expandedAssets.any { it.name == "encoder.data" && it.bytes == 2_454_405_120L })
        assertFalse(gpu.expandedAssets.any { it.name.endsWith(".int8.onnx") })
        assertEquals("SM8750", npu.requiredSocModel)
        assertEquals(442_444_790L, npu.downloadBytes)
        assertEquals(setOf("encoder.bin", "decoder.bin", "joiner.bin", "tokens.txt", "info.txt"), npu.expandedAssets.map { it.name }.toSet())
        assertNotEquals(cpu.modelIdentity, gpu.modelIdentity)
        assertNotEquals(cpu.modelIdentity, npu.modelIdentity)
        assertNotEquals(gpu.modelIdentity, npu.modelIdentity)
        for (pack in listOf(cpu, gpu, npu)) {
            val names = pack.expandedAssets.map { it.name }.toSet()
            assertTrue(names.containsAll(listOfNotNull(pack.encoderFile, pack.decoderFile, pack.joinerFile, pack.tokensFile)))
            assertEquals("OpenMDW-1.1", pack.licenseName)
        }
    }

    @Test
    fun languageSelectionExcludesUnadaptedNemotronLocalesAndUnavailableMoonshineRoutes() {
        val nemotron = VoiceRecognitionModel.NEMOTRON_3_5
        for (language in listOf("en-US", "fr-CA", "pt-PT", "nb-NO", "vi-VN", "zh-CN")) assertTrue(nemotron.supportsLanguage(language))
        for (language in listOf("el-GR", "lt-LT", "lv-LV", "mt-MT", "sl-SI", "he-IL", "th-TH", "nn-NO")) assertFalse(nemotron.supportsLanguage(language))
        assertTrue(VoiceRecognitionModel.ZIPFORMER_FR.supportsLanguage("fr-CA"))
        assertFalse(VoiceRecognitionModel.ZIPFORMER_FR.supportsLanguage("en-US"))
        assertNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_SMALL, VoiceRecognitionCompute.GPU))
        assertNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_FR, VoiceRecognitionCompute.NPU))
        assertEquals(VoiceRecognitionModel.MOONSHINE_TINY, VoiceRecognitionModel.fromSaved("removed-model"))
        assertEquals(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionModel.fromSaved("nemotron-3.5"))
        assertEquals(VoiceRecognitionCompute.CPU, VoiceRecognitionCompute.fromSaved("removed-provider"))
    }

    @Test
    fun everyInstallablePackHasCompletePinnedIntegrityMetadata() {
        val packs = VoiceRecognitionModelCatalog.packs
        assertEquals(packs.size, packs.map { it.id }.toSet().size)
        for (pack in packs) {
            assertTrue(pack.modelIdentity.matches(Regex("[a-f0-9]{64}")))
            assertEquals(pack.expandedAssets.size, pack.expandedAssets.map { it.name }.toSet().size)
            for (asset in pack.assets + pack.expandedAssets) {
                assertTrue(asset.bytes > 0)
                assertTrue(asset.sha256.matches(Regex("[a-f0-9]{64}")))
                assertFalse(asset.name.contains('/'))
                assertFalse(asset.name.contains(".."))
            }
            assertTrue(pack.assets.all { it.urls.isNotEmpty() && it.urls.all { url -> url.startsWith("https://") && !url.contains("/resolve/main/") } })
        }
    }
}
