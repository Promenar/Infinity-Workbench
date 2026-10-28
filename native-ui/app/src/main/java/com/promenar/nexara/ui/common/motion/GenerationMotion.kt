package com.promenar.nexara.ui.common.motion

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRetry
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * 生成过程的动效阶段，浮岛光效与生成状态指示共用；每种样式在各阶段保持同一视觉家族，只换节奏与颜色。
 * 灯语借用交换机面板：首字前像链路协商（慢扫或呼吸，不闪），流式输出像 ACT 收发（随机闪烁，跟随实际出字速率），
 * 工具执行像忙碌寻道（第三色按固定节拍推进），等待审批像告警慢闪（第三色每秒慢闪），重试等待像降速待机（压暗等间隔闪）。
 */
enum class GenerationMotionPhase {
    /** 已发出请求、等模型开口；思考过程也算在这里。 */
    WAIT,
    /** 正文逐字到达。 */
    STREAM,
    /** 在等工具结果。 */
    TOOL,
    /** 暂停，等用户批准或回答。 */
    APPROVAL,
    /** 自动重试前的退避倒计时。 */
    RETRY,
}

/**
 * 把生成任务的阶段映射为动效阶段；[awaitingUser] 为有待批准的工具调用或待回答的提问。
 * 没有进行中的生成且无需用户处理时返回 null。重试倒计时由 [GenerationMotion] 按时间叠加，不在这里判断。
 */
internal fun generationMotionPhase(phase: GenerationPhase?, awaitingUser: Boolean): GenerationMotionPhase? = when {
    awaitingUser -> GenerationMotionPhase.APPROVAL
    phase == null -> null
    else -> when (phase) {
        GenerationPhase.QUEUED,
        GenerationPhase.PREPARING,
        GenerationPhase.BUILDING_CONTEXT,
        GenerationPhase.CONNECTING,
        GenerationPhase.THINKING,
        GenerationPhase.POST_PROCESSING -> GenerationMotionPhase.WAIT
        GenerationPhase.STREAMING -> GenerationMotionPhase.STREAM
        GenerationPhase.EXECUTING_TOOLS -> GenerationMotionPhase.TOOL
        GenerationPhase.WAITING_APPROVAL,
        GenerationPhase.WAITING_INPUT -> GenerationMotionPhase.APPROVAL
        GenerationPhase.COMPLETED,
        GenerationPhase.FAILED,
        GenerationPhase.CANCELLED,
        GenerationPhase.PERSISTENCE_FAILED -> null
    }
}

/** 审批：1 秒一次的慢闪，亮灭各半，边缘柔和。 */
internal fun slowBlink(t: Float): Float {
    val f = fract(t)
    return smoothStep(0f, 0.08f, f) * (1 - smoothStep(0.5f, 0.6f, f))
}

/** 重试：0.8 秒一次的等间隔短闪。 */
internal fun evenBlink(t: Float): Float = if (fract(t / 0.8f) < 0.3f) 1f else 0f

/** 周期为 [period] 秒的呼吸，0..1。 */
internal fun breath(t: Float, period: Float): Float = 0.5f - 0.5f * cos(Tau * t / period)

/** 0→1→0 的往返。 */
internal fun pingPong(u: Float): Float {
    val f = fract(u)
    return if (f < 0.5f) f * 2 else 2 - f * 2
}

/** 1.1 秒一次的心跳双拍。 */
internal fun heartbeat(t: Float): Float {
    val f = fract(t / 1.1f)
    return exp(-((f - 0.05f) / 0.045f).pow(2)) + 0.7f * exp(-((f - 0.26f) / 0.045f).pow(2))
}

/** 流式的 ACT 灯：[act] 为 0 时熄灭，越高闪得越密越快。 */
internal fun actOn(id: Int, time: Float, act: Float, seed: Float = 0f): Boolean {
    if (act < 0.02f) return false
    val burst = act * (0.55f + 0.45f * valueNoise(time * 2.1f + seed))
    val hz = 16 + 34 * act
    return motionHash(id * 3.3f, floor(time * hz + id * 5.1f)) < 0.08f + 0.8f * burst
}

/** 出字速率达到这个值（字/秒）时流式动效最满。 */
internal const val GenerationMotionFullRate = 60f

/**
 * 出字速率：按上报的累计字数估算，时间常数 0.5 秒的指数平滑；每 0.09 秒记一次历史，供点阵波形屏画柱状图。
 * 累计字数变小（新一轮回复）时只更新基准，不计入速率。
 */
internal class OutputRateMeter {
    private var lastLength = -1
    private var pending = 0
    private var historyClock = 0f
    private val history = FloatArray(HistorySize)
    private var historyHead = 0
    private var historyCount = 0

    /** 当前速率，字/秒。 */
    var rate = 0f
        private set

    val act: Float get() = clamp01(rate / GenerationMotionFullRate)

    fun report(length: Int) {
        if (lastLength >= 0 && length > lastLength) pending += length - lastLength
        lastLength = length
    }

    fun step(dt: Float) {
        if (dt <= 0f) return
        val instant = pending / dt
        pending = 0
        rate += (instant - rate) * (1 - exp(-dt / SmoothingSeconds))
        historyClock += dt
        while (historyClock >= HistoryStepSeconds) {
            historyClock -= HistoryStepSeconds
            history[historyHead] = rate
            historyHead = (historyHead + 1) % HistorySize
            if (historyCount < HistorySize) historyCount++
        }
    }

    /** [back] 个记录之前的速率，0 为最新一条；没有记录时为 0。 */
    fun historyAt(back: Int): Float {
        if (back < 0 || back >= historyCount) return 0f
        return history[(historyHead - 1 - back).mod(HistorySize)]
    }

    fun reset() {
        lastLength = -1
        pending = 0
        rate = 0f
        historyClock = 0f
        historyHead = 0
        historyCount = 0
    }

    companion object {
        const val SmoothingSeconds = 0.5f
        const val HistoryStepSeconds = 0.09f
        const val HistorySize = 80
    }
}

/**
 * 阶段控制：真实阶段变化后，显示阶段至少停留 [DwellSeconds] 才切换，避免连续短工具调用时来回闪；
 * 切换时新旧阶段在 [FadeSeconds] 内交叉淡入淡出。真实阶段为 null（生成结束）时保留最后显示的阶段，
 * 由外层的整体淡出收尾；下一次开始时直接切到新阶段，不淡入。
 */
internal class MotionPhaseController {
    private val weights = FloatArray(GenerationMotionPhase.entries.size)
    private var clock = 0f
    private var since = 0f

    var shown: GenerationMotionPhase? = null
        private set

    fun weight(phase: GenerationMotionPhase): Float = weights[phase.ordinal]

    fun snap(phase: GenerationMotionPhase) {
        shown = phase
        since = clock
        weights.fill(0f)
        weights[phase.ordinal] = 1f
    }

    fun step(dt: Float, actual: GenerationMotionPhase?) {
        clock += dt
        if (actual == null) return
        val current = shown
        if (current == null) {
            snap(actual)
            return
        }
        if (actual != current && clock - since >= DwellSeconds) {
            shown = actual
            since = clock
        }
        for (p in GenerationMotionPhase.entries) {
            val target = if (p == shown) 1f else 0f
            val w = weights[p.ordinal]
            weights[p.ordinal] = when {
                target > w -> minOf(1f, w + dt / FadeSeconds)
                target < w -> maxOf(0f, w - dt / FadeSeconds)
                else -> w
            }
        }
    }

    companion object {
        const val DwellSeconds = 0.4f
        const val FadeSeconds = 0.25f
    }
}

/**
 * 一个会话界面共用的生成动效状态：显示阶段与各阶段权重、出字速率与重试倒计时。
 * 字段只在绘制阶段读取，推进时不触发重组。三种来源：
 * - 实时：由 [rememberGenerationMotion] 按生成任务推进；
 * - 演示：设置页预览按固定脚本轮播五个阶段；
 * - 环境：没有提供方时（首页会话列表、静态预览）固定为流式，速率取噪声。
 */
@Stable
class GenerationMotion internal constructor(private val source: Source) {
    internal enum class Source { LIVE, DEMO, AMBIENT }

    private val controller = MotionPhaseController()
    private val meter = OutputRateMeter()
    private var clock = 0f
    private var rawPhase: GenerationMotionPhase? = null
    private var preStreamLength = 0
    private var streamBaseLength = 0
    private var basePhase: GenerationMotionPhase? = null
    private var retry: GenerationRetry? = null
    private var retryStartedAt = 0f
    private var demoRetryLeft = 0f

    /** 真实阶段（已叠加重试倒计时）；没有进行中的生成时为 null。值相同时写入不触发重组，组合中可以读取。 */
    var actual: GenerationMotionPhase? by mutableStateOf(if (source == Source.AMBIENT) GenerationMotionPhase.STREAM else null)
        private set

    init {
        // 演示从脚本里第一段流式开始，系统关闭动画时停在流式。
        if (source == Source.DEMO) clock = DemoStreamStart
        if (source != Source.LIVE) controller.snap(GenerationMotionPhase.STREAM)
    }

    /** [phase] 在当前画面中的权重，0..1；各阶段之和为 1。还没有显示过任何阶段时按流式显示。 */
    fun weight(phase: GenerationMotionPhase): Float = when {
        source == Source.AMBIENT || controller.shown == null -> if (phase == GenerationMotionPhase.STREAM) 1f else 0f
        else -> controller.weight(phase)
    }

    /** 当前显示的阶段；尚未开始过生成时为 null。 */
    val shown: GenerationMotionPhase? get() = controller.shown

    /** 与 [shown] 相同，但只在切换时写入快照状态，供组合中显示阶段名。 */
    var displayed: GenerationMotionPhase? by mutableStateOf(controller.shown)
        private set

    /** 流式动效强度 0..1：实际出字速率除以 [GenerationMotionFullRate]。环境与演示来源按 [time] 取噪声。 */
    fun act(time: Float): Float = when (source) {
        Source.LIVE -> meter.act
        Source.DEMO -> meter.act
        Source.AMBIENT -> clamp01(0.3f + 0.45f * valueNoise(time * 0.5f + 3f))
    }

    /**
     * 点阵波形屏第 [back] 列（0 为最新）的柱高 0..1；时间槽 [slot] 只用于环境来源合成柱高。
     */
    fun historyLevel(back: Int, slot: Float): Float = when (source) {
        Source.AMBIENT -> clamp01(0.2f + 0.65f * valueNoise(slot * 0.19f) + (motionHash(slot, 1.3f) - 0.5f) * 0.35f)
        else -> clamp01(meter.historyAt(back) / (GenerationMotionFullRate * 1.15f))
    }

    /** 重试倒计时剩余比例 1→0。 */
    val retryLeft: Float
        get() = when {
            source == Source.DEMO -> demoRetryLeft
            retry == null -> 0f
            else -> clamp01(1 - (clock - retryStartedAt) / retryDelaySeconds())
        }

    /** 重试倒计时剩余整秒数。 */
    val retrySeconds: Int
        get() = when (source) {
            Source.DEMO -> ceil(DemoRetrySeconds * demoRetryLeft).toInt()
            else -> retry?.let { ceil(retryDelaySeconds() - (clock - retryStartedAt)).toInt().coerceAtLeast(0) } ?: 0
        }

    private fun retryDelaySeconds(): Float = (retry?.delayMs ?: 0L).coerceAtLeast(1L) / 1000f

    internal fun update(phase: GenerationMotionPhase?, retry: GenerationRetry?, outputLength: Int, animate: Boolean) {
        if (retry != this.retry) {
            this.retry = retry
            retryStartedAt = clock
        }
        if (phase == null) meter.reset() else meter.report(outputLength)
        // 生成任务收到首个分片就进入流式，其中可能只有思考内容；正文比进入流式前有新增后才按流式显示。
        if (phase == GenerationMotionPhase.STREAM) {
            if (rawPhase != GenerationMotionPhase.STREAM) streamBaseLength = preStreamLength
            if (outputLength < streamBaseLength) streamBaseLength = outputLength
        } else {
            preStreamLength = outputLength
        }
        rawPhase = phase
        basePhase = if (phase == GenerationMotionPhase.STREAM && outputLength <= streamBaseLength) GenerationMotionPhase.WAIT else phase
        actual = resolveActual()
        // 不推进时（系统关闭动画）直接显示真实阶段。
        if (!animate) actual?.let(controller::snap)
        displayed = controller.shown
    }

    private fun resolveActual(): GenerationMotionPhase? {
        val base = basePhase ?: return null
        if (base == GenerationMotionPhase.APPROVAL) return base
        val pending = retry ?: return base
        return if (clock - retryStartedAt < pending.delayMs / 1000f) GenerationMotionPhase.RETRY else base
    }

    internal fun step(dt: Float) {
        clock += dt
        if (source == Source.DEMO) {
            stepDemo(dt)
        } else {
            actual = resolveActual()
        }
        meter.step(dt)
        controller.step(dt, actual)
        if (displayed != controller.shown) displayed = controller.shown
    }

    /** 演示脚本：首字前 → 流式 → 工具（含三次连续短调用）→ 等待审批 → 工具 → 重试 → 首字前 → 流式，循环。 */
    private fun stepDemo(dt: Float) {
        val t = clock.mod(DemoScript.sumOf { it.second.toDouble() }.toFloat())
        var start = 0f
        var phase = GenerationMotionPhase.WAIT
        var segmentStart = 0f
        var segmentLength = 1f
        for ((p, length) in DemoScript) {
            if (t < start + length) {
                phase = p
                segmentStart = start
                segmentLength = length
                break
            }
            start += length
        }
        actual = phase
        demoRetryLeft = if (phase == GenerationMotionPhase.RETRY) clamp01(1 - (t - segmentStart) / segmentLength) else 0f
        if (phase == GenerationMotionPhase.STREAM) {
            val stall = valueNoise(clock * 0.8f + 7f) < 0.14f
            val rate = if (stall) 0f else 32f * (0.3f + 1.4f * valueNoise(clock * 0.5f + 3f))
            demoChars += rate * dt
            meter.report(demoChars.toInt())
        }
    }

    private var demoChars = 0f

    internal companion object {
        private const val DemoStreamStart = 1.6f
        private const val DemoRetrySeconds = 2.6f
        private val DemoScript = listOf(
            GenerationMotionPhase.WAIT to 1.6f,
            GenerationMotionPhase.STREAM to 3.6f,
            GenerationMotionPhase.TOOL to 2f,
            GenerationMotionPhase.WAIT to 0.12f,
            GenerationMotionPhase.TOOL to 0.18f,
            GenerationMotionPhase.WAIT to 0.1f,
            GenerationMotionPhase.TOOL to 0.15f,
            GenerationMotionPhase.WAIT to 0.9f,
            GenerationMotionPhase.APPROVAL to 2.6f,
            GenerationMotionPhase.TOOL to 1.2f,
            GenerationMotionPhase.RETRY to DemoRetrySeconds,
            GenerationMotionPhase.WAIT to 0.9f,
            GenerationMotionPhase.STREAM to 3.6f,
        )

        val Ambient = GenerationMotion(Source.AMBIENT)
    }
}

/** 当前界面的生成动效状态；会话界面提供实时状态，其余位置为固定流式的环境状态。 */
val LocalGenerationMotion = staticCompositionLocalOf { GenerationMotion.Ambient }

/**
 * 会话界面的实时生成动效：[phase] 为 [generationMotionPhase] 的结果，[retry] 为正在等待的自动重试，
 * [outputLength] 为本轮已到达的正文字数（用于估算出字速率）。只在有进行中的生成时逐帧推进。
 */
@Composable
fun rememberGenerationMotion(phase: GenerationMotionPhase?, retry: GenerationRetry?, outputLength: Int): GenerationMotion {
    val motion = remember { GenerationMotion(GenerationMotion.Source.LIVE) }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    var active by remember { mutableStateOf(false) }
    SideEffect {
        motion.update(phase, retry, outputLength, animate)
        active = phase != null
    }
    LaunchedEffect(motion, animate) {
        if (!animate) return@LaunchedEffect
        snapshotFlow { active }.collect { running ->
            if (!running) return@collect
            var last = -1L
            while (active) {
                withInfiniteAnimationFrameNanos { now ->
                    if (last > 0) motion.step(((now - last) / 1_000_000_000f).coerceAtMost(0.1f))
                    last = now
                }
            }
        }
    }
    return motion
}

/** 设置页预览用：按演示脚本轮播五个阶段，从第一段流式开始。系统关闭动画时停在流式。 */
@Composable
internal fun rememberDemoGenerationMotion(): GenerationMotion {
    val motion = remember { GenerationMotion(GenerationMotion.Source.DEMO) }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    LaunchedEffect(motion, animate) {
        if (!animate) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last > 0) motion.step(((now - last) / 1_000_000_000f).coerceAtMost(0.1f))
                last = now
            }
        }
    }
    return motion
}
