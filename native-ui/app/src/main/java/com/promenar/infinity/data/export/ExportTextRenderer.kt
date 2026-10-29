package com.promenar.infinity.data.export

import com.promenar.infinity.domain.export.SessionExportFormat

internal object ExportTextRenderer {
    fun render(doc: ExportDocument, format: SessionExportFormat, includeAttachments: Boolean): String {
        require(format == SessionExportFormat.MARKDOWN || format == SessionExportFormat.TXT)
        val md = format == SessionExportFormat.MARKDOWN
        return buildString {
            appendLine("# ${doc.title}")
            if (md) appendLine("*${exportTimestamp(doc.createdAt)}* | Model: `${doc.model}`")
            else {
                appendLine("Date: ${exportTimestamp(doc.createdAt)}")
                appendLine("Model: ${doc.model}")
            }
            appendLine()
            if (doc.messages.isEmpty()) appendLine(if (md) "*(No messages)*" else "(No messages)")
            doc.messages.forEach { message ->
                appendLine("${if (md) "###" else "##"} ${exportHeading(message)}")
                appendLine()
                message.thinking?.let {
                    if (md) appendLine("> **Thinking:**\n> ${it.replace("\n", "\n> ")}")
                    else appendLine("--- Thinking ---\n$it")
                    appendLine()
                }
                appendLine(message.body)
                appendLine()
                message.tools.forEach { appendLine("${it.name} · ${it.status} · ${it.summary}") }
                if (!includeAttachments && message.attachments.isNotEmpty()) {
                    appendLine("附件：${message.attachments.joinToString("、") { singleLine(it.name) }}")
                } else message.attachments.forEach { attachment ->
                    if (attachment.text != null) {
                        if (md) appendLine("<!-- infinity-attachment name=\"${escapeExportHtml(attachment.name)}\" mime=\"${escapeExportHtml(attachment.mimeType)}\" -->")
                        else appendLine("--- Attachment: ${attachment.name} (${attachment.mimeType}) ---")
                        append(attachment.text)
                        if (!attachment.text.endsWith('\n')) appendLine()
                        appendLine(if (md) "<!-- /infinity-attachment -->" else "--- End Attachment ---")
                    } else {
                        val name = attachment.name
                        if (md && attachment.source != null) {
                            val label = name.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                            val path = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                            appendLine("${if (attachment.isImage) "!" else ""}[$label]($path)")
                        } else appendLine("附件：${singleLine(name)}")
                    }
                    appendLine()
                }
                appendLine()
            }
        }
    }
}
