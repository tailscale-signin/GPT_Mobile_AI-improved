package dev.chungjungsoo.gptmobile.data.diagnostics

/** Method identities only: messages, file paths and object values are never captured. */
internal fun crashDiagnosticSummary(failure: Throwable, threadName: String): String {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val causes = mutableListOf<Throwable>()
    var next: Throwable? = failure
    while (next != null && causes.size < 8 && seen.add(next)) {
        causes += next
        next = next.cause
    }
    val topFrame = causes.lastOrNull()?.stackTrace?.firstOrNull()
    // Stack origin is evidence, not a root-cause claim. A framework throw can
    // still have been triggered by app or SDK code further down the stack.
    val topFrameOrigin = when {
        topFrame == null -> "unknown"
        topFrame.className.startsWith("dev.chungjungsoo.gptmobile.") || topFrame.className.startsWith("dev.melo.gptmobile.") -> "application"
        topFrame.className.startsWith("android.") || topFrame.className.startsWith("com.android.") -> "android_framework"
        topFrame.className.startsWith("java.") || topFrame.className.startsWith("kotlin.") || topFrame.className.startsWith("dalvik.") -> "runtime"
        else -> "library_or_vendor"
    }
    val allFrames = causes.sumOf { it.stackTrace.size }
    val suppressed = causes.sumOf { it.suppressed.size }
    val records = mutableListOf<String>()
    var retained = 0
    fun identity(value: String) = value.replace(Regex("[^A-Za-z0-9_.$<>-]"), "_").take(200)
    val headers = causes.mapIndexed { index, cause ->
        "causeIndex=$index · type=${identity(cause.javaClass.name)} · framesTotal=${cause.stackTrace.size} · suppressedCount=${cause.suppressed.size}"
    }
    // Leave room for the summary and recorder prefix below the storage/export
    // ceilings. Completeness describes what survives persistence, not a larger
    // in-memory string that the storage layer would silently clip later.
    var frameBudget = 6800 - headers.sumOf { it.length + 1 }
    causes.forEachIndexed { index, cause ->
        records += headers[index]
        cause.stackTrace.take(60).forEach { frame ->
            val line = "at ${identity(frame.className)}.${identity(frame.methodName)}(line=${frame.lineNumber.coerceAtLeast(-1)}; native=${frame.isNativeMethod})"
            if (retained < 240 && line.length + 1 <= frameBudget) {
                records += line
                frameBudget -= line.length + 1
                retained++
            }
        }
    }
    return buildString {
        append("type=${identity(failure.javaClass.name)} · thread=${identity(threadName)}")
        append(" · topFrameOrigin=$topFrameOrigin · rootCauseAttribution=not_established")
        append(" · causesRetained=${causes.size} · causeChainComplete=${next == null}")
        append(" · framesTotal=$allFrames · framesRetained=$retained · framesOmitted=${allFrames - retained}")
        append(" · suppressedTotal=$suppressed · suppressedCaptured=false")
        append(" · stackCaptureComplete=${next == null && retained == allFrames && suppressed == 0} · exceptionMessagesCaptured=false")
        records.forEach { append('\n').append(it) }
    }
}
