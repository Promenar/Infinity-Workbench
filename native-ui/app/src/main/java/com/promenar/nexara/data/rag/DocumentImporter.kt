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
                    val content = readFileContent(uri)
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

    private fun readFileContent(uri: Uri): String {
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
