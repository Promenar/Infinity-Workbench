package com.promenar.infinity.ui.common

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import coil3.compose.AsyncImage
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import com.promenar.infinity.R
import com.promenar.infinity.data.avatar.AvatarSymbolCatalog
import com.promenar.infinity.domain.avatar.AgentAvatarCodec
import com.promenar.infinity.domain.avatar.AgentAvatarSpec
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.InfinityAvatarHighlight
import com.promenar.infinity.ui.theme.InfinityAvatarInitialInk
import com.promenar.infinity.ui.theme.InfinityAvatarInitialShadow
import java.text.BreakIterator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 解析 `#RRGGBB` 或 `#AARRGGBB`；格式不对返回 null。
 * 不依赖 android.graphics，便于 JVM 单测，也保证各处解析结果一致。
 */
fun parseAgentColor(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 6) Color(OPAQUE_ALPHA or value) else Color(value)
}

/** 六位色值补上的不透明 alpha 位。 */
private const val OPAQUE_ALPHA = 0xFF000000L

/** 符号头像的底色与前景色。 */
data class AgentAvatarTones(val container: Color, val content: Color)

/**
 * 由智能体主题色派生同色相的容器色与前景色：浅色主题取色调 90 / 30，深色主题取 30 / 90，
 * 不论用户选的是浅紫还是深靛，图标与底色的对比度都稳定。色度封顶，避免荧光感。
 */
@SuppressLint("RestrictedApi")
fun agentAvatarTones(hex: String, dark: Boolean, fallback: Color): AgentAvatarTones {
    val palette = agentTonalPalette(hex, fallback, maxChroma = TONE_MAX_CHROMA)
    return if (dark) {
        AgentAvatarTones(Color(palette.tone(30)), Color(palette.tone(90)))
    } else {
        AgentAvatarTones(Color(palette.tone(90)), Color(palette.tone(30)))
    }
}

/** 生成头像的渐变参数：第二段色相相对主题色的偏移（带方向）与渐变角度，均由种子确定。 */
data class GeneratedAvatarStyle(val hueShift: Int, val angleDegrees: Int)

/** FNV-1a 32 位散列，按 UTF-16 码元计算，跨版本稳定。 */
internal fun avatarSeedHash(seed: String): UInt {
    var hash = 2166136261u
    for (unit in seed) {
        hash = hash xor unit.code.toUInt()
        hash *= 16777619u
    }
    return hash
}

fun generatedAvatarStyle(seed: String): GeneratedAvatarStyle {
    val hash = avatarSeedHash(seed)
    val magnitude = 25 + (hash % 30u).toInt()
    val clockwise = (hash / 30u) % 2u == 0u
    return GeneratedAvatarStyle(
        hueShift = if (clockwise) magnitude else -magnitude,
        angleDegrees = 110 + ((hash / 60u) % 140u).toInt(),
    )
}

/** 生成头像的两段渐变色。终点色相落进黄绿段时改向另一侧偏移，避免低 tone 下发成橄榄色。 */
@SuppressLint("RestrictedApi")
fun generatedAvatarColors(hex: String, seed: String, dark: Boolean, fallback: Color): Pair<Color, Color> {
    val base = Hct.fromInt((parseAgentColor(hex) ?: fallback).toArgb())
    val shift = generatedAvatarStyle(seed).hueShift
    val forward = (base.hue + shift + 360.0) % 360.0
    val endHue = if (forward in GENERATED_MUDDY_HUES) (base.hue - shift + 360.0) % 360.0 else forward
    val start = TonalPalette.fromHueAndChroma(base.hue, GENERATED_START_CHROMA)
    val end = TonalPalette.fromHueAndChroma(endHue, GENERATED_END_CHROMA)
    return if (dark) Color(start.tone(64)) to Color(end.tone(44)) else Color(start.tone(72)) to Color(end.tone(52))
}

/** 名称的第一个字形簇：拉丁字母转大写，中文、表情原样保留；空名称返回空串。 */
fun agentAvatarInitial(name: String): String {
    val text = name.trim()
    if (text.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.next().takeIf { it != BreakIterator.DONE } ?: text.length
    return text.substring(0, end).uppercase()
}

@SuppressLint("RestrictedApi")
private fun agentTonalPalette(hex: String, fallback: Color, maxChroma: Double): TonalPalette {
    val hct = Hct.fromInt((parseAgentColor(hex) ?: fallback).toArgb())
    return TonalPalette.fromHueAndChroma(hct.hue, hct.chroma.coerceAtMost(maxChroma))
}

private const val TONE_MAX_CHROMA = 48.0
private const val GENERATED_START_CHROMA = 48.0
private const val GENERATED_END_CHROMA = 64.0
/** HCT 黄绿段，两侧各留 5° 余量，抵消色域裁剪带来的色相漂移。 */
private val GENERATED_MUDDY_HUES = 80.0..135.0

/** 符号字形占头像直径的比例；28dp 菜单到 80dp 设置页都按同一比例缩放。 */
private const val SYMBOL_SCALE = 0.54f
private const val INITIAL_SCALE = 0.42f

/**
 * 智能体头像。[icon] 与 [color] 是 agents 表的原值，[seed] 是智能体 ID（生成头像的默认种子），
 * [name] 用于生成头像的首字；有 [photoUri] 时显示照片。
 */
@Composable
fun AgentAvatar(
    icon: String,
    color: String,
    seed: String,
    name: String,
    photoUri: String? = null,
    size: Dp = 80.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val spec = remember(icon) { AgentAvatarCodec.decode(icon) }
    AgentAvatar(
        spec = spec,
        color = color,
        seed = seed,
        name = name,
        photoUri = photoUri,
        size = size,
        onClick = onClick,
        contentDescription = contentDescription,
    )
}

@Composable
fun AgentAvatar(
    spec: AgentAvatarSpec,
    color: String,
    seed: String,
    name: String,
    photoUri: String? = null,
    size: Dp = 80.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
    )
    val avatarDescription = contentDescription ?: stringResource(
        if (onClick != null) R.string.agent_avatar_edit else R.string.agent_avatar
    )

    val touchTargetSize = if (onClick != null && size < 48.dp) 48.dp else size
    Box(
        modifier = Modifier
            .size(touchTargetSize)
            .then(
                if (onClick != null) {
                    Modifier
                        .testTag(UiTags.AGENT_AVATAR_ACTION)
                        .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = avatarDescription,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .semantics {
                this.contentDescription = avatarDescription
                if (onClick != null) role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = null,
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop,
                )
            } else {
                AgentAvatarArt(spec = spec, color = color, seed = seed, name = name, modifier = Modifier.size(size))
            }
        }
    }
}

/** 头像画面本身（不含点击与语义），选择器的预览与网格也复用它。 */
@Composable
fun AgentAvatarArt(
    spec: AgentAvatarSpec,
    color: String,
    seed: String,
    name: String,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.infinityDomainColors.isDark
    val fallback = MaterialTheme.colorScheme.primary
    when (spec) {
        is AgentAvatarSpec.Symbol -> {
            val tones = remember(color, dark, fallback) { agentAvatarTones(color, dark, fallback) }
            val codepoint = remember(spec.name) {
                (AvatarSymbolCatalog.find(spec.name) ?: AvatarSymbolCatalog.find(AgentAvatarCodec.DEFAULT_SYMBOL))?.codepoint
            }
            Box(modifier.background(tones.container), contentAlignment = Alignment.Center) {
                if (codepoint != null) AvatarSymbolGlyph(codepoint = codepoint, color = tones.content, modifier = Modifier.matchParentSize())
            }
        }
        is AgentAvatarSpec.Generated -> {
            val effectiveSeed = spec.seed.ifEmpty { seed }
            val colors = remember(color, effectiveSeed, dark, fallback) { generatedAvatarColors(color, effectiveSeed, dark, fallback) }
            val angle = remember(effectiveSeed) { generatedAvatarStyle(effectiveSeed).angleDegrees }
            val initial = remember(name) { agentAvatarInitial(name) }
            val typeface = rememberAvatarTypeface(symbols = false)
            Canvas(modifier) {
                drawGeneratedBackground(colors.first, colors.second, angle)
                if (initial.isNotEmpty()) drawCenteredText(initial, typeface, InfinityAvatarInitialInk, size.minDimension * INITIAL_SCALE, shadow = true)
            }
        }
    }
}

/** 按字形实际外框居中绘制一个 Material Symbols 码位，字形大小为容器短边的 [SYMBOL_SCALE]。 */
@Composable
fun AvatarSymbolGlyph(codepoint: Int, color: Color, modifier: Modifier = Modifier, scale: Float = SYMBOL_SCALE) {
    val typeface = rememberAvatarTypeface(symbols = true)
    val text = remember(codepoint) { String(Character.toChars(codepoint)) }
    Canvas(modifier) {
        drawCenteredText(text, typeface, color, size.minDimension * scale, shadow = false)
    }
}

@Composable
private fun rememberAvatarTypeface(symbols: Boolean): Typeface? {
    val context = LocalContext.current
    return remember(context, symbols) {
        if (symbols) runCatching { ResourcesCompat.getFont(context, R.font.infinity_avatar_symbols) }.getOrNull()
        else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}

private fun DrawScope.drawGeneratedBackground(start: Color, end: Color, angleDegrees: Int) {
    val radians = Math.toRadians(angleDegrees.toDouble() - 90.0)
    val half = size.minDimension / 2f
    val dx = (cos(radians) * half).toFloat()
    val dy = (sin(radians) * half).toFloat()
    drawRect(Brush.linearGradient(listOf(start, end), start = center - Offset(dx, dy), end = center + Offset(dx, dy)))
    // 左上角柔光，让渐变有一点体积感。
    drawRect(
        Brush.radialGradient(
            listOf(InfinityAvatarHighlight, Color.Transparent),
            center = Offset(size.width * 0.28f, size.height * 0.22f),
            radius = size.minDimension * 0.5f,
        ),
    )
}

private fun DrawScope.drawCenteredText(text: String, typeface: Typeface?, color: Color, textSize: Float, shadow: Boolean) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSize
    }
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    val x = center.x - bounds.exactCenterX()
    val y = center.y - bounds.exactCenterY()
    drawIntoCanvas { canvas ->
        // 投影用同一字形下移一小段先画一遍，再叠正文，让白字在浅色渐变上也有轮廓。
        if (shadow) {
            paint.color = InfinityAvatarInitialShadow.toArgb()
            canvas.nativeCanvas.drawText(text, x, y + textSize * 0.03f, paint)
        }
        paint.color = color.toArgb()
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}
