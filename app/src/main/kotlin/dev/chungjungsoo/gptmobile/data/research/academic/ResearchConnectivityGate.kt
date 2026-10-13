package dev.chungjungsoo.gptmobile.data.research.academic

/** Correlated DNS failures, not one inaccessible site, trigger a short shared recovery window. */
internal class ResearchConnectivityGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        val shared = ResearchConnectivityGate()
    }
    private val failedHosts = linkedMapOf<String, Long>()
    private var blockedUntil = 0L

    @Synchronized fun available() = now() >= blockedUntil

    @Synchronized fun success() {
        failedHosts.clear()
        blockedUntil = 0
    }

    @Synchronized fun failed(host: String, error: Throwable) {
        if (generateSequence(error) { it.cause }.none { it is java.net.UnknownHostException }) return
        val time = now()
        failedHosts.entries.removeAll { time - it.value > 15_000 }
        failedHosts[host] = time
        if (failedHosts.size >= 2) blockedUntil = time + 15_000
    }
}
