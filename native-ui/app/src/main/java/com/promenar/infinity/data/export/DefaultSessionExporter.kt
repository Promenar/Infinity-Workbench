package com.promenar.infinity.data.export

import android.content.Context
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.export.*
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

class DefaultSessionExporter(
    context: Context,
    private val messages: IMessageRepository,
    private val sessions: ISessionRepository,
    mediaResolver: WorkspaceMediaResolver,
) : SessionExporter {
    private val context = context.applicationContext
    private val attachments = ExportAttachments(this.context, mediaResolver)
    private val html = ExportHtmlRenderer(ExportAssets { this.context.assets.open(it).use { input -> input.readBytes() } })
    private val root get() = File(context.cacheDir, "session-export")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun estimate(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportEstimate = withContext(Dispatchers.IO) {
        val doc = document(sessionId, options)
        if (!format.supportsAttachments) return@withContext estimateExportDocument(doc, format, options.includeAttachments)
        val prepared = attachments.prepare(sessionId, doc, format, options.includeAttachments)
        val content = if (format == SessionExportFormat.HTML) html.render(prepared, format, options.includeAttachments)
            else ExportTextRenderer.render(prepared, format, options.includeAttachments)
        ensureActive()
        estimateExportDocument(prepared, format, options.includeAttachments, content.toByteArray(Charsets.UTF_8).size.toLong())
    }

    override suspend fun export(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportResult {
        var created: File? = null
        return try {
            withContext(Dispatchers.IO) {
                check(root.mkdirs() || root.isDirectory) { "无法创建会话导出目录" }
                cleanupExpiredExportDirectories(root, System.currentTimeMillis())
                val workDir = File(root, UUID.randomUUID().toString())
                check(workDir.mkdir()) { "无法创建临时导出目录" }
                created = workDir
                val doc = attachments.prepare(sessionId, document(sessionId, options), format, options.includeAttachments)
                val base = exportBaseName(doc.title, doc.createdAt)
                val files = if (format == SessionExportFormat.PDF || format == SessionExportFormat.PNG) {
                    ExportWebViewRenderer(context).render(html.render(doc, format, options.includeAttachments), workDir, base, format)
                } else {
                    val file = File(workDir, "$base.${format.extension}")
                    val content = if (format == SessionExportFormat.HTML) html.render(doc, format, options.includeAttachments)
                        else ExportTextRenderer.render(doc, format, options.includeAttachments)
                    ensureActive()
                    file.outputStream().buffered().use { output -> copyExportStream(content.byteInputStream(), output) }
                    listOf(SessionExportFile(file, file.name, format.mimeType))
                }.toMutableList()
                val archived = exportArchiveAttachments(doc, format, options.includeAttachments)
                if (archived.isNotEmpty()) {
                    val zip = File(workDir, "$base.zip")
                    attachments.archive(sessionId, archived, zip)
                    files += SessionExportFile(zip, zip.name, "application/zip")
                }
                ensureActive()
                workDir.setLastModified(System.currentTimeMillis())
                SessionExportResult(files, workDir)
            }
        } catch (failure: Throwable) {
            // 也覆盖 IO 返回调用线程时的取消窗口。
            withContext(NonCancellable + Dispatchers.IO) { created?.deleteRecursively() }
            throw failure
        }
    }

    private suspend fun document(sessionId: String, options: SessionExportOptions): ExportDocument {
        val session = sessions.getById(sessionId) ?: throw NoSuchElementException("会话不存在：$sessionId")
        currentCoroutineContext().ensureActive()
        return ExportDocumentBuilder.build(session, messages.getBySession(sessionId), options, System.currentTimeMillis())
    }

    override fun release(result: SessionExportResult) {
        // 契约是非挂起函数，删除在独立 IO 作用域执行，避免阻塞主线程。
        cleanupScope.launch {
            val target = result.workDir
            if (target.parentFile?.canonicalFile == root.canonicalFile && target.name.matches(EXPORT_DIRECTORY_ID) && !Files.isSymbolicLink(target.toPath())) {
                target.deleteRecursively()
            }
        }
    }
}

private val EXPORT_DIRECTORY_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal fun cleanupExpiredExportDirectories(root: File, now: Long) {
    root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name.matches(EXPORT_DIRECTORY_ID) && now - it.lastModified() > 24 * 60 * 60 * 1000L }
        .forEach { it.deleteRecursively() }
}
