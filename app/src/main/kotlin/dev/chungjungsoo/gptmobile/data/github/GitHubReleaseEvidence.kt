package dev.chungjungsoo.gptmobile.data.github

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A trusted exact workflow receipt binds every release binary to its observed digest. */
internal object GitHubReleaseEvidence {
    const val WORKFLOW = ".github/workflows/release-build.yml"
    fun archive(bytes: ByteArray, name: String, digest: String): String {
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == digest) { "Release evidence artifact digest changed." }
        var result: String? = null
        var total = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 64 && !entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "Invalid release evidence archive." }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 256 * 1024) {
                    val count = zip.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    if (count > 0) output.write(buffer, 0, count)
                }
                val content = output.toByteArray()
                total += content.size
                check(content.size <= 256 * 1024 && total <= 1024 * 1024) { "Release evidence expands beyond its limit." }
                if (!entry.isDirectory && entry.name == name) {
                    check(result == null) { "Duplicate release publication receipt." }
                    result = content.decodeToString(throwOnInvalidSequence = true)
                }
            }
        }
        return requireNotNull(result) { "Release publication receipt is absent." }
    }

    fun verify(receipt: JsonObject, release: JsonObject, run: JsonObject, repository: String, source: String): Boolean {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
        if (!source.matches(Regex("[a-fA-F0-9]{40}")) ||
            run.text("head_sha") != source ||
            run.text("status") != "completed" ||
            run.text("conclusion") != "success" ||
            receipt.text("sourceSha") != source ||
            receipt["releaseId"] != release["id"] ||
            receipt["tag"] != release["tag_name"] ||
            release["draft"] != JsonPrimitive(false) ||
            receipt.text("status") != "PUBLISHED_VERIFIED" ||
            receipt.text("tagState") != "VERIFIED" ||
            receipt["assetSourceVerified"] != JsonPrimitive(true) ||
            receipt.text("workflowRun") != run.text("id") ||
            receipt.text("workflowAttempt") != run.text("run_attempt")
        ) {
            return false
        }
        val assets = (receipt["assets"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return false
        val attested = (receipt["assetAttestations"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return false
        val remote = (release["assets"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter { it.text("name").endsWith(".apk") || it.text("name").endsWith(".aab") } ?: return false
        if (assets.isEmpty() || assets.size != attested.size || assets.size != remote.size || assets.map { it.text("name") }.distinct().size != assets.size) return false
        return assets.all { asset ->
            val match = remote.singleOrNull { it["name"] == asset["name"] } ?: return@all false
            val proof = attested.singleOrNull { it["name"] == asset["name"] } ?: return@all false
            asset.text("sha256").matches(Regex("[a-f0-9]{64}")) &&
                match.text("digest") == "sha256:" + asset.text("sha256") &&
                match["size"] == asset["bytes"] &&
                match.text("state") == "uploaded" &&
                proof["sha256"] == asset["sha256"] &&
                proof["bytes"] == asset["bytes"] &&
                proof.text("sourceSha") == source &&
                proof.text("signerWorkflow") == "$repository/$WORKFLOW" &&
                proof.text("verification") == "SIGNATURE_CERTIFICATE_SOURCE_AND_SIGNER_VERIFIED"
        }
    }
}
