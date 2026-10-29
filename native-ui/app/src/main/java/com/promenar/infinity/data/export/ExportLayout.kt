package com.promenar.infinity.data.export

internal data class ExportSlice(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

internal fun splitExportHeight(height: Int, boundaries: List<Int>, maxHeight: Int = 20_000): List<ExportSlice> {
    require(height >= 0 && maxHeight > 0)
    if (height == 0) return listOf(ExportSlice(0, 1))
    val points = boundaries.filter { it > 0 && it < height }.distinct().sorted()
    return buildList {
        var top = 0
        while (top < height) {
            val limit = (top.toLong() + maxHeight).coerceAtMost(height.toLong()).toInt()
            val end = if (limit == height) height else points.lastOrNull { it > top && it <= limit } ?: limit
            add(ExportSlice(top, end))
            top = end
        }
    }
}

internal fun exportImageNames(base: String, count: Int): List<String> {
    require(count > 0)
    return if (count == 1) listOf("$base.png") else (1..count).map { "${base}_${it.toString().padStart(2, '0')}.png" }
}

internal fun safeExportEntryNames(names: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return names.map { raw ->
        val cleaned = raw.replace('\\', '/').substringAfterLast('/').replace("..", "_")
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.').ifBlank { "attachment" }
        val safe = exportUtf8Prefix(cleaned, 180)
        val stem = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var candidate = safe
        var sequence = 2
        while (!used.add(candidate.lowercase(java.util.Locale.ROOT))) candidate = "${stem}_${sequence++}$ext"
        candidate
    }
}

/** 消息以外的代码块、表格行和图片尽量整体放到下一页；超出一页的单块只能硬切。 */
internal fun splitExportPdfHeight(height: Int, blocks: List<ExportSlice>, pageHeight: Int): List<ExportSlice> {
    require(pageHeight > 0 && height > 0)
    return buildList {
        var top = 0
        while (top < height) {
            val limit = (top.toLong() + pageHeight).coerceAtMost(height.toLong()).toInt()
            val avoid = blocks.filter { it.top > top && it.top < limit && it.bottom > limit && it.height <= pageHeight }
                .minOfOrNull { it.top }
            val end = avoid ?: limit
            add(ExportSlice(top, end))
            top = end
        }
    }
}
