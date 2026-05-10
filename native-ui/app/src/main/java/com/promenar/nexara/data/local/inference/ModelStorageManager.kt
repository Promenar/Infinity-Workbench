package com.promenar.nexara.data.local.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class StoredModel(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val format: String,
    val addedAt: Long,
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 0,
    val embeddingLength: Int = 0,
    val parameterCount: String = ""
)

class ModelStorageManager(private val context: Context) {

    val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    suspend fun importModel(uri: Uri): Result<StoredModel> = withContext(Dispatchers.IO) {
        try {
            val fileName = resolveFileName(uri) ?: "unknown.gguf"
            if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Only .gguf files are supported")
                )
            }

            val destFile = resolveUniqueDestination(fileName)
            var totalBytes = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                }
            } ?: throw IllegalStateException("Cannot open URI: $uri")

            val metadata = GgufParser.parse(destFile.absolutePath)

            Result.success(
                StoredModel(
                    id = "model_${System.currentTimeMillis()}",
                    fileName = destFile.name,
                    filePath = destFile.absolutePath,
                    sizeBytes = totalBytes,
                    format = "GGUF",
                    addedAt = System.currentTimeMillis(),
                    architecture = metadata.architecture,
                    quantization = metadata.quantization,
                    contextLength = metadata.contextLength,
                    embeddingLength = metadata.embeddingLength,
                    parameterCount = metadata.parameterCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listModels(): List<StoredModel> = withContext(Dispatchers.IO) {
        modelsDir.listFiles()
            ?.filter { it.extension.equals("gguf", ignoreCase = true) }
            ?.map { file ->
                try {
                    val meta = GgufParser.parse(file.absolutePath)
                    StoredModel(
                        id = "model_${file.nameWithoutExtension}",
                        fileName = file.name,
                        filePath = file.absolutePath,
                        sizeBytes = file.length(),
                        format = "GGUF",
                        addedAt = file.lastModified(),
                        architecture = meta.architecture,
                        quantization = meta.quantization,
                        contextLength = meta.contextLength,
                        embeddingLength = meta.embeddingLength,
                        parameterCount = meta.parameterCount
                    )
                } catch (_: Exception) {
                    StoredModel(
                        id = "model_${file.nameWithoutExtension}",
                        fileName = file.name,
                        filePath = file.absolutePath,
                        sizeBytes = file.length(),
                        format = "Unknown",
                        addedAt = file.lastModified()
                    )
                }
            }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    suspend fun deleteModel(filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists() && file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to delete: $filePath"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getModelSize(filePath: String): Long = File(filePath).length()

    fun isImporting(fileName: String): Boolean =
        fileName.endsWith(".tmp") || fileName.endsWith(".part")

    fun getAvailableStorageBytes(): Long = modelsDir.usableSpace

    private fun resolveUniqueDestination(fileName: String): File {
        var candidate = File(modelsDir, fileName)
        if (!candidate.exists()) return candidate

        val baseName = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(modelsDir, "${baseName}_$counter.$ext")
            counter++
        }
        return candidate
    }

    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }
}
