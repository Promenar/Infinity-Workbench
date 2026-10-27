package com.promenar.nexara.ui.chat

import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.common.motion.rememberMotionPalette
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import com.promenar.nexara.ui.theme.ComposerGlowStyle
import com.promenar.nexara.ui.theme.LocalNexaraMotionStyles
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** 输入框浮岛的圆角；描边、光晕与外壳共用。 */
internal val ComposerIslandCorner = 24.dp

/** 光带绕浮岛一圈的时长；描边与光晕共用同一角度，始终同步。 */
internal const val ComposerGlowPeriodSeconds = 2.8f

/**
 * 浮岛材质：深色沿用底部导航栏的抬升底色、半透明描边与顶边高光；
 * 浅色改用纯白底，层次主要靠投影，顶边高光在亮底上不可见因此不画。
 */
internal data class ComposerIslandMaterial(
    val container: Color,
    val border: BorderStroke,
    val topHighlight: Color?,
)

@Composable
internal fun composerIslandMaterial(): ComposerIslandMaterial {
    val scheme = MaterialTheme.colorScheme
    return if (MaterialTheme.nexaraDomainColors.isDark) {
        ComposerIslandMaterial(
            container = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
            topHighlight = scheme.onSurface.copy(alpha = 0.06f),
        )
    } else {
        ComposerIslandMaterial(
            container = scheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.75f)),
            topHighlight = null,
        )
    }
}

/** 光带两端颜色：深色直接用主色与第三色；浅色主题的主色偏深，提亮并加饱和后才像“发光”。 */
@Composable
internal fun composerGlowColors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (MaterialTheme.nexaraDomainColors.isDark) {
        scheme.primary to scheme.tertiary
    } else {
        vividAccent(scheme.primary) to vividAccent(scheme.tertiary)
    }
}

/** 在 HSV 空间把颜色推到至少 0.75 饱和、0.95 明度，色相不变；灰色保持灰色。 */
internal fun vividAccent(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    if (delta < 1e-4f) return Color(maxOf(maxC, 0.95f), maxOf(maxC, 0.95f), maxOf(maxC, 0.95f), color.alpha)
    val hue = when (maxC) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    val saturation = max(delta / maxC, 0.75f)
    val value = max(maxC, 0.95f)
    return Color.hsv(hue, saturation, value, color.alpha)
}

/** SweepGradient 的 0° 在 3 点钟方向，光带角度以 12 点钟为 0°，两者相差 90°。 */
internal fun sweepRotationForHead(headDegrees: Float): Float = headDegrees - 90f

internal fun advanceGlowAngle(angle: Float, deltaSeconds: Float, periodSeconds: Float = ComposerGlowPeriodSeconds): Float =
    (angle + deltaSeconds * 360f / periodSeconds).mod(360f)

/** 光效的动画状态：只在绘制阶段读取，变化时只重绘不重组。 */
@Stable
internal class ComposerGlowState {
    var headDegrees by mutableFloatStateOf(0f)
    var timeSeconds by mutableFloatStateOf(0f)
    val generating = Animatable(0f)
    val error = Animatable(0f)
}

/**
 * 生成中光带淡入并绕浮岛环行，结束后淡出；进入错误状态时整圈泛红后退去。
 * 系统关闭动画或静态预览时不环行，光带停在右上方。
 */
@Composable
internal fun rememberComposerGlowState(generating: Boolean, error: Boolean): ComposerGlowState {
    val state = remember { ComposerGlowState().apply { headDegrees = 30f } }
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    LaunchedEffect(generating, animate) {
        val target = if (generating) 1f else 0f
        if (animate) state.generating.animateTo(target, tween(if (generating) 400 else 600)) else state.generating.snapTo(target)
    }
    LaunchedEffect(error, animate) {
        if (!error) return@LaunchedEffect
        if (animate) {
            state.error.snapTo(1f)
            state.error.animateTo(0f, tween(2_200, easing = LinearEasing))
        }
    }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        // 只在光带可见时逐帧推进；空闲时不申请帧。
        snapshotFlow { state.generating.value > 0f || state.error.value > 0f }
            .distinctUntilChanged()
            .collectLatest { visible ->
                if (!visible) return@collectLatest
                var last = -1L
                while (true) {
                    withInfiniteAnimationFrameNanos { now ->
                        if (last > 0) {
                            val delta = (now - last) / 1_000_000_000f
                            state.headDegrees = advanceGlowAngle(state.headDegrees, delta)
                            state.timeSeconds += delta
                        }
                        last = now
                    }
                }
            }
    }
    return state
}

/**
 * 浮岛外圈光效：按外观设置的样式绘制。极光环行在外侧画沿边缘环行的光晕（Android 13 起用 AGSL）和同角度的流光描边；
 * 潮汐呼吸画随呼吸扩散的外圈光晕与渐变描边；灯语样式只留一圈呼吸描边，灯效由 [ComposerIslandLights] 画在浮岛内。
 * 出错时所有样式都整圈泛红，深色另加顶边高光。光晕画在浮岛自身边界之外，因此本修饰符须放在裁剪与背景之前。
 */
@Composable
internal fun Modifier.composerIslandGlow(
    state: ComposerGlowState,
    topHighlight: Color?,
    corner: Dp = ComposerIslandCorner,
): Modifier {
    val (colorA, colorB) = composerGlowColors()
    val style = LocalNexaraMotionStyles.current.composer
    val calmColor = rememberMotionPalette().a
    val outlinePaints = remember { ComposerOutlinePaints() }
    val errorColor = MaterialTheme.nexaraDomainColors.signalDown
    val dark = MaterialTheme.nexaraDomainColors.isDark
    // 静态预览不跑着色器；Android 12 只保留描边。着色器按颜色缓存，重组时不重新编译。
    val inspection = LocalInspectionMode.current
    val haloShader: android.graphics.Shader? = remember(colorA, colorB, errorColor, dark, inspection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !inspection) {
            composerHaloShader(colorA, colorB, errorColor, dark)
        } else {
            null
        }
    }
    return this.drawWithCache {
        val cornerPx = corner.toPx()
        val strokePx = 1.5.dp.toPx()
        val reach = 64.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val sweep = SweepGradient(
            center.x, center.y,
            intArrayOf(colorA.copy(alpha = 0f).toArgb(), colorA.toArgb(), colorB.toArgb(), colorB.copy(alpha = 0f).toArgb(), colorB.copy(alpha = 0f).toArgb()),
            floatArrayOf(0f, 70f / 360f, 130f / 360f, 190f / 360f, 1f),
        )
        val sweepMatrix = Matrix()
        val sweepBrush = ShaderBrush(sweep)
        val highlight = topHighlight?.let {
            Brush.verticalGradient(0f to it, 0.4f to Color.Transparent)
        }
        haloShader?.let { updateHaloGeometry(it, center, size, cornerPx, density) }
        val haloBrush = haloShader?.let { ShaderBrush(it) }
        onDrawWithContent {
            val generating = state.generating.value
            val error = state.error.value
            // 环行光晕只属于极光环行；出错时的整圈泛红所有样式共用。
            val haloGenerating = if (style == ComposerGlowStyle.AURORA) generating else 0f
            if (haloShader != null && (haloGenerating > 0f || error > 0f)) {
                updateHaloShader(haloShader, state, haloGenerating, error)
                drawRect(haloBrush!!, topLeft = Offset(-reach, -reach), size = Size(size.width + reach * 2, size.height + reach * 2))
            }
            if (style == ComposerGlowStyle.BREATHE && generating > 0f) {
                drawBreatheHalo(generating, state.timeSeconds, cornerPx, colorA, colorB, outlinePaints)
            }
            drawContent()
            if (highlight != null) {
                drawRoundRect(
                    brush = highlight,
                    topLeft = Offset(strokePx / 2, strokePx / 2),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(1.dp.toPx()),
                )
            }
            if (generating > 0f && style == ComposerGlowStyle.BREATHE) {
                drawBreatheStroke(generating, state.timeSeconds, cornerPx, colorA, colorB, outlinePaints)
            } else if (generating > 0f && style != ComposerGlowStyle.AURORA) {
                drawCalmBorder(generating, state.timeSeconds, cornerPx, calmColor, outlinePaints)
            } else if (generating > 0f) {
                sweepMatrix.setRotate(sweepRotationForHead(state.headDegrees), center.x, center.y)
                sweep.setLocalMatrix(sweepMatrix)
                drawRoundRect(
                    brush = sweepBrush,
                    topLeft = Offset(strokePx / 2, strokePx / 2),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerPx - strokePx / 2),
                    style = Stroke(strokePx),
                    alpha = generating,
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun composerHaloShader(
    colorA: Color,
    colorB: Color,
    errorColor: Color,
    dark: Boolean,
): android.graphics.Shader = RuntimeShader(ComposerHaloAgsl).apply {
    setFloatUniform("lightMode", if (dark) 0f else 1f)
    setColorUniform("colorA", colorA.toArgb())
    setColorUniform("colorB", colorB.toArgb())
    setColorUniform("colorErr", errorColor.toArgb())
}

private fun updateHaloGeometry(shader: android.graphics.Shader, center: Offset, size: Size, cornerPx: Float, density: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val runtime = shader as RuntimeShader
    runtime.setFloatUniform("center", center.x, center.y)
    runtime.setFloatUniform("halfSize", size.width / 2f, size.height / 2f)
    runtime.setFloatUniform("radius", cornerPx)
    runtime.setFloatUniform("density", density)
}

private fun updateHaloShader(shader: android.graphics.Shader, state: ComposerGlowState, generating: Float, error: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val runtime = shader as RuntimeShader
    runtime.setFloatUniform("time", state.timeSeconds)
    runtime.setFloatUniform("gen", generating)
    runtime.setFloatUniform("err", error)
    runtime.setFloatUniform("head", Math.toRadians(state.headDegrees.toDouble()).toFloat())
}

/**
 * 光晕着色器：以浮岛圆角矩形的外距离衰减，按与光带头部的夹角取一段弧（与描边渐变的 70°–130° 亮段一致），
 * 叠加沿边缘流动的噪声纹理；错误时整圈改为红色并向外多扩散一些。光晕在 44dp 内衰减到 0（绘制范围 64dp），
 * 不会在列表上留下矩形边界。输出为预乘透明度。
 */
internal const val ComposerHaloAgsl = """
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float density;
uniform float time;
uniform float gen;
uniform float err;
uniform float head;
uniform float lightMode;
layout(color) uniform half4 colorA;
layout(color) uniform half4 colorB;
layout(color) uniform half4 colorErr;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}
float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) { s += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return s;
}
float roundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 coord) {
    float2 p = (coord - center) / density;
    float d = max(roundedBox(p, halfSize / density, radius / density), 0.0);
    float an = atan(p.x, -p.y);
    float deg = mod(an - head, 6.2831853) * 57.29578;
    float arc = smoothstep(0.0, 70.0, deg) * (1.0 - smoothstep(130.0, 190.0, deg));
    // 噪声在圆周上取样，角度回绕处没有接缝。
    float2 ring = float2(cos(an - time * 0.23), sin(an - time * 0.23)) * 1.6;
    float tx = fbm(ring + float2(d * 0.06 - time * 0.35, time * 0.11));
    // 在绘制范围之内平滑衰减到 0：长尾在 8 位色深下会留下圆角矩形的色阶轮廓。
    float fade = 1.0 - smoothstep(16.0, 44.0, d);
    float core = exp(-d / 3.5) * 0.55;
    float halo = exp(-d * d / 300.0) * (0.45 + 0.75 * tx);
    float ga = gen * arc * (core + halo * 0.85);
    float gb = gen * 0.07 * exp(-d * d / 200.0);
    float e = err * (exp(-d * d / 500.0) * (0.55 + 0.6 * tx) + 0.35 * exp(-d / 14.0));
    // 弧段颜色随角度渐变；整圈底光用两色中间值，光带头尾相接处不跳色。
    half3 arcCol = mix(colorA.rgb, colorB.rgb, half(smoothstep(70.0, 130.0, deg)));
    half3 baseCol = mix(colorA.rgb, colorB.rgb, half(0.5));
    float g = ga + gb;
    half3 col = (arcCol * half(ga) + baseCol * half(gb)) / half(max(g, 0.0001));
    col = mix(col, colorErr.rgb, half(clamp(e / (g + e + 0.001), 0.0, 1.0)));
    float a = clamp((g + e) * fade * mix(1.0, 0.8, lightMode), 0.0, 1.0);
    // 低亮度区加半级抖动，打散色阶。
    a = clamp(a + (hash(coord) - 0.5) / 255.0 * step(0.002, a), 0.0, 1.0);
    return half4(col * half(a), half(a));
}
"""
