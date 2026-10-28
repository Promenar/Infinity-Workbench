package com.promenar.infinity.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object InfinityShapeTokens {
    val XSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
    val XLarge = RoundedCornerShape(24.dp)
}

val InfinityShapes = Shapes(
    extraSmall = InfinityShapeTokens.XSmall,
    small = InfinityShapeTokens.Small,
    medium = InfinityShapeTokens.Medium,
    large = InfinityShapeTokens.Large,
    extraLarge = InfinityShapeTokens.XLarge,
)

object InfinityCustomShapes {
    val ChatBubbleUser = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 4.dp
    )
    val ChatBubbleAssistant = RoundedCornerShape(18.dp)
}
