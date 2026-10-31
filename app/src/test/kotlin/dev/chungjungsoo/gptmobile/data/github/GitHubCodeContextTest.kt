package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCodeContextTest {
    @Test fun importsAndTestsStayBoundToTheSameTreeAndRevision() {
        val source = "src/main/kotlin/dev/example/A.kt"
        val loaded = "src/main/kotlin/dev/example/B.kt"
        val unloaded = "src/main/kotlin/dev/example/C.kt"
        val test = "src/test/kotlin/dev/example/ATest.kt"
        val context = GitHubCodeContext.build(mapOf(source to "package dev.example\nimport dev.example.B\nimport dev.example.C\n", loaded to "class B"), listOf(source, loaded, unloaded, test, "AGENTS.md"), "a".repeat(40))
        val edges = context["edges"]!!.jsonArray
        assertEquals(listOf(loaded, unloaded), edges.map { it.jsonObject["to"]!!.jsonPrimitive.content })
        assertEquals("true", edges.first().jsonObject["loaded"]!!.jsonPrimitive.content)
        assertEquals(unloaded, context["suggested_ranges"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(test, context["test_candidates"]!!.jsonObject[source]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("a".repeat(40), context["commit_sha"]!!.jsonPrimitive.content)
        assertEquals("false", context["compiler_resolution"]!!.jsonPrimitive.content)
    }

    @Test fun externalAndAmbiguousImportsNeverClaimAResolvedCompilerSymbol() {
        val context = GitHubCodeContext.build(mapOf("A.kt" to "import java.util.List\nimport dev.example.*\n"), listOf("one/dev/example/B.kt", "two/dev/example/B.kt"), "b".repeat(40))
        assertEquals("1", context["unresolved_or_external_imports"]!!.jsonPrimitive.content)
        assertTrue(context["edges"]!!.jsonArray.all { it.jsonObject["ambiguous"]!!.jsonPrimitive.content == "true" })
        assertFalse(context.toString().contains("compiler_resolution\":true"))
    }
}
