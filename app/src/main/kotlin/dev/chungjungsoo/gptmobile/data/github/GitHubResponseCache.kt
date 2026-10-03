package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonElement

/**
 * Small in-memory conditional-request cache for GitHub JSON responses.
 *
 * GitHub returns ETags for many REST resources. Keeping the parsed value next
 * to the ETag lets callers send If-None-Match and reuse the parsed response on
 * HTTP 304 instead of spending quota and model context on duplicate payloads.
 */
class GitHubResponseCache(
    private val maxEntries: Int = 128,
    private val maxCharacters: Int = 3 * 1024 * 1024
) {
    data class Entry(
        val etag: String,
        val value: JsonElement,
        val storedAtMillis: Long = System.currentTimeMillis()
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val decodedBlobs = LinkedHashMap<String, String>(16, 0.75f, true)
    private val entrySizes = mutableMapOf<String, Int>()
    private var entryCharacters = 0
    private var blobCharacters = 0

    @Synchronized fun get(key: String): Entry? = entries[key]

    @Synchronized fun put(key: String, etag: String?, value: JsonElement) {
        if (etag.isNullOrBlank()) return
        val size = value.toString().length
        if (size > maxCharacters) return
        entryCharacters += size - (entrySizes.put(key, size) ?: 0)
        entries[key] = Entry(etag, value)
        while (entries.size > maxEntries.coerceAtLeast(1) || entryCharacters > maxCharacters) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
            entryCharacters -= entrySizes.remove(oldest) ?: 0
        }
    }

    @Synchronized fun getDecodedBlob(sha: String): String? = decodedBlobs[sha]

    @Synchronized fun putDecodedBlob(sha: String, content: String) {
        if (sha.isBlank() || content.length > maxCharacters) return
        blobCharacters += content.length - (decodedBlobs.put(sha, content)?.length ?: 0)
        while (decodedBlobs.size > maxEntries.coerceAtLeast(1) || blobCharacters > maxCharacters) {
            blobCharacters -= decodedBlobs.remove(decodedBlobs.keys.first())?.length ?: 0
        }
    }

    @Synchronized fun clear() {
        entries.clear()
        decodedBlobs.clear()
        entrySizes.clear()
        entryCharacters = 0
        blobCharacters = 0
    }
}
