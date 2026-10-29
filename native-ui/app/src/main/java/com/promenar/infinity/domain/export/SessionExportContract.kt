package com.promenar.infinity.domain.export

import java.io.File

/** 会话导出格式。PDF 与图片由 HTML 在离屏 WebView 中渲染得到，三者外观一致。 */
enum class SessionExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    TXT("txt", "text/plain"),
    HTML("html", "text/html"),
    PDF("pdf", "application/pdf"),
    PNG("png", "image/png");

    /** 只有这三种格式提供“附件”开关；PDF 与图片中图片内嵌、其它附件只列文件名。 */
    val supportsAttachments: Boolean
        get() = this == MARKDOWN || this == TXT || this == HTML
}

/** 导出内容开关。思考过程与工具调用默认不导出；工具调用只导出工具名和结果摘要。 */
data class SessionExportOptions(
    val includeThinking: Boolean = false,
    val includeToolCalls: Boolean = false,
    val includeAttachments: Boolean = true,
)

/**
 * 导出前的预计输出，供面板显示“1 个 .md 文件，约 38 KB”一类的说明。
 *
 * - [primaryFileCount]：主文件数；图片格式为预计切出的长图张数，其它格式为 1；
 * - [attachmentArchive]：是否会额外生成同名附件 zip；
 * - [approxBytes]：全部输出文件的大致总字节数；
 * - [approxPages]：PDF 的预计页数，其它格式为 null。
 */
data class SessionExportEstimate(
    val primaryFileCount: Int,
    val attachmentArchive: Boolean,
    val approxBytes: Long,
    val approxPages: Int? = null,
)

/** 一个已生成的导出文件；[file] 位于 cache 目录，可经 FileProvider 分享。 */
data class SessionExportFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
)

/**
 * 一次导出的结果。[files] 按顺序排列：主文件（多张长图按编号）在前，附件 zip 在后。
 * 用完调用 [SessionExporter.release] 删除临时目录。
 */
data class SessionExportResult(
    val files: List<SessionExportFile>,
    val workDir: File,
)

/** 会话导出服务。所有方法在内部切换到合适的线程，可从主线程调用；导出过程可取消。 */
interface SessionExporter {
    /** 估算输出，不启动 WebView，常规会话应在几百毫秒内返回。 */
    suspend fun estimate(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportEstimate

    /** 生成导出文件；协程取消时停止渲染并删除已写入的临时文件。 */
    suspend fun export(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportResult

    /** 删除一次导出的临时目录。分享出去的文件由接收方延后读取，分享场景不立即调用。 */
    fun release(result: SessionExportResult)
}
