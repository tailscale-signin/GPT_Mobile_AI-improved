from pathlib import Path
root = Path.cwd()
b = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/'
def replace(path, old, new):
    p = root / path
    s = p.read_text()
    if s.count(old) != 1:
        raise RuntimeError('Source precondition failed: ' + path)
    p.write_text(s.replace(old, new))
p = root / (b + 'github/GitHubRefAvailability.kt')
if p.exists():
    raise RuntimeError('New file already exists')
p.write_text('''package dev.chungjungsoo.gptmobile.data.github

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
                        put("arguments", buildJsonObject {
                            put("action", "branches")
                            put("owner", ref.repository.substringBefore('/'))
                            put("repo", ref.repository.substringAfter('/'))
                        })
                    }
                )
            }
        )
    }

    @Synchronized fun failed(path: String, status: Int, message: String) {
        val ref = ref(path)?.takeIf { it.lookup } ?: return
        val missing = status == 404 || status == 422 && ref.kind != "BRANCH" &&
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
''')
replace(b+'github/GitHubWorkspaceClient.kt', '    private val authenticationRejected = authenticationRejected ?: if (client === sharedClient) GitHubSessionCaches.authenticationForCredential(token, apiOrigins.hostIdentity) else java.util.concurrent.atomic.AtomicBoolean()\n', '    private val authenticationRejected = authenticationRejected ?: if (client === sharedClient) GitHubSessionCaches.authenticationForCredential(token, apiOrigins.hostIdentity) else java.util.concurrent.atomic.AtomicBoolean()\n    private val refAvailability = if (client === sharedClient) GitHubSessionCaches.refsForCredential(token, apiOrigins.hostIdentity) else GitHubRefAvailability()\n')
replace(b+'github/GitHubWorkspaceClient.kt', '''            token = refreshed
            responseCache.clear()
            authenticationRejected.set(false)''', '''            token = refreshed
            responseCache.clear()
            refAvailability.clear()
            authenticationRejected.set(false)''')
replace(b+'github/GitHubWorkspaceClient.kt', '''        val root = "/repos/${segment(owner)}/${segment(repo)}"
        if (action in setOf("pr_headers", "issue_headers"))''', '''        val root = "/repos/${segment(owner)}/${segment(repo)}"
        if (action == "resolve_context" && args["refresh"] == JsonPrimitive(true)) {
            val selectedRef = field("commit_sha").ifBlank { field("ref").ifBlank { field("branch").ifBlank { repositorySession.context()?.ref.orEmpty() } } }
            if (selectedRef.isNotBlank()) refAvailability.revalidate(root, selectedRef)
        }
        if (action in setOf("pr_headers", "issue_headers"))''')
replace(b+'github/GitHubWorkspaceClient.kt', '''        check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
        val cacheKey''', '''        check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
        if (method == HttpMethod.Get) refAvailability.requireAvailable(path)
        val cacheKey''')
replace(b+'github/GitHubWorkspaceClient.kt', '                    val category = GitHubFailurePolicy.classify(response.status.value, githubMessage.orEmpty(),', '                    if (method == HttpMethod.Get) refAvailability.failed(path, response.status.value, githubMessage.orEmpty())\n                    val category = GitHubFailurePolicy.classify(response.status.value, githubMessage.orEmpty(),')
replace(b+'github/GitHubWorkspaceClient.kt', 'failure is GitHubRequestRejected || failure is GitHubRequestCircuit.Open', 'failure is GitHubRequestRejected || failure is GitHubRequestCircuit.Open || failure is GitHubReadValidation')
replace(b+'github/GitHubWorkspaceClient.kt', '''                val parsed = if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
                if (method != HttpMethod.Get) responseCache.invalidateMutable''', '''                val parsed = if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
                if (method == HttpMethod.Get) refAvailability.succeeded(path)
                if (method != HttpMethod.Get) responseCache.invalidateMutable''')
replace(b+'github/GitHubSessionCaches.kt', 'val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean())', 'val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(), val refs: GitHubRefAvailability = GitHubRefAvailability())')
replace(b+'github/GitHubSessionCaches.kt', '        state.authenticationRejected.set(false)\n', '        state.authenticationRejected.set(false)\n        state.refs.clear()\n')
replace(b+'github/GitHubSessionCaches.kt', '    fun authenticationForCredential(', '    fun refsForCredential(token: String, host: String = "github.com"): GitHubRefAvailability = state(token, host).refs\n    fun authenticationForCredential(')
replace(b+'github/GitHubSessionCaches.kt', '            it.cache.clear()\n', '            it.cache.clear()\n            it.refs.clear()\n')
replace(b+'github/GitHubInspection.kt', '''                val owner = root.removePrefix("/repos/").substringBefore('/')
                val headPrs''', '''                // Resolve first: a missing branch must not fan out into unrelated PR/CI reads.
                val branchDetails = get("$root/branches/${segment(branch)}")
                val owner = root.removePrefix("/repos/").substringBefore('/')
                val headPrs''')
replace(b+'github/GitHubInspection.kt', '                    put("branch", get("$root/branches/${segment(branch)}"))', '                    put("branch", branchDetails)')
