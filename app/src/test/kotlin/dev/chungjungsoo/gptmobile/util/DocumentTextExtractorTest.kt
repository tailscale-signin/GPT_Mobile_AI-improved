package dev.chungjungsoo.gptmobile.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
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
