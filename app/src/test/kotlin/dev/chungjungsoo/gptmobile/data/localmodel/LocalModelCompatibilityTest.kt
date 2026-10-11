package dev.chungjungsoo.gptmobile.data.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCompatibilityTest {
    @Test
    fun `Gemma 4 Qualcomm exports can reach runtime compatibility checks`() {
        val repo = "litert-community/gemma-4-E2B-it-litert-lm"
        val fileNames = listOf(
            "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
            "gemma-4-E2B-it_qualcomm_qcs8275.litertlm",
            "gemma-4-E2B-it.litertlm"
        )
        for (fileName in fileNames) {
            assertNull(LocalModelCompatibility.unsupportedReason(repo, fileName))
            assertNull(
                LocalModelCompatibility.installedPackageIssue(
                    "https://huggingface.co/$repo/resolve/main/$fileName",
                    "/models/$fileName"
                )
            )
            // Imports and legacy rows may lack the source URL or retain different casing.
            assertNull(LocalModelCompatibility.installedPackageIssue("", "/models/${fileName.uppercase()}"))
        }
    }

    @Test
    fun `admitting Gemma 4 does not mark it qualified`() {
        val record = LocalModelCompatibility.records.single { it.id == "gemma4-qnn-dsp-queue" }
        assertEquals("UNVERIFIED", record.state)
        assertNull(LocalModelCompatibility.exactQualification(null))
        assertNull(
            LocalModelCompatibility.exactQualification(
                ModelCompatibilityIdentity("a".repeat(64), "0.18.0", "b".repeat(64), "SM8750", "firmware")
            )
        )
    }

    @Test
    fun `unrelated documented MiniCPM allocation failures remain actionable`() {
        assertTrue(
            LocalModelCompatibility.installedPackageIssue("", "/models/MiniCPM5-1B-qualcomm-sm8750.litertlm")
                ?.contains("QNN memory allocation failure") == true
        )
    }
}
