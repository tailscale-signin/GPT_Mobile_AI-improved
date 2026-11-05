package dev.chungjungsoo.gptmobile.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DocumentTextExtractorTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun wordParagraphsAreReadableAndBounded() {
        val file = office(
            "report.docx",
            "word/document.xml" to
                "<document><p><t>Quarterly report</t></p><p><t>${"Revenue ".repeat(12_000)}</t></p></document>"
        )
        val result = DocumentTextExtractor.extract(null, file, "application/octet-stream")
        assertTrue(result.text.startsWith("Quarterly report\nRevenue"))
        assertTrue(result.text.length <= DocumentTextExtractor.MAX_TEXT_CHARS)
        assertTrue(result.note != null)
    }

    @Test fun spreadsheetResolvesSharedStringsAndPreservesCells() {
        val file = office(
            "budget.xlsx",
            "xl/sharedStrings.xml" to "<sst><si><t>Revenue</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row></worksheet>"
        )
        val result = DocumentTextExtractor.extract(null, file, "application/octet-stream")
        assertTrue(result.text.contains("A1: Revenue"))
        assertTrue(result.text.contains("B1: 42"))
    }

    @Test fun legacyExcelCellsAreExtracted() {
        val file = files.newFile("legacy.xls")
        HSSFWorkbook().use { workbook ->
            val row = workbook.createSheet("Budget").createRow(0)
            row.createCell(0).setCellValue("Sales")
            row.createCell(1).setCellValue(125.0)
            file.outputStream().use(workbook::write)
        }
        val text = DocumentTextExtractor.extract(null, file, "application/vnd.ms-excel").text
        assertTrue(text.contains("Sales"))
        assertTrue(text.contains("125"))
    }

    @Test fun pdfTextReachesProvidersWithoutNativePdfSupport() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(context)
        val file = files.newFile("notes.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(30f, 700f)
                stream.showText("Meeting agenda")
                stream.endText()
            }
            document.save(file)
        }
        assertEquals("Meeting agenda", DocumentTextExtractor.extract(context, file, "application/pdf").text)
    }

    @Test fun pdfPageRangesReturnOnlyRequestedPagesAndAnExactContinuation() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidPdfFonts.initialize(context)
        val file = files.newFile("segmented.pdf")
        PDDocument().use { document ->
            for (index in 1..3) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.TIMES_ITALIC, 12f)
                    stream.newLineAtOffset(30f, 700f)
                    stream.showText("Evidence page $index")
                    stream.endText()
                }
            }
            document.save(file)
        }
        val first = DocumentTextExtractor.extract(context, file, "application/pdf", pageLimit = 2)
        assertTrue(first.text.contains("Evidence page 1"))
        assertTrue(first.text.contains("Evidence page 2"))
        assertTrue(!first.text.contains("Evidence page 3"))
        assertEquals(1, first.firstPage)
        assertEquals(2, first.lastPage)
        assertEquals(3, first.totalPages)
        assertEquals(3, first.nextPage)
        assertTrue(first.note != null)
        val next = DocumentTextExtractor.extract(context, file, "application/pdf", pageStart = requireNotNull(first.nextPage), pageLimit = 2)
        assertEquals("Evidence page 3", next.text)
        assertEquals(null, next.nextPage)
        assertTrue(next.note != null) // Earlier pages are not evidence in this particular result.
        assertThrows(IllegalArgumentException::class.java) { DocumentTextExtractor.extract(context, file, "application/pdf", pageStart = 4) }
    }

    @Test fun aMultiPageRangeTraversesTheDocumentTreeOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidPdfFonts.initialize(context)
        var traversals = 0
        val document = object : PDDocument() {
            override fun getPages(): PDPageTree = object : PDPageTree(super.getPages().cosObject) {
                override fun iterator(): MutableIterator<PDPage> {
                    traversals++
                    return super.iterator()
                }
            }
        }
        document.use {
            repeat(20) { index ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(30f, 700f)
                    stream.showText("Evidence page ${index + 1}")
                    stream.endText()
                }
            }
            traversals = 0
            val result = DocumentTextExtractor.extractPdf(document, 4, 10)
            assertEquals(1, traversals)
            assertEquals(4, result.firstPage)
            assertEquals(13, result.lastPage)
            assertEquals(14, result.nextPage)
            assertTrue(result.text.startsWith("Evidence page 4"))
            assertTrue(result.text.trimEnd().endsWith("Evidence page 13"))
        }
    }

    @Test fun standardFontVariantsAndSymbolGlyphsSurviveTextExtraction() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AndroidPdfFonts.initialize(context)
        val file = files.newFile("font-coverage.pdf")
        val fixtures = listOf(
            PDType1Font.TIMES_BOLD to "Serif bold",
            PDType1Font.TIMES_ITALIC to "Serif italic",
            PDType1Font.TIMES_BOLD_ITALIC to "Serif bold italic",
            PDType1Font.HELVETICA_BOLD to "Sans bold",
            PDType1Font.HELVETICA_OBLIQUE to "Sans oblique",
            PDType1Font.HELVETICA_BOLD_OBLIQUE to "Sans bold oblique",
            // Adobe Symbol maps its Omega glyph to U+2126, not U+03A9.
            PDType1Font.SYMBOL to "\u03B1\u2126",
            PDType1Font.ZAPF_DINGBATS to "✓"
        )
        PDDocument().use { document ->
            for ((font, text) in fixtures) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(30f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            document.save(file)
        }
        val result = DocumentTextExtractor.extract(context, file, "application/pdf")
        for ((_, text) in fixtures) assertTrue("Missing expected text: $text", result.text.contains(text))
        assertEquals(null, result.note)
    }

    @Test fun externalEntitiesAreRejected() {
        val file = office(
            "unsafe.docx",
            "word/document.xml" to
                "<!DOCTYPE doc [<!ENTITY secret SYSTEM 'file:///private'>]><doc><t>&secret;</t></doc>"
        )
        assertThrows(IllegalArgumentException::class.java) {
            DocumentTextExtractor.extract(null, file, "application/octet-stream")
        }
    }

    private fun office(name: String, vararg entries: Pair<String, String>): File = files.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
