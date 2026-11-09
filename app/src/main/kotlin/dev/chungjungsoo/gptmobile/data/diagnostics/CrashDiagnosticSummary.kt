package dev.chungjungsoo.gptmobile.data.diagnostics

/** Bounded throwable structure and safe message templates; arbitrary exception text stays private. */
internal fun crashDiagnosticSummary(failure: Throwable, threadName: String): String {
    data class Node(val error: Throwable, val parent: Int?, val relation: String, val mainCause: Boolean) {
        val frames = error.stackTrace
        val suppressed = error.suppressed
        val message = safeCrashMessage(error.message)
        val selected = sortedSetOf<Int>()
    }
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val nodes = mutableListOf<Node>()
    var next: Throwable? = failure
    while (next != null && nodes.size < 8 && seen.add(next)) {
        nodes += Node(next, (nodes.lastIndex).takeIf { it >= 0 }, if (nodes.isEmpty()) "root" else "cause", true)
        next = next.cause
    }
    val causeChainComplete = next == null
    val mainCauses = nodes.size
    var graphComplete = causeChainComplete
    var suppressedTotal = 0
    var omittedReferences = if (causeChainComplete) 0 else 1
    fun add(error: Throwable, parent: Int, relation: String) {
        if (error in seen) return
        if (nodes.size >= 8) {
            omittedReferences++
            graphComplete = false
        } else {
            seen += error
            nodes += Node(error, parent, relation, false)
        }
    }
    var current = 0
    while (current < nodes.size) {
        val node = nodes[current]
        if (!node.mainCause) node.error.cause?.let { add(it, current, "cause") }
        suppressedTotal += node.suppressed.size
        // Do not traverse an unbounded suppressed graph even if a faulty SDK supplies one.
        val inspect = minOf(node.suppressed.size, 8)
        for (index in 0 until inspect) add(node.suppressed[index], current, "suppressed")
        if (node.suppressed.size > inspect) {
            omittedReferences += node.suppressed.size - inspect
            graphComplete = false
        }
        current++
    }
    val topFrame = nodes[mainCauses - 1].frames.firstOrNull()
    val topFrameOrigin = when {
        topFrame == null -> "unknown"
        isApplicationFrame(topFrame) -> "application"
        topFrame.className.startsWith("android.") || topFrame.className.startsWith("com.android.") -> "android_framework"
        topFrame.className.startsWith("java.") || topFrame.className.startsWith("kotlin.") || topFrame.className.startsWith("dalvik.") -> "runtime"
        else -> "library_or_vendor"
    }
    fun identity(value: String) = value.replace(crashIdentityCharacters, "_").take(200)
    fun frameLine(frame: StackTraceElement) = "at ${identity(frame.className)}.${identity(frame.methodName)}(line=${frame.lineNumber.coerceAtLeast(-1)}; native=${frame.isNativeMethod})"
    val headers = nodes.mapIndexed { index, node ->
        val capture = if (node.error.message.isNullOrEmpty()) {
            "ABSENT"
        } else if (node.message != null) {
            "SAFE_TEMPLATE"
        } else {
            "PRIVATE_OMITTED"
        }
        "throwableIndex=$index · parentIndex=${node.parent ?: -1} · relation=${node.relation} · type=${identity(node.error.javaClass.name)} · framesTotal=${node.frames.size} · suppressedCount=${node.suppressed.size} · messageCapture=$capture" +
            node.message?.let { " · message=$it" }.orEmpty()
    }
    var frameBudget = 6700 - headers.sumOf { it.length + 1 }
    // Every physical continuation acquires an event/sequence prefix before it
    // reaches the 16 KiB ring. Short frames must not make that expansion silently
    // truncate a summary which claimed to retain more frames than were persisted.
    val continuationBytes = 110
    var recordByteBudget = DiagnosticLogFiles.MAX_RECORD_BYTES - 2048 - headers.sumOf { it.toByteArray(Charsets.UTF_8).size + continuationBytes }
    var retained = 0
    fun retain(node: Node, index: Int) {
        if (index in node.selected || retained >= 240 || node.selected.size >= 60) return
        val size = frameLine(node.frames[index]).length + 1
        if (size > frameBudget || size + continuationBytes > recordByteBudget) return
        node.selected += index
        frameBudget -= size
        recordByteBudget -= size + continuationBytes
        retained++
    }
    // Reserve a useful frame for every retained cause/suppressed throwable before
    // a long framework stack can consume the record. Deep application frames win
    // over repetitive framework frames, but emitted frames keep their stack order.
    nodes.forEach { node ->
        val application = node.frames.indexOfFirst(::isApplicationFrame)
        if (node.frames.isNotEmpty()) retain(node, application.takeIf { it >= 0 } ?: 0)
    }
    nodes.forEach { node ->
        for (index in node.frames.indices) {
            if (node.selected.size >= 60) break
            if (isApplicationFrame(node.frames[index])) retain(node, index)
        }
    }
    nodes.forEach { node ->
        for (index in node.frames.indices) {
            if (node.selected.size >= 60) break
            retain(node, index)
        }
    }
    val allFrames = nodes.sumOf { it.frames.size.toLong() }
    val messagesRetained = nodes.count { it.message != null }
    val messagesOmitted = nodes.count { !it.error.message.isNullOrEmpty() && it.message == null }
    return buildString {
        append("type=${identity(failure.javaClass.name)} · thread=${identity(threadName)}")
        append(" · topFrameOrigin=$topFrameOrigin · rootCauseAttribution=not_established")
        append(" · causesRetained=$mainCauses · causeChainComplete=$causeChainComplete")
        append(" · throwablesRetained=${nodes.size} · throwableGraphComplete=$graphComplete · throwableReferencesOmitted=$omittedReferences")
        append(" · framesTotal=$allFrames · framesRetained=$retained · framesOmitted=${allFrames - retained} · frameTotalsScope=RETAINED_THROWABLES")
        append(" · suppressedTotal=$suppressedTotal · suppressedCaptured=$graphComplete")
        append(" · stackCaptureComplete=${graphComplete && retained.toLong() == allFrames}")
        append(" · exceptionMessagesCaptured=${graphComplete && messagesOmitted == 0} · exceptionMessagesRetained=$messagesRetained · exceptionMessagesOmitted=$messagesOmitted")
        nodes.forEachIndexed { index, node ->
            append('\n').append(headers[index])
            node.selected.forEach { append('\n').append(frameLine(node.frames[it])) }
        }
    }
}

private val crashIdentityCharacters = Regex("[^A-Za-z0-9_.$<>-]")
private fun isApplicationFrame(frame: StackTraceElement): Boolean = frame.className.startsWith("dev.chungjungsoo.gptmobile.") || frame.className.startsWith("dev.melo.gptmobile.")

/** Only fixed runtime messages are safe: a secret may be an otherwise ordinary sentence. */
private fun safeCrashMessage(message: String?): String? = message?.takeIf {
    it in setOf(
        "Failed requirement.",
        "Check failed.",
        "width and height must be > 0",
        "LayoutCoordinate operations are only valid when isAttached is true"
    )
}
