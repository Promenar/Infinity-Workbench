package com.promenar.infinity.data.export

import android.content.Context
import android.net.Uri
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class ExportAttachments(private val context: Context, private val resolver: WorkspaceMediaResolver) {
    suspend fun prepare(sessionId: String, doc: ExportDocument, format: SessionExportFormat, enabled: Boolean): ExportDocument {
        val snapshot = !format.supportsAttachments
        val used = safeExportEntryNames(doc.messages.flatMap { it.attachments }.map { it.name })
        var index = 0
        var embeddedChars = 0L
        return doc.copy(messages = doc.messages.map { message ->
            message.copy(attachments = message.attachments.map { attachment ->
                currentCoroutineContext().ensureActive()
                val renamed = attachment.copy(name = used[index++])
                val inlineText = enabled && format.supportsAttachments && renamed.isText && renamed.text == null
                val embed = renamed.isImage && (snapshot || format == SessionExportFormat.HTML && enabled)
                val archive = enabled && format.supportsAttachments && renamed.text == null && (format != SessionExportFormat.HTML || !renamed.isImage)
                if ((!embed && !archive && !inlineText) || renamed.source == null || renamed.text != null) renamed
                else try {
                    if (inlineText) {
                        val output = ByteArrayOutputStream()
                        open(sessionId, renamed).use { copyExportStream(it, output, MAX_EMBEDDED_IMAGE_BYTES) }
                        renamed.copy(text = output.toString("UTF-8"))
                    } else if (embed) {
                        val data = renamed.dataUri ?: open(sessionId, renamed).use { input ->
                            val output = ByteArrayOutputStream()
                            copyExportStream(input, output, MAX_EMBEDDED_IMAGE_BYTES)
                            "data:${renamed.mimeType};base64,${Base64.getEncoder().encodeToString(output.toByteArray())}"
                        }
                        require(isExportImageDataUri(data)) { "图片 data URI 格式无效" }
                        require(data.length <= MAX_EMBEDDED_IMAGE_BYTES * 4 / 3 + 128) { "单张图片超过导出上限" }
                        embeddedChars += data.length
                        require(embeddedChars <= 64L * 1024 * 1024) { "内嵌图片总量超过导出上限" }
                        renamed.copy(dataUri = data)
                    } else {
                        open(sessionId, renamed).use { /* 在估算与导出中使用同一可读性边界。 */ }
                        renamed
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { renamed.copy(name = "${renamed.name}（不可读取）", source = null, dataUri = null) }
            })
        })
    }

    suspend fun archive(sessionId: String, attachments: List<ExportAttachment>, file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            attachments.forEach { attachment ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(attachment.name))
                open(sessionId, attachment).use { copyExportStream(it, zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun open(sessionId: String, attachment: ExportAttachment): InputStream {
        val source = requireNotNull(attachment.source)
        if (attachment.workspace) {
            val media = resolver.resolve(sessionId, source) as? MediaResolution.Available ?: error("工作区附件不可读")
            return resolver.open(media)
        }
        if (source.startsWith("data:", true)) {
            val comma = source.indexOf(',')
            require(comma > 0 && source.substring(0, comma).endsWith(";base64", true))
            val data = source.substring(comma + 1)
            require(data.length <= MAX_EMBEDDED_IMAGE_BYTES * 4 / 3 + 128)
            return Base64.getMimeDecoder().decode(data).inputStream()
        }
        val uri = Uri.parse(source)
        if (uri.scheme == "content") return context.contentResolver.openInputStream(uri) ?: error("附件授权已失效")
        require(uri.scheme == null || uri.scheme == "file") { "不支持的附件来源" }
        // 旧附件真实路径只允许应用 files 目录；工作区文件必须经过 WorkspaceMediaResolver。
        val root = context.filesDir.toPath().toRealPath()
        val candidate = File(uri.path ?: source).toPath().toAbsolutePath().normalize()
        val alias = java.nio.file.Paths.get("/data/data", context.packageName, "files")
        val declared = context.filesDir.toPath().toAbsolutePath().normalize()
        val target = when {
            candidate.startsWith(declared) -> root.resolve(declared.relativize(candidate))
            candidate.startsWith(alias) -> root.resolve(alias.relativize(candidate))
            else -> candidate
        }
        require(target.startsWith(root) && target != root)
        var cursor = target
        while (cursor != root) { require(!Files.isSymbolicLink(cursor)); cursor = requireNotNull(cursor.parent) }
        require(Files.isRegularFile(target))
        return Files.newInputStream(target)
    }
}

private const val MAX_EMBEDDED_IMAGE_BYTES = 16L * 1024 * 1024

internal suspend fun copyExportStream(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= limit) { "附件大小超过导出上限" }
        output.write(buffer, 0, count)
    }
}
