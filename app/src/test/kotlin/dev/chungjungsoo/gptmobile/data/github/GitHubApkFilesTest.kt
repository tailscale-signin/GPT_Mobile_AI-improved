package dev.chungjungsoo.gptmobile.data.github

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubApkFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun releaseAndWorkflowDownloadsUseAssetIdentifiers() {
        assertEquals("https://api.github.com/repos/owner/repo/releases/assets/123", GitHubApkTransferPolicy.assetUri("owner", "repo", 123, false).toString())
        assertEquals("https://api.github.com/repos/owner/repo/actions/artifacts/456/zip", GitHubApkTransferPolicy.assetUri("owner", "repo", 456, true).toString())
        listOf("../outside", "repo?token=secret", "repo/other", "").forEach { repo ->
            assertThrows(IllegalArgumentException::class.java) { GitHubApkTransferPolicy.assetUri("owner", repo, 1, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { GitHubApkTransferPolicy.assetUri("owner", "repo", 0, false) }
    }

    @Test
    fun credentialOnlyGoesToGithubApiAcrossAllowedRedirects() {
        val initial = GitHubApkTransferPolicy.assetUri("owner", "repo", 123, false)
        assertTrue(GitHubApkTransferPolicy.mayAuthorize(initial))
        listOf(
            "https://release-assets.githubusercontent.com/github-production-release-asset/asset?signature=secret",
            "https://objects.githubusercontent.com/github-production-release-asset/asset",
            "https://productionresultssa9.blob.core.windows.net/actions/asset?sv=1&sig=secret",
            "https://github-production-release-asset-2e65be.s3.amazonaws.com/asset?X-Amz-Signature=secret"
        ).forEach { value ->
            assertFalse(GitHubApkTransferPolicy.mayAuthorize(GitHubApkTransferPolicy.redirect(initial, value)))
        }
    }

    @Test
    fun redirectsRejectCredentialsCleartextLookalikeAndArbitraryStorageHosts() {
        val initial = GitHubApkTransferPolicy.assetUri("owner", "repo", 123, false)
        listOf(
            "http://api.github.com/repos/owner/repo",
            "https://api.github.com.evil.example/asset",
            "https://api.github.com@evil.example/asset",
            "https://user:password@api.github.com/asset",
            "https://api.github.com:8443/asset",
            "https://attacker.blob.core.windows.net/asset?sig=secret",
            "https://productionresultssa9.blob.core.windows.net/unsigned",
            "https://evil.s3.amazonaws.com/asset?X-Amz-Signature=secret",
            "//evil.example/asset",
            "file:///tmp/app.apk"
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { GitHubApkTransferPolicy.redirect(initial, value) }
        }
    }

    @Test
    fun resumeRequiresExactOffsetAndBoundedTotal() {
        assertEquals(1000L, GitHubApkTransferPolicy.resumedTotal("bytes 400-999/1000", 400))
        listOf("bytes 399-999/1000", "bytes 400-1000/1000", "bytes 400-399/1000", "bytes */1000", "bytes 400-999/9999999999").forEach { value ->
            assertThrows(GitHubApkDownloadException::class.java) { GitHubApkTransferPolicy.resumedTotal(value, 400) }
        }
    }

    @Test
    fun extractsEveryApkAndIgnoresBundlesAndSignatureSidecars() {
        val archive = archive(
            "arm64/app.apk" to apkBytes(),
            "x86/app.apk" to apkBytes(),
            "app.aab" to byteArrayOf(1),
            "app.apk.idsig" to byteArrayOf(2),
            "README.txt" to byteArrayOf(3)
        )
        val files = GitHubApkArchive.extract(archive, output())
        assertEquals(listOf("1-app.apk", "2-app.apk"), files.map { it.name })
        assertTrue(files.all { it.isFile })
    }

    @Test
    fun nestedDuplicateNamesRemainDistinctAndCannotOverwriteEachOther() {
        val archive = archive("one/output/app.apk" to apkBytes("first"), "two/output/app.apk" to apkBytes("second"))
        val files = GitHubApkArchive.extract(archive, output())
        assertEquals(2, files.map(File::getCanonicalPath).toSet().size)
        assertFalse(files[0].readBytes().contentEquals(files[1].readBytes()))
    }

    @Test
    fun preExistingOutputIsNotOverwrittenOrDeleted() {
        val destination = output()
        val existing = File(destination, "1-app.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        assertThrows(FileAlreadyExistsException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes("download")), destination)
        }
        assertArrayEquals(original, existing.readBytes())
        assertEquals(listOf(existing), destination.listFiles().orEmpty().toList())
    }

    @Test
    fun laterPreExistingOutputPreservedWhileNewlyExtractedFilesAreCleaned() {
        val destination = output()
        val existing = File(destination, "2-two.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        assertThrows(FileAlreadyExistsException::class.java) {
            GitHubApkArchive.extract(archive("one.apk" to apkBytes(), "two.apk" to apkBytes("download")), destination)
        }
        assertFalse(File(destination, "1-one.apk").exists())
        assertArrayEquals(original, existing.readBytes())
    }

    @Test
    fun symlinkCannotEscapeToSiblingDirectoryWithMatchingPrefix() {
        val destination = temporary.newFolder("apks")
        val sibling = temporary.newFolder("apks-outside")
        val existing = File(sibling, "protected.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        val link = File(destination, "1-app.apk").toPath()
        Files.createSymbolicLink(link, existing.toPath())
        assertThrows(GitHubApkDownloadException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes()), destination)
        }
        assertArrayEquals(original, existing.readBytes())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun symlinkToFileInsideDestinationIsNotFollowedOrDeleted() {
        val destination = output()
        val existing = File(destination, "protected.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        val link = File(destination, "1-app.apk").toPath()
        Files.createSymbolicLink(link, existing.toPath())
        assertThrows(FileAlreadyExistsException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes()), destination)
        }
        assertArrayEquals(original, existing.readBytes())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun danglingSymlinkDoesNotCreateItsTarget() {
        val destination = output()
        val missing = File(temporary.newFolder("outside"), "missing.apk")
        val link = File(destination, "1-app.apk").toPath()
        Files.createSymbolicLink(link, missing.toPath())
        assertThrows(IOException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes()), destination)
        }
        assertFalse(missing.exists())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun failureCleanupDeletesReplacementSymlinkWithoutDeletingTarget() {
        val destination = output()
        val existing = temporary.newFile("protected.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        assertThrows(IOException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes()), destination, validateApk = { file ->
                Files.delete(file.toPath())
                Files.createSymbolicLink(file.toPath(), existing.toPath())
                throw IOException("Validation failed.")
            })
        }
        assertArrayEquals(original, existing.readBytes())
        assertFalse(Files.exists(File(destination, "1-app.apk").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun failureCleanupDoesNotFollowReplacedDestinationDirectory() {
        val destination = temporary.newFolder("download")
        val moved = File(temporary.root, "moved-download")
        val outside = temporary.newFolder("outside")
        val existing = File(outside, "1-app.apk").apply { writeBytes(apkBytes("existing")) }
        val original = existing.readBytes()
        assertThrows(IOException::class.java) {
            GitHubApkArchive.extract(archive("app.apk" to apkBytes()), destination, validateApk = {
                Files.move(destination.toPath(), moved.toPath())
                Files.createSymbolicLink(destination.toPath(), outside.toPath())
                throw IOException("Validation failed.")
            })
        }
        assertArrayEquals(original, existing.readBytes())
        assertTrue(Files.isSymbolicLink(destination.toPath()))
    }

    @Test
    fun traversalInAnyEntryRejectsArchiveBeforeWritingEvenIfItIsNotAnApk() {
        listOf("../escape.apk", "/escape.apk", "nested/../../escape.txt", "C:/escape.apk", "nested\\escape.apk", "nested//escape.apk").forEachIndexed { index, badPath ->
            val destination = temporary.newFolder("rejected-$index")
            assertThrows(IllegalArgumentException::class.java) {
                GitHubApkArchive.extract(archive("app.apk" to apkBytes(), badPath to byteArrayOf(1)), destination)
            }
            assertTrue(destination.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun archiveWithoutApksFailsInsteadOfReportingSuccessfulDownload() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubApkArchive.extract(archive("app.aab" to byteArrayOf(1), "app.apk.idsig" to byteArrayOf(2)), output())
        }
    }

    @Test
    fun fakeApkRemovesAllPartiallyExtractedFiles() {
        val destination = output()
        assertThrows(Exception::class.java) {
            GitHubApkArchive.extract(archive("good.apk" to apkBytes(), "fake.apk" to "not an apk".toByteArray()), destination)
        }
        assertTrue(destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun apkWithNoManifestIsRejected() {
        val file = temporary.newFile("no-manifest.apk")
        file.writeBytes(zipBytes("classes.dex" to byteArrayOf(1, 2)))
        assertThrows(GitHubApkDownloadException::class.java) { GitHubApkTransferPolicy.requireApkStructure(file) }
    }

    @Test
    fun cancellationCleansExtractedFiles() {
        val destination = output()
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            GitHubApkArchive.extract(archive("one.apk" to apkBytes(), "two.apk" to apkBytes()), destination, checkActive = {
                if (++checks > 2) throw InterruptedException("canceled")
            })
        }
        assertTrue(destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun excessiveApkOrEntryCountsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubApkArchive.extract(archive(*(1..21).map { "app-$it.apk" to apkBytes() }.toTypedArray()), output())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubApkArchive.extract(archive(*(1..2049).map { "file-$it.txt" to byteArrayOf(1) }.toTypedArray()), output())
        }
    }

    @Test
    fun sha256MismatchNeverAcceptsTheDownloadedArtifact() {
        val file = temporary.newFile("digest.apk")
        file.writeBytes(apkBytes())
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        GitHubApkTransferPolicy.verifyDigest(file, "sha256:$digest")
        assertThrows(GitHubApkDownloadException::class.java) { GitHubApkTransferPolicy.verifyDigest(file, "sha256:" + "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { GitHubApkTransferPolicy.verifyDigest(file, "md5:$digest") }
    }

    @Test
    fun persistedKeysAreScopedToAccountAndAssetWithoutContainingEither() {
        val one = GitHubApkTransferPolicy.downloadKey("account-a", "release-123")
        assertEquals(one, GitHubApkTransferPolicy.downloadKey("account-a", "release-123"))
        assertFalse(one == GitHubApkTransferPolicy.downloadKey("account-b", "release-123"))
        assertFalse(one.contains("account"))
        GitHubApkTransferPolicy.requireKey(one)
        assertThrows(IllegalArgumentException::class.java) { GitHubApkTransferPolicy.requireKey("../escape") }
    }

    private fun output(): File = temporary.newFolder()

    @Test
    fun longApkNamesKeepTheirSuffixAndRemainStableInCompletedManifest() {
        val longName = "a".repeat(240) + ".apk"
        assertEquals(150, GitHubApkTransferPolicy.safeApkName(longName).length)
        val files = GitHubApkArchive.extract(archive(longName to apkBytes()), output())
        assertEquals(files.single().name, GitHubApkTransferPolicy.safeApkName(files.single().name))
        assertTrue(files.single().name.endsWith(".apk"))
    }

    @Test
    fun rateLimitCooldownHonorsServerSecondsDatesAndPrimaryReset() {
        assertEquals(1_300_000L, GitHubApkTransferPolicy.retryDeadlineMillis(1_000_000L, "300", null))
        assertEquals(3_600_000L, GitHubApkTransferPolicy.retryDeadlineMillis(1_000_000L, "30", "3600"))
        assertEquals(1_060_000L, GitHubApkTransferPolicy.retryDeadlineMillis(1_000_000L, "invalid", "invalid"))
        assertEquals(3_600_000L, GitHubApkTransferPolicy.retryDeadlineMillis(1_000_000L, "Thu, 1 Jan 1970 01:00:00 GMT", null))
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().apply { writeBytes(zipBytes(*entries)) }

    private fun apkBytes(marker: String = "manifest"): ByteArray = zipBytes("AndroidManifest.xml" to marker.toByteArray(), "classes.dex" to byteArrayOf(1))

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
