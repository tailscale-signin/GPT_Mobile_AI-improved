package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkGuardVerifierTest {
    @Test fun equivalentGuardExpressionsAreAccepted() {
        listOf(
            "ciGreen && approved",
            "approved && ciGreen",
            "!(!ciGreen || !approved)",
            "(ciGreen == true) && (approved != false)"
        ).forEach { expression ->
            assertTrue(expression, BenchmarkGuardVerifier.passes("release-gate", "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = $expression"))
        }
        assertTrue(BenchmarkGuardVerifier.passes("security-review", "fun elevated(role: String, tokenValid: Boolean): Boolean { return tokenValid && role == \"admin\"; }"))
    }

    @Test fun matchingTextInCommentsOrUnusedFunctionsDoesNotPass() {
        listOf(
            "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = true // ciGreen && approved",
            "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = true /* ciGreen && approved */",
            "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = ciGreen || approved",
            "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = true; fun unused() = ciGreen && approved",
            "ciGreen && approved"
        ).forEach { assertFalse(it, BenchmarkGuardVerifier.passes("release-gate", it)) }
    }

    @Test fun incorrectSecurityTruthTableFails() {
        listOf("role == \"admin\" || tokenValid", "role != \"admin\" && tokenValid", "tokenValid", "true").forEach {
            assertFalse(it, BenchmarkGuardVerifier.passes("security-review", "fun elevated(role: String, tokenValid: Boolean) = $it"))
        }
    }

    @Test fun malformedAndDeepInputsAreRejectedWithoutEscapingTheVerifier() {
        val prefix = "fun releaseAllowed(ciGreen: Boolean, approved: Boolean) = "
        listOf("", "ciGreen &&", "unknown", "1", "(".repeat(1000) + "ciGreen" + ")".repeat(1000), "true".repeat(1500)).forEach {
            assertFalse(BenchmarkGuardVerifier.passes("release-gate", prefix + it))
        }
    }

    @Test fun legacyExpressionScopeStillUsesTruthTablesAndCannotPassBySubstring() {
        assertTrue(BenchmarkGuardVerifier.passes("release-gate", "approved && ciGreen", allowLegacyExpression = true))
        assertFalse(BenchmarkGuardVerifier.passes("release-gate", "true // ciGreen && approved", allowLegacyExpression = true))
        assertFalse(BenchmarkGuardVerifier.passes("release-gate", "ciGreen || approved", allowLegacyExpression = true))
        assertFalse(BenchmarkGuardVerifier.passes("release-gate", "approved && ciGreen"))
    }
}
