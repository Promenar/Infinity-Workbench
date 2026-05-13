package com.promenar.nexara.data.rag

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.DocumentDao
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkObject
import io.mockk.mockkObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class DocumentImporterTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var documentDao: DocumentDao
    private lateinit var vectorizationQueue: VectorizationQueue
    private lateinit var importer: DocumentImporter
    private val uri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        documentDao = mockk(relaxed = true)
        vectorizationQueue = mockk(relaxed = true)
        importer = DocumentImporter(context, documentDao, vectorizationQueue)

        every { context.contentResolver } returns contentResolver
    }

    @Nested
    inner class ReadFileContentRouting {

        @Test
        fun `routes pdf mime type to readPdfContent`() {
            mockkObject(PdfExtractor)
            every { PdfExtractor.extract(any(), any()) } returns Result.success(
                PdfExtractor.PdfResult(pageCount = 3, text = "Extracted PDF text")
            )

            val result = importer.readFileContent(uri, "application/pdf")
            assertThat(result).isEqualTo("Extracted PDF text")

            unmockkObject(PdfExtractor)
        }

        @Test
        fun `routes html mime type to readHtmlContent`() {
            val html = "<html><body><p>Hello from HTML</p></body></html>"
            every { contentResolver.openInputStream(uri) } returns
                ByteArrayInputStream(html.toByteArray())

            val result = importer.readFileContent(uri, "text/html")
            assertThat(result).contains("Hello from HTML")
            assertThat(result).doesNotContain("<p>")
        }

        @Test
        fun `routes null mime type to readPlainText`() {
            every { contentResolver.openInputStream(uri) } returns
                ByteArrayInputStream("plain text".toByteArray())

            val result = importer.readFileContent(uri, null)
            assertThat(result).contains("plain text")
        }

        @Test
        fun `routes unknown mime type to readPlainText`() {
            every { contentResolver.openInputStream(uri) } returns
                ByteArrayInputStream("unknown content".toByteArray())

            val result = importer.readFileContent(uri, "text/plain")
            assertThat(result).contains("unknown content")
        }
    }

    @Nested
    inner class GetFileName {

        @Test
        fun `extracts display name from ContentResolver query`() {
            val cursor = mockk<Cursor>(relaxed = true)
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getString(0) } returns "report.pdf"
            every { contentResolver.query(uri, null, null, null, null) } returns cursor

            val method = DocumentImporter::class.java.getDeclaredMethod("getFileName", Uri::class.java)
            method.isAccessible = true
            val fileName = method.invoke(importer, uri) as String?

            assertThat(fileName).isEqualTo("report.pdf")
        }

        @Test
        fun `falls back to lastPathSegment when query returns no rows`() {
            val cursor = mockk<Cursor>(relaxed = true)
            every { cursor.moveToFirst() } returns false
            every { contentResolver.query(uri, null, null, null, null) } returns cursor
            every { uri.lastPathSegment } returns "fallback-name.txt"

            val method = DocumentImporter::class.java.getDeclaredMethod("getFileName", Uri::class.java)
            method.isAccessible = true
            val fileName = method.invoke(importer, uri) as String?

            assertThat(fileName).isEqualTo("fallback-name.txt")
        }

        @Test
        fun `falls back to lastPathSegment when cursor is null`() {
            every { contentResolver.query(uri, null, null, null, null) } returns null
            every { uri.lastPathSegment } returns "uri-fallback.txt"

            val method = DocumentImporter::class.java.getDeclaredMethod("getFileName", Uri::class.java)
            method.isAccessible = true
            val fileName = method.invoke(importer, uri) as String?

            assertThat(fileName).isEqualTo("uri-fallback.txt")
        }
    }

    @Nested
    inner class GetFileSize {

        @Test
        fun `reads file size from ContentResolver query`() {
            val cursor = mockk<Cursor>(relaxed = true)
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 0
            every { cursor.getLong(0) } returns 2048L
            every { contentResolver.query(uri, null, null, null, null) } returns cursor

            val method = DocumentImporter::class.java.getDeclaredMethod("getFileSize", Uri::class.java)
            method.isAccessible = true
            val size = method.invoke(importer, uri) as Long

            assertThat(size).isEqualTo(2048L)
        }

        @Test
        fun `returns zero when cursor is null`() {
            every { contentResolver.query(uri, null, null, null, null) } returns null

            val method = DocumentImporter::class.java.getDeclaredMethod("getFileSize", Uri::class.java)
            method.isAccessible = true
            val size = method.invoke(importer, uri) as Long

            assertThat(size).isEqualTo(0L)
        }
    }
}
