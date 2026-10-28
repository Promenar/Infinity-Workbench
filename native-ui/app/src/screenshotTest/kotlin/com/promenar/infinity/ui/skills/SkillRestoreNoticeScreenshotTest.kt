package com.promenar.infinity.ui.skills

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.backup.SkillBackupSummary
import com.promenar.infinity.data.backup.SkillGitRemote
import com.promenar.infinity.data.backup.SkippedSkillGitRepository
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "技能恢复提示 深色中文", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun skillRestoreNoticeDarkPreview() = SkillRestoreNoticePreview(dark = true)

@PreviewTest
@Preview(name = "技能恢复提示 浅色英文 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun skillRestoreNoticeLightPreview() = SkillRestoreNoticePreview(dark = false)

private val PreviewSummary = SkillBackupSummary(
    skillCount = 6,
    skippedSymbolicLinks = 2,
    skippedGitRepositories = listOf(
        SkippedSkillGitRepository(".", listOf(SkillGitRemote("origin", "https://github.com/example/agent-skills.git")), "main",
            "3f9c2a71b0d84e6f5a1c9e2d7b3f4a6c8d0e1f2a"),
        SkippedSkillGitRepository("pdf-report", emptyList(), null, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"),
    ),
)

@Composable
private fun SkillRestoreNoticePreview(dark: Boolean) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SkillRestoreNoticeDialog(PreviewSummary, onDismiss = {})
        }
    }
}
