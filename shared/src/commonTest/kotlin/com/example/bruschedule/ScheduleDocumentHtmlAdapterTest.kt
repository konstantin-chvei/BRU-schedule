package com.example.bruschedule

import com.example.bruschedule.data.scraper.ScheduleDocumentFormat
import com.example.bruschedule.data.remote.BinaryResourceTransport
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.GoogleSheetsScheduleUrl
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
        assertEquals(ScheduleDocumentFormat.ZIP, adapter.formatForUrl("https://example.edu/magistracy.zip"))
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
    fun pdfPageTextIsPreservedAsColumnAlignedHtml() {
        val content = """
            BT /F1 10 Tf 50 700 Td (Day) Tj 100 0 Td (Time) Tj 100 0 Td (Group 1-11) Tj ET
            BT /F1 10 Tf 50 680 Td (Monday) Tj 100 0 Td (08:30-09:50) Tj 100 0 Td (Mathematics) Tj ET
        """.trimIndent()

        val html = adapter.toHtml(ScheduleDocumentFormat.PDF, minimalPdf(content))

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("Day"))
        assertTrue(html.contains("Group 1-11"))
        assertTrue(html.contains("Monday"))
        assertTrue(html.contains("08:30-09:50"))
        assertTrue(html.contains("Mathematics"))
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
    fun zipArchiveConvertsContainedXlsxTimetables() {
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByColumnRow(2, 1), TextCellValue("Журналистика"))
        val workbookBytes = checkNotNull(workbook.encode())
        val archiveBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("magistracy/raspisanie.xlsx"))
                zip.write(workbookBytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }

        val html = adapter.toHtml(ScheduleDocumentFormat.ZIP, archiveBytes)

        assertTrue(html.contains("raspisanie.xlsx"))
        assertTrue(html.contains("Группа 1-11"))
        assertTrue(html.contains("Понедельник"))
        assertTrue(html.contains("Журналистика"))
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
    fun bsuCatalogueFindsJournalismGroupsInTheCurrentFacultyXlsx() {
        val facultyUrl = "https://journ.bsu.by/raspisanie.html"
        val fileUrl = "https://journ.bsu.by/images/Journ26_27/Rasklad_1_kurs_26_27_post.xlsx"
        val workbook = Excel.createExcel()
        val sheet = workbook["Расписание"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Математика"))
        val workbookBytes = checkNotNull(workbook.encode())
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/images/Journ26_27/Rasklad_1_kurs_26_27_post.xlsx\">Первый курс, расписание занятий</a>"
            } else {
                error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return workbookBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(fileUrl, requestedBinaryUrl)
        assertEquals(fileUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Факультет журналистики"))
    }

    @Test
    fun bsuCatalogueLoadsTheRadiophysicsGoogleSheetAsHtml() {
        val facultyUrl = "https://rct.bsu.by/education"
        val sheetUrl = "https://docs.google.com/spreadsheets/d/1NcWuxSIA7pwh-VB5X7cILjQ1-0uD1cjj0QLt-I5fJh8/edit"
        val htmlUrl = checkNotNull(GoogleSheetsScheduleUrl.toHtmlTableUrl(sheetUrl))
        val timetableHtml = """
            <table>
              <tr><td>Понедельник</td><td>09:00 - 10:20</td><td>Группа 1-11</td></tr>
              <tr><td>Математика</td><td>Аудитория 115</td><td>Преподаватель</td></tr>
            </table>
        """.trimIndent()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$sheetUrl\">Расписание занятий 1 курс</a>"
                htmlUrl -> timetableHtml
                else -> error("No HTML fixture for $url")
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(sheetUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Google Sheets"))
    }

    @Test
    fun bsuCatalogueDiscoversTimetablesInsideTheJournalismMasterZip() {
        val facultyUrl = "https://journ.bsu.by/raspisanie.html"
        val zipUrl = "https://journ.bsu.by/files/magistratura.zip"
        val workbook = Excel.createExcel()
        val sheet = workbook["Магистратура"]
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("День"))
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Время"))
        sheet.updateCell(CellIndex.indexByString("C1"), TextCellValue("Группа 1-11"))
        sheet.updateCell(CellIndex.indexByString("A2"), TextCellValue("Понедельник"))
        sheet.updateCell(CellIndex.indexByString("B2"), TextCellValue("08:30-09:50"))
        sheet.updateCell(CellIndex.indexByString("C2"), TextCellValue("Журналистика"))
        val workbookBytes = checkNotNull(workbook.encode())
        val zipBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("raspisanie.xlsx"))
                zip.write(workbookBytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = if (url == facultyUrl) {
                "<a href=\"/files/magistratura.zip\">Расписание магистратуры (ZIP)</a>"
            } else {
                error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray = zipBytes
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(zipUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("ZIP"))
    }

    @Test
    fun bsuCatalogueFollowsDropboxFolderLinksToDocxSchedules() {
        val facultyUrl = "https://chemistry.bsu.by/index.php/ru/opisanie-kursa/cv-prepodavatelej/14-studentam/7-raspisanie"
        val folderUrl = "https://www.dropbox.com/scl/fo/course-folder?rlkey=key&dl=0"
        val docxUrl = "https://www.dropbox.com/scl/fi/course-folder/file/1kurs.docx?rlkey=key&dl=0"
        val documentXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>День</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Время</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Предмет</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>Группа 1-11</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Понедельник</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>08:30-09:50</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Математика</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl></w:body>
            </w:document>
        """.trimIndent()
        val docxBytes = ByteArrayOutputStream().let { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.encodeToByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        var requestedBinaryUrl: String? = null
        val transport = object : HtmlTransport, BinaryResourceTransport {
            override suspend fun get(url: String): String = when (url) {
                facultyUrl -> "<a href=\"$folderUrl\">1 курс расписание</a>"
                folderUrl -> "<a href=\"${docxUrl.replace("&", "&amp;")}\">Группа 1-11</a>"
                else -> error("No HTML fixture for $url")
            }

            override suspend fun getBytes(url: String): ByteArray {
                requestedBinaryUrl = url
                return docxBytes
            }
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "1-11")
        }

        assertEquals(adapter.downloadUrl(docxUrl), requestedBinaryUrl)
        assertEquals(docxUrl, matches.single().sourceUrl)
        assertTrue(matches.single().name.contains("1-11"))
        assertTrue(matches.single().subtitle.orEmpty().contains("Химический факультет"))
    }

    @Test
    fun oldBinaryFormatsFailWithAnExplicitConversionHint() {
        val failure = assertFailsWith<IllegalArgumentException> {
            adapter.toHtml(ScheduleDocumentFormat.XLS, byteArrayOf(1))
        }

        assertTrue(failure.message.orEmpty().contains("PDF, XLSX или DOCX"))
    }

    private fun minimalPdf(content: String): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${content.encodeToByteArray().size} >>\nstream\n$content\nendstream",
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, value ->
            offsets += pdf.toString().encodeToByteArray().size
            pdf.append("${index + 1} 0 obj\n$value\nendobj\n")
        }
        val xrefOffset = pdf.toString().encodeToByteArray().size
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { offset ->
            pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
            .append("startxref\n$xrefOffset\n%%EOF")
        return pdf.toString().encodeToByteArray()
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
