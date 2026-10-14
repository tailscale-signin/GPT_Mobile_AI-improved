package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactFile
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBinaryScopeTest {
    private val versions = RuntimeBinaryVersions("lite1", "qnn1", "genie1", "gguf1")

    @Test fun `unrelated family upgrades cannot clear deterministic quarantine`() {
        val cpu = RuntimeBinaryScope.resolve(null, "cpu", null, false)
        assertEquals(cpu.identity(versions), cpu.identity(versions.copy(qairt = "qnn2", genieX = "genie2", gguf = "gguf2")))
        assertNotEquals(cpu.identity(versions), cpu.identity(versions.copy(liteRt = "lite2")))
        assertFalse(cpu.includesLibrary("libQnnHtp.so"))
        assertFalse(cpu.includesLibrary("libgptmobile_gguf.so"))
        assertTrue(cpu.includesLibrary("libLiteRt.so"))
        assertTrue(cpu.includesLibrary("liblitertlm_jni.so"))
    }

    @Test fun `vision dispatch and actual artifact family participate independently`() {
        val npuVision = RuntimeBinaryScope.resolve(null, "gpu", "npu", true)
        assertEquals(RuntimeBinaryScope.LITERT_QNN, npuVision)
        assertNotEquals(npuVision.identity(versions), npuVision.identity(versions.copy(qairt = "qnn2")))
        assertTrue(npuVision.includesLibrary("libLiteRtDispatch_Qualcomm.so"))
        val gguf = RuntimeBinaryScope.resolve(ArtifactRuntime.GENIEX_LLAMA_CPP, "cpu", null, false)
        assertEquals(RuntimeBinaryScope.GGUF, gguf)
        assertEquals(gguf.identity(versions), gguf.identity(versions.copy(liteRt = "lite2", qairt = "qnn2", genieX = "genie2")))
        assertEquals(RuntimeBinaryScope.GENIEX_GGUF, RuntimeBinaryScope.resolve(ArtifactRuntime.GENIEX_LLAMA_CPP, "cpu", null, true))
    }

    @Test fun `manifest identity validates model assets rather than only manifest bytes`() {
        val directory = Files.createTempDirectory("runtime-identity").toFile()
        try {
            val weights = directory.resolve("weights.gguf").apply { writeText("GGUF-original") }
            val manifest = directory.resolve("manifest.json")
            ArtifactManifestStore.write(manifest, ModelArtifactManifest(schemaVersion = 3, familyId = "family", artifactId = "model", runtime = ArtifactRuntime.LLAMA_CPP, format = "gguf", entryFile = weights.name, files = listOf(ArtifactFile(weights.name, weights.length(), PackageDigest.sha256(weights))), contextTokens = 4096, supportedBackends = setOf("cpu")))
            val cache = directory.resolve("cache")
            val original = RuntimeEvidenceIdentity.artifactDigest(manifest, cache)
            assertNotEquals(PackageDigest.sha256(manifest), original)
            assertEquals(original, RuntimeEvidenceIdentity.artifactDigest(manifest, cache))
            val priorTime = weights.lastModified()
            weights.writeText("GGUF-tampered")
            weights.setLastModified(priorTime + 1000)
            assertTrue(runCatching { RuntimeEvidenceIdentity.artifactDigest(manifest, cache) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }
}
