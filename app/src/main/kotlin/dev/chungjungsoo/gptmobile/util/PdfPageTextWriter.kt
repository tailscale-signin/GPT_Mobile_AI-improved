package dev.chungjungsoo.gptmobile.util

import java.io.InterruptedIOException
import java.io.Writer

/** Keeps complete pages under one text cap; a page which does not fit is retried whole. */
internal class PdfPageTextWriter(private val firstRequestedPage: Int, private val lastRequestedPage: Int, private val maxChars: Int) : Writer() {
    class LimitReached : RuntimeException()

    private val text = StringBuilder()
    private var currentPage = firstRequestedPage
    private var pageBoundary = 0
    private var completedReadableText = false
    private var precedingReadableText = false
    private var currentReadableText = false
    var lastPage = firstRequestedPage - 1
        private set
    var nextPage: Int? = null
        private set
    var partialPage = false
        private set

    init {
        require(firstRequestedPage >= 1 && lastRequestedPage >= firstRequestedPage && maxChars > 0)
    }

    fun beginPage(page: Int) {
        checkInterrupted()
        require(page in firstRequestedPage..lastRequestedPage)
        currentPage = page
        pageBoundary = text.length
        precedingReadableText = completedReadableText
        currentReadableText = false
        // PDFBox omits pages without content streams. Pages preceding this one
        // have nevertheless been traversed, so blank pages cannot stall continuation.
        lastPage = page - 1
        if (text.isNotEmpty()) append('\n')
    }

    fun endPage(page: Int) {
        check(page == currentPage)
        lastPage = page
        completedReadableText = completedReadableText || currentReadableText
    }

    fun completeRange() {
        lastPage = lastRequestedPage
    }

    override fun write(buffer: CharArray, offset: Int, length: Int) {
        checkInterrupted()
        val count = minOf(length, maxChars - text.length)
        text.append(buffer, offset, count)
        if (!currentReadableText) currentReadableText = (offset until offset + count).any { !buffer[it].isWhitespace() }
        if (count < length) {
            if (precedingReadableText) {
                text.setLength(pageBoundary)
                lastPage = currentPage - 1
                nextPage = currentPage
            } else {
                // At least return a useful, explicitly partial first page. Never
                // emit half a supplementary Unicode character at the text boundary.
                if (text.lastOrNull()?.isHighSurrogate() == true) text.setLength(text.length - 1)
                lastPage = currentPage
                partialPage = true
            }
            throw LimitReached()
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF extraction interrupted")
    }

    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = text.toString()
}
