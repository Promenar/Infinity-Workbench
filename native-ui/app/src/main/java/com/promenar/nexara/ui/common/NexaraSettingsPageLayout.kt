package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    // 主标签页用自定义页头（如 [NexaraTabHeader]）替换标准顶栏；页头自行处理状态栏内边距。
    header: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            if (header != null) header() else if (showTopBar) TopAppBar(
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
            ) else {
                // 无顶栏时用页面背景色垫住状态栏，滚动内容不会从状态栏图标下方穿过。
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(MaterialTheme.colorScheme.background),
                )
            }
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
