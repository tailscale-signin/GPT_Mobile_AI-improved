package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale

/** A task reads one captured workflow page until it explicitly requests fresh remote evidence. */
internal class GitHubWorkflowPageCapture(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    internal data class Captured(val payload: String, val observedAtMillis: Long, val at: Long)
    private val pages = linkedMapOf<String, Captured>()
    private var bytes = 0L
    private fun stream(owner: String, repo: String, perPage: Int) = "${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}|$perPage|"
    private fun remove(key: String) {
        pages.remove(key)?.let { bytes -= it.payload.toByteArray(Charsets.UTF_8).size }
    }

    @Synchronized fun read(owner: String, repo: String, page: Int, perPage: Int, verifyLive: Boolean): Captured? {
        val prefix = stream(owner, repo, perPage)
        if (verifyLive) {
            if (page == 1) pages.keys.filter { it.startsWith(prefix) }.forEach(::remove) else remove(prefix + page)
            return null
        }
        val key = prefix + page
        val captured = pages[key] ?: return null
        if (now() - captured.at !in 0..300_000) {
            remove(key)
            return null
        }
        return captured
    }

    @Synchronized fun remember(owner: String, repo: String, page: Int, perPage: Int, payload: String) {
        val size = payload.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) return
        val key = stream(owner, repo, perPage) + page
        remove(key)
        pages[key] = Captured(payload, wallClock(), now())
        bytes += size
        while (pages.size > 128 || bytes > MAX_BYTES) remove(pages.keys.first())
    }

    @Synchronized fun clear() {
        pages.clear()
        bytes = 0
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
