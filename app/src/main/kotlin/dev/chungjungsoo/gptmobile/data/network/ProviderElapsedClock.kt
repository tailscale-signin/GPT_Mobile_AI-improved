package dev.chungjungsoo.gptmobile.data.network

/** Android time includes deep sleep. Plain JVM transport tests have no Android clock. */
internal object ProviderElapsedClock {
    private val read: () -> Long = try {
        val androidClock = { android.os.SystemClock.elapsedRealtime() }
        androidClock()
        androidClock
    } catch (stub: RuntimeException) {
        // Android's unit-test stub throws this exact failure. Select once so a live
        // process never changes clock domains, and never mask a device clock failure.
        if (!stub.message.orEmpty().contains("not mocked")) throw stub
        val jvmClock = { System.nanoTime() / 1_000_000 }
        jvmClock
    }

    fun now(): Long = read()
}
