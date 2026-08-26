package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraSpacing

/**
 * 设置层级页共用骨架。
 *
 * 内容边距由 [PaddingValues] 统一下发，调用方可直接传给 LazyColumn 的
 * `contentPadding`，也可应用到自定义滚动容器；骨架不强制内容的滚动实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaraSettingsPageLayout(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    horizontalContentPadding: Dp = NexaraSpacing.ScreenHorizontal,
    showTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val parentColors = MaterialTheme.colorScheme
    val parentShapes = MaterialTheme.shapes
    val parentTypography = MaterialTheme.typography
    val settingsTypography = remember(parentTypography) {
        parentTypography.copy(
            titleLarge = parentTypography.titleLarge.copy(
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Normal,
            ),
            titleMedium = parentTypography.titleMedium.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
            ),
            titleSmall = parentTypography.titleSmall.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
            bodyLarge = parentTypography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            bodyMedium = parentTypography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            bodySmall = parentTypography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelLarge = parentTypography.labelLarge.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
            labelMedium = parentTypography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelSmall = parentTypography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
        )
    }

    MaterialTheme(
        colorScheme = parentColors,
        shapes = parentShapes,
        typography = settingsTypography,
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars,
            topBar = {
                if (showTopBar) TopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            NexaraBackButton(onClick = onBack)
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
        ) { scaffoldPadding ->
            content(
                PaddingValues(
                    start = horizontalContentPadding,
                    top = scaffoldPadding.calculateTopPadding(),
                    end = horizontalContentPadding,
                    bottom = scaffoldPadding.calculateBottomPadding() + NexaraSpacing.XLarge,
                ),
            )
        }
    }
}
