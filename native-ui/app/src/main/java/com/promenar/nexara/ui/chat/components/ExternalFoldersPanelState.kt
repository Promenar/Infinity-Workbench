package com.promenar.nexara.ui.chat.components

import com.promenar.nexara.domain.storage.ExternalMount
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class ExternalMountSource {
    Saf,
    RealPath,
}

internal fun availableExternalMountSources(
    buildAllFilesAccess: Boolean,
    distributionAllFilesAccess: Boolean,
    linuxSession: Boolean = false,
): List<ExternalMountSource> = buildList {
    if (!linuxSession) add(ExternalMountSource.Saf)
    if (buildAllFilesAccess && distributionAllFilesAccess) add(ExternalMountSource.RealPath)
}

/** 空会话不展示挂载，防止页面切换时展示其它会话的路径。 */
internal fun visibleExternalMounts(mounts: List<ExternalMount>, sessionId: String?): List<ExternalMount> =
    mounts.filter { !sessionId.isNullOrBlank() && it.sessionId == sessionId }

/** 浏览器回退只能留在当前挂载内，不能把挂载别名的相似前缀当作子目录。 */
internal fun parentExternalFolderPath(rootPath: String, path: String): String? {
    if (!path.startsWith("$rootPath/") || '\\' in path) return null
    val segments = path.removePrefix("$rootPath/").split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return path.substringBeforeLast('/').takeIf { it == rootPath || it.startsWith("$rootPath/") }
}

internal sealed interface ExternalTextPreview {
    data object Loading : ExternalTextPreview
    data object Failed : ExternalTextPreview
    data object Binary : ExternalTextPreview
    data object TooLarge : ExternalTextPreview
    data class Text(val content: String) : ExternalTextPreview
}

/** 不截断字节流或替换坏字符，避免把损坏内容显示成可信的完整文本。 */
internal fun decodeExternalTextPreview(bytes: ByteArray, maxBytes: Int): ExternalTextPreview {
    if (bytes.size > maxBytes) return ExternalTextPreview.TooLarge
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return ExternalTextPreview.Binary
    }
    if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        return ExternalTextPreview.Binary
    }
    return ExternalTextPreview.Text(text)
}

internal fun externalPreviewFileName(name: String): String = name
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .map { if (it.isISOControl()) '_' else it }
    .joinToString("")
    .take(180)
    .takeUnless { it.isBlank() || it == "." || it == ".." }
    ?: "preview"
