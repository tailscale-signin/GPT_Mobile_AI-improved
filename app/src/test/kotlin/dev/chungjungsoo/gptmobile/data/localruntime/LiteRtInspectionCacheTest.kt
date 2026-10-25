package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtInspectionCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val environment = LiteRtInspectionEnvironment("litert-test-1", "device-1", "firmware-1")
    private val metadata = LocalModelCapabilities(contextTokens = 1024, backends = setOf("cpu"), speculative = false)

    private fun model(name: String = "model.litertlm"): File = temporary.newFile(name).apply {
        RandomAccessFile(this, "rw").use {
            it.write("LITERTLM".toByteArray())
            it.setLength(1024 * 1024)
        }
    }

    private fun cache(environment: LiteRtInspectionEnvironment = this.environment, parse: (String) -> LocalModelCapabilities? = { metadata }) = LiteRtInspectionCache(
        File(temporary.root, "metadata"),
        File(temporary.root, "identity"),
        environment,
        parse,
        validateFile = { file ->
            val validated = LocalModelValidator.validate(file.path, minSizeBytes = 1)
            require(validated is ModelValidationResult.Valid)
        },
        clock = { System.nanoTime() / 1_000_000 }
    )

    @Test fun warmRequestsReuseVerifiedBytesAndMetadataButPerformCurrentCompatibilityChecks() {
        val file = model()
        var parses = 0
        val cache = cache {
            parses++
            metadata
        }
        val spec = LocalEngineSpec(file.path, "cpu", 512)
        assertEquals(metadata, cache.inspect(file, spec))
        assertEquals(file.length(), cache.latestReceipt!!.modelBytesHashed)
        assertEquals("miss_native_parse", cache.latestReceipt!!.metadataCacheDisposition)
        val originalId = cache.latestReceipt!!.inspectionId
        repeat(5) {
            assertEquals(metadata, cache.inspect(file, spec))
            assertEquals(0L, cache.latestReceipt!!.modelBytesHashed)
            assertEquals(0L, cache.latestReceipt!!.opaqueMetadataMs)
            assertEquals("memory_hit", cache.latestReceipt!!.metadataCacheDisposition)
            assertNotNull(cache.latestReceipt!!.fileValidationMs)
            assertNotNull(cache.latestReceipt!!.compatibilityValidationMs)
            assertNotEquals(originalId, cache.latestReceipt!!.inspectionId)
        }
        assertEquals(1, parses)
        val allowedKey = cache.latestReceipt!!.cacheKeyDigest
        assertThrows(LocalModelConfigurationException::class.java) { cache.inspect(file, spec.copy(maxTokens = 2048)) }
        assertNotEquals(allowedKey, cache.latestReceipt!!.cacheKeyDigest)
        assertEquals("FAILED", cache.latestReceipt!!.outcome)
        assertEquals(1, parses)
        assertThrows(LocalModelConfigurationException::class.java) { cache.inspect(file, spec.copy(accelerator = "gpu")) }
        assertEquals("gpu", cache.latestReceipt!!.backend)
    }

    @Test fun restartReusesDurableMetadataAndDigestWithoutCallingTheNativeParser() {
        val file = model()
        var parses = 0
        val first = cache {
            parses++
            metadata
        }
        first.inspect(file, null)
        PackageDigest.clearIdentityMemoryReceipts()
        val restarted = cache {
            parses++
            metadata
        }
        assertEquals(metadata, restarted.inspect(file, null))
        assertEquals(1, parses)
        assertEquals("disk_hit", restarted.latestReceipt!!.metadataCacheDisposition)
        assertEquals("disk_hit", restarted.latestReceipt!!.digestCacheDisposition)
        assertEquals(0L, restarted.latestReceipt!!.modelBytesHashed)
        assertEquals("generic_declaration", restarted.latestReceipt!!.backend)
        assertEquals(null, restarted.latestReceipt!!.tokenizerMetadataMs)
    }

    @Test fun inPlaceMutationWithRestoredSizeAndMtimeInvalidatesTheDigestAndDeclaration() {
        val file = model()
        var parses = 0
        val cache = cache {
            parses++
            metadata
        }
        cache.inspect(file, null)
        val firstDigest = cache.latestReceipt!!.artifactDigest
        val timestamp = file.lastModified()
        RandomAccessFile(file, "rw").use {
            it.seek(256)
            it.write(7)
        }
        assertTrue(file.setLastModified(timestamp))
        cache.inspect(file, null)
        assertEquals(2, parses)
        assertNotEquals(firstDigest, cache.latestReceipt!!.artifactDigest)
        assertEquals(file.length(), cache.latestReceipt!!.modelBytesHashed)
    }

    @Test fun replacingAFileAtTheSamePathInvalidatesTheInodeBoundReceipt() {
        val file = model()
        val cache = cache()
        cache.inspect(file, null)
        val oldDigest = cache.latestReceipt!!.artifactDigest
        val replacement = model("replacement.litertlm")
        RandomAccessFile(replacement, "rw").use {
            it.seek(300)
            it.write(3)
        }
        assertTrue(replacement.setLastModified(file.lastModified()))
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cache.inspect(file, null)
        assertNotEquals(oldDigest, cache.latestReceipt!!.artifactDigest)
        assertEquals(file.length(), cache.latestReceipt!!.modelBytesHashed)
    }

    @Test fun aChangedPublisherDigestIsRecheckedEvenWhenTheModelFileIsUnchanged() {
        val file = model()
        val cache = cache()
        cache.inspect(file, null)
        File(file.parentFile, file.name + ".digest").writeText("0".repeat(64))
        assertThrows(IOException::class.java) { cache.inspect(file, null) }
        assertEquals("FAILED", cache.latestReceipt!!.outcome)
    }

    @Test fun runtimeFirmwareAndDeviceChangesInvalidateParsedDeclarations() {
        val file = model()
        var parses = 0
        val read: (String) -> LocalModelCapabilities = {
            parses++
            metadata
        }
        cache(parse = read).inspect(file, null)
        cache(environment.copy(runtimeIdentity = "litert-test-2"), read).inspect(file, null)
        cache(environment.copy(firmwareDigest = "firmware-2"), read).inspect(file, null)
        cache(environment.copy(deviceIdentity = "device-2"), read).inspect(file, null)
        assertEquals(4, parses)
    }

    @Test fun changingValidJsonInsideAMetadataReceiptCannotPromoteCapabilities() {
        val file = model()
        var parses = 0
        val read: (String) -> LocalModelCapabilities = {
            parses++
            metadata
        }
        cache(parse = read).inspect(file, null)
        val saved = File(temporary.root, "metadata").listFiles()!!.single { it.name.endsWith(".json") }
        saved.writeText(saved.readText().replace("1024", "2048"))
        val restarted = cache(parse = read)
        assertEquals(metadata, restarted.inspect(file, null))
        assertEquals(2, parses)
    }

    @Test fun fileChangedDuringNativeMetadataParsingIsNeverPublishedToCache() {
        val file = model()
        val cache = cache {
            RandomAccessFile(file, "rw").use {
                it.seek(512)
                it.write(1)
            }
            metadata
        }
        assertThrows(IOException::class.java) { cache.inspect(file, null) }
        assertEquals("FAILED", cache.latestReceipt!!.outcome)
        assertTrue(File(temporary.root, "metadata").listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test fun explicitVerificationInvalidatesOnlyTheMatchingDeclaration() {
        val first = model()
        val second = model("second.litertlm")
        RandomAccessFile(second, "rw").use {
            it.seek(32)
            it.write(9)
        }
        var parses = 0
        val cache = cache {
            parses++
            metadata
        }
        cache.inspect(first, null)
        cache.inspect(second, null)
        var permittedIdentity: String? = null
        cache.allowVerification(first) { permittedIdentity = it }
        cache.inspect(second, null)
        assertEquals(2, parses)
        cache.inspect(first, null)
        assertEquals(3, parses)
        assertTrue(permittedIdentity!!.startsWith("inspection|"))
    }
}
