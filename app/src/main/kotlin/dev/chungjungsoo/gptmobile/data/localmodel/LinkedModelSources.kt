package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LinkedModelAsset(val path: String, val uri: String, val sizeBytes: Long, val sha256: String)

@Serializable
internal data class LinkedModelSource(val version: Int = 1, val root: String, val grantUri: String, val assets: List<LinkedModelAsset>)

/** Only app-created receipts can resolve external assets. Model manifests cannot grant file access. */
internal object LinkedModelSources {
    @Volatile private var context: Context? = null
    private val json = Json { encodeDefaults = true }
    private data class Lease(val descriptor: ParcelFileDescriptor, val identity: String, val file: File, val assetIdentity: String?)
    private val leases = mutableMapOf<String, Lease>()

    fun initialize(context: Context) {
        this.context = context.applicationContext ?: context
    }

    private fun receiptFile(app: Context, root: File): File = File(app.noBackupFilesDir, "model-source-references/${PackageDigest.sha256(root.canonicalPath.toByteArray())}.json")

    private fun read(root: File): LinkedModelSource? {
        val app = context ?: return null
        val receipt = AtomicFile(receiptFile(app, root))
        if (!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists()) return null
        return receipt.openRead().use { input ->
            val bytes = readModelReferenceMetadata(input)
            require(bytes.size <= 262_144) { "Linked model receipt is oversized" }
            json.decodeFromString<LinkedModelSource>(bytes.decodeToString()).also {
                require(it.version == 1 && it.root == root.canonicalPath && it.assets.size in 1..256 && it.assets.map { asset -> asset.path }.distinct().size == it.assets.size) { "Invalid linked model receipt" }
            }
        }
    }

    @Synchronized
    fun register(root: File, grant: Uri, assets: List<LinkedModelAsset>) {
        val app = checkNotNull(context)
        require(listOfNotNull(app.filesDir, app.getExternalFilesDir(null)).any { root.canonicalPath.startsWith(it.canonicalPath + "/models/") }) { "Invalid model reference location" }
        require(assets.size in 1..256 && assets.map { it.path }.distinct().size == assets.size)
        assets.forEach {
            ModelArtifactManifest.safeFile(File(app.noBackupFilesDir, "reference-path-validation"), it.path)
            require(Uri.parse(it.uri).scheme == "content" && it.sizeBytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
        }
        require(app.contentResolver.persistedUriPermissions.any { it.uri == grant && it.isReadPermission }) { "Durable source access is missing. Select the model again." }
        val source = LinkedModelSource(root = root.canonicalPath, grantUri = grant.toString(), assets = assets)
        val existing = read(root)
        require(existing == null || existing == source) { "This saved model link conflicts with its source. Remove the link, then select the original model again." }
        if (existing == source) return
        val receipt = AtomicFile(receiptFile(app, root))
        check(receipt.baseFile.parentFile!!.mkdirs() || receipt.baseFile.parentFile!!.isDirectory)
        val output = receipt.startWrite()
        try {
            val encoded = json.encodeToString(source).toByteArray()
            require(encoded.size <= 262_144) { "Linked model reference metadata is oversized" }
            output.write(encoded)
            receipt.finishWrite(output)
        } catch (failure: Throwable) {
            receipt.failWrite(output)
            throw failure
        }
        LocalModelInstallationEpoch.changed()
    }

    fun isLinked(root: File): Boolean = read(root) != null

    fun usesGrant(grant: Uri): Boolean {
        val app = context ?: return false
        return File(app.noBackupFilesDir, "model-source-references").listFiles().orEmpty().filter { it.extension == "json" || it.name.endsWith(".json.bak") }.any {
            runCatching {
                require(it.length() <= 262_144)
                json.decodeFromString<LinkedModelSource>(it.readText()).grantUri == grant.toString()
            }.getOrDefault(true)
        }
    }

    fun resolveFile(file: File, checkCurrent: () -> Unit = {}): File = file.parentFile?.let { resolveAsset(it, file.name, checkCurrent) } ?: file

    /** Each process owns its read-only descriptors. Only small aliases are stored, never weight bytes. */
    @Synchronized
    fun resolveAsset(root: File, path: String, checkCurrent: () -> Unit = {}): File? {
        val app = context ?: return null
        val source = read(root) ?: return null
        val asset = source.assets.singleOrNull { it.path == path } ?: return null
        require(app.contentResolver.persistedUriPermissions.any { it.uri.toString() == source.grantUri && it.isReadPermission }) { "Model source access was revoked. Select the model again." }
        val descriptor = try {
            app.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r") ?: error("Model source is unavailable")
        } catch (failure: Exception) {
            throw IllegalStateException("Model source is unavailable or access was revoked. Select its existing location again.", failure)
        }
        try {
            val stat = Os.fstat(descriptor.fileDescriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == asset.sizeBytes) { "Model source changed or does not support random access. Select an on-device model file again." }
            val identity = "${asset.uri}|${asset.sha256}|${stat.st_dev}|${stat.st_ino}|${stat.st_size}|${stat.st_mtim}|${stat.st_ctim}"
            val key = root.canonicalPath + "|" + path
            leases[key]?.takeIf { it.identity == identity && it.assetIdentity != null && PackageDigest.installedFileIdentity(it.file) == it.assetIdentity }?.let {
                descriptor.close()
                return it.file
            }
            check(leases.size < 512 || key in leases) { "Too many open model assets. Restart the app before linking another package." }
            val directory = File(app.noBackupFilesDir, "linked-model-open/${android.os.Process.myPid()}/${PackageDigest.sha256(root.canonicalPath.toByteArray())}")
            check(directory.mkdirs() || directory.isDirectory)
            val alias = File(directory, path)
            check(alias.parentFile!!.mkdirs() || alias.parentFile!!.isDirectory)
            java.nio.file.Files.deleteIfExists(alias.toPath())
            Os.symlink("/proc/self/fd/${descriptor.fd}", alias.path)
            try {
                PackageDigest.verifyInstalledAssetCached(alias, asset.sha256, checkCurrent)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                alias.delete()
                throw cancelled
            } catch (failure: Exception) {
                alias.delete()
                throw IllegalStateException("The linked model source changed or cannot be verified. Select its current file again and update the profile.", failure)
            }
            leases.put(key, Lease(descriptor, identity, alias, PackageDigest.installedFileIdentity(alias)))?.descriptor?.close()
            return alias
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
    }

    @Synchronized
    fun isOpenAlias(file: File): Boolean = leases.values.any { it.file.absolutePath == file.absolutePath }

    @Synchronized
    fun closeLeases() {
        leases.values.forEach {
            it.descriptor.close()
            it.file.delete()
        }
        leases.clear()
    }

    @Synchronized
    fun forget(root: File) {
        val app = context ?: return
        AtomicFile(receiptFile(app, root)).delete()
        val prefix = root.canonicalPath + "|"
        leases.keys.filter { it.startsWith(prefix) }.forEach { key ->
            leases.remove(key)?.let {
                it.descriptor.close()
                it.file.delete()
            }
        }
        // A persisted URI grant may also belong to another app feature. Unlinking
        // never revokes a shared grant or deletes the user's document.
        LocalModelInstallationEpoch.changed()
    }
}

/** Android 12 does not implement InputStream.readNBytes. */
internal fun readModelReferenceMetadata(input: java.io.InputStream): ByteArray {
    val buffer = ByteArray(262_145)
    var count = 0
    while (count < buffer.size) {
        val read = input.read(buffer, count, buffer.size - count)
        if (read < 0) break
        count += read
    }
    require(count <= 262_144) { "Model metadata is oversized" }
    return buffer.copyOf(count)
}
