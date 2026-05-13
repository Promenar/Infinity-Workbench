package com.promenar.nexara.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class DocumentImporter(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val vectorizationQueue: VectorizationQueue
) {
    suspend fun importFromUris(uris: List<Uri>, folderId: String? = null) {
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                try {
                    val fileName = getFileName(uri)
                    val mimeType = context.contentResolver.getType(uri)
                    val content = readFileContent(uri, mimeType)
                    val fileSize = getFileSize(uri)

                    val docId = UUID.randomUUID().toString()
                    val entity = DocumentEntity(
                        id = docId,
                        folderId = folderId,
                        title = fileName,
                        content = content,
                        fileSize = fileSize,
                        type = context.contentResolver.getType(uri) ?: "text/plain",
                        vectorized = 1, // Pending
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )

                    documentDao.insert(entity)
                    vectorizationQueue.enqueueDocument(
                        docId = docId,
                        docTitle = fileName ?: "Untitled",
                        content = content
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun readFileContent(uri: Uri, mimeType: String? = null): String {
        return when {
            mimeType == "application/pdf" -> readPdfContent(uri)
            mimeType in listOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword"
            ) -> readPlainWithWarning(uri, "Word")
            mimeType == "text/html" -> readHtmlContent(uri)
            else -> readPlainTextContent(uri)
        }
    }

    private fun readPlainTextContent(uri: Uri): String {
        val stringBuilder = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line).append("\n")
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }

    private fun readHtmlContent(uri: Uri): String {
        val raw = readPlainTextContent(uri)
        return raw.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun readPdfContent(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val tempFile = java.io.File(context.cacheDir, "pdf_temp_${System.currentTimeMillis()}.pdf")
                tempFile.outputStream().use { it.write(bytes) }
                val pfd = android.os.ParcelFileDescriptor.open(
                    tempFile,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                pfd.use { parcelFd ->
                    val renderer = android.graphics.pdf.PdfRenderer(parcelFd)
                    val sb = StringBuilder()
                    val pageCount = renderer.pageCount
                    for (i in 0 until pageCount) {
                        val page = renderer.openPage(i)
                        sb.append("[Page ${i + 1}] ")
                        page.close()
                    }
                    renderer.close()
                    tempFile.delete()
                    if (sb.isEmpty()) {
                        "PDF content: $pageCount pages (text extraction requires PDFBox library). " +
                            "Consider converting PDF to text first."
                    } else {
                        sb.toString()
                    }
                }
            } ?: "Failed to read PDF file."
        } catch (e: Exception) {
            "PDF reading error: ${e.message}"
        }
    }

    private fun readPlainWithWarning(uri: Uri, format: String): String {
        val content = readPlainTextContent(uri)
        if (content.length < 100 && content.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) {
            return "[Binary $format file - cannot extract text. Please convert to plain text first.]"
        }
        return content
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun getFileSize(uri: Uri): Long {
        var size: Long = 0
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    size = it.getLong(index)
                }
            }
        }
        return size
    }
}
