package com.promenar.infinity.ui.media

/** Markdown 图片目标 `![说明](目标)` 的加载方式。 */
sealed interface MarkdownImageTarget {
    /** https 远程图片或内嵌 data:image，保持原有图片加载。 */
    data class Remote(val url: String) : MarkdownImageTarget

    /** 旧版生图结果引用的 `filesDir/generated_images/` 绝对路径，保持原有加载。 */
    data class LegacyGenerated(val path: String) : MarkdownImageTarget

    /** 工作区虚拟路径或无 scheme 相对路径，经解析器按媒体类别渲染。 */
    data class Workspace(val path: String) : MarkdownImageTarget

    /** 其它 scheme（file:、content:、http: 等）与协议相对地址：不加载。 */
    data object Blocked : MarkdownImageTarget
}

/** Markdown 链接目标 `[名称](目标)` 的处理方式。 */
sealed interface MarkdownLinkTarget {
    /** 工作区虚拟路径或无 scheme 相对路径：渲染为文件卡片或打开媒体查看器。 */
    data class Workspace(val path: String) : MarkdownLinkTarget

    /** 锚点、mailto、https 等普通链接：行为不变。 */
    data object External : MarkdownLinkTarget
}

private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** 去掉 `<...>` 包裹与首尾空白，得到原样的目标文本。 */
internal fun normalizeMarkdownTarget(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length >= 2 && trimmed.first() == '<' && trimmed.last() == '>') {
        trimmed.substring(1, trimmed.lastIndex).trim()
    } else {
        trimmed
    }
}

/**
 * 判定图片目标。[legacyGeneratedRoots] 为旧生图目录的绝对路径（以 `/` 结尾或不结尾均可）。
 * 本机其它绝对路径一律按虚拟路径交给解析器，不直接读取真实文件系统。
 */
fun classifyMarkdownImageTarget(raw: String, legacyGeneratedRoots: List<String>): MarkdownImageTarget {
    val target = normalizeMarkdownTarget(raw)
    if (target.isEmpty()) return MarkdownImageTarget.Blocked
    val lower = target.lowercase()
    return when {
        lower.startsWith("https://") -> MarkdownImageTarget.Remote(target)
        // 内嵌图片不访问网络与文件系统，沿用原有显示。
        lower.startsWith("data:image/") -> MarkdownImageTarget.Remote(target)
        target.startsWith("//") -> MarkdownImageTarget.Blocked
        target.startsWith("/") -> if (isLegacyGeneratedPath(target, legacyGeneratedRoots)) {
            MarkdownImageTarget.LegacyGenerated(target)
        } else {
            MarkdownImageTarget.Workspace(target)
        }
        schemePattern.containsMatchIn(target) -> MarkdownImageTarget.Blocked
        target.startsWith("#") || target.startsWith("?") -> MarkdownImageTarget.Blocked
        else -> MarkdownImageTarget.Workspace(target)
    }
}

/** 判定链接目标：只有工作区虚拟路径与无 scheme 相对路径接管，其它保持普通链接行为。 */
fun classifyMarkdownLinkTarget(raw: String): MarkdownLinkTarget {
    val target = normalizeMarkdownTarget(raw)
    return when {
        target.isEmpty() -> MarkdownLinkTarget.External
        target.startsWith("#") || target.startsWith("?") -> MarkdownLinkTarget.External
        target.startsWith("//") -> MarkdownLinkTarget.External
        schemePattern.containsMatchIn(target) -> MarkdownLinkTarget.External
        else -> MarkdownLinkTarget.Workspace(target)
    }
}

private fun isLegacyGeneratedPath(path: String, roots: List<String>): Boolean {
    if (path.contains("/../") || path.endsWith("/..")) return false
    return roots.any { root ->
        val prefix = root.trimEnd('/') + "/"
        prefix.length > 1 && path.startsWith(prefix) && path.length > prefix.length
    }
}
