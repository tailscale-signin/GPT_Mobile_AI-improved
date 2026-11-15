package dev.chungjungsoo.gptmobile.data.localmodel

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactManifestTest {
    @Test fun standaloneGgufImportedWithOlderEngineRemainsPortable() {
        val root = temporary.newFolder()
        val manifest = artifact(root).copy(
            runtime = ArtifactRuntime.LLAMA_CPP,
            schemaVersion = 3,
            runtimeVersion = "4413a192410fad1f054e9216114087a3e01527a0"
        )
        manifest.validate(root, "cpu")
        assertFalse(manifest.verifiedOnDevice("digest", dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.GGUF_REVISION, "firmware", "cpu", "1"))
        assertTrue(runCatching { manifest.copy(runtimeVersion = "").validate(root) }.isFailure)
        assertTrue(runCatching { manifest.copy(evidence = ArtifactEvidence.QUARANTINED).validate(root) }.isFailure)
    }

    @get:Rule val temporary = TemporaryFolder()

    private fun artifact(root: File): ModelArtifactManifest {
        val file = File(root, "weights.gguf").apply { writeText("GGUF-test-fixture") }
        return ModelArtifactManifest(familyId = "qwen3-0.6b", artifactId = "test", runtime = ArtifactRuntime.GENIEX_LLAMA_CPP, format = "gguf", entryFile = file.name, files = listOf(ArtifactFile(file.name, file.length(), PackageDigest.sha256(file))), contextTokens = 4096, supportedBackends = setOf("cpu"), runtimeVersion = "0.8.0")
    }

    private fun bundle(manifest: ModelArtifactManifest, contents: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { archive ->
            archive.putNextEntry(ZipEntry("manifest.json"))
            archive.write(Json.encodeToString(manifest).toByteArray())
            archive.closeEntry()
            contents.forEach { (path, content) ->
                archive.putNextEntry(ZipEntry(path))
                archive.write(content)
                archive.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun corruptedShardAndUnsafePathsFailBeforeNativeEntry() {
        val root = temporary.newFolder()
        val manifest = artifact(root)
        manifest.validate(root, "cpu")
        File(root, "weights.gguf").writeText("GGUF-test-corrupt")
        assertTrue(runCatching { manifest.validate(root) }.isFailure)
        for (path in listOf("../escape", "/absolute", "C:/escape", "a/../../escape", "libevil.so", "nested\\escape")) {
            assertTrue(path, runCatching { ModelArtifactManifest.safeFile(root, path) }.isFailure)
        }
    }

    @Test fun contextAndEvidenceDoNotTransferAcrossDeviceTuples() {
        val manifest = artifact(temporary.newFolder()).copy(evidence = ArtifactEvidence.VERIFIED_ON_DEVICE)
        assertFalse(manifest.verifiedOnDevice("digest", "0.8.0", "new-firmware", "cpu", "1"))
        assertTrue(runCatching { manifest.copy(contextTokens = 0).validate(temporary.newFolder()) }.isFailure)
    }

    @Test fun standaloneGgufCanDeclareCpuAndVulkanBackends() {
        val root = temporary.newFolder()
        val manifest = artifact(root).copy(runtime = ArtifactRuntime.LLAMA_CPP, schemaVersion = 3, supportedBackends = setOf("cpu", "gpu"), runtimeVersion = dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.GGUF_REVISION)
        manifest.validate(root, "gpu")
        assertTrue(runCatching { manifest.copy(supportedBackends = setOf("gpu")).validate(root, "gpu") }.isFailure)
    }

    @Test fun atomicImportStripsUntrustedBadgesAndRejectsUndeclaredFiles() {
        val source = temporary.newFolder()
        val manifest = artifact(source).copy(evidence = ArtifactEvidence.VERIFIED_ON_DEVICE)
        fun zip(extra: Boolean): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(Json.encodeToString(manifest).toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("weights.gguf"))
                out.write(File(source, "weights.gguf").readBytes())
                out.closeEntry()
                if (extra) {
                    out.putNextEntry(ZipEntry("../escape"))
                    out.write(byteArrayOf(1))
                    out.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
        val success = ModelBundleInstaller.install(ByteArrayInputStream(zip(false)), temporary.newFolder(), runtimeAdmission = {})
        assertTrue(success is LocalModelImportResult.Success)
        val installed = ArtifactManifestStore.read(File((success as LocalModelImportResult.Success).absoluteFilePath))
        assertTrue(installed.evidence == ArtifactEvidence.DISCOVERED)
        assertTrue(ModelBundleInstaller.install(ByteArrayInputStream(zip(true)), temporary.newFolder(), runtimeAdmission = {}) is LocalModelImportResult.Failure)
    }

    @Test fun duplicatePathsAndWrongSocAreRejected() {
        val root = temporary.newFolder()
        val manifest = artifact(root)
        assertTrue(runCatching { manifest.copy(files = manifest.files + manifest.files).validate(root) }.isFailure)
        assertTrue(runCatching { manifest.copy(supportedBackends = setOf("npu"), soc = "SM8850").validate(root, "npu", "SM8750") }.isFailure)
    }

    @Test fun declaredTraversalCannotWriteOutsideStaging() {
        val source = temporary.newFolder()
        val valid = artifact(source)
        val storage = temporary.newFolder()
        val escaped = File(storage, "models/escape.gguf").apply {
            check(parentFile!!.mkdirs())
            writeText("outside staging sentinel")
        }
        val sentinel = escaped.readBytes()
        val paths = listOf("../escape.gguf", "nested/../../escape.gguf", escaped.absolutePath, "C:/escape.gguf", "nested\\escape.gguf", "./escape.gguf", "nested//escape.gguf", "nested/./escape.gguf", "nested/libevil.so")
        for (path in paths) {
            val manifest = valid.copy(entryFile = path, files = listOf(valid.files.single().copy(path = path)))
            val bytes = bundle(manifest, mapOf(path to File(source, "weights.gguf").readBytes()))
            var admitted = 0
            val result = ModelBundleInstaller.install(ByteArrayInputStream(bytes), storage, runtimeAdmission = {
                assertEquals(ArtifactRuntime.GENIEX_LLAMA_CPP, it)
                admitted++
            })
            assertEquals(path, 1, admitted)
            assertTrue(path, result is LocalModelImportResult.Failure)
            result as LocalModelImportResult.Failure
            assertEquals(path, LocalModelImportResult.Failure.Reason.INVALID_MODEL, result.reason)
            assertEquals(path, if (path.endsWith(".so")) "Imported code is not a model asset" else "Unsafe package path", result.message)
            assertArrayEquals(path, sentinel, escaped.readBytes())
            assertEquals(path, setOf("escape.gguf"), File(storage, "models").listFiles().orEmpty().map { it.name }.toSet())
        }
    }

    @Test fun nestedArchiveValidationCannotUseLinkedSourcesOrTrustCorruptedExistingFiles() {
        val source = temporary.newFolder()
        val original = artifact(source)
        val path = "nested/weights.gguf"
        val content = File(source, "weights.gguf").readBytes()
        val manifest = original.copy(entryFile = path, files = listOf(original.files.single().copy(path = path)))
        val bytes = bundle(manifest, mapOf(path to content))
        val storage = temporary.newFolder()
        var admitted = 0
        fun install() = ModelBundleInstaller.install(ByteArrayInputStream(bytes), storage, runtimeAdmission = {
            assertEquals(ArtifactRuntime.GENIEX_LLAMA_CPP, it)
            admitted++
        })
        mockkObject(LinkedModelSources)
        try {
            every { LinkedModelSources.resolveAsset(any(), any(), any()) } throws AssertionError("Archive validation must not resolve a linked source")
            val first = install()
            assertTrue(first.toString(), first is LocalModelImportResult.Success)
            first as LocalModelImportResult.Success
            val installedRoot = File(first.absoluteFilePath).parentFile!!
            val installed = File(installedRoot, path)
            assertArrayEquals(content, installed.readBytes())
            val second = install()
            assertTrue(second.toString(), second is LocalModelImportResult.Success)
            assertEquals(first.absoluteFilePath, (second as LocalModelImportResult.Success).absoluteFilePath)
            val corrupted = content.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
            installed.writeBytes(corrupted)
            val rejected = install()
            assertTrue(rejected.toString(), rejected is LocalModelImportResult.Failure)
            assertTrue((rejected as LocalModelImportResult.Failure).message.contains("checksum", ignoreCase = true))
            assertArrayEquals(corrupted, installed.readBytes())
            assertEquals(3, admitted)
            assertTrue(File(storage, "models").listFiles().orEmpty().none { it.name.startsWith(".staging-") })
            verify(exactly = 0) { LinkedModelSources.resolveAsset(any(), any(), any()) }
        } finally {
            unmockkObject(LinkedModelSources)
        }
    }

    @Test fun qairtArchiveValidationStaysLocalWhileNormalReadsKeepLinkedResolution() {
        val content = linkedMapOf(
            "genie_config.json" to """{"dialog":{"context":{"size":2048},"tokenizer":{"path":"tokenizer.json"},"engine":{"model":{"type":"binary","binary":{"ctx-bins":["shards/part.bin"]}}}}}""".toByteArray(),
            "tokenizer.json" to "{}".toByteArray(),
            "shards/part.bin" to "compiled context fixture".toByteArray()
        )
        val manifest = ModelArtifactManifest(
            familyId = "fixture", artifactId = "qairt", runtime = ArtifactRuntime.GENIEX_QAIRT,
            format = "qairt_context", entryFile = "genie_config.json", contextTokens = 2048,
            supportedBackends = setOf("npu"), soc = "SM8750", tokenizerFile = "tokenizer.json",
            files = content.map { (path, bytes) -> ArtifactFile(path, bytes.size.toLong(), PackageDigest.sha256(bytes)) }
        )
        val bytes = bundle(manifest, content)
        val storage = temporary.newFolder()
        var admitted = 0
        mockkObject(LinkedModelSources)
        try {
            every { LinkedModelSources.resolveAsset(any(), any(), any()) } throws AssertionError("Linked read resolver invoked")
            val installed = (1..2).map {
                val result = ModelBundleInstaller.install(ByteArrayInputStream(bytes), storage, runtimeAdmission = { runtime ->
                    assertEquals(ArtifactRuntime.GENIEX_QAIRT, runtime)
                    admitted++
                })
                assertTrue(result.toString(), result is LocalModelImportResult.Success)
                result as LocalModelImportResult.Success
            }
            assertEquals(2, admitted)
            assertEquals(installed[0].absoluteFilePath, installed[1].absoluteFilePath)
            val root = File(installed[0].absoluteFilePath).parentFile!!
            content.forEach { (path, expected) -> assertArrayEquals(path, expected, File(root, path).readBytes()) }
            verify(exactly = 0) { LinkedModelSources.resolveAsset(any(), any(), any()) }
            val stored = ArtifactManifestStore.read(File(root, "manifest.json"))
            val failure = runCatching { stored.validate(root) }.exceptionOrNull()
            assertTrue(failure is AssertionError)
            assertEquals("Linked read resolver invoked", failure?.message)
            verify(exactly = 1) { LinkedModelSources.resolveAsset(any(), any(), any()) }
        } finally {
            unmockkObject(LinkedModelSources)
        }
    }

    @Test fun qairtConfigurationCannotEscapeDeclaredBundleOrInventContext() {
        val root = temporary.newFolder()
        File(root, "shard.bin").writeText("context binary fixture")
        File(root, "tokenizer.json").writeText("{}")
        fun manifest(shard: String = "shard.bin", context: Int = 2048): ModelArtifactManifest {
            File(root, "genie_config.json").writeText(
                """{"dialog":{"context":{"size":$context},"tokenizer":{"path":"tokenizer.json"},"engine":{"model":{"type":"binary","binary":{"ctx-bins":["$shard"]}}}}}"""
            )
            return ModelArtifactManifest(
                familyId = "fixture", artifactId = "qairt", runtime = ArtifactRuntime.GENIEX_QAIRT,
                format = "qairt_context", entryFile = "genie_config.json", contextTokens = 2048,
                supportedBackends = setOf("npu"), soc = "SM8750", tokenizerFile = "tokenizer.json",
                files = root.listFiles()!!.map { ArtifactFile(it.name, it.length(), PackageDigest.sha256(it)) }
            )
        }
        manifest().validate(root, "npu", "SM8750")
        assertTrue(runCatching { manifest("../shard.bin").validate(root) }.isFailure)
        assertTrue(runCatching { manifest("/data/private.bin").validate(root) }.isFailure)
        assertTrue(runCatching { manifest(context = 4096).validate(root) }.isFailure)
    }
}
