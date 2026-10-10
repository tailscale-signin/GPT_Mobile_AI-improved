package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactManifestTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun artifact(root: File): ModelArtifactManifest {
        val file = File(root, "weights.gguf").apply { writeText("GGUF-test-fixture") }
        return ModelArtifactManifest(familyId = "qwen3-0.6b", artifactId = "test", runtime = ArtifactRuntime.GENIEX_LLAMA_CPP, format = "gguf", entryFile = file.name, files = listOf(ArtifactFile(file.name, file.length(), PackageDigest.sha256(file))), contextTokens = 4096, supportedBackends = setOf("cpu"), runtimeVersion = "0.8.0")
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
        val success = ModelBundleInstaller.install(ByteArrayInputStream(zip(false)), temporary.newFolder())
        assertTrue(success is LocalModelImportResult.Success)
        val installed = ArtifactManifestStore.read(File((success as LocalModelImportResult.Success).absoluteFilePath))
        assertTrue(installed.evidence == ArtifactEvidence.DISCOVERED)
        assertTrue(ModelBundleInstaller.install(ByteArrayInputStream(zip(true)), temporary.newFolder()) is LocalModelImportResult.Failure)
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
        val escaped = File(storage, "models/escape.gguf")
        for (path in listOf("../escape.gguf", "nested/../../escape.gguf", escaped.absolutePath)) {
            val manifest = valid.copy(entryFile = path, files = listOf(valid.files.single().copy(path = path)))
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(Json.encodeToString(manifest).toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry(path))
                out.write(File(source, "weights.gguf").readBytes())
                out.closeEntry()
            }
            assertTrue(path, ModelBundleInstaller.install(ByteArrayInputStream(bytes.toByteArray()), storage) is LocalModelImportResult.Failure)
            assertFalse(path, escaped.exists())
            assertTrue(File(storage, "models").listFiles().orEmpty().isEmpty())
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
