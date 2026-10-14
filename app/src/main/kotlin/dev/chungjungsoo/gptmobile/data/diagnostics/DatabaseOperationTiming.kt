package dev.chungjungsoo.gptmobile.data.diagnostics

/** Opt-in elapsed timing for a named DAO operation; SQL, bind arguments and rows are never logged. */
internal suspend fun <T> timedDatabaseOperation(operation: String, block: suspend () -> T): T {
    if (!AppLogRecorder.enabled.value) return block()
    require(operation.matches(Regex("[A-Za-z0-9_.]{1,80}")))
    val start = System.nanoTime()
    val mainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    try {
        return block()
    } finally {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (elapsed >= 100 || mainThread) AppLogRecorder.record("Database", "DAO_TIMING · operation=$operation · elapsedMs=$elapsed · mainThread=$mainThread · measurement=COROUTINE_ELAPSED", "W")
    }
}
