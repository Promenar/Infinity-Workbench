package com.promenar.nexara.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.ui.theme.NexaraTheme

@PreviewTest
@Preview(
    name = "Prompt editor compact large font",
    widthDp = 360,
    heightDp = 640,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorCompactLargeFontPreview() {
    NexaraTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = (1..80).joinToString("\n") { line ->
                "$line. Long prompt content remains editable and save stays reachable."
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "Prompt editor empty Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorEmptyTabletPreview() {
    NexaraTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = "",
        )
    }
}
