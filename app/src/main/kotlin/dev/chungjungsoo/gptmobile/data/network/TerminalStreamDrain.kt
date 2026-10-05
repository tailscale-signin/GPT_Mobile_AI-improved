package dev.chungjungsoo.gptmobile.data.network

/** Allow trailing usage after the FIRST finish marker, never indefinite heartbeats. */
internal class TerminalStreamDrain(
    private val graceMillis: Long = 1000L,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init {
        require(graceMillis >= 0L) { "Terminal stream grace must not be negative" }
    }

    private var terminalAt: Long? = null

    fun markTerminal() {
        if (terminalAt == null) terminalAt = nowNanos()
    }

    fun remainingMillis(): Long? = terminalAt?.let { started ->
        (graceMillis - ((nowNanos() - started).coerceAtLeast(0L) / 1_000_000L)).coerceAtLeast(0L)
    }
}
