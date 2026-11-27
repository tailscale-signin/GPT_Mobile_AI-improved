package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositorySourcePriorityTest {
    private fun entry(path: String) = buildJsonObject {
        put("path", path)
        put("type", "blob")
        put("mode", "100644")
    }

    @Test fun firstPartySourceAndTestsPrecedeAlphabeticallyEarlierVendorAssets() {
        val paths = listOf(
            "app/src/main/assets/MathJax/braille-d.js",
            "app/src/main/kotlin/example/Chat.kt",
            "app/src/test/kotlin/example/ChatTest.kt",
            "docs/architecture/repository-guide.md",
            "vendor/aa.ts",
            ".github/workflows/check.yml"
        )
        val ordered = RepositorySourcePriority.ordered(paths.reversed().map(::entry)).map { it.getValue("path").jsonPrimitive.content }
        assertEquals(paths.toSet(), ordered.toSet())
        assertEquals("app/src/main/kotlin/example/Chat.kt", ordered.first())
        assertTrue(ordered.indexOf("app/src/test/kotlin/example/ChatTest.kt") < ordered.indexOf(paths.first()))
        assertTrue(ordered.indexOf("docs/architecture/repository-guide.md") < ordered.indexOf("vendor/aa.ts"))
        assertEquals(ordered, RepositorySourcePriority.ordered(paths.map(::entry)).map { it.getValue("path").jsonPrimitive.content })
    }

    @Test fun qualificationUsesOnlyNamedSupportedSourceFilesAndSkipsSymlinks() {
        val entries = listOf(entry("README.md"), entry("src/a.kt"), entry("src/b.kt"), entry("src/c.kt"), entry("src/d.kt"), entry("src/e.kt")) +
            buildJsonObject {
                put("path", "src/0.kt")
                put("mode", "120000")
            }
        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt", "src/d.kt"), RepositorySourcePriority.sample(entries))
        assertTrue(RepositorySourcePriority.sample(listOf(entry("README.md"))).isEmpty())
        assertEquals(JsonPrimitive("100644"), entries.first()["mode"])
    }
}
