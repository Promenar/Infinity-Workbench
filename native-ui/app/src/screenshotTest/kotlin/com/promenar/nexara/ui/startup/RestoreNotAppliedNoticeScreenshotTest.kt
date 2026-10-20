package com.promenar.nexara.ui.startup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "恢复未应用提示 深色中文", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun restoreNotAppliedNoticeDarkPreview() = RestoreNotAppliedNoticePreview(dark = true)

@PreviewTest
@Preview(name = "恢复未应用提示 浅色英文 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun restoreNotAppliedNoticeLightPreview() = RestoreNotAppliedNoticePreview(dark = false)

@Composable
private fun RestoreNotAppliedNoticePreview(dark: Boolean) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            RestoreNotAppliedDialog(onDismiss = {})
        }
    }
}
