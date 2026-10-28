package com.promenar.nexara.ui.chat

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 折叠思考块预览的两种呈现：单行匀速滚动，或按行翻滚。 */
internal enum class ThinkingPreviewMode { SINGLE_LINE, LINE_ROLL }

/** 折叠思考预览的全部可调参数，集中在此，界面与测试都引用这里。 */
internal object ThinkingPreviewTuning {
    /** 平滑速率高于此值（字/秒）切到按行翻滚。 */
    const val ENTER_ROLL_RATE = 45f

    /** 平滑速率低于此值（字/秒）才切回单行；与进入阈值形成回差。 */
    const val EXIT_ROLL_RATE = 35f

    /** 到达速率的指数平滑时间常数。 */
    const val RATE_TIME_CONSTANT_MS = 800f

    /** 按行翻滚时每行至少停留的时间。 */
    const val MIN_LINE_DWELL_MS = 500L

    /** 已写满行之后的积压超过这么多行时，直接跳到最新一行。 */
    const val JUMP_BACKLOG_LINES = 1.6f

    /** 两种呈现之间的交叉淡化时长。 */
    const val MODE_CROSS_FADE_MS = 200

    /** 整行上移换行的动画时长。 */
    const val LINE_ROLL_MS = 180

    /** 放出速率不低于 积压 / 此秒数，积压越多放得越快。 */
    const val CATCH_UP_SECONDS = 0.5f
    const val MIN_REVEAL_RATE = 12f
    const val MAX_REVEAL_RATE = 2000f

    /** 积压超过此字符数（恢复、重连）时直接跳到末尾附近再放出。 */
    const val JUMP_BACKLOG_CHARS = 600
    const val JUMP_KEEP_CHARS = 80

    /** 新放出的字符在放出后多少个字符的位置内从半透明过渡到不透明。 */
    const val FADE_IN_CHARS = 3f
    const val FADE_FLOOR_ALPHA = 0.3f

    /** 单行模式每帧只排版末尾这么多字符，足够铺满任何预览宽度。 */
    const val SINGLE_LINE_WINDOW_CHARS = 64

    /** 行排版只覆盖末尾这么多字符，更早的内容不参与翻滚。 */
    const val LAYOUT_TAIL_CHARS = 3000
    const val MAX_LINE_CHARS = 300

    /** 标题微光速度倍率：速率为 0 时的下限与速率达到 [SHIMMER_FULL_RATE] 时的上限。 */
    const val SHIMMER_MIN_SPEED = 0.6f
    const val SHIMMER_MAX_SPEED = 2.4f
    const val SHIMMER_FULL_RATE = 100f
}

/** “思考中 N 秒”标题微光的速度倍率，随平滑速率线性变快并封顶。 */
internal fun thinkingShimmerSpeed(rate: Float): Float {
    val t = (rate / ThinkingPreviewTuning.SHIMMER_FULL_RATE).coerceIn(0f, 1f)
    return ThinkingPreviewTuning.SHIMMER_MIN_SPEED +
        (ThinkingPreviewTuning.SHIMMER_MAX_SPEED - ThinkingPreviewTuning.SHIMMER_MIN_SPEED) * t
}

/** 第 [index] 个字符在已放出位置 [shown] 时的不透明度：刚放出的字符半透明，随位置推进渐显。 */
internal fun thinkingFadeAlpha(index: Int, shown: Float): Float {
    val progress = ((shown - index) / ThinkingPreviewTuning.FADE_IN_CHARS).coerceIn(0f, 1f)
    return ThinkingPreviewTuning.FADE_FLOOR_ALPHA + (1f - ThinkingPreviewTuning.FADE_FLOOR_ALPHA) * progress
}

/**
 * 折叠思考预览的状态机：纯 Kotlin，时间与字宽测量都由调用方注入。
 *
 * 流程：思考文本折叠空白后只追加；每帧估计平滑到达速率，按回差在“单行匀速”与“按行翻滚”之间切换；
 * 文字以不低于积压/0.5 秒的速度放出（积压越多越快，停止输入后平稳停住）；翻滚模式按测量宽度断行，
 * 每行写满后至少停留 0.5 秒才换行，积压超过约 1.6 行时直接跳到最新一行。
 */
internal class ThinkingPreviewEngine(private val widthOf: (String) -> Float) {
    private val text = StringBuilder()
    private var rawConsumed = 0
    private var lastWasSpace = true
    private var viewportWidth = 0f
    private val lineStarts = ArrayList<Int>()
    private var initialized = false
    private var lastNowMs = 0L
    private var lastTextLength = 0
    private var dwellStartMs = 0L

    /** 已放出位置（字符数，浮点；整数部分为已完整放出的字符）。 */
    var shown = 0f
        private set

    /** 平滑到达速率，字符/秒。 */
    var smoothedRate = 0f
        private set
    var mode = ThinkingPreviewMode.SINGLE_LINE
        private set

    /** 翻滚模式下当前行在行表中的序号；单行模式下无意义。 */
    var displayLine = 0
        private set

    /** 每次换行或跳行加一，界面据此触发换行动画。 */
    var rollSeq = 0
        private set

    /** 最近一次被换出的那一行的文字，用于上移淡出动画。 */
    var rolledOutLine = ""
        private set

    val textLength: Int get() = text.length
    val lineCount: Int get() = lineStarts.size

    /** 已放出的整数位置，不落在代理对中间。 */
    val shownIndex: Int
        get() {
            val index = shown.toInt().coerceIn(0, text.length)
            return if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index - 1 else index
        }

    /** 仍需逐帧推进：还有未放出的字、翻滚模式尚未退出，或等待停留结束换行。 */
    val isBusy: Boolean get() = shown < text.length - 0.001f || mode == ThinkingPreviewMode.LINE_ROLL

    fun width(value: String): Float = widthOf(value)

    fun substring(start: Int, end: Int): String = text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length))

    fun setViewport(width: Float) {
        if (kotlin.math.abs(width - viewportWidth) < 0.5f) return
        viewportWidth = width
        lineStarts.clear()
        if (mode == ThinkingPreviewMode.LINE_ROLL) {
            layoutTail()
            displayLine = lineOf(shownIndex)
        }
    }

    /** 当前行起止（字符位置）；仅翻滚模式有意义。 */
    fun lineRange(index: Int): IntRange {
        if (index !in lineStarts.indices) return IntRange.EMPTY
        return lineStarts[index] until lineEnd(index)
    }

    fun lineText(index: Int): String = lineRange(index).let { if (it.isEmpty()) "" else text.substring(it.first, it.last + 1) }

    /** 当前行里已放出的部分。 */
    fun visibleLine(): Pair<Int, String> {
        val range = lineRange(displayLine)
        if (range.isEmpty()) return 0 to ""
        val end = min(shownIndex, range.last + 1)
        return range.first to text.substring(range.first, max(range.first, end))
    }

    /** 单行模式的排版窗口：返回窗口起点与终点（含正在放出的那一个字符）。 */
    fun singleLineWindow(): IntRange {
        val index = shownIndex
        val end = min(text.length, index + 1)
        val start = max(0, index - ThinkingPreviewTuning.SINGLE_LINE_WINDOW_CHARS)
        return start until end
    }

    /** 用最新的累计思考文本推进一帧；[nowMs] 为单调时钟毫秒。 */
    fun update(reasoning: String, nowMs: Long) {
        if (reasoning.length < rawConsumed) reset()
        appendNormalized(reasoning)
        if (!initialized) {
            // 首次挂载直接显示当前全文，之后到达的增量才参与放出与渐显。
            initialized = true
            shown = text.length.toFloat()
            lastTextLength = text.length
            lastNowMs = nowMs
            return
        }
        val dtMs = (nowMs - lastNowMs).coerceAtLeast(0)
        lastNowMs = nowMs
        val delta = text.length - lastTextLength
        lastTextLength = text.length
        if (dtMs > 0) {
            val seconds = dtMs / 1000f
            val alpha = 1f - exp(-dtMs / ThinkingPreviewTuning.RATE_TIME_CONSTANT_MS)
            smoothedRate += (delta / seconds - smoothedRate) * alpha
        }
        updateMode(nowMs)
        reveal(dtMs / 1000f)
        if (mode == ThinkingPreviewMode.LINE_ROLL) {
            layoutTail()
            rollLines(nowMs)
        }
    }

    private fun reset() {
        text.clear()
        rawConsumed = 0
        lastWasSpace = true
        lineStarts.clear()
        initialized = false
        shown = 0f
        smoothedRate = 0f
        mode = ThinkingPreviewMode.SINGLE_LINE
        displayLine = 0
    }

    /** 空白折叠为单个空格并只追加，已处理的前缀不重算。 */
    private fun appendNormalized(reasoning: String) {
        for (i in rawConsumed until reasoning.length) {
            val c = reasoning[i]
            if (c.isWhitespace()) {
                if (!lastWasSpace) text.append(' ')
                lastWasSpace = true
            } else {
                text.append(c)
                lastWasSpace = false
            }
        }
        rawConsumed = reasoning.length
    }

    private fun updateMode(nowMs: Long) {
        when (mode) {
            ThinkingPreviewMode.SINGLE_LINE ->
                if (smoothedRate > ThinkingPreviewTuning.ENTER_ROLL_RATE && viewportWidth > 0f) {
                    mode = ThinkingPreviewMode.LINE_ROLL
                    layoutTail()
                    displayLine = lineOf(shownIndex)
                    dwellStartMs = nowMs
                }
            ThinkingPreviewMode.LINE_ROLL ->
                if (smoothedRate < ThinkingPreviewTuning.EXIT_ROLL_RATE) mode = ThinkingPreviewMode.SINGLE_LINE
        }
    }

    private fun reveal(seconds: Float) {
        val backlog = text.length - shown
        if (backlog <= 0f) return
        if (backlog > ThinkingPreviewTuning.JUMP_BACKLOG_CHARS) {
            shown = (text.length - ThinkingPreviewTuning.JUMP_KEEP_CHARS).toFloat()
            return
        }
        val speed = max(smoothedRate, backlog / ThinkingPreviewTuning.CATCH_UP_SECONDS)
            .coerceIn(ThinkingPreviewTuning.MIN_REVEAL_RATE, ThinkingPreviewTuning.MAX_REVEAL_RATE)
        // 剩余不足半个字符时直接收尾，避免无限逼近。
        shown = if (backlog < 0.5f) text.length.toFloat() else min(text.length.toFloat(), shown + speed * seconds)
    }

    private fun rollLines(nowMs: Long) {
        if (lineStarts.isEmpty()) return
        displayLine = displayLine.coerceIn(0, lineStarts.lastIndex)
        val latest = lineStarts.lastIndex
        if (displayLine >= latest) return
        val lineLength = max(1, lineEnd(displayLine) - lineStarts[displayLine])
        val pendingLines = (text.length - lineEnd(displayLine)).toFloat() / lineLength
        if (pendingLines > ThinkingPreviewTuning.JUMP_BACKLOG_LINES) {
            // 不追读旧内容：从最新一行的行首重新放出。
            rolledOutLine = visibleLine().second
            displayLine = latest
            shown = max(shown, lineStarts[latest].toFloat())
            dwellStartMs = nowMs
            rollSeq++
        } else if (shownIndex >= lineEnd(displayLine) && nowMs - dwellStartMs >= ThinkingPreviewTuning.MIN_LINE_DWELL_MS) {
            rolledOutLine = lineText(displayLine)
            displayLine++
            dwellStartMs = nowMs
            rollSeq++
        }
    }

    private fun lineEnd(index: Int): Int = if (index + 1 < lineStarts.size) lineStarts[index + 1] else text.length

    private fun lineOf(position: Int): Int {
        if (lineStarts.isEmpty()) return 0
        var low = 0
        var high = lineStarts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= position) low = mid else high = mid - 1
        }
        return low
    }

    /** 按实际字形测量增量断行：只重排最后一行，已断开的行不再变化。 */
    private fun layoutTail() {
        if (viewportWidth <= 0f) return
        if (lineStarts.isEmpty()) lineStarts.add(max(0, text.length - ThinkingPreviewTuning.LAYOUT_TAIL_CHARS))
        var start = lineStarts.last()
        while (start < text.length) {
            val remaining = text.length - start
            val candidate = min(remaining, ThinkingPreviewTuning.MAX_LINE_CHARS)
            if (candidate == remaining && widthOf(text.substring(start, start + candidate)) <= viewportWidth) break
            var low = 1
            var high = candidate
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                if (widthOf(text.substring(start, start + mid)) <= viewportWidth) low = mid else high = mid - 1
            }
            val end = wrapPoint(start, low)
            if (end >= text.length) break
            lineStarts.add(end)
            start = end
        }
    }

    /** 从 [start] 起能放下 [fit] 个字符时的断点：拉丁词不拆开（整行都没有空格的超长词才从中间断），断点处的空格留在上一行。 */
    private fun wrapPoint(start: Int, fit: Int): Int {
        var end = start + fit
        if (end >= text.length) return end
        if (isLatinWordChar(text[end - 1]) && isLatinWordChar(text[end])) {
            var i = end - 1
            while (i > start) {
                if (text[i] == ' ') return i + 1
                i--
            }
        }
        if (text[end] == ' ') end++
        return end
    }

    private fun isLatinWordChar(c: Char) = c.isLetterOrDigit() && c.code < 0x2E80
}
