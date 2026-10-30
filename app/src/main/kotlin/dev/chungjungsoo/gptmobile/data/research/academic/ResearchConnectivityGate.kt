package dev.chungjungsoo.gptmobile.data.research.academic

import java.net.UnknownHostException

/** Correlated DNS loss admits one recovery probe while known healthy hosts keep working. */
internal class ResearchConnectivityGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        val shared = ResearchConnectivityGate()
        private const val WINDOW = 15_000L
        private const val HEALTHY_WINDOW = 120_000L
        private const val MAX_HOSTS = 128
    }

    private val failedHosts = linkedMapOf<String, Long>()
    private val healthyHosts = linkedMapOf<String, Long>()
    private var blockedUntil = 0L
    private var backoff = WINDOW
    private var leaseSequence = 0L
    private var probeLease: Long? = null
    private var probeStarted = 0L

    @Synchronized fun available(host: String? = null): Boolean {
        expire()
        if (dnsIndependent(host) || healthy(host)) return true
        return now() >= blockedUntil && probeLease == null
    }

    /** Zero means an ordinary request; a positive token owns the single recovery probe. */
    @Synchronized fun acquire(host: String? = null): Long? {
        expire()
        if (dnsIndependent(host) || healthy(host) || failedHosts.size < 2 && blockedUntil == 0L) return 0L
        if (now() < blockedUntil || probeLease != null) return null
        return (++leaseSequence).also {
            probeLease = it
            probeStarted = now()
        }
    }

    @Synchronized fun release(lease: Long) {
        if (lease != 0L && probeLease == lease) probeLease = null
    }

    @Synchronized fun success(host: String) {
        val key = host.lowercase().trim().removeSuffix(".")
        if (key.isEmpty()) return
        failedHosts.remove(key)
        healthyHosts[key] = now()
        trim(healthyHosts)
        if (failedHosts.isEmpty()) {
            blockedUntil = 0L
            backoff = WINDOW
        } else if (probeLease != null) {
            blockedUntil = maxOf(blockedUntil, now() + WINDOW)
        }
    }

    /** Compatibility for explicit network-change callers only; responses must use success(host). */
    @Synchronized fun success() = reset()

    @Synchronized fun reset() {
        failedHosts.clear()
        healthyHosts.clear()
        blockedUntil = 0L
        backoff = WINDOW
        probeLease = null
    }

    @Synchronized fun failed(host: String, error: Throwable) {
        if (dnsIndependent(host)) return
        if (generateSequence(error) { it.cause }.take(16).none { it is UnknownHostException }) return
        val key = host.lowercase().trim().removeSuffix(".")
        if (key.isEmpty()) return
        val time = now()
        healthyHosts.remove(key)
        if (blockedUntil == 0L) failedHosts.entries.removeAll { time - it.value > WINDOW }
        failedHosts[key] = time
        trim(failedHosts)
        if (failedHosts.size >= 2) {
            blockedUntil = time + backoff
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }

    /** Literal endpoints (including LAN/Tailscale IPs) do not depend on public DNS. */
    private fun dnsIndependent(host: String?): Boolean {
        val value = host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (value.equals("localhost", true)) return true
        if (':' in value) return value.matches(Regex("[0-9a-fA-F:.]+"))
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 }
    }

    private fun healthy(host: String?): Boolean = host?.lowercase()?.trim()?.removeSuffix(".")?.let { key ->
        key !in failedHosts && healthyHosts[key]?.let { now() - it < HEALTHY_WINDOW } == true
    } == true

    private fun expire() {
        healthyHosts.entries.removeAll { now() - it.value >= HEALTHY_WINDOW }
        if (probeLease != null && now() - probeStarted >= 120_000L) probeLease = null
    }

    private fun trim(values: MutableMap<String, Long>) {
        while (values.size > MAX_HOSTS) values.remove(values.keys.first())
    }
}
