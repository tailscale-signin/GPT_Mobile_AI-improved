package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Stable preparation order; inventory membership and immutable blob reuse are unchanged. */
internal object RepositorySourcePriority {
    const val VERSION = 2
    const val BOUNDED_SAMPLE = "BOUNDED_SOURCE_SAMPLE"
    const val SAMPLE_FILES = 4

    fun ordered(entries: List<JsonObject>): List<JsonObject> = entries.sortedWith(
        compareBy<JsonObject> { category(it.path()) }.thenBy { it.path().lowercase(Locale.ROOT) }.thenBy { it.path() }
    )

    fun sample(entries: List<JsonObject>): List<String> = ordered(entries)
        .asSequence()
        .filter { PhoneRepositorySourceIndex.supports(it.path()) && it["mode"] !in setOf(JsonPrimitive("120000"), JsonPrimitive("160000")) }
        .map { it.path() }
        .take(SAMPLE_FILES)
        .toList()

    private fun category(path: String): Int {
        val parts = path.lowercase(Locale.ROOT).split('/')
        val name = parts.last()
        // Vendored/generated source is still discoverable and explicitly readable.
        // Its size and alphabetical position must not delay the app's own source.
        if (parts.any { it in setOf("vendor", "vendors", "third_party", "third-party", "node_modules", "generated", "dist", "mathjax") } ||
            name.endsWith(".min.js") ||
            name.endsWith(".min.css")
        ) {
            return 5
        }
        val test = parts.any { it in setOf("test", "tests", "androidtest", "jvmtest", "commontest", "testfixtures") } ||
            name.endsWith("test.kt") ||
            name.endsWith("test.java") ||
            name.startsWith("test_")
        if (test) return 1
        if (PhoneRepositorySourceIndex.supports(path)) return 0
        if (name == "agents.md" ||
            name == "readme.md" ||
            parts.any { it in setOf(".github", "gradle") } ||
            name.substringAfterLast('.') in setOf("kts", "gradle", "toml", "properties", "yaml", "yml", "xml")
        ) {
            return 2
        }
        if (parts.any { it in setOf("docs", "doc", "documentation") } || name.endsWith(".md")) return 3
        return 4
    }

    private fun JsonObject.path() = (get("path") as? JsonPrimitive)?.contentOrNull.orEmpty()
}
