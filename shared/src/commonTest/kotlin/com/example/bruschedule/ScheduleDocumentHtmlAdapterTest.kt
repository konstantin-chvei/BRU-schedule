package com.example.bruschedule

import com.example.bruschedule.data.scraper.ScheduleDocumentFormat
import com.example.bruschedule.data.remote.BinaryResourceTransport
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.gyanoba.kexcel.Excel
import com.gyanoba.kexcel.sheet.CellIndex
import com.gyanoba.kexcel.sheet.TextCellValue
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScheduleDocumentHtmlAdapterTest {
    private val adapter = ScheduleDocumentHtmlAdapter()

    @Test
    fun detectsOpenXmlFormatsBeforeTheirLegacySuffixes() {
        assertEquals(ScheduleDocumentFormat.XLSX, adapter.formatForUrl("https://example.edu/schedule.xlsx?download=1"))
        assertEquals(ScheduleDocumentFormat.DOCX, adapter.formatForUrl("https://example.edu/schedule.docx#page=1"))
        assertEquals(ScheduleDocumentFormat.XLS, adapter.formatForUrl("https://example.edu/schedule.xls"))
        assertEquals(ScheduleDocumentFormat.DOC, adapter.formatForUrl("https://example.edu/schedule.doc"))
        assertEquals(ScheduleDocumentFormat.PDF, adapter.formatForUrl("https://example.edu/schedule.pdf"))
    }

    @Test
    fun dropboxShareLinksAreConvertedToDirectDownloadLinks() {
        assertEquals(
            "https://www.dropbox.com/scl/fi/example/schedule.docx?rlkey=abc&dl=1#view",
            adapter.downloadUrl("https://www.dropbox.com/scl/fi/example/schedule.docx?rlkey=abc&dl=0#view"),
        )
        assertEquals(
            "https://other.example/schedule.pdf?dl=0",
            adapter.downloadUrl("https://other.example/schedule.pdf?dl=0"),
        )
    }

    @Test
    fun xlsxWorksheetIsConvertedToAnHtmlTable() {
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30–10:00"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val bytes = checkNotNull(workbook.encode())

        val html = adapter.toHtml(ScheduleDocumentFormat.XLSX, bytes)

        assertTrue(html.contains("<table><tbody>"))
        assertTrue(html.contains("Расписание"))
        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("08:30–10:00"))
        assertTrue(html.contains("Математика"))
    }

    @Test
    fun docxTableIsExtractedFromTheOpenXmlPackage() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>День</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Время</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>Понедельник</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>08:30–10:00</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Экология &amp; климат</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl></w:body>
            </w:document>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(xml.encodeToByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

        val html = adapter.toHtml(ScheduleDocumentFormat.DOCX, bytes)

        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("08:30–10:00"))
        assertTrue(html.contains("Экология &amp; климат"))
    }

    @Test
    fun bsuCatalogueDiscoversGroupsInsideAnXlsxTimetableLink() {
        val facultyUrl = "https://fpmi.bsu.by/timetable"
        val fileUrl = "https://fpmi.bsu.by/files/raspisanie.xlsx"
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val workbookBytes = checkNotNull(workbook.encode())
        var binaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"/files/raspisanie.xlsx\">Расписание групп</a>"
                else -> error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                binaryUrl = url
                return workbookBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(fileUrl, binaryUrl)
        assertEquals(fileUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("XLSX"))
    }

    @Test
    fun oldBinaryFormatsFailWithAnExplicitConversionHint() {
        val failure = assertFailsWith<IllegalArgumentException> {
            adapter.toHtml(ScheduleDocumentFormat.XLS, byteArrayOf(1))
        }

        assertTrue(failure.message.orEmpty().contains("PDF или XLSX"))
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result
            }
        })
        return checkNotNull(value).getOrThrow()
    }
}
