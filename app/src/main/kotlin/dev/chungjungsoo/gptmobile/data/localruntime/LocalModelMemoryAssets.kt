package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File

/** Called after runtime preflight has validated the package. Metadata is never a weight estimate. */
internal fun localModelAssetBytes(model: File): Long {
    if (!model.name.endsWith("manifest.json")) return model.length().also { require(it > 0) }
    val manifest = dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(model)
    return manifest.files.fold(0L) { total, asset ->
        val file = dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest.safeFile(requireNotNull(model.parentFile), asset.path)
        require(asset.sizeBytes > 0 && file.isFile && file.length() == asset.sizeBytes) { "Missing or truncated model asset: ${asset.path}" }
        Math.addExact(total, file.length())
    }
}
