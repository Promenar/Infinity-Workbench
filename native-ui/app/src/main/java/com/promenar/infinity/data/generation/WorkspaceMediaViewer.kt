package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.MediaViewMode
import com.promenar.infinity.domain.media.ViewedMedia
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.media.planKeyframes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

const val VIEW_MEDIA_MAX_BYTES = 20 * 1024 * 1024
const val VIEW_MEDIA_MAX_IMAGE_BYTES = 4 * 1024 * 1024

data class MediaViewRequest(
    val paths: List<String>,
    val question: String? = null,
    val asFrames: Boolean = false,
    val frames: Int? = null,
    val intervalSeconds: Double? = null,
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
)

data class EncodedViewedMedia(val kind: MediaKind, val mimeType: String, val bytes: ByteArray, val timestampSeconds: Double? = null)
data class PreparedViewedMedia(val viewed: ViewedMedia, val parts: List<EncodedViewedMedia>, val note: String = "")

fun interface MediaViewPreprocessor {
    suspend fun prepare(source: MediaResolution.Available, mode: MediaViewMode, timestamps: List<Double>, expectedSha256: String?): PreparedViewedMedia
}

/** 工具与当前轮重载共用相同的模态限制、预处理和字节预算。 */
class WorkspaceMediaViewer(
    private val resolver: WorkspaceMediaResolver,
    private val preprocessor: MediaViewPreprocessor,
) {
    suspend fun view(sessionId: String, request: MediaViewRequest, target: MediaViewTarget): ToolResult = withContext(Dispatchers.IO) {
        require(request.paths.size in 1..8 && request.paths.all { it.isNotBlank() }) { "paths 必须包含 1–8 个工作区路径。" }
        require(request.frames == null || request.intervalSeconds == null) { "frames 与 interval_seconds 不能同时提供。" }
        require(request.frames == null || request.frames > 0) { "frames 必须是正整数。" }
        require(request.intervalSeconds == null || request.intervalSeconds.isFinite() && request.intervalSeconds > 0) { "interval_seconds 必须是正有限数。" }
        require(listOfNotNull(request.startSeconds, request.endSeconds).all(Double::isFinite)) { "时间范围必须是有限数。" }
        if (!target.canView) return@withContext ToolResult("view_media", "当前模型不支持图片、音频或视频输入，无法使用 view_media。", "error")
        val lines = mutableListOf<String>()
        val viewed = mutableListOf<ViewedMedia>()
        var remaining = VIEW_MEDIA_MAX_BYTES
        var remainingFrames = 32
        var truncated = false
        request.paths.forEach { path ->
            coroutineContext.ensureActive()
            if (truncated) {
                lines += "$path：单次媒体总量上限为 20 MiB，已按顺序截断，请分批查看。"
                return@forEach
            }
            try {
                val resolved = resolver.resolve(sessionId, path)
                if (resolved !is MediaResolution.Available) {
                    val unavailable = resolved as MediaResolution.Unavailable
                    val reason = when (unavailable.reason) {
                        MediaUnavailableReason.INVALID_PATH -> "路径无效或超出工作区范围"
                        MediaUnavailableReason.NOT_FOUND -> "文件不存在"
                        MediaUnavailableReason.IS_DIRECTORY -> "路径是目录"
                        MediaUnavailableReason.UNREADABLE -> "文件不可读或权限已失效"
                    }
                    lines += "$path：媒体不可用（$reason）。"
                    return@forEach
                }
                val ref = resolved.ref
                val metadata = mediaMetadata(ref)
                val unsupported = unsupportedMediaReason(ref, target)
                if (unsupported != null) {
                    lines += "${ref.path}：$unsupported；$metadata"
                    return@forEach
                }
                val frames = ref.kind == MediaKind.VIDEO && (request.asFrames || !target.supportsVideo(ref.mimeType) || ref.sizeBytes > VIEW_MEDIA_MAX_BYTES)
                if (frames && !target.supportsImages) {
                    lines += "${ref.path}：当前模型或协议无法按关键帧查看视频；$metadata"
                    return@forEach
                }
                val plan = if (frames) planKeyframes(ref.durationMs?.div(1000.0), request.frames, request.intervalSeconds, request.startSeconds, request.endSeconds) else null
                val plannedTimes = plan?.timestampsSeconds.orEmpty()
                val times = plannedTimes.take(remainingFrames)
                if (frames && times.isEmpty()) {
                    lines += "${ref.path}：已达到单次调用 32 帧上限；请从 t=${formatMediaSeconds(plannedTimes.first())}s 分批查看；$metadata"
                    return@forEach
                }
                val mode = if (frames) MediaViewMode.KEYFRAMES else MediaViewMode.ORIGINAL
                val prepared = preprocessor.prepare(resolved, mode, times, null)
                val accepted = prepared.parts.takeWhile { part ->
                    if (part.bytes.size > remaining) false else { remaining -= part.bytes.size; true }
                }
                truncated = accepted.size != prepared.parts.size || prepared.note.contains("20 MiB")
                if (accepted.isEmpty()) {
                    lines += "${ref.path}：单次媒体总量超过 20 MiB，已按顺序截断；$metadata"
                    return@forEach
                }
                val kept = prepared.viewed.copy(frameTimestampsSeconds = accepted.mapNotNull { it.timestampSeconds })
                viewed += kept
                remainingFrames -= kept.frameTimestampsSeconds.size
                lines += buildString {
                    append("${ref.path}：已查看（${if (frames) "关键帧" else "原文件"}）；$metadata")
                    if (frames) append("\n以下为关键帧，不含声音。\n" + kept.frameTimestampsSeconds.joinToString("\n") { "帧 t=${formatMediaSeconds(it)}s" })
                    plan?.note?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                    if (frames && times.size < plannedTimes.size) append("\n达到单次调用 32 帧上限；请从 t=${formatMediaSeconds(plannedTimes[times.size])}s 继续查看。")
                    if (prepared.note.isNotBlank()) append("\n${prepared.note}")
                    if (truncated) append("\n单次媒体总量达到 20 MiB，已截断；请从未发送的下一时间点或路径继续查看。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                lines += "$path：${invalid.message ?: "媒体参数无效"}"
            } catch (_: Exception) {
                lines += "$path：媒体无法读取或预处理失败，请检查文件与工作区权限。"
            }
        }
        ToolResult("view_media", lines.joinToString("\n\n"), media = viewed.map { it.ref }.takeIf { it.isNotEmpty() }, viewedMedia = viewed.takeIf { it.isNotEmpty() })
    }

    /** 每次请求都重验 SHA-256；缓存命中也必须确认来源，文件修改或删除后只发送说明。 */
    suspend fun reload(sessionId: String, records: List<ViewedMedia>, target: MediaViewTarget): Pair<List<PreparedViewedMedia>, List<String>> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<PreparedViewedMedia>()
        val notes = mutableListOf<String>()
        var remaining = VIEW_MEDIA_MAX_BYTES
        var truncated = false
        records.forEach { record ->
            coroutineContext.ensureActive()
            val ref = record.ref
            try {
                if (truncated) { notes += "${ref.path}：媒体总量超过 20 MiB，未发送。"; return@forEach }
                val resolved = resolver.resolve(sessionId, ref.path) as? MediaResolution.Available
                if (resolved == null) { notes += "${ref.path}：文件已删除或不可读，未发送媒体。"; return@forEach }
                val unsupported = if (record.mode == MediaViewMode.KEYFRAMES) {
                    if (target.supportsImages) null else "当前模型不支持图片输入，未发送关键帧"
                } else unsupportedMediaReason(resolved.ref, target)
                if (unsupported != null) { notes += "${ref.path}：$unsupported；${mediaMetadata(resolved.ref)}"; return@forEach }
                if (ref.sha256.isNullOrBlank()) { notes += "${ref.path}：缺少文件内容身份，请重新调用 view_media。"; return@forEach }
                val prepared = preprocessor.prepare(resolved, record.mode, record.frameTimestampsSeconds, ref.sha256)
                val accepted = prepared.parts.takeWhile { part ->
                    if (part.bytes.size > remaining) false else { remaining -= part.bytes.size; true }
                }
                truncated = accepted.size != prepared.parts.size
                if (accepted.isNotEmpty()) loaded += prepared.copy(parts = accepted)
                if (truncated) notes += "${ref.path}：媒体总量超过 20 MiB，已截断。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                notes += "${ref.path}：${invalid.message ?: "文件已修改或无法读取"}"
            } catch (_: Exception) {
                notes += "${ref.path}：文件已修改、删除或不可读，未发送媒体。"
            }
        }
        loaded to notes
    }
}

fun unsupportedMediaReason(ref: MediaRef, target: MediaViewTarget): String? = when (ref.kind) {
    MediaKind.FILE -> "该文件不是可查看的图片、音频或视频"
    MediaKind.IMAGE -> if (target.supportsImages) null else "当前模型或协议不支持图片输入"
    MediaKind.AUDIO -> when {
        !target.capabilities.audioInput -> "当前模型不支持音频输入"
        !target.supportsAudio(ref.mimeType) -> "当前协议不接受该音频格式（不执行转码）"
        ref.sizeBytes > VIEW_MEDIA_MAX_BYTES -> "音频超过单个 20 MiB 上限"
        else -> null
    }
    MediaKind.VIDEO -> if (!target.supportsVideo(ref.mimeType) && !target.supportsImages) "当前模型不支持视频输入" else null
}

internal fun mediaMetadata(ref: MediaRef): String = buildString {
    append("MIME=${ref.mimeType}，大小=${ref.sizeBytes} 字节")
    if (ref.width != null && ref.height != null) append("，尺寸=${ref.width}×${ref.height}")
    ref.durationMs?.let { append("，时长=${formatMediaSeconds(it / 1000.0)}s") }
}
internal fun formatMediaSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.6f", seconds).trimEnd('0').trimEnd('.')
