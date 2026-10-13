package dev.chungjungsoo.gptmobile.data.localmodel

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A supported folder is a manifest plus its exact declared data assets, never arbitrary code. */
internal object ModelFolderArchive {
    fun copy(resolver: ContentResolver, tree: Uri, target: File, checkCurrent: () -> Unit) {
        val root = DocumentsContract.getTreeDocumentId(tree)
        fun children(parent: String): Map<String, String> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        checkCurrent()
                        require(size < 1024) { "Folder contains too many entries" }
                        require(put(cursor.getString(1), cursor.getString(0)) == null) { "Ambiguous duplicate file name" }
                    }
                }
            } ?: error("Folder permission lost; select it again")
        }
        val childCache = mutableMapOf<String, Map<String, String>>()
        fun open(path: String): java.io.InputStream {
            require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.contains('\\') }) { "Unsafe manifest path" }
            var id = root
            path.split('/').forEach { segment -> id = childCache.getOrPut(id) { children(id) }[segment] ?: error("Missing declared file: $path") }
            return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: error("Could not read $path")
        }
        val manifestBytes = open("manifest.json").use { input ->
            val buffer = ByteArray(262_145)
            var count = 0
            while (count < buffer.size) {
                checkCurrent()
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        require(manifestBytes.size <= 262_144) { "Manifest is too large" }
        val manifest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<ModelArtifactManifest>(manifestBytes.decodeToString())
        require(manifest.files.size in 1..256 && manifest.files.map { it.path }.distinct().size == manifest.files.size)
        require(manifest.files.all { it.path != "manifest.json" && it.sizeBytes in 1..16_000_000_000L })
        val total = manifest.files.sumOf { it.sizeBytes }
        require(total <= 32_000_000_000L && total + 8_388_608 < target.parentFile!!.usableSpace) { "Insufficient staging space" }
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
            val buffer = ByteArray(262_144)
            manifest.files.forEach { asset ->
                checkCurrent()
                zip.putNextEntry(ZipEntry(asset.path))
                var copied = 0L
                open(asset.path).use { input ->
                    while (true) {
                        checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= asset.sizeBytes) { "File exceeds declared size: ${asset.path}" }
                        zip.write(buffer, 0, count)
                    }
                }
                require(copied == asset.sizeBytes) { "Truncated file: ${asset.path}" }
                zip.closeEntry()
            }
        }
    }
}
