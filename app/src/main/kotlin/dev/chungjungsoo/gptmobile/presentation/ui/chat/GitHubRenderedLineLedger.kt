package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Counts actual renderer lines, not tokens, Markdown source newlines or row height. */
internal class GitHubRenderedLineLedger {
    private data class Layout(val text: String, val lines: Int, val geometry: Any)
    private val layouts = mutableMapOf<Any, Layout>()
    private val documents = mutableMapOf<Any, Pair<Int, Int>>()
    private var pending = 0
    var armed = false
        private set

    fun document(key: Any, sourceLength: Int) {
        val previous = documents[key]
        documents[key] = (previous?.first ?: if (armed) 0 else sourceLength) to sourceLength
    }

    fun observe(key: Any, document: Any, sourceOffset: Int, text: String, lines: Int, geometry: Any, updatesAddLines: Boolean = true, previousLinesAtGeometry: Int? = null, firstAppearanceAddsLines: Boolean = true) {
        val previous = layouts.put(key, Layout(text, if (text.isBlank()) 0 else lines, geometry))
        if (!armed || text.isBlank()) return
        val added = when {
            previous == null -> if (firstAppearanceAddsLines && sourceOffset >= (documents[document]?.first ?: 0)) lines else 0
            !updatesAddLines || previous.text == text -> 0
            previous.geometry != geometry -> previousLinesAtGeometry?.let { (lines - it).coerceAtLeast(0) } ?: 0
            else -> (lines - previous.lines).coerceAtLeast(0)
        }
        pending = (pending.toLong() + added).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun takeLines(): Int = pending.also { pending = 0 }

    /** Observations continue while detached, so a return starts from what is visible now. */
    fun baseline() {
        armed = false
        pending = 0
        documents.replaceAll { _, lengths -> lengths.second to lengths.second }
    }

    fun arm() {
        armed = true
    }
}
