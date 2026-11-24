package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class PrivacyProtectedRange(val start: Int, val endExclusive: Int)

/** Local display evidence contains offsets and a digest, never the replacement dictionary. */
@Serializable
data class PrivacyProtectionReceipt(
    val contentDigest: String,
    val ranges: List<PrivacyProtectedRange> = emptyList(),
    val confirmed: Boolean = true,
    val version: Int = 1
) {
    fun validFor(text: String): Boolean = version == 1 &&
        contentDigest == digest(text) &&
        ranges.all { it.start >= 0 && it.endExclusive > it.start && it.endExclusive <= text.length } &&
        ranges.zipWithNext().all { (left, right) -> left.endExclusive <= right.start }

    /** A repeated literal is safe to colour only when every occurrence has local evidence. */
    fun protectedValues(text: String): List<String> {
        if (!confirmed || !validFor(text)) return emptyList()
        return ranges.map { text.substring(it.start, it.endExclusive) }.distinct().filter { value ->
            occurrences(text, value).all { occurrence -> ranges.any { it.start <= occurrence.start && it.endExclusive >= occurrence.endExclusive } }
        }
    }

    fun intersect(text: String, next: PrivacyProtectionReceipt): PrivacyProtectionReceipt {
        if (!validFor(text) || !next.validFor(text)) return create(text, emptyList())
        if (!next.confirmed) return create(text, emptyList(), confirmed = confirmed)
        if (!confirmed) return next
        return create(
            text,
            ranges.flatMap { old ->
                next.ranges.mapNotNull { new ->
                    val start = maxOf(old.start, new.start)
                    val end = minOf(old.endExclusive, new.endExclusive)
                    PrivacyProtectedRange(start, end).takeIf { start < end }
                }
            }
        )
    }

    companion object {
        fun create(text: String, ranges: List<PrivacyProtectedRange>, confirmed: Boolean = true): PrivacyProtectionReceipt {
            val merged = mutableListOf<PrivacyProtectedRange>()
            ranges.filter { it.start >= 0 && it.endExclusive > it.start && it.endExclusive <= text.length }
                .sortedBy { it.start }.forEach { range ->
                    val previous = merged.lastOrNull()
                    if (previous != null && range.start <= previous.endExclusive) {
                        merged[merged.lastIndex] = previous.copy(endExclusive = maxOf(previous.endExclusive, range.endExclusive))
                    } else {
                        merged += range
                    }
                }
            return PrivacyProtectionReceipt(digest(text), merged, confirmed)
        }

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun occurrences(text: String, value: String): List<PrivacyProtectedRange> = buildList {
            if (value.isEmpty()) return@buildList
            var offset = 0
            while (offset <= text.length - value.length) {
                val found = text.indexOf(value, offset)
                if (found < 0) break
                add(PrivacyProtectedRange(found, found + value.length))
                offset = found + 1
            }
        }
    }
}

internal data class PrivateProtectedText(
    val text: String,
    val receipt: PrivacyProtectionReceipt,
    val ordinaryModalProof: PrivateOrdinaryModalProof? = null
)

/** Revisions and edits cannot inherit evidence from a different body. */
fun MessageV2.effectivePrivacyProtectedValues(): List<String> {
    val text = effectiveContent()
    val items = effectiveTimeline()
    val whole = items.filter { it.type == AssistantTimelineItemType.NOTICE && it.privacyProtection != null }
        .mapNotNull { it.privacyProtection }.lastOrNull { it.validFor(text) }
        ?.takeIf { it.confirmed } ?: return emptyList()
    // Rendering removes Markdown delimiters. Keep the exact protected phrase,
    // and expose its Unicode words so emphasis or links inside that phrase do
    // not erase the local protection indicator. This is display projection of
    // existing evidence, never another privacy detector.
    val word = Regex("""[\p{L}\p{M}\p{N}]+(?:['’-][\p{L}\p{M}\p{N}]+)*""")
    val candidates = whole.ranges.flatMap { range ->
        val value = text.substring(range.start, range.endExclusive)
        listOf(value) + word.findAll(value).map { it.value }.toList()
    }.filter(String::isNotBlank).distinct()
    // The renderer matches exact values. A single unprotected occurrence,
    // including a substring in another word, suppresses that display value.
    return candidates.filter { value ->
        var offset = text.indexOf(value)
        var protected = true
        while (offset >= 0 && protected) {
            protected = whole.ranges.any { it.start <= offset && it.endExclusive >= offset + value.length }
            offset = text.indexOf(value, offset + 1)
        }
        protected
    }
}
