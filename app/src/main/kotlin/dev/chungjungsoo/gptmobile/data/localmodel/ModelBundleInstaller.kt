package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A .localmodel ZIP starts with manifest.json; only declared data assets are extracted. */
internal object ModelBundleInstaller {
    fun install(input: InputStream, storageRoot: File, expectedCatalogId: String? = null, checkCurrent: () -> Unit = {}, stagingId: String? = null, onProgress: (Long) -> Unit = {}): LocalModelImportResult {
        val staging = File(storageRoot, "models/.staging-${importWorkspaceId(stagingId)}")
        if (stagingId != null) staging.deleteRecursively() // Only this persisted WorkManager job owns this directory.
        return try {
            check(staging.mkdirs())
            val manifestFile = File(staging, "manifest.json")
            var copied = 0L
            val advance: (Long) -> Unit = { delta ->
                copied += delta
                onProgress(copied)
            }
            val manifest = ZipInputStream(input).use { archive ->
                val first = archive.nextEntry
                require(first?.name == "manifest.json" && !first.isDirectory) { "Bundle must begin with manifest.json" }
                copyBounded(archive, manifestFile, 262_144, exact = false, checkCurrent = checkCurrent, progress = advance)
                val artifact = ArtifactManifestStore.read(manifestFile)
                require(artifact.files.size in 1..256 && artifact.files.none { it.path == "manifest.json" })
                require(artifact.files.all { it.sizeBytes in 1..16_000_000_000L })
                val expanded = artifact.files.sumOf { it.sizeBytes }
                require(expanded <= 32_000_000_000L && expanded < storageRoot.usableSpace) { "Not enough storage for this bundle's declared assets" }
                val declared = artifact.files.associateBy { it.path }
                require(declared.size == artifact.files.size)
                val extracted = mutableSetOf<String>()
                while (true) {
                    val entry = archive.nextEntry ?: break
                    require(!entry.isDirectory && extracted.add(entry.name)) { "Duplicate or undeclared archive entry" }
                    val asset = declared[entry.name] ?: error("Undeclared archive asset: ${entry.name}")
                    val target = ModelArtifactManifest.safeFile(staging, entry.name)
                    // Keep the extraction boundary explicit before either filesystem write.
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IllegalArgumentException("Archive entry escapes installation")
                    }
                    check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                    copyBounded(archive, target, asset.sizeBytes, exact = true, checkCurrent = checkCurrent, progress = advance)
                }
                require(extracted == declared.keys) { "Missing bundle assets" }
                // Imported assertions cannot manufacture a device verification badge.
                artifact.copy(evidence = ArtifactEvidence.DISCOVERED, validations = emptyList())
            }
            manifest.validate(staging, checkCurrent = checkCurrent)
            require(manifest.runtime in setOf(ArtifactRuntime.GENIEX_QAIRT, ArtifactRuntime.GENIEX_LLAMA_CPP)) { "This bundle importer supports GenieX QAIRT/GGUF. Import LiteRT as .litertlm; legacy Genie needs a qualified native SDK." }
            ArtifactManifestStore.write(manifestFile, manifest)
            val digest = PackageDigest.sha256(manifestFile)
            if (expectedCatalogId != null) require(manifest.artifactId == expectedCatalogId) { "Bundle identity differs from the selected artifact" }
            val id = expectedCatalogId ?: "local_bundle_${digest.take(24)}"
            val commit = if (expectedCatalogId != null) "bundle-${digest.take(24)}" else LocalModelLocator.LOCAL_COMMIT_HASH
            val relative = LocalModelDownloadPaths.relativeDirectory(id, commit)
            val destination = File(storageRoot, relative)
            check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
            // Content-addressed installs never overwrite an in-use artifact.
            checkCurrent()
            if (destination.exists()) {
                ArtifactManifestStore.read(File(destination, "manifest.json")).validate(destination, checkCurrent = checkCurrent)
                staging.deleteRecursively()
            } else {
                java.nio.file.Files.move(staging.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            LocalModelImportResult.Success(
                LocalModelRecord(id, commit, "manifest.json", relative, LocalModelStatus.READY),
                File(destination, "manifest.json").absolutePath,
                manifest.files.sumOf { it.sizeBytes }
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            LocalModelImportResult.Failure(LocalModelImportResult.Failure.Reason.INVALID_MODEL, failure.message ?: "Bundle validation failed")
        } finally {
            input.close()
            staging.deleteRecursively()
        }
    }

    private fun copyBounded(input: InputStream, file: File, limit: Long, exact: Boolean, checkCurrent: () -> Unit, progress: (Long) -> Unit) {
        var count = 0L
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCurrent()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= limit) { "Expanded archive asset exceeds its declared size" }
                output.write(buffer, 0, read)
                progress(read.toLong())
            }
            output.fd.sync()
        }
        require(!exact || count == limit) { "Truncated archive asset" }
    }
}
