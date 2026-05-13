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
                        type = mimeType ?: "text/plain",
                        vectorized = 1,
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

    internal fun readFileContent(uri: Uri, mimeType: String? = null): String {
        return when {
            mimeType == "application/pdf" -> readPdfContent(uri)
            mimeType in WORD_MIME_TYPES -> readWordContent(uri)
            mimeType == "text/html" -> readHtmlContent(uri)
            else -> readPlainTextContent(uri)
        }
    }

    private fun readPlainTextContent(uri: Uri): String {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = reader.readLine()
                }
            }
        }
        return sb.toString()
    }

    private fun readHtmlContent(uri: Uri): String {
        val raw = readPlainTextContent(uri)
        return HtmlExtractor.extractTextWithStructure(raw)
    }

    private fun readPdfContent(uri: Uri): String {
        val result = PdfExtractor.extract(context, uri)
        return result.getOrElse { e ->
            PdfExtractor.PdfResult(pageCount = 0, text = "PDF import error: ${e.message}")
        }.text
    }

    private fun readWordContent(uri: Uri): String {
        val raw = readPlainTextContent(uri)
        val hasBinaryContent = raw.length < 200 &&
            raw.any { it.code < 32 && it.code !in setOf(9, 10, 13) }
        if (hasBinaryContent) {
            return "[Word document — text extraction requires Apache POI integration. " +
                "Please convert to .txt or .md first.]"
        }
        return raw
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

    companion object {
        private val WORD_MIME_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
        )
    }
}
