package dev.chungjungsoo.gptmobile.data.huggingface

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import kotlinx.coroutines.CancellationException

/** Artifact identity is case-sensitive and never shortened before hashing. Revision is separate. */
internal fun hubArtifactId(repoId: String, filePath: String): String = "hf_" +
    java.security.MessageDigest.getInstance("SHA-256")
        .digest((repoId + "\u0000" + filePath).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal suspend fun <T> isolatedHubRequest(onFailure: () -> Unit = {}, request: suspend () -> List<T>): List<T> = try {
    request()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    onFailure()
    emptyList()
}

/** Keep old profile IDs only for one unambiguous matching repository/file provenance. */
internal fun preserveInstalledHubIdentity(entry: CatalogEntry, installed: List<CatalogEntry>): CatalogEntry {
    fun artifact(url: String): String? {
        val path = url.substringBefore('?')
        if (!path.startsWith("https://huggingface.co/") || "/resolve/" !in path) return null
        return path.substringBefore("/resolve/") + "/" + path.substringAfter("/resolve/").substringAfter('/', "")
    }
    val identity = artifact(entry.downloadUrl) ?: return entry
    val match = installed.filter { it.id.startsWith("hf_") && artifact(it.downloadUrl) == identity }.distinctBy { it.id }.singleOrNull()
    return match?.let { entry.copy(id = it.id) } ?: entry
}
