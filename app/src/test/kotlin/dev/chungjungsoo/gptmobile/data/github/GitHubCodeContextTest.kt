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

    @Test fun relativeScriptImportsResolveAgainstTheImportingDirectoryAtThePinnedRevision() {
        val source = "src/views/Main.tsx"
        val context = GitHubCodeContext.build(
            mapOf(source to "import { value } from '../lib/value.js'\nexport { Widget } from './widgets'\nimport './setup'\nimport React from 'react'"),
            listOf(source, "src/lib/value.ts", "src/views/widgets/index.tsx", "src/views/setup.ts", "elsewhere/setup.ts", "react.ts"),
            "c".repeat(40)
        )
        assertEquals(
            listOf("src/lib/value.ts", "src/views/widgets/index.tsx", "src/views/setup.ts"),
            context["edges"]!!.jsonArray.map { it.jsonObject["to"]!!.jsonPrimitive.content }
        )
        assertEquals("1", context["unresolved_or_external_imports"]!!.jsonPrimitive.content)
        assertEquals("c".repeat(40), context["commit_sha"]!!.jsonPrimitive.content)
    }

    @Test fun relativeImportsCannotEscapeTheRepositoryAndRootWildcardsWork() {
        val context = GitHubCodeContext.build(
            mapOf("A.ts" to "import '../outside'", "B.kt" to "import example.*"),
            listOf("outside.ts", "example/Thing.kt"),
            "a".repeat(40)
        )
        assertEquals("1", context["unresolved_or_external_imports"]!!.jsonPrimitive.content)
        assertEquals("example/Thing.kt", context["edges"]!!.jsonArray.single().jsonObject["to"]!!.jsonPrimitive.content)
    }

    @Test fun importStressStopsAtTheDeclaredBoundAndReportsIncompleteCoverage() {
        val sources = (1..100).associate { "src/$it.kt" to "import example.Thing\n".repeat(1000) }
        val context = GitHubCodeContext.build(sources, listOf("example/Thing.kt"), "a".repeat(40))
        assertEquals("512", context["imports_observed"]!!.jsonPrimitive.content)
        assertEquals(64, context["edges"]!!.jsonArray.size)
        assertEquals("true", context["source_scan_truncated"]!!.jsonPrimitive.content)
        assertEquals("true", context["edges_bounded"]!!.jsonPrimitive.content)
    }

    @Test fun testCandidatesIncludeCommonScriptPythonAndJvmNamingConventions() {
        val paths = listOf("test_value.py", "value_test.py", "value.test.ts", "value.spec.ts", "valueTests.kt", "unrelated.kt")
        assertEquals(paths.take(5), GitHubSearchContext.related("value.py", paths).map { it.jsonPrimitive.content })
    }
}
