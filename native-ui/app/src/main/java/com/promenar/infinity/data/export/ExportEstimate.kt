package com.promenar.infinity.data.export

import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFormat
import kotlin.math.ceil

/** 只估算布局，不加载 WebView；按中文等宽字符和代码行给出保守体量。 */
internal fun estimateExportDocument(doc: ExportDocument, format: SessionExportFormat, attachments: Boolean, renderedBytes: Long? = null): SessionExportEstimate {
    val textChars = doc.messages.sumOf { it.body.length.toLong() + (it.thinking?.length ?: 0) + it.tools.sumOf { t -> t.summary.length } }
    val lines = doc.messages.sumOf { message ->
        (message.body + message.thinking.orEmpty()).lineSequence().sumOf { ceil(it.length / 40.0).toLong().coerceAtLeast(1) } + 5L
    }
    val imageCount = doc.messages.sumOf { it.attachments.count(ExportAttachment::isImage) }
    val codeCount = doc.messages.sumOf { Regex("(?m)^\\s*```").findAll(it.body).count() / 2 }
    val height = 240L + lines * 30L + imageCount * 650L + codeCount * 60L
    val pages = ceil(height / 1350.0).toInt().coerceAtLeast(1)
    val images = ceil(height / 20_000.0).toInt().coerceAtLeast(1)
    val archive = exportArchiveAttachments(doc, format, attachments)
    val archiveBytes = archive.sumOf { it.sizeBytes.coerceAtLeast(0) + 128 }
    val bytes = renderedBytes ?: when (format) {
        SessionExportFormat.PDF -> pages * 40_000L + textChars * 2
        SessionExportFormat.PNG -> height * 1080L / 5
        else -> textChars * 3 + 512
    }
    return SessionExportEstimate(if (format == SessionExportFormat.PNG) images else 1, archive.isNotEmpty(), bytes + archiveBytes,
        if (format == SessionExportFormat.PDF) pages else null)
}
