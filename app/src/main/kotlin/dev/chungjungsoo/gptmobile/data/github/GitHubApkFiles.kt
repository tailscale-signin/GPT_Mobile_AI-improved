package dev.chungjungsoo.gptmobile.data.github

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Bounds apply to streamed bytes, independently of untrusted HTTP/ZIP size metadata. */
internal object GitHubApkTransferPolicy {
    const val MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_APK_BYTES = 1536L * 1024 * 1024
    const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 2048
    const val MAX_APKS = 20

    fun requireRepository(owner: String, repo: String) {
        require(listOf(owner, repo).all { it.length in 1..100 && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            "Choose a valid GitHub repository."
        }
    }

    fun downloadKey(connectionUid: String, buildId: String): String {
        require(connectionUid.isNotBlank() && connectionUid.length <= 200 && buildId.isNotBlank() && buildId.length <= 300)
        return MessageDigest.getInstance("SHA-256").digest("$connectionUid\u0000$buildId".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun requireKey(key: String) {
        require(key.matches(Regex("[a-f0-9]{64}"))) { "Invalid download identifier." }
    }

    fun assetUri(owner: String, repo: String, assetId: Long, workflow: Boolean): URI {
        requireRepository(owner, repo)
        require(assetId > 0) { "Choose a valid GitHub build." }
        val path = if (workflow) "actions/artifacts/$assetId/zip" else "releases/assets/$assetId"
        return URI("https://api.github.com/repos/$owner/$repo/$path")
    }

    fun requireTrustedUri(uri: URI) {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val signedAzure = host.matches(Regex("productionresultssa[a-z0-9-]*\\.blob\\.core\\.windows\\.net")) &&
            uri.rawQuery.orEmpty().split('&').any { it.startsWith("sig=") }
        val signedS3 = host.matches(Regex("github-production-[a-z0-9-]+\\.s3(?:\\.[a-z0-9-]+)?\\.amazonaws\\.com")) &&
            uri.rawQuery.orEmpty().contains("Signature=", ignoreCase = true)
        val trustedHost = host in setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com") ||
            host == "pipelines.actions.githubusercontent.com" ||
            signedAzure ||
            signedS3
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443) && trustedHost) {
            "GitHub returned an unsupported download destination."
        }
    }

    fun mayAuthorize(uri: URI): Boolean {
        requireTrustedUri(uri)
        return uri.host.equals("api.github.com", ignoreCase = true)
    }

    fun redirect(current: URI, location: String): URI {
        require(location.length <= 16_384 && '\r' !in location && '\n' !in location) { "Invalid GitHub download redirect." }
        return current.resolve(location).also(::requireTrustedUri)
    }

    fun resumedTotal(contentRange: String?, expectedStart: Long): Long {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
            ?: throw GitHubApkDownloadException("GitHub returned an invalid download range.")
        val start = match.groupValues[1].toLongOrNull() ?: -1
        val end = match.groupValues[2].toLongOrNull() ?: -1
        val total = match.groupValues[3].toLongOrNull() ?: -1
        if (start != expectedStart || end < start || total <= end || total > MAX_TRANSFER_BYTES) {
            throw GitHubApkDownloadException("GitHub returned a different download range. Retry the build.")
        }
        return total
    }

    fun safeApkName(value: String): String {
        val name = value.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(name.endsWith(".apk", ignoreCase = true) && name.length > 4) { "This build is not an APK file." }
        return name.dropLast(4).take(146) + ".apk"
    }

    fun retryDeadlineMillis(now: Long, retryAfter: String?, rateReset: String?): Long {
        val secondsDeadline = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            runCatching { Math.addExact(now, Math.multiplyExact(seconds, 1000)) }.getOrNull()
        }
        val dateDeadline = retryAfter?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        val resetDeadline = rateReset?.toLongOrNull()?.let { seconds -> runCatching { Math.multiplyExact(seconds, 1000) }.getOrNull() }
        return listOfNotNull(secondsDeadline, dateDeadline, resetDeadline).filter { it > now }.maxOrNull() ?: now + 60_000
    }

    fun validateEntryPath(value: String) {
        require(value.length in 1..1024 && !value.startsWith('/') && '\\' !in value && ':' !in value && value.none(Char::isISOControl)) {
            "The build archive contains an unsafe file path."
        }
        require(value.removeSuffix("/").split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "The build archive contains an unsafe file path."
        }
    }

    fun expectedSha256(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        require(digest.matches(Regex("(?i)sha256:[a-f0-9]{64}"))) { "This build has an unsupported checksum." }
        return digest.substringAfter(':').lowercase(Locale.ROOT)
    }

    fun verifyDigest(file: File, digest: String?, checkActive: () -> Unit = {}) {
        val expected = expectedSha256(digest) ?: return
        val actual = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                actual.update(buffer, 0, count)
            }
        }
        if (actual.digest().joinToString("") { "%02x".format(it) } != expected) {
            throw GitHubApkDownloadException("The build checksum did not match GitHub. Download it again.")
        }
    }

    fun requireApkStructure(file: File) {
        if (file.length() !in 1..MAX_APK_BYTES) throw GitHubApkDownloadException("This APK exceeds the supported file size.")
        ZipFile(file).use { zip ->
            if (zip.size() > 100_000 || zip.getEntry("AndroidManifest.xml")?.let { !it.isDirectory && it.size in 1..(5L * 1024 * 1024) } != true) {
                throw GitHubApkDownloadException("The downloaded file is not a valid Android APK.")
            }
        }
    }
}

internal class GitHubApkDownloadException(message: String, val retryable: Boolean = false, val retainPartial: Boolean = false) : IOException(message)

/** Extract only APK files; every archive path is checked before any file is exposed. */
internal object GitHubApkArchive {
    fun extract(
        archive: File,
        directory: File,
        checkActive: () -> Unit = {},
        validateApk: (File) -> Unit = GitHubApkTransferPolicy::requireApkStructure
    ): List<File> {
        require(archive.length() in 1..GitHubApkTransferPolicy.MAX_TRANSFER_BYTES) { "The build archive exceeds the supported size." }
        require(directory.isDirectory || directory.mkdirs()) { "Could not create the APK download directory." }
        val written = mutableListOf<File>()
        try {
            ZipFile(archive).use { zip ->
                require(zip.size() <= GitHubApkTransferPolicy.MAX_ARCHIVE_ENTRIES) { "The build archive contains too many files." }
                val entries = zip.entries().toList()
                entries.forEach { GitHubApkTransferPolicy.validateEntryPath(it.name) }
                val apks = entries.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                require(apks.isNotEmpty()) { "This build artifact contains no APK files. Choose an APK artifact." }
                require(apks.size <= GitHubApkTransferPolicy.MAX_APKS) { "The build archive contains too many APKs." }
                var totalWritten = 0L
                apks.forEachIndexed { index, entry ->
                    checkActive()
                    require(entry.size in 1..GitHubApkTransferPolicy.MAX_APK_BYTES) { "An APK in this artifact exceeds the supported size." }
                    require(entry.size <= GitHubApkTransferPolicy.MAX_EXTRACTED_BYTES - totalWritten) { "The extracted APKs exceed the supported total size." }
                    if (directory.usableSpace < entry.size + GitHubApkTransferPolicy.STORAGE_RESERVE_BYTES) {
                        throw GitHubApkDownloadException("Not enough storage to extract this build. Free space and retry.")
                    }
                    val prefix = "${index + 1}-"
                    val baseName = GitHubApkTransferPolicy.safeApkName(entry.name).dropLast(4).take(146 - prefix.length) + ".apk"
                    // Resolve the destination against its canonical extraction root before writing.
                    // This enforces filesystem containment even if name normalization changes.
                    val root = directory.canonicalFile
                    val output = File(root, prefix + baseName).canonicalFile
                    require(output.parentFile == root && !output.isDirectory) {
                        "The build archive contains an unsafe APK destination."
                    }
                    written += output
                    val crc = CRC32()
                    var entryWritten = 0L
                    zip.getInputStream(entry).buffered().use { input ->
                        output.outputStream().buffered().use { target ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > entry.size - entryWritten || count > GitHubApkTransferPolicy.MAX_EXTRACTED_BYTES - totalWritten) {
                                    throw GitHubApkDownloadException("The build archive expands beyond its declared size.")
                                }
                                target.write(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                entryWritten += count
                                totalWritten += count
                            }
                        }
                    }
                    require(entryWritten == entry.size && (entry.crc < 0 || crc.value == entry.crc)) { "An APK in the build archive is incomplete or corrupt." }
                    validateApk(output)
                }
            }
            return written
        } catch (error: Exception) {
            written.forEach(File::delete)
            throw error
        }
    }
}
