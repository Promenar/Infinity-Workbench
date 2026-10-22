package com.promenar.nexara.ui.chat

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 字标中心所在的纵向比例：略高于正中，给下方的提示语留位置。 */
private const val WordmarkCenterFraction = 0.42f
private const val WordmarkLogoLimit = 900
private const val WordmarkAmbientCount = 260
private const val WordmarkSampleStepDp = 2
private const val WordmarkMaxTextSizeDp = 68f

/**
 * 空会话的粒子字标：粒子飞入拼成品牌字标并轻微呼吸，手指按住划过时让开（位置见 [WordmarkPointer]）；开始输入后散成流场，清空输入后重新聚拢。
 * 只在会话还没有消息时出现，离开组合后动画循环随之停止；系统关闭动画或静态预览时直接显示静止的字标。
 * 纯装饰，不进入无障碍树；下方提示语可读。
 */
/**
 * 字标的指针位置（根坐标）。字标画在消息列表下层、不拦截触摸，由外层容器在 Initial 阶段旁路记录手指位置，
 * 不消费事件，下层与上层控件的点击都不受影响。
 */
@Stable
internal class WordmarkPointer {
    /** 按下时为根坐标，抬起后为 NaN。 */
    val root = floatArrayOf(Float.NaN, Float.NaN)
}

/** 挂在包含字标的容器上：记录按下中的手指位置，不消费事件。 */
internal fun Modifier.trackWordmarkPointer(pointer: WordmarkPointer): Modifier {
    var origin = Offset.Zero
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(pointer) {
            awaitPointerEventScope {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                    if (change != null && change.pressed) {
                        pointer.root[0] = origin.x + change.position.x
                        pointer.root[1] = origin.y + change.position.y
                    } else {
                        pointer.root[0] = Float.NaN
                        pointer.root[1] = Float.NaN
                    }
                }
            }
        }
}

@Composable
internal fun EmptyChatWordmark(dispersed: Boolean, pointer: WordmarkPointer, modifier: Modifier = Modifier) {
    val wordmark = stringResource(R.string.brand_wordmark)
    val prompt = stringResource(R.string.chat_empty_prompt)
    val density = LocalDensity.current.density
    val inspection = LocalInspectionMode.current
    val animate = rememberAnimationsEnabled() && !inspection
    val dark = MaterialTheme.nexaraDomainColors.isDark
    val (primary, tertiary) = composerGlowColors()
    val secondary = MaterialTheme.colorScheme.secondary.let { if (dark) it else vividAccent(it) }
    val palette = remember(primary, tertiary, secondary) { intArrayOf(primary.toArgb(), tertiary.toArgb(), secondary.toArgb()) }

    BoxWithConstraints(modifier.fillMaxSize().testTag(UiTags.CHAT_EMPTY_WORDMARK)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val centerY = heightPx * WordmarkCenterFraction
        // 只按宽度重建：高度随键盘和输入框变化，交给 recenter 平移，粒子不会因此重置。
        // 静态预览同步生成，截图才有确定内容；运行时放到后台线程。
        val preview = remember(wordmark, widthPx, density) {
            if (inspection) buildWordmarkParticles(wordmark, widthPx, heightPx, centerY, density).also { it.settle() } else null
        }
        val particles by produceState(preview, wordmark, widthPx, density) {
            if (value == null) {
                value = withContext(Dispatchers.Default) {
                    buildWordmarkParticles(wordmark, widthPx, heightPx, centerY, density)
                }
            }
        }
        var frame by remember { mutableLongStateOf(0L) }
        val origin = remember { floatArrayOf(0f, 0f) }

        LaunchedEffect(particles, centerY, animate) {
            val system = particles ?: return@LaunchedEffect
            system.recenter(centerY, heightPx)
            if (!animate) {
                system.settle()
                frame++
            }
        }

        LaunchedEffect(particles, dispersed) {
            val system = particles ?: return@LaunchedEffect
            if (dispersed) system.disperse(widthPx / 2f, centerY) else system.reform()
        }
        LaunchedEffect(particles, animate) {
            val system = particles ?: return@LaunchedEffect
            if (!animate) return@LaunchedEffect
            var last = -1L
            var time = 0f
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    if (last > 0) {
                        val delta = (now - last) / 1_000_000_000f
                        time += delta
                        system.step(delta, time, pointer.root[0] - origin[0], pointer.root[1] - origin[1])
                    }
                    last = now
                    frame = now
                }
            }
        }

        val paint = remember {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
        }
        // 绘制阶段先于重组读到新的粒子系统，缓冲区在绘制时按需扩容。
        val lineBuffer = remember { arrayOf(FloatArray(0)) }
        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInRoot()
                    origin[0] = position.x
                    origin[1] = position.y
                },
        ) {
            val system = particles ?: return@Canvas
            if (lineBuffer[0].size < system.count * 4) lineBuffer[0] = FloatArray(system.count * 4)
            val lines = lineBuffer[0]
            val time = frame / 1_000_000_000f
            paint.blendMode = if (dark) android.graphics.BlendMode.PLUS else android.graphics.BlendMode.SRC_OVER
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                for (bucket in 0 until WordmarkParticles.TwinkleBuckets) {
                    val twinkle = 0.65f + 0.35f * sin(time * 3f + bucket * 1.57f)
                    for (layer in 0..WordmarkParticles.LogoLayer) {
                        paint.strokeWidth = ParticleSizeDp[layer] * density
                        for (hue in 0..2) {
                            var n = 0
                            for (i in 0 until system.count) {
                                if (system.twinkle[i] != bucket || system.layer[i] != layer || system.hue[i] != hue) continue
                                lines[n++] = system.x[i] - system.vx[i] * 1.5f
                                lines[n++] = system.y[i] - system.vy[i] * 1.5f
                                lines[n++] = system.x[i]
                                lines[n++] = system.y[i]
                            }
                            if (n == 0) continue
                            val alpha = ParticleAlpha[layer] * twinkle * (if (dark) 1f else 0.75f)
                            paint.color = Color(palette[hue]).copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
                            native.drawLines(lines, 0, n, paint)
                        }
                    }
                }
            }
        }

        val promptAlpha by animateFloatAsState(if (dispersed) 0f else 1f, tween(500), label = "empty_prompt_alpha")
        val promptTop by animateDpAsState(with(LocalDensity.current) { centerY.toDp() } + 56.dp, label = "empty_prompt_top")
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = promptTop)
                .padding(horizontal = 24.dp)
                .alpha(promptAlpha),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 按层：远、中、近三层环境粒子，最后一项是字标粒子。 */
private val ParticleSizeDp = floatArrayOf(0.7f, 1.1f, 1.7f, 1.6f)
private val ParticleAlpha = floatArrayOf(0.35f, 0.6f, 0.9f, 1f)

/** 把字标按 dp 网格栅格化后采样笔画点，再换算成像素坐标；栅格只有屏幕的 1/density² 大小，几毫秒内完成。 */
private fun buildWordmarkParticles(
    wordmark: String,
    widthPx: Float,
    heightPx: Float,
    centerY: Float,
    density: Float,
): WordmarkParticles {
    val width = (widthPx / density).toInt().coerceAtLeast(1)
    val height = (heightPx / density).toInt().coerceAtLeast(1)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        letterSpacing = 0.12f
        textAlign = Paint.Align.CENTER
    }
    paint.textSize = fitWordmarkTextSize({ size -> paint.textSize = size; paint.measureText(wordmark) }, width * 0.78f, WordmarkMaxTextSizeDp)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val metrics = paint.fontMetrics
    val baseline = centerY / density - (metrics.ascent + metrics.descent) / 2f
    android.graphics.Canvas(bitmap).drawText(wordmark, width / 2f, baseline, paint)
    val alpha = ByteArray(width * height)
    bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(alpha))
    bitmap.recycle()
    val targets = sampleWordmarkTargets(alpha, width, height, WordmarkSampleStepDp, WordmarkLogoLimit)
    for (i in targets.indices) targets[i] *= density
    return WordmarkParticles(targets, WordmarkAmbientCount, widthPx, heightPx, density).also { it.recenter(centerY, heightPx) }
}
