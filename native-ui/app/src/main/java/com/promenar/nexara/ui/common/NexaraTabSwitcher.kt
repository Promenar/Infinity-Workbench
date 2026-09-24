package com.promenar.nexara.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 单项页签描述模型
 */
data class NexaraTabItem(
    val title: String,
    val icon: ImageVector? = null,
    val badge: String? = null,
    val testTag: String? = null,
)

/**
 * 全站统一全圆胶囊 Tab 切换组件 (移植自 Homebar 与知识库设计语言)
 *
 * 特性：
 * 1. 外层全胶囊容器 (CircleShape) + surfaceContainer 底色 + outlineVariant 0.5f 微描边；
 * 2. 同心全圆胶囊滑动底座 (secondaryContainer) + 物理弹簧动画 (Spring, dampingRatio=0.85f)；
 * 3. 切换触发原生 SEGMENT_TICK 触觉反馈震动；
 * 4. 选中项加粗并应用 onSecondaryContainer，未选中态为 onSurfaceVariant。
 */
@Composable
fun NexaraTabSwitcher(
    itemCount: Int,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    tabModifier: (index: Int) -> Modifier = { Modifier },
    tabContent: @Composable (index: Int, isSelected: Boolean, contentColor: Color) -> Unit,
) {
    if (itemCount <= 0) return

    val view = LocalView.current
    val safeSelectedIndex = selectedIndex.coerceIn(0, itemCount - 1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / itemCount
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * safeSelectedIndex,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "nexaraTabIndicatorOffset",
        )

        // 胶囊滑动高亮底座
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(indicatorColor),
        )

        // 选项文本与点击行
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in 0 until itemCount) {
                val selected = safeSelectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 200),
                    label = "nexaraTabContentColor_$index",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (safeSelectedIndex != index) {
                                    runCatching {
                                        val hapticConstant = if (Build.VERSION.SDK_INT >= 34) {
                                            HapticFeedbackConstants.SEGMENT_TICK
                                        } else {
                                            HapticFeedbackConstants.CLOCK_TICK
                                        }
                                        view.performHapticFeedback(hapticConstant)
                                    }
                                    onTabSelected(index)
                                }
                            },
                        )
                        .then(tabModifier(index)),
                    contentAlignment = Alignment.Center,
                ) {
                    tabContent(index, selected, contentColor)
                }
            }
        }
    }
}

/**
 * 针对标准 NexaraTabItem 列表的开箱即用胶囊 Tab 切换器
 */
@Composable
fun NexaraCapsuleTabRow(
    tabs: List<NexaraTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    NexaraTabSwitcher(
        itemCount = tabs.size,
        selectedIndex = selectedIndex,
        onTabSelected = onTabSelected,
        modifier = modifier,
        height = height,
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        tabModifier = { index ->
            tabs.getOrNull(index)?.testTag?.let { Modifier.testTag(it) } ?: Modifier
        },
    ) { index, selected, contentColor ->
        val item = tabs.getOrNull(index) ?: return@NexaraTabSwitcher
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            if (item.icon != null) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.badge.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = item.badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}
