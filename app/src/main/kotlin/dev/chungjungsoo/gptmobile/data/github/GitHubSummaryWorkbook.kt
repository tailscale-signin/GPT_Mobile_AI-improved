package dev.chungjungsoo.gptmobile.data.github

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a small workbook from the same validated values used by the visible check table. */
internal object GitHubSummaryWorkbook {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val PACKAGE_RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DOCUMENT_RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun write(output: OutputStream, table: GitHubRichSummaryPresentation.CheckTable) {
        val rows = table.rows.map { listOf(it.label, it.count, table.source, table.coverage, table.observation) }
        val sheet = buildString {
            append("<worksheet xmlns=\"$SHEET_NS\"><sheetData>")
            (listOf(table.headers) + rows).forEachIndexed { rowIndex, row ->
                val number = rowIndex + 1
                append("<row r=\"$number\">")
                row.forEachIndexed { column, value ->
                    val ref = "${'A' + column}$number"
                    if (value is Int) {
                        append("<c r=\"$ref\" t=\"n\"><v>$value</v></c>")
                    } else {
                        append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        append(escapeXml(value.toString()))
                        append("</t></is></c>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        ZipOutputStream(output).use { zip ->
            fun part(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part(
                "[Content_Types].xml",
                "<Types xmlns=\"$CONTENT_TYPES_NS\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>"
            )
            part(
                "_rels/.rels",
                "<Relationships xmlns=\"$PACKAGE_RELS_NS\"><Relationship Id=\"rId1\" Type=\"$DOCUMENT_RELS_NS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
            )
            part(
                "xl/workbook.xml",
                "<workbook xmlns=\"$SHEET_NS\" xmlns:r=\"$DOCUMENT_RELS_NS\"><sheets><sheet name=\"Recorded checks\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
            )
            part(
                "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns=\"$PACKAGE_RELS_NS\"><Relationship Id=\"rId1\" Type=\"$DOCUMENT_RELS_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
            )
            part("xl/worksheets/sheet1.xml", sheet)
        }
    }

    private fun escapeXml(text: String): String = buildString {
        text.codePoints().forEach { char ->
            when (char) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                in 0x20..0xd7ff, in 0xe000..0xfffd, in 0x10000..0x10ffff, 0x09, 0x0a, 0x0d -> appendCodePoint(char)
            }
        }
    }
}
