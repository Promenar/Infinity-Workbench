package com.promenar.infinity.ui.chat

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.usecase.ExportSessionUseCase
import com.promenar.infinity.ui.chat.command.SlashBlockReason
import com.promenar.infinity.ui.chat.command.SlashCommand
import com.promenar.infinity.ui.chat.command.SlashCommandAction
import com.promenar.infinity.ui.chat.command.SlashCommandCatalog
import com.promenar.infinity.ui.chat.command.SlashCommandContext
import com.promenar.infinity.ui.chat.command.SlashCommandController
import com.promenar.infinity.ui.chat.command.SlashCommandRejection
import com.promenar.infinity.ui.chat.command.SlashCommandViewModel
import com.promenar.infinity.ui.chat.command.rememberBuiltinSlashCommands
import com.promenar.infinity.ui.common.SnackbarType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天页的斜杠命令入口：命令目录、点选与发送拦截。 */
internal class ChatSlashCommands(
    val commands: List<SlashCommand>,
    val onPaletteShown: () -> Unit,
    val onPick: (SlashCommand) -> Unit,
    /** 代替直接发送：普通消息与可用技能命令调用 [send]，内置命令在本机执行。 */
    val submit: (text: String, send: () -> Unit) -> Unit,
)

/**
 * 把命令动作映射到聊天页现有的 ViewModel 方法、对话框与轻提示。
 * 不可逆的清空历史只打开现有确认框；结果统一用 snackbar 反馈。
 */
@Composable
internal fun rememberChatSlashCommands(
    sessionId: String,
    chatViewModel: ChatViewModel,
    uiState: ChatUiState,
    inputText: String,
    isCompressing: Boolean,
    requestOverlay: (ChatOverlay) -> Unit,
    onNavigateToSession: (String) -> Unit,
    showFeedback: (String, SnackbarType) -> Unit,
): ChatSlashCommands {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val slashViewModel: SlashCommandViewModel = viewModel(
        factory = SlashCommandViewModel.factory(context.applicationContext as Application),
    )
    val skills by slashViewModel.skills.collectAsStateWithLifecycle()
    val builtins = rememberBuiltinSlashCommands()
    val commands = remember(builtins, skills) { SlashCommandCatalog.build(builtins, skills) }

    val latestState by rememberUpdatedState(uiState)
    val latestCompressing by rememberUpdatedState(isCompressing)
    val latestInput by rememberUpdatedState(inputText)
    val latestSessionId by rememberUpdatedState(sessionId)
    val latestFeedback by rememberUpdatedState(showFeedback)

    // 导出：先生成内容，再让用户在系统文件选择器中选保存位置。
    val pendingExport = remember { arrayOfNulls<ExportSessionUseCase.ExportResult>(1) }
    val onExportTarget: (Uri?) -> Unit = { uri ->
        val export = pendingExport[0]
        pendingExport[0] = null
        if (uri != null && export != null) {
            scope.launch {
                val saved = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(export.content.toByteArray(Charsets.UTF_8)) } != null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (saved) latestFeedback(resources.getString(R.string.slash_feedback_exported, export.fileName), SnackbarType.SUCCESS)
                else latestFeedback(resources.getString(R.string.slash_feedback_export_failed), SnackbarType.ERROR)
            }
        }
    }
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), onExportTarget)
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), onExportTarget)

    val perform: (SlashCommandAction) -> Unit = { action ->
        val feedback = latestFeedback
        when (action) {
            SlashCommandAction.CreateSession -> scope.launch {
                val created = chatViewModel.createSessionForCurrentAgent()
                if (created != null) onNavigateToSession(created)
                else feedback(resources.getString(R.string.slash_feedback_new_failed), SnackbarType.ERROR)
            }
            is SlashCommandAction.Rename -> {
                chatViewModel.renameSession(action.title)
                feedback(resources.getString(R.string.slash_feedback_renamed, action.title), SnackbarType.SUCCESS)
            }
            SlashCommandAction.OpenRenameDialog -> requestOverlay(ChatOverlay.RenameSession)
            is SlashCommandAction.SetSessionPrompt -> scope.launch {
                val saved = chatViewModel.saveCustomPrompt(action.prompt).isSuccess
                feedback(
                    resources.getString(if (saved) R.string.slash_feedback_prompt_saved else R.string.slash_feedback_prompt_failed),
                    if (saved) SnackbarType.SUCCESS else SnackbarType.ERROR,
                )
            }
            SlashCommandAction.OpenPromptEditor -> requestOverlay(ChatOverlay.PromptEditor)
            SlashCommandAction.Compact -> {
                chatViewModel.compactNow()
                feedback(resources.getString(R.string.slash_feedback_compacting), SnackbarType.INFO)
            }
            SlashCommandAction.ConfirmClearHistory -> requestOverlay(ChatOverlay.ClearHistory)
            is SlashCommandAction.SetExecutionMode -> {
                chatViewModel.updateExecutionMode(ExecutionModeCodec.serialize(action.mode))
                val label = resources.getString(
                    when (action.mode) {
                        ExecutionMode.WORKSPACE -> R.string.common_mode_workspace
                        ExecutionMode.FULL_ACCESS -> R.string.common_mode_full_access
                    },
                )
                feedback(resources.getString(R.string.slash_feedback_mode, label), SnackbarType.SUCCESS)
            }
            SlashCommandAction.OpenToolSettings -> requestOverlay(ChatOverlay.SessionTools)
            is SlashCommandAction.SetToolsEnabled -> {
                chatViewModel.toggleTool("toolsEnabled", action.enabled)
                feedback(
                    resources.getString(if (action.enabled) R.string.slash_feedback_tools_on else R.string.slash_feedback_tools_off),
                    SnackbarType.SUCCESS,
                )
            }
            is SlashCommandAction.Export -> scope.launch {
                val export = try {
                    chatViewModel.exportSession(latestSessionId, action.format)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (export == null) {
                    feedback(resources.getString(R.string.slash_feedback_export_failed), SnackbarType.ERROR)
                } else {
                    pendingExport[0] = export
                    val launcher = if (action.format == ExportSessionUseCase.Format.MARKDOWN) markdownLauncher else textLauncher
                    try {
                        launcher.launch(export.fileName)
                    } catch (_: android.content.ActivityNotFoundException) {
                        pendingExport[0] = null
                        feedback(resources.getString(R.string.slash_feedback_export_failed), SnackbarType.ERROR)
                    }
                }
            }
            is SlashCommandAction.Reject -> feedback(
                when (action.reason) {
                    SlashCommandRejection.COMPACT_GENERATING -> resources.getString(R.string.chat_context_compress_unavailable_generating)
                    SlashCommandRejection.COMPACT_RUNNING -> resources.getString(R.string.chat_context_compress_unavailable_running)
                    SlashCommandRejection.NOTHING_TO_CLEAR -> resources.getString(R.string.slash_reject_nothing_to_clear)
                    SlashCommandRejection.NOTHING_TO_EXPORT -> resources.getString(R.string.slash_reject_nothing_to_export)
                    SlashCommandRejection.UNKNOWN_MODE -> resources.getString(R.string.slash_reject_unknown_mode, action.argument)
                    SlashCommandRejection.UNKNOWN_TOGGLE -> resources.getString(R.string.slash_reject_unknown_toggle)
                    SlashCommandRejection.UNKNOWN_EXPORT_FORMAT -> resources.getString(R.string.slash_reject_unknown_format)
                },
                SnackbarType.INFO,
            )
        }
    }

    val latestPerform by rememberUpdatedState(perform)
    val controller = remember(chatViewModel, slashViewModel) {
        SlashCommandController(
            scope = scope,
            scanSkills = { slashViewModel.scan(latestSessionId) },
            context = {
                val state = latestState
                SlashCommandContext(
                    isGenerating = state.isGenerating,
                    isCompressing = latestCompressing,
                    hasMessages = state.messages.isNotEmpty(),
                    toolsEnabled = state.session?.options?.toolsEnabled ?: true,
                    executionMode = ExecutionModeCodec.parseOrWorkspace(state.session?.executionMode),
                )
            },
            perform = { latestPerform(it) },
            block = { reason, name ->
                val message = when (reason) {
                    SlashBlockReason.DISABLED -> resources.getString(R.string.slash_block_disabled, name)
                    SlashBlockReason.PENDING_TRUST -> resources.getString(R.string.slash_block_pending_trust, name)
                    SlashBlockReason.BROKEN -> resources.getString(R.string.slash_block_broken, name)
                    SlashBlockReason.SCAN_FAILED -> resources.getString(R.string.slash_block_scan_failed)
                }
                latestFeedback(message, SnackbarType.INFO)
            },
            setInput = chatViewModel::updateInputText,
        )
    }
    return remember(commands, controller) {
        ChatSlashCommands(
            commands = commands,
            onPaletteShown = { slashViewModel.refresh(latestSessionId) },
            onPick = { command -> controller.pick(command, latestInput) },
            submit = controller::submit,
        )
    }
}
