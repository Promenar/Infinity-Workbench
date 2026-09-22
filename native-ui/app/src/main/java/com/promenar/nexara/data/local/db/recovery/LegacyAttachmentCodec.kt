package com.promenar.nexara.data.local.db.recovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

internal data class LegacyAttachmentItem(
    val uri: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val type: String,
    val unreadableReason: String? = null,
)

internal sealed interface LegacyAttachmentOpenResult {
    data class Ready(val intent: Intent) : LegacyAttachmentOpenResult
    data class Unreadable(val reason: String) : LegacyAttachmentOpenResult
}

/** 仅解析历史payload；任何字节读取都延迟到用户点开，并受单文件上限约束。 */
internal object LegacyAttachmentCodec {
    const val MAX_ATTACHMENT_BYTES = 16L * 1024L * 1024L
    private const val MAX_PAYLOAD_CHARS = 24 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun parse(payload: String?): List<LegacyAttachmentItem> {
        if (payload.isNullOrBlank()) return emptyList()
        if (payload.length > MAX_PAYLOAD_CHARS) return listOf(
            LegacyAttachmentItem("", "application/octet-stream", "历史附件清单", 0, "DOCUMENT", "附件清单超过安全上限"),
        )
        val elements = runCatching { Json.parseToJsonElement(payload).jsonArray }.getOrElse {
            return listOf(LegacyAttachmentItem("", "application/octet-stream", "历史附件", 0, "DOCUMENT", "附件记录已损坏"))
        }
        if (elements.size > MAX_ATTACHMENT_ITEMS) return listOf(
            LegacyAttachmentItem("", "application/octet-stream", "历史附件清单", 0, "DOCUMENT", "附件数量超过安全上限"),
        )
        return elements.mapIndexed { index, element ->
            runCatching { json.decodeFromJsonElement(Raw.serializer(), element) }.fold(
                onSuccess = { raw ->
                    val normalizedType = raw.type.uppercase()
                    val mime = raw.mimeType.ifBlank { mimeForType(normalizedType) }
                    val name = raw.fileName.ifBlank { "历史附件 ${index + 1}" }
                    val reason = when {
                        normalizedType !in TYPES -> "未知的历史附件类型"
                        raw.uri.isBlank() -> "附件地址为空"
                        raw.sizeBytes < 0 || raw.sizeBytes > MAX_ATTACHMENT_BYTES -> "附件大小超过安全上限"
                        Uri.parse(raw.uri).scheme?.lowercase() !in setOf("data", "content", "file", null) -> "不支持的附件地址"
                        else -> null
                    }
                    LegacyAttachmentItem(raw.uri, mime, name, raw.sizeBytes, normalizedType, reason)
                },
                onFailure = {
                    LegacyAttachmentItem("", "application/octet-stream", "历史附件 ${index + 1}", 0, "DOCUMENT", "附件记录已损坏")
                },
            )
        }
    }

    fun open(
        context: Context,
        item: LegacyAttachmentItem,
        previewUriFactory: (Context, File) -> Uri = ::fileProviderUri,
    ): LegacyAttachmentOpenResult {
        item.unreadableReason?.let { return LegacyAttachmentOpenResult.Unreadable(it) }
        return runCatching {
            val source = Uri.parse(item.uri)
            val viewUri = when (source.scheme?.lowercase()) {
                "data" -> materializeDataUri(context, item, previewUriFactory)
                "content" -> {
                    context.contentResolver.openInputStream(source)?.use {
                        publishPreview(context, item, it, previewUriFactory)
                    }
                        ?: error("附件授权已失效")
                }
                "file", null -> materializePrivateFile(context, item, source, previewUriFactory)
                else -> error("不支持的附件地址")
            }
            LegacyAttachmentOpenResult.Ready(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(viewUri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.getOrElse { LegacyAttachmentOpenResult.Unreadable(it.message ?: "附件不可读") }
    }

    private fun materializeDataUri(
        context: Context,
        item: LegacyAttachmentItem,
        previewUriFactory: (Context, File) -> Uri,
    ): Uri {
        val raw = item.uri
        val comma = raw.indexOf(',')
        require(comma > 5 && raw.substring(5, comma).contains(";base64", ignoreCase = true)) { "历史data附件格式无效" }
        val encoded = raw.substring(comma + 1)
        require(encoded.length <= ((MAX_ATTACHMENT_BYTES + 2) / 3 * 4 + 16).toInt()) { "附件大小超过安全上限" }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "附件大小超过安全上限" }
        return publishPreview(context, item, bytes.inputStream(), previewUriFactory)
    }

    private fun materializePrivateFile(
        context: Context,
        item: LegacyAttachmentItem,
        uri: Uri,
        previewUriFactory: (Context, File) -> Uri,
    ): Uri {
        val declaredAnchor = context.filesDir.toPath().toAbsolutePath().normalize()
        val anchor = declaredAnchor.toRealPath()
        val declaredCandidate = File(uri.path ?: item.uri).toPath().toAbsolutePath().normalize()
        val legacyAlias = Paths.get("/data/data", context.packageName, "files")
        val candidate = when {
            declaredCandidate.startsWith(declaredAnchor) ->
                anchor.resolve(declaredAnchor.relativize(declaredCandidate)).normalize()
            declaredCandidate.startsWith(legacyAlias) ->
                anchor.resolve(legacyAlias.relativize(declaredCandidate)).normalize()
            else -> declaredCandidate
        }
        require(candidate.startsWith(anchor)) { "历史文件不在受信附件目录" }
        var cursor = candidate
        while (cursor != anchor) {
            require(!Files.isSymbolicLink(cursor)) { "历史文件路径包含符号链接" }
            cursor = requireNotNull(cursor.parent)
        }
        val before = Files.readAttributes(candidate, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.isRegularFile && before.size() <= MAX_ATTACHMENT_BYTES) { "历史文件缺失或超过安全上限" }
        val result = Files.newInputStream(candidate).use {
            publishPreview(context, item, it, previewUriFactory)
        }
        val after = Files.readAttributes(candidate, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size()) { "历史文件读取期间发生变化" }
        return result
    }

    @Synchronized
    private fun publishPreview(
        context: Context,
        item: LegacyAttachmentItem,
        input: InputStream,
        previewUriFactory: (Context, File) -> Uri,
    ): Uri {
        val directory = File(context.cacheDir, "legacy-attachment-preview")
        if (!directory.exists()) Files.createDirectory(directory.toPath())
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "只读附件目录无效" }
        cleanupPreviewCache(directory, protected = null)
        val contentDigest = MessageDigest.getInstance("SHA-256")
        val extension = item.fileName.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"
        val temporary = File(directory, ".preview-${System.nanoTime()}.tmp")
        try {
            temporary.outputStream().use { sink ->
                copyBounded(input, sink, contentDigest)
                sink.flush()
            }
            val digest = contentDigest.digest().joinToString("") { "%02x".format(it) }
            val output = File(directory, "$digest.$extension")
            if (output.exists()) {
                require(output.isFile && !Files.isSymbolicLink(output.toPath()) &&
                    output.length() <= MAX_ATTACHMENT_BYTES && output.length() == temporary.length() &&
                    MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(output.readBytes()), hexToBytes(digest))) {
                    "只读附件缓存冲突"
                }
                temporary.delete()
            } else if (!temporary.renameTo(output)) {
                error("无法准备只读附件")
            }
            cleanupPreviewCache(directory, protected = output)
            return previewUriFactory(context, output)
        } finally { temporary.delete() }
    }

    internal fun copyBounded(input: InputStream, sink: OutputStream, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val actualCount = if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                buffer[0] = byte.toByte()
                1
            } else {
                count
            }
            total += actualCount
            require(total <= MAX_ATTACHMENT_BYTES) { "附件大小超过安全上限" }
            sink.write(buffer, 0, actualCount)
            digest?.update(buffer, 0, actualCount)
        }
        return total
    }

    private fun cleanupPreviewCache(directory: File, protected: File?) {
        val owned = mutableListOf<File>()
        directory.listFiles().orEmpty().forEach { file ->
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "只读附件缓存包含未知节点" }
            if (file.name.matches(Regex("\\.preview-[0-9]+\\.tmp"))) {
                check(file.delete()) { "无法清理中断的附件缓存" }
            } else {
                require(file.name.matches(Regex("[0-9a-f]{64}\\.[A-Za-z0-9]{1,10}"))) {
                    "只读附件缓存包含未知文件"
                }
                owned += file
            }
        }
        var retainedCount = if (protected == null) 0 else 1
        var retainedBytes = protected?.length() ?: 0L
        owned.filter { it != protected }.sortedByDescending(File::lastModified).forEach { file ->
            val retain = retainedCount < MAX_CACHED_PREVIEWS &&
                retainedBytes <= MAX_CACHED_PREVIEW_BYTES - file.length()
            if (retain) retainedBytes += file.length() else check(file.delete()) { "无法收敛附件缓存预算" }
            if (retain) retainedCount++
        }
        require(retainedBytes <= MAX_CACHED_PREVIEW_BYTES) { "只读附件缓存预算不足" }
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun mimeForType(type: String) = when (type) {
        "IMAGE" -> "image/*"
        "VIDEO" -> "video/*"
        "AUDIO" -> "audio/*"
        else -> "application/octet-stream"
    }

    @Serializable
    private data class Raw(
        val uri: String,
        val mimeType: String,
        val fileName: String = "",
        val sizeBytes: Long = 0,
        val type: String = "IMAGE",
    )

    private val TYPES = setOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT")
    private const val MAX_CACHED_PREVIEWS = 16
    private const val MAX_CACHED_PREVIEW_BYTES = 64L * 1024L * 1024L
    private const val MAX_ATTACHMENT_ITEMS = 32
}
