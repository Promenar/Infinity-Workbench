package com.promenar.nexara.domain.usecase

import com.promenar.nexara.domain.model.Message
import com.promenar.nexara.domain.repository.IMessageRepository
import com.promenar.nexara.domain.repository.ISessionRepository
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportSessionUseCase(
    private val messageRepository: IMessageRepository,
    private val sessionRepository: ISessionRepository
) {

    enum class Format { TXT, MARKDOWN }

    data class ExportResult(
        val content: String,
        val fileName: String,
        val mimeType: String
    )

    suspend fun export(sessionId: String, format: Format): ExportResult {
        val session = sessionRepository.observeById(sessionId).first()
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val messages = messageRepository.observeBySession(sessionId).first()

        val (content, extension, mimeType) = when (format) {
            Format.TXT -> Triple(exportAsText(session.title, session.modelId, session.createdAt, messages), "txt", "text/plain")
            Format.MARKDOWN -> Triple(exportAsMarkdown(session.title, session.modelId, session.createdAt, messages), "md", "text/markdown")
        }

        val safeTitle = session.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_.\\-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(session.createdAt))
        val fileName = "${safeTitle}_$timestamp.$extension"

        return ExportResult(content = content, fileName = fileName, mimeType = mimeType)
    }

    private fun exportAsText(title: String, modelId: String, createdAt: Long, messages: List<Message>): String {
        return buildString {
            appendLine("# $title")
            appendLine("Date: ${formatTimestamp(createdAt)}")
            appendLine("Model: $modelId")
            appendLine()
            if (messages.isEmpty()) {
                appendLine("(No messages)")
            } else {
                messages.forEach { msg ->
                    appendLine("## ${messageHeading(msg)}")
                    appendLine(msg.content)
                    if (msg.thinking != null) {
                        appendLine("--- Thinking ---")
                        appendLine(msg.thinking)
                    }
                    appendTextAttachments(msg)
                    appendLine()
                }
            }
        }
    }

    private fun exportAsMarkdown(title: String, modelId: String, createdAt: Long, messages: List<Message>): String {
        return buildString {
            appendLine("# $title")
            appendLine("*${formatTimestamp(createdAt)}* | Model: `$modelId`")
            appendLine()
            if (messages.isEmpty()) {
                appendLine("*(No messages)*")
            } else {
                messages.forEach { msg ->
                    appendLine("### ${messageHeading(msg)}")
                    appendLine()
                    appendLine(msg.content)
                    appendLine()
                    if (msg.thinking != null) {
                        appendLine("> **Thinking:**")
                        appendLine("> ${msg.thinking.replace("\n", "\n> ")}")
                        appendLine()
                    }
                    appendMarkdownAttachments(msg)
                }
            }
        }
    }

    private fun messageHeading(message: Message): String = buildString {
        append(message.role.name)
        append(" · ")
        append(formatTimestamp(message.timestamp))
        message.modelId?.takeIf(String::isNotBlank)?.let { modelId ->
            append(" · Model: ")
            append(modelId)
        }
    }

    private fun StringBuilder.appendTextAttachments(message: Message) {
        message.documents.forEach { document ->
            appendLine()
            appendLine("--- Attachment: ${document.name} (${document.mimeType}) ---")
            append(document.content)
            if (!document.content.endsWith('\n')) appendLine()
            appendLine("--- End Attachment ---")
        }
    }

    private fun StringBuilder.appendMarkdownAttachments(message: Message) {
        message.documents.forEach { document ->
            appendLine("<!-- nexara-attachment name=\"${document.name.escapeHtmlAttribute()}\" mime=\"${document.mimeType.escapeHtmlAttribute()}\" -->")
            append(document.content)
            if (!document.content.endsWith('\n')) appendLine()
            appendLine("<!-- /nexara-attachment -->")
            appendLine()
        }
    }

    private fun String.escapeHtmlAttribute(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun formatTimestamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}
