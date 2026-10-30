package com.promenar.infinity.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.ui.settings.GitCredentialSettingsViewModel
import com.promenar.infinity.ui.settings.GitHubLoginSheet
import com.promenar.infinity.ui.settings.GitHubLoginSheetActions
import com.promenar.infinity.ui.settings.GitHubLoginState

/**
 * 保密输入卡上的“改用浏览器登录 GitHub”：复用设置 › Git 凭据的设备码登录与授权面板，
 * 登录成功后 github.com 的凭据经同一存储保存，并通知协调器结束等待中的请求。未配置浏览器登录时直接关闭。
 */
@Composable
internal fun SecretGitHubLoginHost(onLoggedIn: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: GitCredentialSettingsViewModel = viewModel(
        key = "secret_input_github_login",
        factory = GitCredentialSettingsViewModel.factory(context.applicationContext as android.app.Application),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        if (viewModel.state.value.githubLoginAvailable) viewModel.startGitHubLogin() else onClose()
    }
    val loggedIn = state.githubLogin is GitHubLoginState.Success
    LaunchedEffect(loggedIn) {
        if (loggedIn) onLoggedIn()
    }
    val close = {
        viewModel.closeGitHubLogin()
        onClose()
    }
    GitHubLoginSheet(
        state = state.githubLogin,
        actions = GitHubLoginSheetActions(
            onCancel = close,
            onRetry = viewModel::startGitHubLogin,
            onOpenBrowser = { uri, code -> openGitHubDeviceVerification(context, uri, code) },
            onDone = close,
        ),
    )
}

/** 复制设备码并用系统浏览器打开验证页；只接受 https 的 github.com 地址（与设置页一致）。 */
private fun openGitHubDeviceVerification(context: Context, verificationUri: String, userCode: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.github_login_code_label), userCode))
    val uri = Uri.parse(verificationUri).takeIf { it.scheme == "https" && it.host == "github.com" }
        ?: Uri.parse("https://github.com/login/device")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // 没有浏览器时代码已复制，用户可在其它设备打开验证页。
    }
}
