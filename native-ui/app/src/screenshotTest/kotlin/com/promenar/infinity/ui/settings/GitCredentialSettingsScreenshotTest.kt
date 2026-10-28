package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/** 截图只消费展示状态，不创建 ViewModel，也不读写凭据存储。 */
@PreviewTest
@Preview(name = "Git 凭据 浅色英文", widthDp = 412, heightDp = 1400, locale = "en")
@Composable
fun gitCredentialsLightPreview() = GitCredentialsPreview(dark = false, operation = GitCredentialOperation.Idle)

@PreviewTest
@Preview(name = "Git 凭据 深色中文 已保存", widthDp = 412, heightDp = 1400, locale = "zh-rCN")
@Composable
fun gitCredentialsDarkPreview() = GitCredentialsPreview(dark = true, operation = GitCredentialOperation.Saved("github.com"))

@PreviewTest
@Preview(name = "Git 凭据 深色中文 字号130 错误", widthDp = 360, heightDp = 1900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun gitCredentialsLargeTextPreview() =
    GitCredentialsPreview(dark = true, operation = GitCredentialOperation.Error(GitCredentialErrorCode.INVALID_TOKEN))

@PreviewTest
@Preview(name = "Git 凭据 浅色英文 空", widthDp = 412, heightDp = 1200, locale = "en")
@Composable
fun gitCredentialsEmptyPreview() = GitCredentialsPreview(dark = false, operation = GitCredentialOperation.Idle, empty = true)

@Composable
private fun GitCredentialsPreview(dark: Boolean, operation: GitCredentialOperation, empty: Boolean = false) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            GitCredentialSettingsContent(
                state = GitCredentialUiState(
                    credentials = if (empty) emptyList() else listOf(
                        GitCredential("git.example.com:8443", "ci-bot"),
                        GitCredential("github.com", "octocat"),
                    ),
                    operation = operation,
                ),
                actions = GitCredentialSettingsActions(),
            )
        }
    }
}
