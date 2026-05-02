package com.promenar.nexara.native.ui.welcome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

@Composable
fun WelcomeScreen(
    onNavigateToChat: () -> Unit
) {
    // Scaffold provides the true immersive edge-to-edge canvas
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars // Enforces avoidance of notch/nav bar naturally
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Background Atmosphere (The massive blurs)
            AtmosphereBackground()

            // Main Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp), // safe-margin: 20px
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Header Area
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 32.dp) // mb-xl
                ) {
                    // Logo representation
                    Text(
                        text = "NEXARA",
                        style = NexaraTypography.headlineLarge.copy(
                            fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.05).sp
                        ),
                        color = NexaraColors.Primary,
                        modifier = Modifier.padding(bottom = 8.dp) // mb-sm
                    )
                    Text(
                        text = "INTELLIGENCE REIMAGINED",
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.Secondary.copy(alpha = 0.8f),
                        letterSpacing = 0.1.sp
                    )
                }

                // Action Area (Language Selection)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp), // mt-lg
                    verticalArrangement = Arrangement.spacedBy(16.dp) // space-y-md
                ) {
                    LanguageButton(
                        icon = Icons.Rounded.Language,
                        text = "English",
                        onClick = onNavigateToChat
                    )
                    LanguageButton(
                        icon = Icons.Rounded.Translate,
                        text = "中文 (简体)",
                        onClick = onNavigateToChat
                    )
                }
            }

            // Bottom decorative line (from HTML spec)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp) // bottom-12
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    NexaraColors.Outline,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun BoxScope.AtmosphereBackground() {
    // 120px blur light spots
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (-100).dp, y = (-100).dp)
            .size(200.dp)
            .blur(80.dp)
            .background(NexaraColors.Primary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.CircleShape)
    )
    
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 100.dp, y = 100.dp)
            .size(200.dp)
            .blur(80.dp)
            .background(NexaraColors.Tertiary.copy(alpha = 0.05f), shape = androidx.compose.foundation.shape.CircleShape)
    )
}

@Composable
private fun LanguageButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Stitch explicitly demands a 0.95 scale down on active press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "ButtonScale"
    )

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Disable default ripple because the scale + glass is the intended feedback
                onClick = onClick
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp), // px-lg py-md
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp) // gap-md
            ) {
                // Icon circular container
                Box(
                    modifier = Modifier
                        .size(32.dp) // w-8 h-8
                        .background(NexaraColors.Primary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Text(
                    text = text,
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnBackground
                )
            }
            
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = NexaraColors.Outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
