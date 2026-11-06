package dev.chungjungsoo.gptmobile.data.github

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Credential-local negative observations, never a default-branch fallback or proof of deletion. */
internal class GitHubRefAvailability(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cooldownMillis: Long = 30_000
) {
    private data class Ref(val repository: String, val kind: String, val encoded: String, val lookup: Boolean)
    private data class Rejection(val status: Int, val until: Long)
    private val rejected = LinkedHashMap<String, Rejection>()

    init {
        require(cooldownMillis in 1..60_000)
    }

    private fun ref(path: String): Ref? {
        val parts = path.substringBefore('?').split('/').filter(String::isNotEmpty)
        if (parts.size < 5 || parts[0] != "repos") return null
        val repository = (parts[1] + "/" + parts[2]).lowercase(Locale.ROOT)
        return when {
            parts[3] == "commits" && (parts.size == 5 || parts.size == 6 && parts[5] in setOf("check-runs", "status", "statuses")) ->
                Ref(repository, if (parts[4].matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) "COMMIT_SHA" else "COMMITISH", parts[4], parts.size == 5)
            parts[3] == "branches" && (parts.size == 5 || parts.size == 6 && parts[5] == "protection") ->
                Ref(repository, "BRANCH", parts[4], parts.size == 5)
            parts.size == 7 && parts[3] == "git" && parts[4] == "ref" && parts[5] == "heads" ->
                Ref(repository, "BRANCH", parts[6], true)
            else -> null
        }
    }

    private fun Ref.key() = "$repository:$kind:$encoded"

    @Synchronized fun requireAvailable(path: String) {
        val ref = ref(path) ?: return
        val observed = rejected[ref.key()] ?: return
        val remaining = observed.until - now()
        if (remaining <= 0) {
            rejected.remove(ref.key())
            return
        }
        throw GitHubReadValidation(
            "GITHUB_REF_UNAVAILABLE",
            buildJsonObject {
                put("schema", "github.ref-admission.v1")
                put("reason", "RECENT_REF_LOOKUP_REJECTED")
                put("repository", ref.repository)
                put("ref", URLDecoder.decode(ref.encoded, StandardCharsets.UTF_8.name()))
                put("ref_kind", ref.kind)
                put("observed_http_status", observed.status)
                put("retry_after_ms", remaining)
                put("dispatched", false)
                put("automatic_branch_substitution", false)
                put("message", "This exact ref was recently unavailable or inaccessible. No additional request was sent. List branches and verify access, then explicitly resolve the intended ref with refresh=true; no different branch was selected.")
                put(
                    "recovery_call",
                    buildJsonObject {
                        put("tool", "github_context")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("action", "branches")
                                put("owner", ref.repository.substringBefore('/'))
                                put("repo", ref.repository.substringAfter('/'))
                            }
                        )
                    }
                )
            }
        )
    }

    @Synchronized fun failed(path: String, status: Int, message: String) {
        val ref = ref(path)?.takeIf { it.lookup } ?: return
        val missing = status == 404 ||
            status == 422 &&
            ref.kind != "BRANCH" &&
            Regex("(?i)no commit found|not a valid object name").containsMatchIn(message)
        if (!missing) return
        rejected.entries.removeAll { it.value.until <= now() }
        rejected[ref.key()] = Rejection(status, now() + cooldownMillis)
        while (rejected.size > 128) rejected.remove(rejected.keys.first())
    }

    @Synchronized fun succeeded(path: String) {
        ref(path)?.takeIf { it.lookup }?.let { rejected.remove(it.key()) }
    }

    /** Explicit revalidation or credential replacement only; ordinary retries do not clear it. */
    @Synchronized fun revalidate(repository: String, value: String) {
        val prefix = repository.removePrefix("/repos/").lowercase(Locale.ROOT) + ":"
        val encoded = GitHubWorkspaceClient.segment(value)
        rejected.keys.removeAll { it.startsWith(prefix) && it.substringAfterLast(':') == encoded }
    }

    @Synchronized fun clear() = rejected.clear()
}
