package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryContextPackSelectionTest {
    @Test fun exactRangesHaveSeparateSourceAndOutputBudgets() {
        val value = requireNotNull(RepositoryContextPackSelection.arguments("src/Large.kt:400-480\nsrc/Large.kt:1200-1260", true, "builtin", "json", "4096"))
        assertFalse("paths" in value)
        assertEquals(2, (value["ranges"] as JsonArray).size)
        assertEquals(JsonPrimitive(4096), value["max_tokens"])
        assertEquals(JsonPrimitive(65536), value["max_bytes"])
        assertEquals(JsonPrimitive(2097152), value["max_input_bytes"])
    }

    @Test fun invalidOrAmbiguousRangesCannotBeSubmitted() {
        for (invalid in listOf("src/File.kt:0-2", "src/File.kt:10-9", "src/File.kt:1-1001", "../secret:1-2", "/etc/config:1-2", "src/File.kt:999999999999-9999999999999", "src/File.kt")) {
            assertNull(invalid, RepositoryContextPackSelection.arguments(invalid, true, "builtin", "markdown", ""))
        }
        assertNull(RepositoryContextPackSelection.arguments("src/A.kt:1-2\nsrc/A.kt:10-11", true, "repomix", "json", ""))
        assertNull(RepositoryContextPackSelection.arguments("src/A.kt", false, "builtin", "json", "unknown"))
        assertNotNull(RepositoryContextPackSelection.arguments("src/A.kt", false, "builtin", "json", ""))
    }
}
