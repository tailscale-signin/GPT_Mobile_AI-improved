package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.HttpMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Native benchmark requests have no agent-selected endpoint, workflow, ref update or rerun operation. */
internal object GitHubPhoneBenchmarkApi {
    private val sha = Regex("[a-f0-9]{40}")
    private val submission = Regex("phone_[a-f0-9]{32}")
    private val branch = Regex("benchmark/phone/[A-Za-z0-9_-]{8,96}/phone_[a-f0-9]{32}")

    fun validate(repository: String, path: String, method: HttpMethod, body: JsonObject?) {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        val root = "/repos/$repository"
        require(path == root || path.startsWith("$root/")) { "PHONE_BENCHMARK_REPOSITORY_MISMATCH" }
        val relative = path.removePrefix(root)
        if (method == HttpMethod.Get) {
            require(body == null)
            val allowed = relative.isEmpty() ||
                Regex("/git/(?:commits|trees)/[a-f0-9]{40}(?:\\?recursive=1)?").matches(relative) ||
                Regex("/compare/[a-f0-9]{40}\\.\\.\\.[a-f0-9]{40}").matches(relative) ||
                relative.startsWith("/git/ref/heads/") &&
                branch.matches(decode(relative.removePrefix("/git/ref/heads/"))) ||
                Regex("/contents/(?:\\.benchmark-phone/[A-Za-z0-9_./-]+|\\.github/workflows/phone-benchmark-phone_[a-f0-9]{32}\\.yml)\\?ref=[a-f0-9]{40}").matches(relative) &&
                relative.substringBefore('?').split('/').none { it == ".." || it == "." } ||
                Regex("/actions/runs\\?branch=[A-Za-z0-9_%.-]+&event=push&head_sha=[a-f0-9]{40}&per_page=100").matches(relative) ||
                Regex("/actions/runs/[1-9][0-9]*(?:/attempts/[1-9][0-9]*/jobs\\?per_page=100|/artifacts\\?per_page=100)?").matches(relative)
            require(allowed) { "PHONE_BENCHMARK_READ_OUTSIDE_SCOPE" }
            return
        }
        require(method == HttpMethod.Post && body != null) { "PHONE_BENCHMARK_MUTATION_OUTSIDE_SCOPE" }
        when {
            relative == "/git/trees" -> {
                require(body.keys == setOf("base_tree", "tree") && sha.matches(body.text("base_tree")))
                val files = (body["tree"] as? JsonArray)?.map { it as? JsonObject ?: error("PHONE_BENCHMARK_TREE_INVALID") }.orEmpty()
                require(files.size == 2 && files.all { it.keys == setOf("path", "mode", "type", "content") && it.text("mode") == "100644" && it.text("type") == "blob" })
                val first = files.singleOrNull { it.text("path").startsWith(".benchmark-phone/submissions/") } ?: error("PHONE_BENCHMARK_SUBMISSION_PATH_INVALID")
                val id = first.text("path").removePrefix(".benchmark-phone/submissions/").removeSuffix(".json")
                require(submission.matches(id) && first.text("path") == ".benchmark-phone/submissions/$id.json")
                require(files.singleOrNull { it !== first }?.text("path") == ".github/workflows/phone-benchmark-$id.yml")
                require(files.all { it.text("content").encodeToByteArray().size <= 512 * 1024 && '\u0000' !in it.text("content") })
            }
            relative == "/git/commits" -> {
                require(body.keys == setOf("message", "tree", "parents") && sha.matches(body.text("tree")))
                val parents = (body["parents"] as? JsonArray).orEmpty()
                require(parents.size == 1 && sha.matches((parents.single() as? JsonPrimitive)?.content.orEmpty()))
                require(body.text("message").matches(Regex("Benchmark submission phone_[a-f0-9]{32}")))
            }
            relative == "/git/refs" -> {
                require(body.keys == setOf("ref", "sha") && sha.matches(body.text("sha")))
                require(body.text("ref").startsWith("refs/heads/") && branch.matches(body.text("ref").removePrefix("refs/heads/")))
            }
            Regex("/actions/runs/[1-9][0-9]*/cancel").matches(relative) -> require(body.isEmpty())
            else -> error("PHONE_BENCHMARK_MUTATION_OUTSIDE_SCOPE")
        }
    }

    /** A bound artifact is one small result document, never an arbitrary archive extraction. */
    fun archive(bytes: ByteArray, digest: String): String {
        require(digest.matches(Regex("[a-f0-9]{64}")) && bytes.size <= 4 * 1024 * 1024)
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == digest) { "PHONE_BENCHMARK_ARTIFACT_DIGEST_MISMATCH" }
        return ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = requireNotNull(zip.nextEntry) { "PHONE_BENCHMARK_RESULT_MISSING" }
            require(!entry.isDirectory && entry.name == "result.json") { "PHONE_BENCHMARK_ARTIFACT_ENTRY_INVALID" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 256 * 1024) {
                val count = zip.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            require(output.size() <= 256 * 1024 && zip.nextEntry == null) { "PHONE_BENCHMARK_ARTIFACT_BOUND_EXCEEDED" }
            output.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
