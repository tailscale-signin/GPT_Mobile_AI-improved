package dev.chungjungsoo.gptmobile.util

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.io.Writer
import java.util.zip.ZipFile
import org.apache.poi.hslf.extractor.QuickButCruddyTextExtractor
import org.apache.poi.hssf.extractor.EventBasedExcelExtractor
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Bounded text extraction; Office macros, formulas and embedded objects are never executed. */
object DocumentTextExtractor {
    const val MAX_TEXT_CHARS = 80_000
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_LEGACY_BYTES = 8 * 1024 * 1024

    data class Result(
        val text: String,
        val note: String? = null,
        val firstPage: Int? = null,
        val lastPage: Int? = null,
        val totalPages: Int? = null,
        val nextPage: Int? = null,
        val warnings: List<String> = emptyList()
    )

    fun extract(context: Context?, file: File, mimeType: String, pageStart: Int = 1, pageLimit: Int = 100): Result {
        require(file.isFile) { "The document is no longer available. Attach it again." }
        require(file.length() <= FileUtils.MAX_UPLOAD_SIZE_BYTES) { "Documents cannot exceed 50 MB." }
        val extension = file.extension.lowercase()
        val raw = when {
            mimeType == "application/pdf" || extension == "pdf" -> {
                requireNotNull(context) { "PDF extraction needs the Android document reader." }
                require(pageStart >= 1 && pageLimit in 1..100) { "PDF page ranges must start at page 1 or later and contain at most 100 pages." }
                AndroidPdfFonts.initialize(context.applicationContext)
                return PDDocument.load(file, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)).use { pdf ->
                    extractPdf(pdf, pageStart, pageLimit)
                }
            }
            extension in setOf("docx", "xlsx", "pptx") -> return readOpenXml(file, extension)
            extension in setOf("doc", "xls", "ppt") -> {
                require(file.length() <= MAX_LEGACY_BYTES) { "Legacy Office files over 8 MB must be converted to DOCX, XLSX or PDF." }
                POIFSFileSystem(file, true).use { filesystem ->
                    when (extension) {
                        "doc" -> WordExtractor(filesystem).use { it.text }
                        "xls" -> EventBasedExcelExtractor(filesystem).use { it.text }
                        else -> {
                            val extractor = QuickButCruddyTextExtractor(filesystem)
                            try {
                                extractor.textAsString
                            } finally {
                                extractor.close()
                            }
                        }
                    }
                }
            }
            mimeType.startsWith("text/") || extension in setOf("txt", "md", "csv", "tsv", "json", "xml", "log") ->
                file.reader().use { reader ->
                    val chars = CharArray(MAX_TEXT_CHARS + 1)
                    var count = 0
                    while (count < chars.size) {
                        val read = reader.read(chars, count, chars.size - count)
                        if (read < 0) break
                        count += read
                    }
                    String(chars, 0, count)
                }
            else -> throw IllegalArgumentException("Convert this document to PDF, DOCX, XLSX, PPTX or text before attaching it.")
        }
        return finish(raw, raw.length > MAX_TEXT_CHARS)
    }

    internal fun extractPdf(pdf: PDDocument, pageStart: Int, pageLimit: Int): Result {
        require(pageStart >= 1 && pageLimit in 1..100) { "PDF page ranges must start at page 1 or later and contain at most 100 pages." }
        require(pdf.currentAccessPermission.canExtractContent()) { "This PDF does not allow text extraction." }
        require(pageStart <= pdf.numberOfPages) { "The requested PDF page is beyond the document's ${pdf.numberOfPages} pages." }
        val lastRequested = minOf(pdf.numberOfPages.toLong(), pageStart.toLong() + pageLimit - 1).toInt()
        val pageTextWriter = PdfPageTextWriter(pageStart, lastRequested, MAX_TEXT_CHARS)
        val stripper = object : PDFTextStripper() {
            override fun processPage(page: PDPage) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("PDF extraction interrupted")
                if (currentPageNo > lastRequested) throw PdfRangeComplete()
                super.processPage(page)
            }

            override fun startPage(page: PDPage) {
                pageTextWriter.beginPage(currentPageNo)
            }

            override fun endPage(page: PDPage) {
                pageTextWriter.endPage(currentPageNo)
                if (currentPageNo == lastRequested) throw PdfRangeComplete()
            }
        }.apply {
            startPage = pageStart
            endPage = lastRequested
        }
        // writeText walks the page tree. One pass avoids scanning every page in
        // the document again for each page in this bounded range.
        try {
            stripper.writeText(pdf, pageTextWriter)
            pageTextWriter.completeRange()
        } catch (_: PdfRangeComplete) {
            pageTextWriter.completeRange()
        } catch (_: PdfPageTextWriter.LimitReached) { }
        val text = pageTextWriter.toString()
        val nextPage = pageTextWriter.nextPage ?: pageTextWriter.lastPage.takeIf { !pageTextWriter.partialPage && it < pdf.numberOfPages }?.plus(1)
        val incomplete = pageStart > 1 || pageTextWriter.lastPage < pdf.numberOfPages || pageTextWriter.partialPage
        val warnings = buildList {
            if (pageTextWriter.partialPage) add("Page ${pageTextWriter.lastPage} exceeds the text limit; the remainder of that page was not examined.")
            if ('\uFFFD' in text) add("Unmapped Unicode characters were detected; verify affected passages against the PDF.")
        }
        return finish(text, incomplete).copy(
            note = if (incomplete) "Read PDF pages $pageStart–${pageTextWriter.lastPage} of ${pdf.numberOfPages}${if (pageTextWriter.partialPage) "; the last page is partial" else ""}." else null,
            firstPage = pageStart,
            lastPage = pageTextWriter.lastPage,
            totalPages = pdf.numberOfPages,
            nextPage = nextPage,
            warnings = warnings
        )
    }

    private class PdfRangeComplete : RuntimeException()

    private fun finish(text: String, truncated: Boolean): Result {
        val value = text.take(MAX_TEXT_CHARS).trim()
        require(value.isNotEmpty()) { "No readable text found. For a scanned document, attach its pages as images or use a PDF-capable profile." }
        return Result(value, if (truncated) "Document excerpt limited to 80,000 characters or 100 pages." else null)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(minOf(8192, limit))
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                output.write(next)
            } else {
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun readOpenXml(file: File, extension: String): Result = ZipFile(file).use { zip ->
        var remaining = MAX_XML_BYTES
        fun read(name: String): String? {
            val entry = zip.getEntry(name) ?: return null
            require(entry.size <= remaining) { "The document's expanded text exceeds 8 MB." }
            val bytes = zip.getInputStream(entry).use { readBounded(it, remaining + 1) }
            require(bytes.size <= remaining) { "The document's expanded text exceeds 8 MB." }
            remaining -= bytes.size
            return bytes.toString(Charsets.UTF_8).also {
                require(!it.contains("<!DOCTYPE", true) && !it.contains("<!ENTITY", true)) { "Document XML entities are unsupported." }
            }
        }
        val sharedStrings = mutableListOf<String>()
        if (extension == "xlsx") {
            read("xl/sharedStrings.xml")?.let { xml ->
                var value = StringBuilder()
                parse(xml) { parser, event ->
                    if (event == XmlPullParser.START_TAG && parser.name == "si") value = StringBuilder()
                    if (event == XmlPullParser.TEXT) value.append(parser.text)
                    if (event == XmlPullParser.END_TAG && parser.name == "si") sharedStrings += value.toString()
                }
            }
        }
        val names = zip.entries().asSequence().map { it.name }.filter { name ->
            when (extension) {
                "docx" -> name == "word/document.xml"
                "xlsx" -> name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
                else -> name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))
            }
        }.sortedBy { Regex("[0-9]+").find(it)?.value?.toIntOrNull() ?: 0 }.toList()
        require(names.isNotEmpty()) { "This Office file has no readable document content." }
        val out = LimitedWriter()
        try {
            for (name in names.take(100)) {
                val xml = read(name) ?: continue
                if (extension != "docx") out.append("\n${name.substringAfterLast('/').substringBefore('.')}\n")
                var textElement = false
                var shared = false
                parse(xml) { parser, event ->
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (extension == "xlsx" && parser.name == "c") {
                                shared = parser.getAttributeValue(null, "t") == "s"
                                out.append(parser.getAttributeValue(null, "r").orEmpty()).append(": ")
                            }
                            textElement = parser.name == "t" || (extension == "xlsx" && parser.name == "v")
                            if (parser.name == "tab") out.append('\t')
                        }
                        XmlPullParser.TEXT -> if (textElement) {
                            out.append(if (shared) sharedStrings.getOrNull(parser.text.toIntOrNull() ?: -1).orEmpty() else parser.text)
                        }
                        XmlPullParser.END_TAG -> {
                            textElement = false
                            if (parser.name in setOf("p", "row", "br")) out.append('\n')
                            if (extension == "xlsx" && parser.name == "c") out.append('\t')
                        }
                    }
                }
                out.append('\n')
            }
        } catch (_: TextLimitReached) { }
        finish(out.toString(), out.truncated || names.size > 100)
    }

    private fun parse(xml: String, event: (XmlPullParser, Int) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            event(parser, parser.eventType)
            parser.next()
        }
    }

    private class TextLimitReached : RuntimeException()
    private class LimitedWriter : Writer() {
        private val text = StringBuilder()
        var truncated = false
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val count = minOf(length, MAX_TEXT_CHARS - text.length)
            text.append(buffer, offset, count)
            if (count < length) {
                truncated = true
                throw TextLimitReached()
            }
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }
}
