package com.promenar.infinity.ui.common

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.floor

internal data class StreamRevealRange(val start: Int, val end: Int, val revealedAtNanos: Long)

internal data class StreamRevealFrame(
    val text: String,
    val nowNanos: Long,
    val recentRanges: List<StreamRevealRange> = emptyList(),
    val sourceText: String = text,
) {
    val hasFadingText: Boolean get() = recentRanges.isNotEmpty()
}

/** 新放出的字从出现到完全融入正文的时长。 */
internal const val StreamRevealFadeNanos = 280_000_000L
private const val StreamRevealStartAlpha = 0.2f

private fun streamRevealProgress(ageNanos: Long): Float = (ageNanos.toFloat() / StreamRevealFadeNanos).coerceIn(0f, 1f)

/** 新字的不透明度：从 0.2 缓出到 1，前段上升快，高速流式时末尾不会发虚。 */
internal fun streamRevealAlpha(ageNanos: Long): Float {
    val remaining = 1f - streamRevealProgress(ageNanos)
    return StreamRevealStartAlpha + (1f - StreamRevealStartAlpha) * (1f - remaining * remaining * remaining)
}

/** 新字混入强调色的比例：刚出现时为 1，随后平滑退回正文颜色。 */
internal fun streamRevealTint(ageNanos: Long): Float {
    val x = streamRevealProgress(ageNanos)
    return 1f - x * x * (3f - 2f * x)
}

/** 时间只由调用方注入；长度以 UTF-16 累加，真正显示的位置对齐到完整字形。 */
internal class StreamRevealer(initialText: String? = null) {
    private var target = initialText.orEmpty()
    private var shown = target.length.toDouble()
    private var mounted = initialText != null
    private var streaming = initialText != null
    private var initialized = false
    private var lastFrameNanos = 0L
    private var lastGrowthNanos = 0L
    private var hasRateSample = false
    private var finishAtNanos: Long? = null
    private var boundaries = intArrayOf(0, target.length)
    private val recentRanges = ArrayDeque<StreamRevealRange>()

    var visibleText: String = target
        private set
    var estimatedRate: Double = 0.0
        private set
    val shownLength: Int get() = visibleText.length
    val hasPendingText: Boolean get() = shownLength < target.length

    fun updateTarget(
        text: String,
        isStreaming: Boolean,
        nowNanos: Long,
        animationsEnabled: Boolean = true,
    ): StreamRevealFrame {
        val now = if (initialized) maxOf(nowNanos, lastFrameNanos) else nowNanos
        val wasStreaming = streaming
        if (initialized) {
            advanceFrame(now)
        } else {
            initialized = true
            lastFrameNanos = now
            lastGrowthNanos = now
        }
        val previousTarget = target
        if (text != target) {
            target = text
            boundaries = graphemeBoundaries(text)
            if (!text.startsWith(visibleText)) {
                val common = visibleText.commonPrefixWith(text).length
                val previousBoundary = graphemeBoundaries(visibleText).floorBoundary(common)
                shown = boundaries.floorBoundary(previousBoundary).toDouble()
                visibleText = text.take(shown.toInt())
                recentRanges.clear()
            }
            if (text.length > previousTarget.length && text.startsWith(previousTarget)) {
                val elapsed = now - lastGrowthNanos
                if (elapsed > 0L) {
                    val sample = (text.length - previousTarget.length) * 1_000_000_000.0 / elapsed
                    estimatedRate = if (hasRateSample) estimatedRate * 0.7 + sample * 0.3 else sample
                    hasRateSample = true
                }
                lastGrowthNanos = now
            } else if (!text.startsWith(previousTarget)) {
                estimatedRate = 0.0
                hasRateSample = false
                lastGrowthNanos = now
            }
            shown = shown.coerceAtMost(text.length.toDouble())
        }
        streaming = isStreaming
        if (!mounted || !animationsEnabled || (!isStreaming && !wasStreaming && finishAtNanos == null)) {
            showAll()
        } else {
            if (isStreaming) finishAtNanos = null
            if (wasStreaming && !isStreaming) finishAtNanos = now + 300_000_000L
            if (target.length - shown > 4000) {
                shown = boundaries.floorBoundary(target.length - 200).toDouble()
                visibleText = target.take(shown.toInt())
                recentRanges.clear()
            }
        }
        mounted = true
        return snapshot(now)
    }

    fun advanceFrame(nowNanos: Long): StreamRevealFrame {
        if (!initialized) return snapshot(nowNanos)
        val now = maxOf(nowNanos, lastFrameNanos)
        val previousFrameNanos = lastFrameNanos
        val elapsedSeconds = (now - previousFrameNanos) / 1_000_000_000.0
        lastFrameNanos = now
        if (hasPendingText && elapsedSeconds > 0.0) {
            val backlog = target.length - shown
            var speed = maxOf(estimatedRate, backlog / 0.5).coerceIn(30.0, 2000.0)
            val deadline = finishAtNanos
            if (deadline != null) {
                val remainingSeconds = (deadline - previousFrameNanos) / 1_000_000_000.0
                if (now >= deadline) {
                    shown = target.length.toDouble()
                } else {
                    speed = maxOf(speed, backlog / remainingSeconds.coerceAtLeast(0.000_001), 30.0)
                }
            }
            val previousLength = shownLength
            shown = (shown + speed * elapsedSeconds).coerceAtMost(target.length.toDouble())
            val nextLength = boundaries.floorBoundary(floor(shown + 0.000_001).toInt())
            visibleText = target.take(nextLength)
            if (nextLength > previousLength) recentRanges.addLast(StreamRevealRange(previousLength, nextLength, now))
            if (!hasPendingText) finishAtNanos = null
        }
        return snapshot(now)
    }

    private fun showAll() {
        shown = target.length.toDouble()
        visibleText = target
        finishAtNanos = null
        recentRanges.clear()
    }

    private fun snapshot(nowNanos: Long): StreamRevealFrame {
        while (recentRanges.firstOrNull()?.let { nowNanos - it.revealedAtNanos >= StreamRevealFadeNanos } == true) {
            recentRanges.removeFirst()
        }
        return StreamRevealFrame(visibleText, nowNanos, recentRanges.toList(), sourceText = target)
    }
}

private fun IntArray.floorBoundary(offset: Int): Int {
    val index = binarySearch(offset)
    return if (index >= 0) this[index] else this[(-index - 2).coerceAtLeast(0)]
}

private fun graphemeBoundaries(text: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val result = mutableListOf(0)
    var boundary = iterator.next()
    while (boundary != BreakIterator.DONE) {
        if (boundary == text.length || isSafeGraphemeBoundary(text, boundary)) result += boundary
        boundary = iterator.next()
    }
    return result.toIntArray()
}

/** 补齐不同 Android/JVM Unicode 版本对 emoji 连字、肤色、标签和区域码的差异。 */
private fun isSafeGraphemeBoundary(text: String, boundary: Int): Boolean {
    if (boundary <= 0 || boundary >= text.length) return true
    val before = text.codePointBefore(boundary)
    val after = text.codePointAt(boundary)
    val type = Character.getType(after)
    if (
        before == 0x200D || after == 0x200D ||
        type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        after in 0x1F3FB..0x1F3FF || after in 0xE0020..0xE007F ||
        after in 0xFE00..0xFE0F || after in 0xE0100..0xE01EF ||
        before == 0x0D && after == 0x0A
    ) return false
    if (before in 0x1F1E6..0x1F1FF && after in 0x1F1E6..0x1F1FF) {
        var offset = boundary
        var regionalCount = 0
        while (offset > 0 && text.codePointBefore(offset) in 0x1F1E6..0x1F1FF) {
            offset -= Character.charCount(text.codePointBefore(offset))
            regionalCount++
        }
        if (regionalCount % 2 == 1) return false
    }
    return !Character.isLowSurrogate(text[boundary])
}
