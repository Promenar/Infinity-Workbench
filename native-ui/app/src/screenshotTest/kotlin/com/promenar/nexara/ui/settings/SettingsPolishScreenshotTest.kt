package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshHost
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

/** 工具、MCP、SSH 操作面板与 Git 凭据编辑面板的展示截图；只消费无状态组件。 */
@PreviewTest
@Preview(name = "内置工具 深色中文", widthDp = 412, heightDp = 2200, locale = "zh-rCN")
@Composable
fun builtinToolsDarkPreview() = PolishTheme(dark = true) { BuiltinToolsPreview() }

@PreviewTest
@Preview(name = "内置工具 浅色英文", widthDp = 412, heightDp = 2400, locale = "en")
@Composable
fun builtinToolsLightPreview() = PolishTheme(dark = false) { BuiltinToolsPreview() }

@PreviewTest
@Preview(name = "内置工具 字号130", widthDp = 360, heightDp = 3000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun builtinToolsLargeTextPreview() = PolishTheme(dark = true) { BuiltinToolsPreview() }

@PreviewTest
@Preview(name = "MCP 服务器 深色中文", widthDp = 412, heightDp = 700, locale = "zh-rCN")
@Composable
fun mcpServersDarkPreview() = PolishTheme(dark = true) { McpPreview(empty = false) }

@PreviewTest
@Preview(name = "MCP 服务器 浅色英文 空", widthDp = 412, heightDp = 420, locale = "en")
@Composable
fun mcpServersEmptyPreview() = PolishTheme(dark = false) { McpPreview(empty = true) }

@PreviewTest
@Preview(name = "旧版自定义工具 深色中文", widthDp = 412, heightDp = 400, locale = "zh-rCN")
@Composable
fun legacyToolsDarkPreview() = PolishTheme(dark = true) {
    LegacyUserToolsSection(
        tools = listOf(
            SkillInfo("custom-1", "天气查询", "按城市名返回当前天气。", enabled = true),
            SkillInfo("custom-2", "汇率换算", "", enabled = false),
        ),
        onToggle = {}, onEdit = {}, onDelete = {},
    )
}

@PreviewTest
@Preview(name = "SSH 主机操作 深色中文", widthDp = 412, heightDp = 760, locale = "zh-rCN")
@Composable
fun sshHostActionsDarkPreview() = PolishTheme(dark = true) {
    SshHostActionsContent(
        host = SshHost(
            id = "preview-production", alias = "production", hostname = "server.example.com", username = "deploy",
            trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:preview-public-fingerprint",
            keepTerminal = true, localForwardPort = 8080, forwardPort = 3000,
        ),
        forwardPort = 8080, enabled = true,
        onTerminal = {}, onVerify = {}, onCopyPublicKey = {}, onEdit = {}, onToggleForward = {}, onDelete = {},
    )
}

@PreviewTest
@Preview(name = "SSH 主机操作 浅色英文 未信任", widthDp = 412, heightDp = 620, locale = "en")
@Composable
fun sshHostActionsLightPreview() = PolishTheme(dark = false) {
    SshHostActionsContent(
        host = SshHost(
            id = "preview-lab", alias = "homelab", hostname = "long-hostname.home-network.example.com",
            username = "developer", authType = SshAuthType.PASSWORD,
        ),
        forwardPort = null, enabled = true,
        onTerminal = {}, onVerify = {}, onCopyPublicKey = {}, onEdit = {}, onToggleForward = {}, onDelete = {},
    )
}

@PreviewTest
@Preview(name = "Git 凭据编辑 新增 深色中文", widthDp = 412, heightDp = 560, locale = "zh-rCN")
@Composable
fun gitCredentialEditorNewPreview() = PolishTheme(dark = true) {
    GitCredentialEditor(
        host = "", onHostChange = {}, username = "", onUsernameChange = {}, token = "", onTokenChange = {},
        hostLocked = false, operation = GitCredentialOperation.Idle, onCancel = {}, onSave = {},
    )
}

@PreviewTest
@Preview(name = "Git 凭据编辑 更换令牌 浅色英文 错误", widthDp = 412, heightDp = 640, locale = "en")
@Composable
fun gitCredentialEditorReplacePreview() = PolishTheme(dark = false) {
    GitCredentialEditor(
        host = "github.com", onHostChange = {}, username = "octocat", onUsernameChange = {}, token = "preview", onTokenChange = {},
        hostLocked = true, operation = GitCredentialOperation.Error(GitCredentialErrorCode.INVALID_TOKEN),
        onCancel = {}, onSave = {},
    )
}

@Composable
private fun PolishTheme(dark: Boolean, content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(NexaraSpacing.Large)) { content() }
        }
    }
}

@Composable
private fun BuiltinToolsPreview() {
    val ids = listOf(
        "web_search" to (R.string.skill_web_search to R.string.skill_web_search_desc),
        "web_fetch" to (R.string.skill_web_fetch to R.string.skill_web_fetch_desc),
        "calculator" to (R.string.skill_calculator to R.string.skill_calculator_desc),
        "image_generation" to (R.string.skill_image_generation to R.string.skill_image_generation_desc),
        "file_read" to (R.string.skill_file_read to R.string.skill_file_read_desc),
        "file_write" to (R.string.skill_file_write to R.string.skill_file_write_desc),
        "file_list" to (R.string.skill_file_list to R.string.skill_file_list_desc),
        "file_search" to (R.string.skill_file_search to R.string.skill_file_search_desc),
        "file_diff" to (R.string.skill_file_diff to R.string.skill_file_diff_desc),
        "file_patch" to (R.string.skill_file_patch to R.string.skill_file_patch_desc),
        "exec_js" to (R.string.skill_exec_js to R.string.skill_exec_js_desc),
        "run_script" to (R.string.skill_run_script to R.string.skill_run_script_desc),
        "initialize_plan" to (R.string.skill_initialize_plan to R.string.skill_initialize_plan_desc),
        "update_plan" to (R.string.skill_update_plan to R.string.skill_update_plan_desc),
        "get_plan" to (R.string.skill_get_plan to R.string.skill_get_plan_desc),
        "drop_plan" to (R.string.skill_drop_plan to R.string.skill_drop_plan_desc),
    )
    val tools = ids.mapIndexed { index, (id, res) ->
        SkillInfo(id, stringResource(res.first), stringResource(res.second), enabled = index != 3)
    }
    BuiltinToolsSections(tools = tools, onToggle = {}, onConfigureSearch = {})
}

@Composable
private fun McpPreview(empty: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large)) {
        McpServersSection(
            servers = if (empty) emptyList() else listOf(
                McpServerUiModel(
                    id = "docs", name = "Docs", url = "https://mcp.example.com/sse", type = "sse",
                    isConnected = true, isEnabled = true, isDefault = false, callIntervalMs = 0,
                    tools = listOf("search_docs", "fetch_page"),
                ),
                McpServerUiModel(
                    id = "lab", name = "Homelab", url = "https://lab.example.com/mcp", type = "http",
                    isConnected = false, isEnabled = false, isDefault = false, callIntervalMs = 0,
                    tools = emptyList(),
                ),
            ),
            expandedServerId = if (empty) null else "docs",
            onToggleExpand = {}, onToggleEnabled = { _, _ -> }, onDelete = {}, onSync = {}, onAdd = {},
        )
    }
}
