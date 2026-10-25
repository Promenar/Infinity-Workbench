package com.promenar.nexara.ui.linux.terminal

import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.jobs.BackgroundJobState
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxTerminalLocation
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.promenar.nexara.ui.common.ConfirmDialog
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.linux.output.AnsiPalette
import com.promenar.nexara.ui.linux.output.AnsiParser
import com.promenar.nexara.ui.linux.output.OutputDocument
import com.promenar.nexara.ui.linux.output.OutputLineColors
import com.promenar.nexara.ui.linux.output.outputLine
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

/**
 * 本地 Linux 终端页。
 *
 * @param request 非 null 时进入页面即新开一个会话（可带自动输入的命令）；null 时展示已有会话，没有会话则在 `/root` 新开一个。
 * @param onOpenEnvironment 环境未就绪时“前往 Linux 环境”按钮的回调。
 */
@Composable
fun LinuxTerminalScreen(
    request: LinuxTerminalRequest?,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as NexaraApplication
    val viewModel: LinuxTerminalViewModel = viewModel(key = "linux-terminal", factory = LinuxTerminalViewModel.factory(app, request))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bridge = rememberTerminalBridge()

    LinuxTerminalContent(
        state = state,
        atBottom = bridge.atBottom,
        onBack = onBack,
        onOpenEnvironment = onOpenEnvironment,
        onNewTerminal = viewModel::openNew,
        onRetry = viewModel::retryOpen,
        onSelect = viewModel::select,
        onClose = viewModel::close,
        onKey = viewModel::sendKey,
        onToggleCtrl = viewModel::toggleCtrl,
        onToggleAlt = viewModel::toggleAlt,
        onToggleKeyboard = { visible -> if (visible) bridge.hideKeyboard() else bridge.showKeyboard() },
        onScrollToBottom = bridge::scrollToBottom,
        shellPane = { sessionId, modifier ->
            LocalTerminalPane(
                sessionId = sessionId,
                terminals = viewModel.terminals,
                bridge = bridge,
                readControl = viewModel::consumeCtrl,
                readAlt = viewModel::consumeAlt,
                modifier = modifier,
            )
        },
    )
}

/**
 * 无状态界面。[shellPane] 渲染真实终端视图；截图预览中传入示例文本占位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinuxTerminalContent(
    state: LinuxTerminalUiState,
    atBottom: Boolean,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onNewTerminal: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onKey: (TerminalExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleKeyboard: (visible: Boolean) -> Unit,
    onScrollToBottom: () -> Unit,
    shellPane: @Composable (sessionId: String, modifier: Modifier) -> Unit,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    val selected = state.selectedTab
    var confirmClose by remember { mutableStateOf<TerminalTab.Shell?>(null) }
    // 只读任务页签自己管理滚动位置；切换页签时重置。
    val jobScroll = remember(selected?.key) { ScrollState(0) }
    var jobFollow by remember(selected?.key) { mutableStateOf(true) }

    NexaraPageLayout(
        title = stringResource(R.string.linux_terminal_title),
        onBack = onBack,
        scrollable = false,
        modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_SCREEN),
        actions = {
            if (state.ready) {
                IconButton(onClick = onNewTerminal, enabled = !state.opening, modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_NEW)) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.linux_terminal_new))
                }
                if (selected != null) {
                    IconButton(
                        onClick = {
                            if (selected is TerminalTab.Shell && selected.info.running) confirmClose = selected else onClose(selected.key)
                        },
                        modifier = Modifier.testTag(UiTags.LINUX_TERMINAL_CLOSE),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.linux_terminal_close))
                    }
                }
            }
        },
    ) {
        Subtitle(state)
        if (!state.ready) {
            EnvironmentNotReady(state.runtime, onOpenEnvironment)
            return@NexaraPageLayout
        }
        TabsRow(state, onSelect)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = NexaraSpacing.Small),
        ) {
            when (selected) {
                is TerminalTab.Shell -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    shellPane(selected.info.id, Modifier.fillMaxSize().padding(NexaraSpacing.XSmall))
                }
                is TerminalTab.AgentJob -> JobOutputPane(
                    text = state.jobTails[selected.job.id]?.text,
                    scrollState = jobScroll,
                    follow = jobFollow,
                    onFollowChange = { jobFollow = it },
                    modifier = Modifier.fillMaxSize(),
                )
                null -> OpeningOrFailed(state.opening, state.openFailed, onRetry)
            }
            val showBottom = when (selected) {
                is TerminalTab.Shell -> !atBottom
                is TerminalTab.AgentJob -> !jobFollow
                null -> false
            }
            if (showBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        if (selected is TerminalTab.AgentJob) jobFollow = true else onScrollToBottom()
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NexaraSpacing.Medium)
                        .size(NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.LINUX_TERMINAL_BOTTOM),
                ) {
                    Icon(Icons.Rounded.KeyboardDoubleArrowDown, contentDescription = stringResource(R.string.linux_terminal_bottom))
                }
            }
        }
        when (selected) {
            is TerminalTab.Shell -> {
                if (!selected.info.running) ExitedBanner(selected, onClose)
                ExtraKeys(
                    modifiers = state.modifiers,
                    enabled = selected.info.running,
                    imeVisible = imeVisible,
                    onKey = onKey,
                    onToggleCtrl = onToggleCtrl,
                    onToggleAlt = onToggleAlt,
                    onToggleKeyboard = { onToggleKeyboard(imeVisible) },
                )
                FooterNote(R.string.linux_terminal_footer)
            }
            is TerminalTab.AgentJob -> {
                JobStatusLine(selected, state.jobTails[selected.job.id]?.truncated == true)
                FooterNote(R.string.linux_terminal_job_footer)
            }
            null -> FooterNote(R.string.linux_terminal_footer)
        }
    }

    ConfirmDialog(
        show = confirmClose != null,
        onDismiss = { confirmClose = null },
        onConfirm = {
            confirmClose?.let { onClose(it.key) }
            confirmClose = null
        },
        title = stringResource(R.string.linux_terminal_close_title, confirmClose?.info?.ordinal ?: 0),
        description = stringResource(R.string.linux_terminal_close_message),
        confirmLabel = stringResource(R.string.linux_terminal_close_confirm),
    )
}

@Composable
private fun Subtitle(state: LinuxTerminalUiState) {
    val environment = state.environmentLabel ?: return
    val workspaceSessionId = when (val tab = state.selectedTab) {
        is TerminalTab.Shell -> (tab.info.location as? LinuxTerminalLocation.SessionWorkspace)?.sessionId
        is TerminalTab.AgentJob -> tab.job.sessionId
        null -> null
    }
    val directory = ((state.selectedTab as? TerminalTab.Shell)?.info?.location as? LinuxTerminalLocation.Directory)?.path
    val text = when {
        workspaceSessionId != null -> stringResource(
            R.string.linux_terminal_subtitle_workspace,
            environment,
            state.sessionTitles[workspaceSessionId] ?: stringResource(R.string.linux_terminal_workspace_untitled),
        )
        directory != null -> stringResource(R.string.linux_terminal_subtitle_directory, environment, directory)
        else -> environment
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = NexaraSpacing.Small),
    )
}

@Composable
private fun TabsRow(state: LinuxTerminalUiState, onSelect: (String) -> Unit) {
    if (state.tabs.isEmpty()) return
    val selectedKey = state.selectedTab?.key
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        contentPadding = PaddingValues(vertical = NexaraSpacing.XSmall),
    ) {
        items(state.tabs, key = { it.key }) { tab ->
            val label = when (tab) {
                is TerminalTab.Shell -> stringResource(
                    if (tab.info.running) R.string.linux_terminal_tab else R.string.linux_terminal_tab_exited,
                    tab.info.ordinal,
                )
                is TerminalTab.AgentJob -> stringResource(R.string.linux_terminal_job_tab, tab.ordinal)
            }
            val tag = when (tab) {
                is TerminalTab.Shell -> UiTags.linuxTerminalTab(tab.info.id)
                is TerminalTab.AgentJob -> UiTags.linuxTerminalJobTab(tab.job.id)
            }
            FilterChip(
                selected = tab.key == selectedKey,
                onClick = { onSelect(tab.key) },
                label = { Text(label, maxLines = 1) },
                leadingIcon = if (tab is TerminalTab.AgentJob) {
                    {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (tab.job.state == BackgroundJobState.RUNNING) MaterialTheme.nexaraDomainColors.warning
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(tag),
            )
        }
    }
}

@Composable
private fun OpeningOrFailed(opening: Boolean, failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium, Alignment.CenterVertically),
    ) {
        if (failed && !opening) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.linux_terminal_open_failed), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_TERMINAL_RETRY)) {
                Text(stringResource(R.string.linux_terminal_retry))
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(NexaraSpacing.XLarge))
            Text(stringResource(R.string.linux_terminal_opening), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.EnvironmentNotReady(runtime: LinuxRuntimeState?, onOpenEnvironment: () -> Unit) {
    val (title, description) = when (runtime) {
        null -> R.string.linux_terminal_state_checking to null
        is LinuxRuntimeState.NotInstalled -> R.string.linux_terminal_state_not_installed to R.string.linux_terminal_state_not_installed_desc
        is LinuxRuntimeState.Installing -> R.string.linux_terminal_state_installing to R.string.linux_terminal_state_installing_desc
        is LinuxRuntimeState.InstallInterrupted -> R.string.linux_terminal_state_interrupted to R.string.linux_terminal_state_interrupted_desc
        is LinuxRuntimeState.Maintaining -> R.string.linux_terminal_state_maintaining to null
        is LinuxRuntimeState.Broken -> R.string.linux_terminal_state_broken to R.string.linux_terminal_state_broken_desc
        is LinuxRuntimeState.Unavailable -> when (runtime.reason) {
            LinuxUnavailableReason.DISTRIBUTION -> R.string.linux_terminal_state_distribution to R.string.linux_terminal_state_distribution_desc
            LinuxUnavailableReason.UNSUPPORTED_ABI -> R.string.linux_terminal_state_abi to null
        }
        is LinuxRuntimeState.Ready -> R.string.linux_terminal_opening to null
    }
    val canOpenEnvironment = runtime != null && runtime !is LinuxRuntimeState.Unavailable
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.LINUX_TERMINAL_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Rounded.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        description?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (canOpenEnvironment) {
            Button(
                onClick = onOpenEnvironment,
                modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(UiTags.LINUX_TERMINAL_OPEN_ENVIRONMENT),
            ) {
                Text(stringResource(R.string.linux_terminal_open_environment))
            }
        }
    }
    FooterNote(R.string.linux_terminal_footer)
}

@Composable
private fun ExitedBanner(tab: TerminalTab.Shell, onClose: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.Small),
    ) {
        Row(
            modifier = Modifier.padding(start = NexaraSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tab.info.exitCode?.let { stringResource(R.string.linux_terminal_exited, it) }
                    ?: stringResource(R.string.linux_terminal_exited_unknown),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onClose(tab.key) }, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.linux_terminal_close_confirm))
            }
        }
    }
}

/**
 * 额外按键。按键触控区不小于 48dp：宽度足够放下 8 个键时排成“Esc Tab Ctrl Alt ← ↓ ↑ →”一行，
 * 否则拆为两行四键；第三行是 `| / - ~` 与“键盘”切换。
 */
@Composable
private fun ExtraKeys(
    modifiers: TerminalModifiers,
    enabled: Boolean,
    imeVisible: Boolean,
    onKey: (TerminalExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val gap = 6.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= NexaraSpacing.MinimumTouchTarget * 8 + gap * 7
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            val modifierKeys: @Composable RowScope.() -> Unit = {
                KeyButton("Esc", TerminalExtraKey.ESC.id, enabled) { onKey(TerminalExtraKey.ESC) }
                KeyButton("Tab", TerminalExtraKey.TAB.id, enabled) { onKey(TerminalExtraKey.TAB) }
                KeyButton("Ctrl", "ctrl", enabled, selected = modifiers.ctrl, onClick = onToggleCtrl)
                KeyButton("Alt", "alt", enabled, selected = modifiers.alt, onClick = onToggleAlt)
            }
            // 方向键是按钮符号而非输入字符；JetBrains Mono 的箭头只占一格宽度，在按键上显得过小。
            val arrowKeys: @Composable RowScope.() -> Unit = {
                KeyButton("←", TerminalExtraKey.LEFT.id, enabled, monospace = false, description = stringResource(R.string.linux_terminal_key_left)) { onKey(TerminalExtraKey.LEFT) }
                KeyButton("↓", TerminalExtraKey.DOWN.id, enabled, monospace = false, description = stringResource(R.string.linux_terminal_key_down)) { onKey(TerminalExtraKey.DOWN) }
                KeyButton("↑", TerminalExtraKey.UP.id, enabled, monospace = false, description = stringResource(R.string.linux_terminal_key_up)) { onKey(TerminalExtraKey.UP) }
                KeyButton("→", TerminalExtraKey.RIGHT.id, enabled, monospace = false, description = stringResource(R.string.linux_terminal_key_right)) { onKey(TerminalExtraKey.RIGHT) }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { modifierKeys(); arrowKeys() }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { modifierKeys() }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { arrowKeys() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                KeyButton("|", TerminalExtraKey.PIPE.id, enabled) { onKey(TerminalExtraKey.PIPE) }
                KeyButton("/", TerminalExtraKey.SLASH.id, enabled) { onKey(TerminalExtraKey.SLASH) }
                KeyButton("-", TerminalExtraKey.DASH.id, enabled) { onKey(TerminalExtraKey.DASH) }
                KeyButton("~", TerminalExtraKey.TILDE.id, enabled) { onKey(TerminalExtraKey.TILDE) }
                KeyButton(
                    label = stringResource(R.string.linux_terminal_keyboard),
                    id = "keyboard",
                    enabled = enabled,
                    selected = imeVisible,
                    weight = 2f,
                    monospace = false,
                    tag = UiTags.LINUX_TERMINAL_KEYBOARD,
                    onClick = onToggleKeyboard,
                )
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    id: String,
    enabled: Boolean,
    selected: Boolean = false,
    description: String? = null,
    weight: Float = 1f,
    monospace: Boolean = true,
    tag: String = UiTags.linuxTerminalKey(id),
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier
            .weight(weight)
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .testTag(tag)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = if (monospace) NexaraMonospace else null,
                maxLines = 1,
                color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else colors.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun JobStatusLine(tab: TerminalTab.AgentJob, truncated: Boolean) {
    val job = tab.job
    val status = when {
        job.state == BackgroundJobState.RUNNING -> stringResource(R.string.linux_terminal_job_running, job.summary)
        job.exitCode != null -> stringResource(R.string.linux_terminal_job_ended, job.exitCode)
        else -> stringResource(R.string.linux_terminal_job_ended_unknown)
    }
    Text(
        text = if (truncated) status + " · " + stringResource(R.string.linux_terminal_job_truncated) else status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.XSmall),
    )
}

@Composable
private fun FooterNote(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small),
    )
}

/**
 * 只读的等宽输出区：解析 ANSI 颜色，跟随尾部直到用户向上翻看。也用于预览中代替 TerminalView。
 */
@Composable
fun JobOutputPane(
    text: String?,
    scrollState: ScrollState,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = if (scheme.background.luminance() < 0.5f) AnsiPalette.Dark else AnsiPalette.Light
    val colors = OutputLineColors(
        foreground = scheme.onSurface,
        stderr = scheme.error,
        separator = scheme.onSurfaceVariant,
        match = scheme.tertiaryContainer,
        onMatch = scheme.onTertiaryContainer,
        currentMatch = scheme.primary,
        onCurrentMatch = scheme.onPrimary,
    )
    val annotated = remember(text, palette, colors) {
        val document = OutputDocument.of(AnsiParser.parse(text.orEmpty()))
        if (document.text.isEmpty()) null
        else outputLine(document, 0 until document.text.length, emptyList(), null, palette, colors)
    }
    LaunchedEffect(scrollState, follow) {
        if (!follow) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to scrollState.value }.collect { (scrolling, value) ->
            if (scrolling) onFollowChange(value >= scrollState.maxValue - 8)
        }
    }
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.testTag(UiTags.LINUX_TERMINAL_JOB_OUTPUT),
    ) {
        if (annotated == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.linux_terminal_job_waiting), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        } else {
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace, color = scheme.onSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(NexaraSpacing.Medium),
            )
        }
    }
}

/** 预览与截图测试使用：带固定滚动状态的只读输出区。 */
@Composable
fun TerminalTextPreviewPane(text: String, modifier: Modifier = Modifier) {
    JobOutputPane(text = text, scrollState = rememberScrollState(), follow = false, onFollowChange = {}, modifier = modifier)
}
