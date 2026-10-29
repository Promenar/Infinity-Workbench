package com.promenar.infinity.domain.usecase

import com.promenar.infinity.data.export.*
import com.promenar.infinity.data.model.MessageQuoteFormatter
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.repository.IMessageRepository
import com.promenar.infinity.domain.repository.ISessionRepository
import kotlinx.coroutines.flow.first

/** 兼容斜杠命令的字符串导出入口；文件导出和内容开关由 SessionExporter 提供。 */
class ExportSessionUseCase(
    private val messageRepository: IMessageRepository,
    private val sessionRepository: ISessionRepository,
) {
    enum class Format { TXT, MARKDOWN }
    data class ExportResult(val content: String, val fileName: String, val mimeType: String)

    suspend fun export(sessionId: String, format: Format): ExportResult {
        val session = sessionRepository.observeById(sessionId).first()
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val messages = messageRepository.observeBySession(sessionId).first()
        val doc = ExportDocument(session.title, session.modelId, session.createdAt, System.currentTimeMillis(), messages.map { message ->
            ExportMessage(message.role.name, message.timestamp, message.modelId,
                MessageQuoteFormatter.formatUserContent(message.content, message.quotes), message.thinking,
                attachments = message.documents.map { ExportAttachment(it.name, it.mimeType, text = it.content) })
        })
        val target = if (format == Format.TXT) SessionExportFormat.TXT else SessionExportFormat.MARKDOWN
        return ExportResult(ExportTextRenderer.render(doc, target, true), "${exportBaseName(session.title, session.createdAt)}.${target.extension}", target.mimeType)
    }
}
