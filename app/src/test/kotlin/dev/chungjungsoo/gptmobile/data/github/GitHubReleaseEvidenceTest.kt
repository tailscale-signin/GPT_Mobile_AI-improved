package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseEvidenceTest {
    private val source = "a".repeat(40)
    private val digest = "b".repeat(64)
    private val release = """{"id":17,"tag_name":"v1.0","draft":false,"published_at":"2026-11-10","assets":[{"id":8,"name":"app.apk","size":10,"state":"uploaded","digest":"sha256:$digest"}]}"""
    private val run = """{"id":42,"workflow_id":9,"head_sha":"$source","run_attempt":1,"status":"completed","conclusion":"success","event":"workflow_dispatch"}"""
    private val proof = """{"sourceSha":"$source","releaseId":17,"tag":"v1.0","status":"PUBLISHED_VERIFIED","tagState":"VERIFIED","assetSourceVerified":true,"workflowRun":"42","workflowAttempt":"1","assets":[{"name":"app.apk","sha256":"$digest","bytes":10}],"assetAttestations":[{"name":"app.apk","sha256":"$digest","bytes":10,"sourceSha":"$source","signerWorkflow":"o/r/.github/workflows/release-build.yml","verification":"SIGNATURE_CERTIFICATE_SOURCE_AND_SIGNER_VERIFIED"}]}"""
    private fun objectOf(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun archive(text: String = proof): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("release-publication.json"))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun provenanceRequiresExactRunAttemptSourceAndEveryRemoteBinaryDigest() {
        assertTrue(GitHubReleaseEvidence.verify(objectOf(proof), objectOf(release), objectOf(run), "o/r", source))
        assertFalse(GitHubReleaseEvidence.verify(objectOf(proof), objectOf(release), objectOf(run.replace("\"run_attempt\":1", "\"run_attempt\":2")), "o/r", source))
        assertFalse(GitHubReleaseEvidence.verify(objectOf(proof), objectOf(release.replace(digest, "c".repeat(64))), objectOf(run), "o/r", source))
        assertFalse(GitHubReleaseEvidence.verify(objectOf(proof), objectOf(release), objectOf(run), "other/repo", source))
        assertFalse(GitHubReleaseEvidence.verify(objectOf(proof.replace("\"assetSourceVerified\":true", "\"assetSourceVerified\":false")), objectOf(release), objectOf(run), "o/r", source))
    }

    @Test fun archiveDigestAndExpansionLimitsAreEnforced() {
        val bytes = archive()
        assertEquals(proof, GitHubReleaseEvidence.archive(bytes, "release-publication.json", sha(bytes)))
        assertTrue(runCatching { GitHubReleaseEvidence.archive(bytes, "release-publication.json", "f".repeat(64)) }.isFailure)
        val bomb = archive("a".repeat(300 * 1024))
        assertTrue(runCatching { GitHubReleaseEvidence.archive(bomb, "release-publication.json", sha(bomb)) }.isFailure)
    }

    @Test fun statusVerifiesTrustedArtifactAndRechecksRunAttemptWithoutClaimingDispatchOwnership() = runTest {
        val bytes = archive()
        var reads = 0
        var changedAttempt = false
        var changedAssets = false
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/releases" -> respond("[$release]")
                    "/repos/o/r/releases/17" -> respond(if (changedAssets) release.replace(digest, "c".repeat(64)) else release)
                    "/repos/o/r/actions/workflows" -> respond("""{"workflows":[{"id":9,"name":"Publish Signed Release","path":".github/workflows/release-build.yml","state":"active"}]}""")
                    "/repos/o/r/actions/runs/42" -> {
                        reads++
                        respond(if (changedAttempt && reads % 2 == 0) run.replace("\"run_attempt\":1", "\"run_attempt\":2") else run)
                    }
                    "/repos/o/r/actions/runs/42/artifacts" -> respond("""{"total_count":1,"artifacts":[{"id":5,"name":"release-evidence-$source","expired":false,"digest":"sha256:${sha(bytes)}"}]}""")
                    "/repos/o/r/actions/artifacts/5/zip" -> respond(bytes)
                    "/repos/o/r/commits/v1.0" -> respond("""{"sha":"$source"}""")
                    else -> error("Unexpected endpoint ${request.url.encodedPath}")
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("", http, conditionalReads = false)
            val args = buildJsonObject {
                put("owner", "o")
                put("repo", "r")
                put("tag_name", "v1.0")
                put("workflow_run_id", 42)
                put("expected_head_sha", source)
            }
            val result = client.execute("release_status", args).jsonObject
            assertEquals(JsonPrimitive(true), result["release_asset_source_verified"])
            assertEquals(JsonPrimitive("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE"), result["workflow_correlation"])
            changedAttempt = true
            val stale = client.execute("release_status", args).jsonObject
            assertEquals(JsonPrimitive(false), stale["release_asset_source_verified"])
            changedAttempt = false
            changedAssets = true
            val replaced = client.execute("release_status", args).jsonObject
            assertEquals(JsonPrimitive(false), replaced["release_asset_source_verified"])
        } finally {
            http.close()
        }
    }
}
