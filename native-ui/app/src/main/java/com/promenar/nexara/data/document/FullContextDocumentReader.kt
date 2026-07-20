package com.promenar.nexara.data.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.promenar.nexara.data.model.MessageDocumentAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

class FullContextDocumentReader(
    private val contentResolver: ContentResolver,
    private val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
) {
    init {
        require(maxDocumentBytes > 0) { "文档大小上限必须为正数" }
    }

    suspend fun read(uri: Uri): DocumentReadResult = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(uri) ?: return@withContext DocumentReadResult.Rejected(
            DocumentReadRejectReason.ReadFailed,
        )
        val declaredMimeType = try {
            contentResolver.getType(uri)
        } catch (_: SecurityException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IllegalArgumentException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        }
        val mimeType = resolveMimeType(declaredMimeType, metadata.displayName)
            ?: return@withContext DocumentReadResult.Rejected(
                DocumentReadRejectReason.UnsupportedDocument,
            )
        if (metadata.sizeBytes != null && metadata.sizeBytes <= 0L) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)
        }
        if (metadata.sizeBytes != null && metadata.sizeBytes > maxDocumentBytes) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.DocumentTooLarge)
        }

        val bytes = try {
            readBoundedBytes(uri)
        } catch (_: DocumentTooLargeException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.DocumentTooLarge)
        } catch (_: SecurityException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IOException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        } catch (_: IllegalArgumentException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.ReadFailed)
        }
        if (bytes.isEmpty()) return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)

        val content = try {
            decodeText(bytes)
        } catch (_: CharacterCodingException) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.InvalidTextEncoding)
        }
        if (content.isEmpty()) return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.EmptyDocument)
        if (content.hasBinaryControlCharacters()) {
            return@withContext DocumentReadResult.Rejected(DocumentReadRejectReason.BinaryContent)
        }

        DocumentReadResult.Success(
            MessageDocumentAttachment(
                id = UUID.randomUUID().toString(),
                name = metadata.displayName ?: uri.lastPathSegment ?: "document.txt",
                mimeType = mimeType,
                content = content,
                sizeBytes = bytes.size.toLong(),
                sha256 = bytes.sha256(),
                estimatedTokens = DocumentTokenEstimator.estimate(content),
            ),
        )
    }

    private fun queryMetadata(uri: Uri): DocumentMetadata? = try {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it)?.trim()?.takeIf(String::isNotEmpty) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it) }
            }
        }
        DocumentMetadata(displayName, sizeBytes)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readBoundedBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选文档")
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size().toLong() > maxDocumentBytes - read) {
                    throw DocumentTooLargeException()
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val (charset, offset) = when {
            bytes.hasPrefix(UTF8_BOM) -> Charsets.UTF_8 to UTF8_BOM.size
            bytes.hasPrefix(UTF16_LE_BOM) -> Charsets.UTF_16LE to UTF16_LE_BOM.size
            bytes.hasPrefix(UTF16_BE_BOM) -> Charsets.UTF_16BE to UTF16_BE_BOM.size
            else -> Charsets.UTF_8 to 0
        }
        return charset.strictDecode(bytes, offset)
    }

    private fun resolveMimeType(declaredMimeType: String?, displayName: String?): String? {
        val extensionMimeType = when {
            displayName?.endsWith(".txt", ignoreCase = true) == true -> "text/plain"
            displayName?.endsWith(".md", ignoreCase = true) == true -> "text/markdown"
            displayName?.endsWith(".markdown", ignoreCase = true) == true -> "text/markdown"
            else -> null
        }
        if (displayName != null) return extensionMimeType
        val declared = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (declared in SUPPORTED_MIME_TYPES) return declared
        return null
    }

    private data class DocumentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private class DocumentTooLargeException : IOException()

    companion object {
        const val DEFAULT_MAX_DOCUMENT_BYTES = 1024L * 1024L
        private val SUPPORTED_MIME_TYPES = setOf("text/plain", "text/markdown")
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}

sealed interface DocumentReadResult {
    data class Success(val attachment: MessageDocumentAttachment) : DocumentReadResult
    data class Rejected(val reason: DocumentReadRejectReason) : DocumentReadResult
}

enum class DocumentReadRejectReason {
    UnsupportedDocument,
    EmptyDocument,
    DocumentTooLarge,
    BinaryContent,
    InvalidTextEncoding,
    ReadFailed,
}

object DocumentTokenEstimator {
    fun estimate(content: String): Int =
        ConservativeContextTokenEstimator.estimate(content)
}

/** 一字节按一个 token 计入，作为未知 tokenizer 下不会低估文本的保守门禁上界。 */
object ConservativeContextTokenEstimator {
    fun estimate(content: String): Int = content.toByteArray(Charsets.UTF_8).size
}

private fun Charset.strictDecode(bytes: ByteArray, offset: Int): String = newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
    .toString()

private fun String.hasBinaryControlCharacters(): Boolean = any { character ->
    character == '\u0000' || (character.isISOControl() && character !in "\n\r\t")
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }
