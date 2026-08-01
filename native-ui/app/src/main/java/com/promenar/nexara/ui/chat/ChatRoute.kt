package com.promenar.nexara.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_ASKED
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_PREFS
import com.promenar.nexara.background.generation.NotificationPermissionOverlayState
import com.promenar.nexara.background.generation.NotificationPermissionPromptState
import com.promenar.nexara.background.generation.shouldExplainNotificationPermission
import com.promenar.nexara.background.generation.shouldShowNotificationPermissionDialog
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.domain.repository.ITaskRepository
import com.promenar.nexara.domain.repository.PlanPatchOp
import com.promenar.nexara.ui.common.EditorMode
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraSnackbarData
import com.promenar.nexara.ui.common.SnackbarType
import com.promenar.nexara.ui.common.UnifiedPromptEditor
import com.promenar.nexara.ui.testing.UiTags
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

internal sealed interface ChatOverlay {
    data object Workspace : ChatOverlay
    data object ModelSettings : ChatOverlay
    data object PromptEditor : ChatOverlay
    data object ClearHistory : ChatOverlay
    data object DeleteSession : ChatOverlay
    data object RenameSession : ChatOverlay
    data class Truncate(val messageId: String) : ChatOverlay
}

internal data class ChatOverlayQueueState(
    val active: ChatOverlay? = null,
    val queued: ChatOverlay? = null,
)

/** 聊天页只展示一个模态层，冲突时保留最早的一个待展示请求。 */
internal fun enqueueChatOverlay(
    state: ChatOverlayQueueState,
    requested: ChatOverlay,
    hasPendingNotificationPermission: Boolean,
): ChatOverlayQueueState = when {
    state.active == null && !hasPendingNotificationPermission -> state.copy(active = requested)
    state.queued == null -> state.copy(queued = requested)
    else -> state
}

internal fun advanceChatOverlay(
    state: ChatOverlayQueueState,
    hasPendingNotificationPermission: Boolean,
): ChatOverlayQueueState = if (
    state.active == null && state.queued != null && !hasPendingNotificationPermission
) {
    ChatOverlayQueueState(active = state.queued)
} else {
    state
}

private const val TRUNCATE_OVERLAY_PREFIX = "truncate:"

internal fun saveChatOverlay(overlay: ChatOverlay?): String? = when (overlay) {
    null -> null
    ChatOverlay.Workspace -> "workspace"
    ChatOverlay.ModelSettings -> "model_settings"
    ChatOverlay.PromptEditor -> "prompt_editor"
    ChatOverlay.ClearHistory -> "clear_history"
    ChatOverlay.DeleteSession -> "delete_session"
    ChatOverlay.RenameSession -> "rename_session"
    is ChatOverlay.Truncate -> TRUNCATE_OVERLAY_PREFIX + overlay.messageId
}

internal fun restoreChatOverlay(token: String?): ChatOverlay? = when {
    token == "workspace" -> ChatOverlay.Workspace
    token == "model_settings" -> ChatOverlay.ModelSettings
    token == "prompt_editor" -> ChatOverlay.PromptEditor
    token == "clear_history" -> ChatOverlay.ClearHistory
    token == "delete_session" -> ChatOverlay.DeleteSession
    token == "rename_session" -> ChatOverlay.RenameSession
    token?.startsWith(TRUNCATE_OVERLAY_PREFIX) == true ->
        ChatOverlay.Truncate(token.removePrefix(TRUNCATE_OVERLAY_PREFIX))
    else -> null
}

internal enum class NotificationPermissionDisposition {
    GRANTED,
    SHOW_EXPLANATION,
    WAIT_FOR_SYSTEM_RESULT,
    REJECTED,
}

internal fun notificationPermissionDisposition(
    requestTaskId: String,
    granted: Boolean,
    shouldExplain: Boolean,
    inFlightTaskId: String?,
): NotificationPermissionDisposition = when {
    granted -> NotificationPermissionDisposition.GRANTED
    inFlightTaskId == requestTaskId -> NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT
    shouldExplain -> NotificationPermissionDisposition.SHOW_EXPLANATION
    else -> NotificationPermissionDisposition.REJECTED
}

internal fun notificationPermissionResultTaskId(
    inFlightTaskId: String?,
    explanationTaskId: String?,
): String? = inFlightTaskId ?: explanationTaskId

/** Chat 路由的显式依赖边界；测试可替换工厂和系统端口，无需全局可变注册表。 */
data class ChatRouteDependencies(
    val viewModelFactory: ViewModelProvider.Factory,
    val taskRepository: ITaskRepository,
    val copyToClipboard: (Context, String) -> Unit = ::copyTextToClipboard,
) {
    companion object {
        fun production(application: NexaraApplication): ChatRouteDependencies =
            ChatRouteDependencies(
                viewModelFactory = ChatViewModel.factory(application),
                taskRepository = application.taskRepository,
            )

        fun withGenerationCoordinator(
            application: NexaraApplication,
            generationCoordinator: com.promenar.nexara.domain.generation.GenerationCoordinator,
        ): ChatRouteDependencies = ChatRouteDependencies(
            viewModelFactory = ChatViewModel.factory(
                application = application,
                generationCoordinatorOverride = generationCoordinator,
            ),
            taskRepository = application.taskRepository,
        )
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nexara", text))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatRoute(
    sessionId: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (String) -> Unit = {},
    dependencies: ChatRouteDependencies? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as NexaraApplication
    val resolvedDependencies = remember(application, dependencies) {
        dependencies ?: ChatRouteDependencies.production(application)
    }
    val chatViewModel: ChatViewModel = viewModel(factory = resolvedDependencies.viewModelFactory)
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val activeTaskTree by remember(sessionId, resolvedDependencies.taskRepository) {
        resolvedDependencies.taskRepository.observeActiveTree(sessionId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val taskPanel = remember(activeTaskTree, uiState.isGenerating) {
        taskPanelUiState(activeTaskTree, uiState.isGenerating)
    }
    val inputText by chatViewModel.inputText.collectAsStateWithLifecycle()
    val tokenState by chatViewModel.tokenIndicatorState.collectAsStateWithLifecycle()
    val ragPhases by chatViewModel.ragPhases.collectAsStateWithLifecycle()
    val compressionState by chatViewModel.compressionState.collectAsStateWithLifecycle()
    val postProcessTasks by chatViewModel.postProcessTasks.collectAsStateWithLifecycle()
    val draftDocuments by chatViewModel.draftDocuments.collectAsStateWithLifecycle()
    val isImportingDocument by chatViewModel.isImportingDocument.collectAsStateWithLifecycle()
    val draftConsumptionEpoch by chatViewModel.draftConsumptionEpoch.collectAsStateWithLifecycle()
    val providerModels by ProviderManager.getInstance().providerModels.collectAsStateWithLifecycle()
    val modelDisplayNames = remember(providerModels, uiState.session?.modelId, uiState.messages) {
        resolveModelDisplayNames(
            sessionModelId = uiState.session?.modelId,
            messageModelIds = uiState.messages.map { it.modelId },
            providerModels = providerModels,
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarData by remember { mutableStateOf<NexaraSnackbarData?>(null) }
    var snackbarAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var selectedImageUriStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val selectedImageUris = selectedImageUriStrings.map(Uri::parse)
    var activeOverlayToken by rememberSaveable { mutableStateOf<String?>(null) }
    var queuedOverlayToken by rememberSaveable { mutableStateOf<String?>(null) }
    val activeOverlay = restoreChatOverlay(activeOverlayToken)
    var permissionExplanationTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionInFlightTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingNotificationPermission = permissionExplanationTaskId
        ?.let(::NotificationPermissionRequest)
    val scope = rememberCoroutineScope()
    val continueTaskPrompt = stringResource(R.string.chat_task_continue_prompt)

    val hasBlockingOverlay = activeOverlay != null
    val permissionDialogVisible = shouldShowNotificationPermissionDialog(
        NotificationPermissionOverlayState(
            hasPendingRequest = permissionExplanationTaskId != null,
            hasBlockingOverlay = hasBlockingOverlay,
        ),
    )
    val permissionPrefs = remember(context) {
        context.getSharedPreferences(GENERATION_NOTIFICATION_PERMISSION_PREFS, 0)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
        notificationPermissionResultTaskId(
            inFlightTaskId = permissionInFlightTaskId,
            explanationTaskId = permissionExplanationTaskId,
        )?.let { chatViewModel.onNotificationPermissionResult(it, granted) }
        permissionInFlightTaskId = null
        permissionExplanationTaskId = null
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> selectedImageUriStrings = uris.map(Uri::toString) }
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> chatViewModel.importFullContextDocuments(uris) }
    LaunchedEffect(draftConsumptionEpoch) {
        if (draftConsumptionEpoch > 0L) selectedImageUriStrings = emptyList()
    }
    val requestOverlay: (ChatOverlay) -> Unit = { requested ->
        val next = enqueueChatOverlay(
            state = ChatOverlayQueueState(
                active = restoreChatOverlay(activeOverlayToken),
                queued = restoreChatOverlay(queuedOverlayToken),
            ),
            requested = requested,
            hasPendingNotificationPermission =
                permissionExplanationTaskId != null || permissionInFlightTaskId != null,
        )
        activeOverlayToken = saveChatOverlay(next.active)
        queuedOverlayToken = saveChatOverlay(next.queued)
    }

    LaunchedEffect(
        activeOverlayToken,
        queuedOverlayToken,
        permissionExplanationTaskId,
        permissionInFlightTaskId,
    ) {
        val next = advanceChatOverlay(
            state = ChatOverlayQueueState(
                active = restoreChatOverlay(activeOverlayToken),
                queued = restoreChatOverlay(queuedOverlayToken),
            ),
            hasPendingNotificationPermission =
                permissionExplanationTaskId != null || permissionInFlightTaskId != null,
        )
        activeOverlayToken = saveChatOverlay(next.active)
        queuedOverlayToken = saveChatOverlay(next.queued)
    }

    LaunchedEffect(chatViewModel) {
        chatViewModel.notificationPermissionRequests.collect { request ->
            if (request == null) {
                permissionExplanationTaskId = null
                return@collect
            }
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            val shouldExplain = shouldExplainNotificationPermission(
                NotificationPermissionPromptState(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = granted,
                    alreadyAsked = permissionPrefs.getBoolean(
                        GENERATION_NOTIFICATION_PERMISSION_ASKED,
                        false,
                    ),
                ),
            )
            when (notificationPermissionDisposition(
                requestTaskId = request.taskId,
                granted = granted,
                shouldExplain = shouldExplain,
                inFlightTaskId = permissionInFlightTaskId,
            )) {
                NotificationPermissionDisposition.GRANTED ->
                    chatViewModel.onNotificationPermissionResult(request.taskId, true)
                NotificationPermissionDisposition.SHOW_EXPLANATION ->
                    permissionExplanationTaskId = request.taskId
                NotificationPermissionDisposition.WAIT_FOR_SYSTEM_RESULT -> Unit
                NotificationPermissionDisposition.REJECTED ->
                    chatViewModel.onNotificationPermissionResult(request.taskId, false)
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose(chatViewModel::saveCurrentDraft)
    }
    LaunchedEffect(sessionId) { chatViewModel.loadSession(sessionId) }
    val activity = context as? Activity
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(uiState.isGenerating, activity) {
        if (uiState.isGenerating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val dismissLabel = stringResource(R.string.common_dismiss)
    val copiedLabel = stringResource(R.string.chat_copy_success)
    val branchFailedLabel = stringResource(R.string.chat_branch_failed)
    val branchUnstableLabel = stringResource(R.string.chat_branch_failed_unstable)
    val generationErrorMessage = uiState.generationNotice
        ?.let(GenerationFailureNotice::template)
        ?.let { resolved -> stringResource(resolved.resourceId, *resolved.args.toTypedArray()) }
    LaunchedEffect(
        uiState.generationNotice,
        generationErrorMessage,
        uiState.error,
        permissionDialogVisible,
    ) {
        if (permissionDialogVisible) {
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val message = generationErrorMessage ?: uiState.error ?: return@LaunchedEffect
        snackbarAction = chatViewModel::clearError
        snackbarData = NexaraSnackbarData(message, SnackbarType.ERROR, dismissLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = message,
            actionLabel = dismissLabel,
            duration = SnackbarDuration.Long,
        )
        chatViewModel.clearError()
    }
    LaunchedEffect(
        uiState.backgroundWarning,
        uiState.generationNotice,
        uiState.error,
        permissionDialogVisible,
    ) {
        if (
            permissionDialogVisible ||
            uiState.generationNotice != null ||
            uiState.error != null
        ) return@LaunchedEffect
        val warning = uiState.backgroundWarning ?: return@LaunchedEffect
        snackbarAction = { chatViewModel.clearBackgroundWarning(warning.taskId) }
        snackbarData = NexaraSnackbarData(warning.message, SnackbarType.INFO, dismissLabel)
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = warning.message,
            actionLabel = dismissLabel,
            duration = SnackbarDuration.Long,
        )
    }

    ChatScreenContent(
        state = ChatScreenState(
            uiState = uiState,
            inputText = inputText,
            tokenState = tokenState,
            ragPhases = ragPhases,
            compressionState = compressionState,
            postProcessTasks = postProcessTasks,
            selectedImageUris = selectedImageUris,
            draftDocuments = draftDocuments,
            isImportingDocument = isImportingDocument,
            modelDisplayNames = modelDisplayNames,
            taskPanel = taskPanel,
        ),
        actions = ChatScreenActions(
            onNavigateBack = onNavigateBack,
            onOpenWorkspace = { requestOverlay(ChatOverlay.Workspace) },
            onOpenSettings = { requestOverlay(ChatOverlay.ModelSettings) },
            onOpenPromptEditor = { requestOverlay(ChatOverlay.PromptEditor) },
            onOpenClearDialog = { requestOverlay(ChatOverlay.ClearHistory) },
            onOpenRenameDialog = { requestOverlay(ChatOverlay.RenameSession) },
            onOpenDeleteDialog = { requestOverlay(ChatOverlay.DeleteSession) },
            onContentChange = chatViewModel::updateMessageContentOnly,
            onCopy = { text ->
                resolvedDependencies.copyToClipboard(context, text)
                snackbarAction = null
                snackbarData = NexaraSnackbarData(copiedLabel, SnackbarType.SUCCESS)
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(copiedLabel, duration = SnackbarDuration.Short)
                }
            },
            onDeleteMessage = chatViewModel::deleteMessage,
            onRegenerateMessage = { requestOverlay(ChatOverlay.Truncate(it)) },
            onBranchMessage = { messageId ->
                scope.launch {
                    when (val result = chatViewModel.branchFromMessage(messageId)) {
                        is com.promenar.nexara.data.session.BranchSessionResult.Success ->
                            onNavigateToSession(result.sessionId)
                        is com.promenar.nexara.data.session.BranchSessionResult.Rejected -> {
                            val message = if (
                                result.reason == com.promenar.nexara.data.session.BranchSessionRejectReason.TargetNotStable
                            ) branchUnstableLabel else branchFailedLabel
                            snackbarData = NexaraSnackbarData(message, SnackbarType.ERROR)
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = message,
                                duration = SnackbarDuration.Long,
                            )
                        }
                    }
                }
            },
            onApprove = chatViewModel::approveRequest,
            onDecline = chatViewModel::rejectRequest,
            onRemovePostProcessTask = chatViewModel::removePostProcessTask,
            onManualSummary = chatViewModel::summarizeHistory,
            onTextChange = chatViewModel::updateInputText,
            onSend = { text, images ->
                chatViewModel.sendMessage(text, images)
            },
            onStop = chatViewModel::stopGeneration,
            onPickImages = { imagePickerLauncher.launch("image/*") },
            onPickDocuments = {
                documentPickerLauncher.launch(
                    arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"),
                )
            },
            onRemoveDocument = chatViewModel::removeDraftDocument,
            onRemoveImage = { index ->
                selectedImageUriStrings = selectedImageUriStrings.toMutableList().apply {
                    if (index in indices) removeAt(index)
                }
            },
            onSnackbarAction = {
                snackbarAction?.invoke()
                snackbarHostState.currentSnackbarData?.dismiss()
            },
            onContinueTask = {
                if (!uiState.isGenerating) {
                    chatViewModel.sendMessage(continueTaskPrompt)
                }
            },
            onCompleteTask = {
                if (!uiState.isGenerating) {
                    val unfinishedIds = unfinishedTaskLeafIds(activeTaskTree)
                    if (unfinishedIds.isNotEmpty()) {
                        scope.launch {
                            resolvedDependencies.taskRepository.updatePlan(
                                sessionId = sessionId,
                                operations = unfinishedIds.map { stepId ->
                                    PlanPatchOp(
                                        action = "set_status",
                                        stepId = stepId,
                                        payload = mapOf("status" to "done"),
                                    )
                                },
                            )
                        }
                    }
                }
            },
        ),
        snackbarHostState = snackbarHostState,
        snackbarData = snackbarData,
    )

    pendingNotificationPermission?.takeIf { permissionDialogVisible }?.let { request ->
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_DIALOG),
            onDismissRequest = {
                permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                chatViewModel.onNotificationPermissionResult(request.taskId, false)
                permissionExplanationTaskId = null
            },
            title = { Text(stringResource(R.string.generation_notification_permission_title)) },
            text = { Text(stringResource(R.string.generation_notification_permission_explanation)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_CONTINUE),
                    onClick = {
                        permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                        permissionInFlightTaskId = request.taskId
                        permissionExplanationTaskId = null
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                ) { Text(stringResource(R.string.generation_notification_permission_continue)) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(UiTags.NOTIFICATION_PERMISSION_FOREGROUND_ONLY),
                    onClick = {
                        permissionPrefs.edit().putBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, true).apply()
                        chatViewModel.onNotificationPermissionResult(request.taskId, false)
                        permissionExplanationTaskId = null
                    },
                ) { Text(stringResource(R.string.common_btn_cancel)) }
            },
        )
    }
    if (activeOverlay == ChatOverlay.ClearHistory) {
        Dialog(onDismissRequest = { activeOverlayToken = null }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.chat_dialog_clear_history_title),
                message = stringResource(R.string.chat_dialog_clear_history_msg),
                confirmText = stringResource(R.string.common_btn_confirm),
                onConfirm = { chatViewModel.clearHistory(); activeOverlayToken = null },
                onCancel = { activeOverlayToken = null },
            )
        }
    }
    if (activeOverlay == ChatOverlay.DeleteSession) {
        Dialog(onDismissRequest = { activeOverlayToken = null }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.chat_dialog_delete_session_title),
                message = stringResource(R.string.chat_dialog_delete_session_msg),
                confirmText = stringResource(R.string.shared_btn_delete),
                onConfirm = { chatViewModel.deleteSession(); activeOverlayToken = null; onNavigateBack() },
                onCancel = { activeOverlayToken = null },
            )
        }
    }
    if (activeOverlay == ChatOverlay.RenameSession) {
        RenameDialog(
            currentName = uiState.session?.title ?: stringResource(R.string.chat_title_new),
            onDismiss = { activeOverlayToken = null },
            onConfirm = { chatViewModel.renameSession(it); activeOverlayToken = null },
        )
    }
    (activeOverlay as? ChatOverlay.Truncate)?.let { truncate ->
        Dialog(onDismissRequest = { activeOverlayToken = null }) {
            NexaraConfirmDialog(
                title = stringResource(R.string.chat_confirm_truncate_title),
                message = stringResource(R.string.chat_confirm_truncate_message),
                confirmText = stringResource(R.string.common_btn_confirm),
                onConfirm = {
                    chatViewModel.regenerateMessage(truncate.messageId)
                    activeOverlayToken = null
                },
                onCancel = { activeOverlayToken = null },
                isDestructive = true,
            )
        }
    }
    if (activeOverlay == ChatOverlay.Workspace) {
        ResourceExplorerSheet(
            onDismiss = { activeOverlayToken = null },
            sessionId = sessionId,
        )
    }
    SessionSettingsSheet(
        show = activeOverlay == ChatOverlay.ModelSettings,
        onDismiss = { activeOverlayToken = null },
        chatViewModel = chatViewModel,
    )
    UnifiedPromptEditor(
        show = activeOverlay == ChatOverlay.PromptEditor,
        onDismiss = { activeOverlayToken = null },
        onSave = chatViewModel::saveCustomPrompt,
        initialText = uiState.session?.customPrompt.orEmpty(),
        title = stringResource(R.string.chat_session_prompt_title),
        placeholder = stringResource(R.string.chat_session_prompt_placeholder),
        mode = EditorMode.DIALOG,
    )
}

/** 兼容现有调用；新导航使用 [ChatRoute]。 */
@Composable
fun ChatScreen(sessionId: String, onNavigateBack: () -> Unit = {}) =
    ChatRoute(sessionId, onNavigateBack)
