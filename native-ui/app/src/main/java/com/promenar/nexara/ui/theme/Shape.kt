package com.promenar.nexara.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object NexaraShapeTokens {
    val XSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
    val XLarge = RoundedCornerShape(24.dp)
}

val NexaraShapes = Shapes(
    extraSmall = NexaraShapeTokens.XSmall,
    small = NexaraShapeTokens.Small,
    medium = NexaraShapeTokens.Medium,
    large = NexaraShapeTokens.Large,
    extraLarge = NexaraShapeTokens.XLarge,
)

object NexaraCustomShapes {
    val ChatBubbleUser = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 4.dp
    )
    val ChatBubbleAssistant = RoundedCornerShape(18.dp)
}
