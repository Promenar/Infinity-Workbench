package com.promenar.nexara.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTheme

enum class LoadingSize(val dotSize: Dp, val spacing: Dp) {
    SMALL(6.dp, 4.dp),
    MEDIUM(10.dp, 8.dp),
    LARGE(16.dp, 8.dp)
}

@Composable
fun NexaraLoadingIndicator(
    size: LoadingSize = LoadingSize.MEDIUM,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "dots")

    val scale1 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale1"
    )
    val alpha1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha1"
    )

    val scale2 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale2"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha2"
    )

    val scale3 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale3"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha3"
    )

    Row(modifier = modifier) {
        Dot(size.dotSize, scale1, alpha1)
        Spacer(modifier = Modifier.width(size.spacing))
        Dot(size.dotSize, scale2, alpha2)
        Spacer(modifier = Modifier.width(size.spacing))
        Dot(size.dotSize, scale3, alpha3)
    }
}

@Composable
private fun Dot(dotSize: Dp, scale: Float, alpha: Float) {
    Box(
        modifier = Modifier
            .size(dotSize)
            .scale(scale)
            .graphicsLayer { this.alpha = alpha }
            .background(NexaraColors.Primary, CircleShape)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraLoadingIndicatorSmallPreview() {
    NexaraTheme {
        NexaraLoadingIndicator(size = LoadingSize.SMALL)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraLoadingIndicatorMediumPreview() {
    NexaraTheme {
        NexaraLoadingIndicator(size = LoadingSize.MEDIUM)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraLoadingIndicatorLargePreview() {
    NexaraTheme {
        NexaraLoadingIndicator(size = LoadingSize.LARGE)
    }
}
