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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
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
    const val PACKAGE_MANIFEST_VERSION = 2

    enum class Verification { CURRENT, LEGACY_TRUSTED }

    /** Immutable package identity. User-facing catalog copy is intentionally excluded. */
    fun manifest(entry: GitHubMarketplacePackage): ByteArray {
        val assets = expectedAssets(entry)
        return buildJsonObject {
            put("schemaVersion", PACKAGE_MANIFEST_VERSION)
            put("packageId", entry.id)
            put("providerId", entry.provider)
            put("runtime", entry.runtime.name)
            put("sourceRepository", GitHubMarketplaceCatalog.SOURCE_REPOSITORY)
            put("sourceCommit", GitHubMarketplaceCatalog.SOURCE_COMMIT)
            put("packageRevision", 1)
            put("assets", buildJsonObject { assets.forEach { (name, sha) -> put(name, sha) } })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun expectedAssets(entry: GitHubMarketplacePackage): Map<String, String> = buildMap {
        put("README.md", GitHubMarketplaceCatalog.GUIDE_SHA256)
        if (entry.runtime == MarketplaceRuntime.COMPANION) put("location_mcp.py", GitHubMarketplaceCatalog.COMPANION_SHA256)
    }

    /** Verify trust against hashes embedded in this APK, never hashes supplied by the archive. */
    fun verifyPackage(entry: GitHubMarketplacePackage, bytes: ByteArray): Verification {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PACKAGE_BYTES) { "Package size is invalid." }
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                require(!item.isDirectory && item.name.matches(Regex("[A-Za-z0-9._-]{1,80}")) && item.name !in files) { "Package file list is invalid." }
                val data = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = zip.read(chunk)
                    if (count < 0) break
                    require(data.size() + count <= MAX_ASSET_BYTES) { "Package asset size is invalid." }
                    data.write(chunk, 0, count)
                }
                files[item.name] = data.toByteArray()
                require(files.size <= 8) { "Package has too many files." }
            }
        }
        val expected = expectedAssets(entry)
        expected.forEach { (name, digest) ->
            val content = files[name] ?: error("Package is missing a trusted asset.")
            verify(content, digest)
        }
        val manifestBytes = files["manifest.json"]
        if (manifestBytes != null) {
            require(files.keys == expected.keys + "manifest.json") { "Package has untrusted files." }
            val actual = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
            require(actual.keys == setOf("schemaVersion", "packageId", "providerId", "runtime", "sourceRepository", "sourceCommit", "packageRevision", "assets")) {
                "Package manifest fields are invalid."
            }
            require(actual["schemaVersion"]?.jsonPrimitive?.intOrNull == PACKAGE_MANIFEST_VERSION)
            require(actual["packageRevision"]?.jsonPrimitive?.intOrNull == 1)
            require(actual["packageId"]?.jsonPrimitive?.contentOrNull == entry.id)
            require(actual["providerId"]?.jsonPrimitive?.contentOrNull == entry.provider)
            require(actual["runtime"]?.jsonPrimitive?.contentOrNull == entry.runtime.name)
            require(actual["sourceRepository"]?.jsonPrimitive?.contentOrNull == GitHubMarketplaceCatalog.SOURCE_REPOSITORY)
            require(actual["sourceCommit"]?.jsonPrimitive?.contentOrNull == GitHubMarketplaceCatalog.SOURCE_COMMIT)
            val manifestAssets = actual["assets"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            require(manifestAssets == expected) { "Package manifest does not match the APK trust catalog." }
            return Verification.CURRENT
        }

        // V1 stored setup text in setup.json. Validate only immutable identity fields;
        // presentation copy is not part of the trust decision.
        val setup = files["setup.json"] ?: error("Package manifest is missing.")
        require(files.keys == expected.keys + "setup.json") { "Legacy package has untrusted files." }
        val legacy = Json.parseToJsonElement(setup.toString(Charsets.UTF_8)).jsonObject
        require(legacy["schemaVersion"]?.jsonPrimitive?.intOrNull == 1)
        require(legacy["id"]?.jsonPrimitive?.contentOrNull == entry.id)
        require(legacy["provider"]?.jsonPrimitive?.contentOrNull == entry.provider)
        require(legacy["runtime"]?.jsonPrimitive?.contentOrNull == entry.runtime.name)
        require(legacy["sourceRepository"]?.jsonPrimitive?.contentOrNull == GitHubMarketplaceCatalog.SOURCE_REPOSITORY)
        require(legacy["sourceCommit"]?.jsonPrimitive?.contentOrNull == GitHubMarketplaceCatalog.SOURCE_COMMIT)
        return Verification.LEGACY_TRUSTED
    }

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

    suspend fun download(
        entry: GitHubMarketplacePackage,
        onPhase: suspend (MarketplaceOperationPhase) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        locks.getValue(entry.id).withLock {
            if (readVerified(entry) != null) return@withLock
            onPhase(MarketplaceOperationPhase.DOWNLOADING)
            val files = linkedMapOf("manifest.json" to MarketplaceDownloadPolicy.manifest(entry))
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
            onPhase(MarketplaceOperationPhase.VERIFYING)
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

    suspend fun pendingRemovalIds(): Set<String> = withContext(Dispatchers.IO) {
        GitHubMarketplaceCatalog.packages.filter { File(directory, "${it.id}.removing").isFile }.mapTo(mutableSetOf()) { it.id }
    }

    suspend fun beginRemoval(entry: GitHubMarketplacePackage) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        check(directory.mkdirs() || directory.isDirectory)
        val journal = AtomicFile(File(directory, "${entry.id}.removing"))
        val output = journal.startWrite()
        try {
            output.write(entry.id.toByteArray())
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw error
        }
    }

    suspend fun finishRemoval(entry: GitHubMarketplacePackage) = withContext(Dispatchers.IO) {
        requireKnown(entry)
        AtomicFile(File(directory, "${entry.id}.removing")).delete()
        check(!File(directory, "${entry.id}.removing").exists()) { "Removal needs to be resumed." }
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
        MarketplaceDownloadPolicy.verifyPackage(entry, bytes)
        true
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
        val locks = (GitHubMarketplaceCatalog.packages + GitHubMarketplaceCatalog.legacyPackages).associate { it.id to Mutex() }
    }
}
