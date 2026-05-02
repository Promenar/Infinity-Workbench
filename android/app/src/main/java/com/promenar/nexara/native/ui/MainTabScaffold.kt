package com.promenar.nexara.native.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraTypography

enum class AppTab(val title: String, val icon: ImageVector) {
    CHAT("CHAT", Icons.Rounded.ChatBubble),
    INSIGHTS("INSIGHTS", Icons.Rounded.Insights),
    ARTIFACTS("ARTIFACTS", Icons.Rounded.Widgets),
    SETTINGS("SETTINGS", Icons.Rounded.Settings)
}

@Composable
fun MainTabScaffold(
    onNavigateToSecondary: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppTab.CHAT) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // Base layer handles no insets, children handle it
        bottomBar = {
            NexaraBottomNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Content swapping based on selected tab
            when (selectedTab) {
                AppTab.CHAT -> com.promenar.nexara.native.ui.hub.AgentHubScreen(onNavigateToChat = { onNavigateToSecondary("session_list") })
                AppTab.INSIGHTS -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Insights Coming Soon", color = NexaraColors.OnBackground, style = NexaraTypography.headlineMedium)
                    }
                }
                AppTab.ARTIFACTS -> com.promenar.nexara.native.ui.rag.RagHomeScreen(onNavigateToDetail = { onNavigateToSecondary("rag_advanced") })
                AppTab.SETTINGS -> com.promenar.nexara.native.ui.hub.UserSettingsHomeScreen(onNavigateToAdvancedRAG = { onNavigateToSecondary("rag_advanced") })
            }
        }
    }
}

@Composable
private fun NexaraBottomNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    val isBlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    
    // We construct the glass bar
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Height is not fixed 80px, it adapts to navigationBars system inset!
            .windowInsetsPadding(WindowInsets.navigationBars) 
    ) {
        // Blur Backdrop
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(NexaraColors.CanvasBackground.copy(alpha = 0.8f)) // bg-zinc-950/80
                .then(if (isBlurSupported) Modifier.blur(20.dp) else Modifier) // backdrop-blur-2xl
        )
        
        // Top 0.5px border (border-t-[0.5px] border-white/10)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(NexaraColors.GlassBorder)
                .align(Alignment.TopCenter)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp), // pt-3 pb-3 (pb-8 handled by insets) px-4
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                TabItem(
                    tab = tab,
                    isSelected = isSelected,
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f, // active:scale-90
        animationSpec = tween(durationMillis = 200),
        label = "TabScale"
    )

    val contentColor = if (isSelected) NexaraColors.Primary else NexaraColors.Outline
    
    // The specific glow effect drop-shadow-[0_0_8px_rgba(192,193,255,0.5)]
    val glowColor = if (isSelected) NexaraColors.Primary.copy(alpha = 0.5f) else Color.Transparent

    Column(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // No standard ripple, rely on scale
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.title,
            tint = contentColor,
            modifier = Modifier
                .size(24.dp)
                .drawBehind {
                    // Custom Drop Shadow Glow
                    if (isSelected) {
                        drawIntoCanvas { canvas ->
                            val paint = Paint().apply {
                                color = glowColor
                            }
                            val frameworkPaint = paint.asFrameworkPaint()
                            frameworkPaint.setShadowLayer(
                                8.dp.toPx(), // 8px blur
                                0f, 0f, // center
                                android.graphics.Color.argb(128, 192, 193, 255) // rgba(192,193,255,0.5)
                            )
                            // Draw an invisible shape just for the shadow
                            canvas.drawCircle(center, center.x * 0.8f, paint)
                        }
                    }
                }
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = tab.title,
            style = NexaraTypography.labelMedium.copy(
                fontSize = 10.sp, // text-[10px]
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp // tracking-widest
            ),
            color = contentColor
        )
    }
}
