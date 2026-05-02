package com.promenar.nexara.native.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Nexara UI Shape System (Stitch MD3 Spec)
 *
 * Specific components constraints:
 * - Cards: 16dp (large)
 * - Chat Bubbles: 18dp (special case)
 * - Inputs: 24dp (extra large)
 */

val NexaraShapes = Shapes(
    small = RoundedCornerShape(4.dp),       // sm
    medium = RoundedCornerShape(8.dp),      // DEFAULT
    large = RoundedCornerShape(16.dp),      // lg (Cards)
    extraLarge = RoundedCornerShape(24.dp)  // xl (Inputs)
)

// Specific non-standard shapes for the design system
object NexaraCustomShapes {
    val ChatBubbleUser = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 4.dp // Pointy tail
    )
    val ChatBubbleAssistant = RoundedCornerShape(18.dp)
}
