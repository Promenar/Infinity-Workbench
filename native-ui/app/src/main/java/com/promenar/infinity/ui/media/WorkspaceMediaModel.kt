package com.promenar.infinity.ui.media

import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaSource
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 交给 Coil 的工作区媒体模型：只携带会话与虚拟路径，字节一律经 [com.promenar.infinity.domain.media.WorkspaceMediaResolver] 读取。
 *
 * [identity] 为内容身份（sha256，或大小与修改时间），进入缓存键；文件被替换后身份变化，不会命中旧图。
 */
data class WorkspaceMediaModel(
    val sessionId: String,
    val path: String,
    val identity: String? = null,
) {
    /** 编码为 `infinity-workspace://<会话 id>/<URL 编码的虚拟路径>[?v=<身份>]`，各段整体编码，含 `/`、`#`、`?` 与空格。 */
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(encode(sessionId)).append('/').append(encode(path))
        identity?.let { append("?v=").append(encode(it)) }
    }

    /** Coil 内存缓存键：会话、路径与内容身份共同决定。 */
    fun cacheKey(): String = toUri()

    companion object {
        const val SCHEME = "infinity-workspace"
        private const val PREFIX = "$SCHEME://"

        fun isWorkspaceUri(value: String): Boolean = value.startsWith(PREFIX)

        /** 解析 [toUri] 的结果；格式不符时返回 null。 */
        fun parse(value: String): WorkspaceMediaModel? {
            if (!isWorkspaceUri(value)) return null
            val body = value.removePrefix(PREFIX)
            val slash = body.indexOf('/')
            if (slash <= 0) return null
            val query = body.indexOf('?', startIndex = slash)
            val encodedPath = if (query < 0) body.substring(slash + 1) else body.substring(slash + 1, query)
            val identity = if (query < 0) null else body.substring(query + 1)
                .split('&')
                .firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            return runCatching {
                WorkspaceMediaModel(
                    sessionId = decode(body.substring(0, slash)),
                    path = decode(encodedPath).takeIf(String::isNotEmpty) ?: return null,
                    identity = identity?.let(::decode),
                )
            }.getOrNull()
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}

/** 卡片使用的解析结果：解析器结论加内容身份，身份在 IO 线程计算。 */
data class ResolvedWorkspaceMedia(
    val resolution: MediaResolution,
    val identity: String?,
) {
    val available: MediaResolution.Available? get() = resolution as? MediaResolution.Available
    val ref: MediaRef? get() = available?.ref
}

/** 内容身份：优先 sha256；否则真实文件取大小与修改时间，其它来源只取大小。 */
fun mediaContentIdentity(ref: MediaRef, lastModifiedMs: Long?): String =
    ref.sha256?.takeIf(String::isNotBlank)?.let { "sha256:$it" }
        ?: buildString {
            append("size:").append(ref.sizeBytes)
            lastModifiedMs?.takeIf { it > 0 }?.let { append("-mtime:").append(it) }
        }

/** 在调用方线程读取元数据并计算身份；需在 IO 线程调用。 */
fun resolvedWithIdentity(resolution: MediaResolution): ResolvedWorkspaceMedia = when (resolution) {
    is MediaResolution.Unavailable -> ResolvedWorkspaceMedia(resolution, null)
    is MediaResolution.Available -> {
        val modified = (resolution.source as? MediaSource.LocalFile)?.file?.let { file ->
            runCatching { file.lastModified() }.getOrNull()
        }
        ResolvedWorkspaceMedia(resolution, mediaContentIdentity(resolution.ref, modified))
    }
}

/** 虚拟路径的文件名部分，用于卡片标题、保存与分享的显示名。 */
fun mediaDisplayName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

/** 虚拟路径的父目录；根下文件返回 `/`，无法确定时返回 null。 */
fun mediaParentPath(path: String): String? {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val absolute = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    val index = absolute.lastIndexOf('/')
    return if (index <= 0) "/" else absolute.substring(0, index)
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts")
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")

/** 按扩展名推断媒体类别；资源管理器条目缺少 MIME 时使用，类别以解析器结果为准。 */
fun mediaKindForFileName(name: String): com.promenar.infinity.domain.media.MediaKind {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        in imageExtensions -> com.promenar.infinity.domain.media.MediaKind.IMAGE
        in videoExtensions -> com.promenar.infinity.domain.media.MediaKind.VIDEO
        in audioExtensions -> com.promenar.infinity.domain.media.MediaKind.AUDIO
        else -> com.promenar.infinity.domain.media.MediaKind.FILE
    }
}

/** 定位用的目录路径：补齐前导 `/`，去掉末尾 `/`；空路径视为根。 */
fun normalizeLocatePath(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
}
