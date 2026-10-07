package dev.chungjungsoo.gptmobile.data.marketplace

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Integrity anchored in this APK, not a checksum downloaded beside untrusted bytes. */
object MarketplaceDownloadPolicy {
    const val MAX_ASSET_BYTES = 96 * 1024
    const val MAX_PACKAGE_BYTES = 256 * 1024

    fun assetUrl(file: String): String {
        require(file in setOf("README.md", "location_mcp.py")) { "Unapproved package asset." }
        return "https://raw.githubusercontent.com/${GitHubMarketplaceCatalog.SOURCE_REPOSITORY}/" +
            "${GitHubMarketplaceCatalog.SOURCE_COMMIT}/mcp/marketplace/$file"
    }

    fun validateUrl(url: String) {
        val uri = URI(url)
        require(
            uri.scheme == "https" &&
                uri.host == "raw.githubusercontent.com" &&
                uri.port == -1 &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                url in setOf(assetUrl("README.md"), assetUrl("location_mcp.py"))
        ) { "Unapproved GitHub package URL." }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun verify(bytes: ByteArray, expected: String) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ASSET_BYTES) { "Package asset size is invalid." }
        require(expected.matches(Regex("[0-9a-f]{64}")) && sha256(bytes) == expected) { "Package integrity check failed." }
    }
}

/** Stores inert ZIP packages. Never extracts or executes downloaded source on Android. */
@Singleton
class MarketplacePackageStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.noBackupFilesDir, "optional_marketplace_v1")
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun downloadedIds(): Set<String> = withContext(Dispatchers.IO) {
        GitHubMarketplaceCatalog.packages.filter { entry ->
            ensureActive()
            locks.getValue(entry.id).withLock { readVerified(entry) != null }
        }.map { it.id }.toSet()
    }

    suspend fun download(entry: GitHubMarketplacePackage) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        locks.getValue(entry.id).withLock {
            if (readVerified(entry) != null) return@withLock
            val files = linkedMapOf("setup.json" to descriptor(entry))
            files["README.md"] = fetchAsset("README.md", GitHubMarketplaceCatalog.GUIDE_SHA256)
            if (entry.runtime == MarketplaceRuntime.COMPANION) {
                files["location_mcp.py"] = fetchAsset("location_mcp.py", GitHubMarketplaceCatalog.COMPANION_SHA256)
            }
            ensureActive()
            val bytes = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    files.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content)
                        zip.closeEntry()
                    }
                }
                buffer.toByteArray()
            }
            check(verifyPackage(entry, bytes)) { "Package validation failed." }
            ensureActive()
            check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Unable to create package storage." }
            val file = packageFile(entry)
            val output = file.startWrite()
            try {
                output.write(bytes)
                ensureActive()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }

    suspend fun exportBytes(entry: GitHubMarketplacePackage): ByteArray = withContext(Dispatchers.IO) {
        requireKnown(entry)
        locks.getValue(entry.id).withLock { readVerified(entry) ?: throw IOException("Download the package again before exporting.") }
    }

    suspend fun remove(entry: GitHubMarketplacePackage) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        locks.getValue(entry.id).withLock {
            val file = packageFile(entry)
            file.delete()
            check(!file.baseFile.exists()) { "Unable to remove package files." }
        }
    }

    private fun requireKnown(entry: GitHubMarketplacePackage) {
        require(GitHubMarketplaceCatalog.find(entry.id) == entry) { "Unknown package." }
        require(entry.id.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid package ID." }
    }

    private fun packageFile(entry: GitHubMarketplacePackage): AtomicFile = AtomicFile(File(directory, "${entry.id}.zip"))

    private fun descriptor(entry: GitHubMarketplacePackage): ByteArray = buildJsonObject {
        put("schemaVersion", 1)
        put("id", entry.id)
        put("provider", entry.provider)
        put("runtime", entry.runtime.name)
        put("sourceRepository", GitHubMarketplaceCatalog.SOURCE_REPOSITORY)
        put("sourceCommit", GitHubMarketplaceCatalog.SOURCE_COMMIT)
        put("endpoint", entry.preset.defaultEndpoint)
        put("authType", entry.preset.suggestedAuthType)
        put("credentialVariable", entry.credentialVariable)
        put("setup", entry.preset.setupInstructions)
        put("serviceNotice", entry.serviceNotice)
        put("enabled", false)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun readVerified(entry: GitHubMarketplacePackage): ByteArray? = runCatching {
        val bytes = packageFile(entry).openRead().use { input ->
            // readBytes() alone would allow an oversized/corrupt local package to exhaust memory.
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                require(total <= MarketplaceDownloadPolicy.MAX_PACKAGE_BYTES)
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        bytes.takeIf { verifyPackage(entry, it) }
    }.getOrNull()

    private fun verifyPackage(entry: GitHubMarketplacePackage, bytes: ByteArray): Boolean = runCatching {
        require(bytes.size <= MarketplaceDownloadPolicy.MAX_PACKAGE_BYTES)
        val expected = mutableMapOf(
            "setup.json" to MarketplaceDownloadPolicy.sha256(descriptor(entry)),
            "README.md" to GitHubMarketplaceCatalog.GUIDE_SHA256
        )
        if (entry.runtime == MarketplaceRuntime.COMPANION) expected["location_mcp.py"] = GitHubMarketplaceCatalog.COMPANION_SHA256
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                require(!item.isDirectory)
                val digest = expected.remove(item.name) ?: error("Unexpected or duplicate ZIP entry.")
                val data = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = zip.read(chunk)
                    if (count < 0) break
                    require(data.size() + count <= MarketplaceDownloadPolicy.MAX_ASSET_BYTES)
                    data.write(chunk, 0, count)
                }
                MarketplaceDownloadPolicy.verify(data.toByteArray(), digest)
            }
        }
        expected.isEmpty()
    }.getOrDefault(false)

    private suspend fun fetchAsset(file: String, expected: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val url = MarketplaceDownloadPolicy.assetUrl(file)
        MarketplaceDownloadPolicy.validateUrl(url)
        // Dedicated unauthenticated client: app/GitHub/provider keys are never attached to downloads.
        val call = client.newCall(Request.Builder().url(url).header("Accept", "text/plain").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("GitHub download failed. Check your connection and retry."))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful) { "GitHub returned HTTP ${it.code}. No package was installed." }
                        val body = it.body ?: throw IOException("Empty GitHub response.")
                        require(body.contentLength() <= MarketplaceDownloadPolicy.MAX_ASSET_BYTES) { "Package asset exceeds the size limit." }
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (continuation.isActive) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                require(output.size() + count <= MarketplaceDownloadPolicy.MAX_ASSET_BYTES) { "Package asset exceeds the size limit." }
                                output.write(chunk, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (continuation.isActive) {
                        MarketplaceDownloadPolicy.verify(bytes, expected)
                        continuation.resume(bytes)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Package download or integrity validation failed. Nothing was enabled.", error)
                        )
                    }
                }
            }
        })
    }

    private companion object {
        // Serialize changes to the same package without blocking unrelated downloads or exports.
        val locks = GitHubMarketplaceCatalog.packages.associate { it.id to Mutex() }
    }
}
