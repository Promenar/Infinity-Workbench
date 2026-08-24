package com.promenar.nexara.ui.rag

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class DocEditorViewMode { EDIT, PREVIEW, SPLIT }

private val DOC_EDITOR_BACK_ICON: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack

enum class DocEditorBackDecision { NavigateBack, ConfirmDiscard, StaySaving }

enum class DocEditorConfirmation { DiscardChanges, ReloadLatest }

enum class DocEditorVisibleState {
    Loading,
    LoadError,
    NotFoundOnLoad,
    Ready,
    Saving,
    SaveError,
    SaveConflict,
    NotFoundAfterSave,
    MetadataOnly,
    PerformanceProtected,
}

data class DocEditorScreenState(
    val editorState: DocEditorUiState,
    val viewMode: DocEditorViewMode = DocEditorViewMode.EDIT,
    val confirmation: DocEditorConfirmation? = null,
)

data class DocEditorScreenActions(
    val onRequestBack: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onContentChange: (String) -> Unit = {},
    val onViewModeChange: (DocEditorViewMode) -> Unit = {},
    val onSave: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onRetrySave: () -> Unit = {},
    val onCopyLocalContent: () -> Unit = {},
    val onRequestReload: () -> Unit = {},
    val onDismissWarning: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onConfirmReload: () -> Unit = {},
    val onUseWorkspaceTitle: () -> Unit = {},
    val onRetryMyTitle: () -> Unit = {},
    val onRetryPendingIndex: () -> Unit = {},
)

fun docEditorBackDecision(
    isDirty: Boolean,
    phase: DocEditorPhase = DocEditorPhase.Ready,
): DocEditorBackDecision = when {
    phase == DocEditorPhase.Saving -> DocEditorBackDecision.StaySaving
    isDirty -> DocEditorBackDecision.ConfirmDiscard
    else -> DocEditorBackDecision.NavigateBack
}

fun isDocEditorSplitAvailable(availableWidthDp: Float): Boolean =
    availableWidthDp >= SPLIT_MIN_WIDTH_DP

internal fun shouldStackDocEditorNoticeActions(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < NOTICE_ACTIONS_STACK_MIN_WIDTH_DP || fontScale >= 1.5f

fun DocEditorUiState.toVisibleState(): DocEditorVisibleState {
    return when (phase) {
        DocEditorPhase.Loading -> DocEditorVisibleState.Loading
        DocEditorPhase.LoadError -> DocEditorVisibleState.LoadError
        DocEditorPhase.Ready -> when (contentAccess) {
            DocEditorContentAccess.Editable -> DocEditorVisibleState.Ready
            DocEditorContentAccess.PerformanceProtected -> {
                DocEditorVisibleState.PerformanceProtected
            }
            DocEditorContentAccess.MetadataOnly -> DocEditorVisibleState.MetadataOnly
        }
        DocEditorPhase.Saving -> DocEditorVisibleState.Saving
        DocEditorPhase.SaveError -> DocEditorVisibleState.SaveError
        DocEditorPhase.SaveConflict -> DocEditorVisibleState.SaveConflict
        DocEditorPhase.NotFound -> if (hasLoadedDocument || isDirty) {
            DocEditorVisibleState.NotFoundAfterSave
        } else {
            DocEditorVisibleState.NotFoundOnLoad
        }
    }
}

internal fun shouldShowDocEditorPendingIndexNotice(editor: DocEditorUiState): Boolean =
    editor.indexQueueFailed && editor.indexPendingTargets.isNotEmpty()

@Composable
fun DocEditorScreen(
    workspaceRootUuid: String,
    docId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.promenar.nexara.NexaraApplication
    val viewModel: DocEditorViewModel = viewModel(
        factory = DocEditorViewModel.Factory(application = app),
    )
    val editorState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clipboardLabel = stringResource(R.string.doc_editor_clipboard_label)

    LaunchedEffect(workspaceRootUuid, docId) {
        viewModel.loadDocument(workspaceRootUuid, docId)
    }

    DocEditorRouteContent(
        editorState = editorState,
        onNavigateBack = onNavigateBack,
        stateKey = workspaceRootUuid to docId,
        actions = DocEditorScreenActions(
            onTitleChange = viewModel::updateTitle,
            onContentChange = viewModel::onContentChanged,
            onSave = viewModel::saveDocument,
            onRetryLoad = viewModel::reload,
            onRetrySave = viewModel::saveDocument,
            onCopyLocalContent = {
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        clipboardLabel,
                        editorState.content,
                    ),
                )
            },
            onDismissWarning = viewModel::dismissWarning,
            onConfirmReload = viewModel::reload,
            onUseWorkspaceTitle = viewModel::useWorkspaceTitle,
            onRetryMyTitle = viewModel::retryMyTitle,
            onRetryPendingIndex = viewModel::retryPendingIndex,
        ),
    )
}

/**
 * 生产 Route/back-guard seam。真实页面与交互测试共用本层，避免返回保护接线被测试替身绕过。
 */
@Composable
fun DocEditorRouteContent(
    editorState: DocEditorUiState,
    onNavigateBack: () -> Unit,
    actions: DocEditorScreenActions,
    modifier: Modifier = Modifier,
    stateKey: Any? = editorState.workspaceRootUuid to editorState.documentId,
) {
    var viewMode by rememberSaveable(stateKey) { mutableStateOf(DocEditorViewMode.EDIT) }
    var confirmation by rememberSaveable(stateKey) {
        mutableStateOf<DocEditorConfirmation?>(null)
    }
    val snackbarHostState = remember(stateKey) { SnackbarHostState() }
    val savingFeedbackJobState = remember(stateKey) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val savingBackFeedback = stringResource(R.string.doc_editor_saving_back_feedback)
    LaunchedEffect(stateKey, editorState.phase) {
        if (editorState.phase != DocEditorPhase.Saving) {
            savingFeedbackJobState.value?.cancel()
            savingFeedbackJobState.value = null
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }
    DisposableEffect(stateKey, snackbarHostState) {
        onDispose {
            savingFeedbackJobState.value?.cancel()
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }
    val requestBack: () -> Unit = {
        when (docEditorBackDecision(editorState.isDirty, editorState.phase)) {
            DocEditorBackDecision.NavigateBack -> onNavigateBack()
            DocEditorBackDecision.ConfirmDiscard -> {
                confirmation = DocEditorConfirmation.DiscardChanges
            }
            DocEditorBackDecision.StaySaving -> {
                if (savingFeedbackJobState.value?.isActive != true) {
                    val feedbackJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            snackbarHostState.showSnackbar(savingBackFeedback)
                        } finally {
                            if (savingFeedbackJobState.value === coroutineContext[Job]) {
                                savingFeedbackJobState.value = null
                            }
                        }
                    }
                    savingFeedbackJobState.value = feedbackJob
                    feedbackJob.start()
                }
            }
        }
    }
    BackHandler(onBack = requestBack)

    DocEditorScreenContent(
        state = DocEditorScreenState(
            editorState = editorState,
            viewMode = viewMode,
            confirmation = confirmation,
        ),
        snackbarHostState = snackbarHostState,
        actions = actions.copy(
            onRequestBack = requestBack,
            onViewModeChange = { viewMode = it },
            onRequestReload = {
                confirmation = DocEditorConfirmation.ReloadLatest
            },
            onDismissConfirmation = { confirmation = null },
            onConfirmDiscard = {
                confirmation = null
                onNavigateBack()
            },
            onConfirmReload = {
                confirmation = null
                actions.onConfirmReload()
            },
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreenContent(
    state: DocEditorScreenState,
    actions: DocEditorScreenActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val editor = state.editorState
    val visibleState = editor.toVisibleState()
    val isRecoveryState = when (visibleState) {
        DocEditorVisibleState.SaveError,
        DocEditorVisibleState.SaveConflict,
        DocEditorVisibleState.NotFoundAfterSave,
        -> true
        else -> false
    }
    val shouldPrioritizeRecoveryFeedback =
        isRecoveryState || shouldShowDocEditorPendingIndexNotice(editor)
    if (shouldPrioritizeRecoveryFeedback) {
        val focusManager = LocalFocusManager.current
        val softwareKeyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            // 异步保存终态与索引失败的恢复操作比继续输入更紧急。
            // 先主动释放焦点与 IME，同时下方布局优先级确保关闭动画期间也不隐藏反馈。
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val savingContentDescription = stringResource(R.string.doc_editor_saving)
    val canSave = editor.hasLoadedDocument &&
        editor.contentAccess != DocEditorContentAccess.MetadataOnly &&
        editor.isDirty &&
        editor.phase !in setOf(
            DocEditorPhase.Saving,
            DocEditorPhase.SaveConflict,
            DocEditorPhase.NotFound,
        )

    Scaffold(
        modifier = modifier.testTagCompat(UiTags.DOC_EDITOR_ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            snackbarHostState?.let { hostState ->
                SnackbarHost(hostState = hostState)
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.doc_editor_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onRequestBack,
                        modifier = Modifier
                            .size(NexaraSpacing.MinimumTouchTarget)
                            .zIndex(1f)
                            .testTagCompat(UiTags.DOC_EDITOR_BACK),
                    ) {
                        Icon(
                            imageVector = DOC_EDITOR_BACK_ICON,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(NexaraSpacing.XLarge),
                        )
                    }
                },
                actions = {
                    if (editor.hasLoadedDocument &&
                        editor.contentAccess != DocEditorContentAccess.MetadataOnly
                    ) {
                        if (editor.phase == DocEditorPhase.Saving) {
                            Box(
                                modifier = Modifier
                                    .size(NexaraSpacing.MinimumTouchTarget)
                                    .testTagCompat(UiTags.DOC_EDITOR_STATE_SAVING)
                                    .semantics {
                                        contentDescription = savingContentDescription
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(NexaraSpacing.XLarge),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = actions.onSave,
                                enabled = canSave,
                                modifier = Modifier
                                    .size(NexaraSpacing.MinimumTouchTarget)
                                    .testTagCompat(UiTags.DOC_EDITOR_SAVE),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = stringResource(R.string.common_cd_save),
                                    tint = if (canSave) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val splitAvailable = isDocEditorSplitAvailable(maxWidth.value)
            val density = LocalDensity.current
            // 放大字体时，原本「正常高度」的手机窗口也会被标题、模式和状态区占满。
            // 这里及早切换到紧凑结构，把正文/故障操作保留在可见区域，而不是让
            // 可点击的恢复操作被零高度编辑区挤出窗口。
            val compactVertical = maxHeight < 480.dp || (
                maxHeight < 680.dp && density.fontScale >= 1.3f
            )
            // imePadding() 只会在测量阶段收缩 Column；这里直接读取真实 IME inset，
            // 在收缩后已不足以同时容纳输入面板与辅助 chrome 时，优先保留可编辑内容。
            val isImeVisible = WindowInsets.isImeVisible
            val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
            val prioritizeEditorSurface = isImeVisible &&
                !shouldPrioritizeRecoveryFeedback &&
                maxHeight - imeBottomDp < DOC_EDITOR_IME_MIN_EDITABLE_HEIGHT_DP.dp
            // 索引待重试也可能在保存完成后异步出现。IME 关闭动画期间先让出
            // 身份、模式与状态栏高度，确保失败提示和 48dp 重试操作立即可达。
            val prioritizeImeFeedbackActions = compactVertical && isImeVisible &&
                shouldPrioritizeRecoveryFeedback
            // 保存终态没有继续编辑的收益，紧凑窗口应先确保恢复操作而不是文档身份和模式。
            val prioritizeRecoveryActions = compactVertical && isRecoveryState
            val showEditorChrome = !prioritizeEditorSurface &&
                !prioritizeImeFeedbackActions &&
                !prioritizeRecoveryActions
            val effectiveMode = if (!splitAvailable && state.viewMode == DocEditorViewMode.SPLIT) {
                DocEditorViewMode.EDIT
            } else {
                state.viewMode
            }
            val horizontalPadding = when {
                maxWidth >= 840.dp -> NexaraSpacing.XXLarge
                maxWidth >= 600.dp -> NexaraSpacing.XLarge
                else -> NexaraSpacing.Large
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 960.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .imePadding()
                    .padding(
                        horizontal = horizontalPadding,
                        // 紧凑保存终态把全部纵向预算交给恢复操作与待索引重试。
                        vertical = if (prioritizeRecoveryActions) 0.dp else NexaraSpacing.Small,
                    ),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                when (visibleState) {
                    DocEditorVisibleState.Loading -> LoadingState(Modifier.weight(1f))
                    DocEditorVisibleState.LoadError -> LoadErrorState(
                        onRetry = actions.onRetryLoad,
                        modifier = Modifier.weight(1f),
                    )
                    DocEditorVisibleState.NotFoundOnLoad -> NotFoundState(
                        onRetry = actions.onRetryLoad,
                        modifier = Modifier.weight(1f),
                    )
                    DocEditorVisibleState.MetadataOnly -> MetadataOnlyState(
                        editor = editor,
                        onDismissWarning = actions.onDismissWarning,
                        modifier = Modifier.weight(1f),
                    )
                    else -> LoadedDocumentContent(
                        editor = editor,
                        visibleState = visibleState,
                        viewMode = effectiveMode,
                        splitAvailable = splitAvailable,
                        compactVertical = compactVertical,
                        showEditorChrome = showEditorChrome,
                        showRecoveryNotice = !prioritizeEditorSurface,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (shouldShowDocEditorPendingIndexNotice(editor) && !prioritizeEditorSurface) {
                    PendingIndexNotice(onRetry = actions.onRetryPendingIndex)
                }

                if (editor.hasLoadedDocument &&
                    !isRecoveryState &&
                    !prioritizeEditorSurface &&
                    !prioritizeImeFeedbackActions
                ) {
                    EditorStatusBar(
                        editor = editor,
                        viewMode = effectiveMode,
                        compactVertical = compactVertical,
                    )
                }
            }
        }
    }

    DocEditorConfirmationDialog(
        confirmation = state.confirmation,
        actions = actions,
    )
}

@Composable
private fun LoadedDocumentContent(
    editor: DocEditorUiState,
    visibleState: DocEditorVisibleState,
    viewMode: DocEditorViewMode,
    splitAvailable: Boolean,
    compactVertical: Boolean,
    showEditorChrome: Boolean,
    showRecoveryNotice: Boolean,
    actions: DocEditorScreenActions,
    modifier: Modifier = Modifier,
) {
    val previewScope = rememberCoroutineScope()
    val previewSnapshotController = remember(
        editor.workspaceRootUuid,
        editor.documentId,
        editor.documentEpoch,
        previewScope,
    ) {
        DocEditorPreviewSnapshotController(
            scope = previewScope,
            initialContent = editor.content,
        )
    }
    val previewSnapshot by previewSnapshotController.snapshot.collectAsState()
    val isPerformanceProtected =
        editor.contentAccess == DocEditorContentAccess.PerformanceProtected
    SideEffect {
        previewSnapshotController.update(
            editor.content,
            if (isPerformanceProtected) DocEditorViewMode.PREVIEW else viewMode,
        )
    }
    DisposableEffect(previewSnapshotController) {
        onDispose(previewSnapshotController::close)
    }
    val editorVerticalScrollState = rememberSaveable(
        editor.workspaceRootUuid,
        editor.documentId,
        saver = ScrollState.Saver,
    ) { ScrollState(0) }
    val editorHorizontalScrollState = rememberSaveable(
        editor.workspaceRootUuid,
        editor.documentId,
        saver = ScrollState.Saver,
    ) { ScrollState(0) }
    val previewVerticalScrollState = rememberSaveable(
        editor.workspaceRootUuid,
        editor.documentId,
        saver = ScrollState.Saver,
    ) { ScrollState(0) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        val titleEditable = isPerformanceProtected || viewMode != DocEditorViewMode.PREVIEW
        if (showEditorChrome && compactVertical && !isPerformanceProtected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                DocumentIdentity(
                    editor = editor,
                    editable = titleEditable,
                    onTitleChange = actions.onTitleChange,
                    compactVertical = true,
                    modifier = Modifier.weight(1f),
                )
                ModeSelector(
                    selectedMode = viewMode,
                    splitAvailable = splitAvailable,
                    onModeChange = actions.onViewModeChange,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (showEditorChrome) {
            DocumentIdentity(
                editor = editor,
                editable = titleEditable,
                onTitleChange = actions.onTitleChange,
                compactVertical = compactVertical,
            )
            if (!isPerformanceProtected) {
                ModeSelector(
                    selectedMode = viewMode,
                    splitAvailable = splitAvailable,
                    onModeChange = actions.onViewModeChange,
                )
            }
        }
        if (showRecoveryNotice) when (visibleState) {
            DocEditorVisibleState.SaveError -> SaveNotice(
                title = stringResource(R.string.doc_editor_save_failed_title),
                description = failureDescription(editor.failureCode),
                primaryLabel = stringResource(R.string.doc_editor_retry_save),
                primaryTag = UiTags.DOC_EDITOR_RETRY_SAVE,
                primaryIcon = Icons.Rounded.Refresh,
                onPrimary = actions.onRetrySave,
                tag = UiTags.DOC_EDITOR_STATE_SAVE_ERROR,
                compact = compactVertical,
            )
            DocEditorVisibleState.SaveConflict -> if (
                editor.failureCode == DocEditorFailureCode.TitleConflict
            ) {
                SaveNotice(
                    title = stringResource(R.string.doc_editor_title_conflict_title),
                    description = stringResource(
                        R.string.doc_editor_title_conflict_description,
                        editor.titleConflictCurrentName.orEmpty(),
                    ),
                    primaryLabel = stringResource(R.string.doc_editor_use_workspace_title),
                    primaryTag = UiTags.DOC_EDITOR_USE_WORKSPACE_TITLE,
                    primaryIcon = Icons.Rounded.Refresh,
                    onPrimary = actions.onUseWorkspaceTitle,
                    secondaryLabel = stringResource(R.string.doc_editor_retry_my_title),
                    secondaryTag = UiTags.DOC_EDITOR_RETRY_MY_TITLE,
                    onSecondary = actions.onRetryMyTitle,
                    tag = UiTags.DOC_EDITOR_STATE_CONFLICT,
                    compact = compactVertical,
                )
            } else {
                SaveNotice(
                    title = stringResource(R.string.doc_editor_conflict_title),
                    description = stringResource(R.string.doc_editor_conflict_description),
                    primaryLabel = stringResource(R.string.doc_editor_copy_local),
                    primaryTag = UiTags.DOC_EDITOR_COPY_LOCAL,
                    onPrimary = actions.onCopyLocalContent,
                    secondaryLabel = stringResource(R.string.doc_editor_reload_latest),
                    secondaryTag = UiTags.DOC_EDITOR_REQUEST_RELOAD,
                    onSecondary = actions.onRequestReload,
                    tag = UiTags.DOC_EDITOR_STATE_CONFLICT,
                    compact = compactVertical,
                )
            }
            DocEditorVisibleState.NotFoundAfterSave -> SaveNotice(
                title = stringResource(R.string.doc_editor_missing_after_save_title),
                description = stringResource(R.string.doc_editor_missing_after_save_description),
                primaryLabel = stringResource(R.string.doc_editor_copy_local),
                primaryTag = UiTags.DOC_EDITOR_COPY_LOCAL,
                onPrimary = actions.onCopyLocalContent,
                secondaryLabel = stringResource(R.string.doc_editor_reload_latest),
                secondaryTag = UiTags.DOC_EDITOR_REQUEST_RELOAD,
                onSecondary = actions.onRequestReload,
                tag = UiTags.DOC_EDITOR_STATE_NOT_FOUND,
                compact = compactVertical,
            )
            else -> Unit
        }

        val suppressEditorSurfaceForCompactRecovery = compactVertical && showRecoveryNotice &&
            visibleState in setOf(
                DocEditorVisibleState.SaveError,
                DocEditorVisibleState.SaveConflict,
                DocEditorVisibleState.NotFoundAfterSave,
            )
        // 保存终态的正文不能与恢复操作竞争 weight；否则第二个冲突操作会被压缩到
        // 小于触控目标。IME 路径会传入 showRecoveryNotice=false，因此仍保留编辑面板。
        if (!suppressEditorSurfaceForCompactRecovery) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTagCompat(UiTags.DOC_EDITOR_MAIN_PANE),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                if (isPerformanceProtected) {
                    PerformanceProtectedPane(
                        previewSnapshot = previewSnapshot,
                        onCopyFullContent = actions.onCopyLocalContent,
                        previewScrollState = previewVerticalScrollState,
                    )
                } else when (viewMode) {
                    DocEditorViewMode.EDIT -> EditorPane(
                        content = editor.content,
                        onContentChange = actions.onContentChange,
                        verticalScrollState = editorVerticalScrollState,
                        horizontalScrollState = editorHorizontalScrollState,
                    )
                    DocEditorViewMode.PREVIEW -> PreviewPane(
                        content = previewSnapshot.content,
                        isTruncated = previewSnapshot.isTruncated,
                        verticalScrollState = previewVerticalScrollState,
                    )
                    DocEditorViewMode.SPLIT -> Row(Modifier.fillMaxSize()) {
                        EditorPane(
                            content = editor.content,
                            onContentChange = actions.onContentChange,
                            verticalScrollState = editorVerticalScrollState,
                            horizontalScrollState = editorHorizontalScrollState,
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .testTagCompat(UiTags.DOC_EDITOR_SPLIT_DIVIDER),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        PreviewPane(
                            content = previewSnapshot.content,
                            isTruncated = previewSnapshot.isTruncated,
                            verticalScrollState = previewVerticalScrollState,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentIdentity(
    editor: DocEditorUiState,
    editable: Boolean,
    onTitleChange: (String) -> Unit,
    compactVertical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTagCompat(UiTags.DOC_EDITOR_IDENTITY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(NexaraSpacing.XLarge),
        )
        if (compactVertical) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                DocumentTitleField(
                    editor = editor,
                    editable = editable,
                    onTitleChange = onTitleChange,
                    modifier = Modifier.weight(1f),
                )
                DocumentMetadata(
                    editor = editor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                DocumentTitleField(
                    editor = editor,
                    editable = editable,
                    onTitleChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                DocumentMetadata(editor = editor)
            }
        }
    }
}

@Composable
private fun DocumentTitleField(
    editor: DocEditorUiState,
    editable: Boolean,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleDescription = stringResource(R.string.doc_editor_title_input_description)
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = editor.title,
        onValueChange = { value ->
            onTitleChange(value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '))
        },
        readOnly = !editable,
        singleLine = false,
        maxLines = 2,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
            // 紧凑横屏中标题与元数据各占一半宽度；长标题最多换成两行，仍不能
            // 越过自身测量边界压住相邻元数据。
            .clipToBounds()
            .testTagCompat(UiTags.DOC_EDITOR_TITLE_INPUT)
            .semantics { contentDescription = titleDescription },
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (editor.title.isEmpty()) {
                    Text(
                        text = stringResource(R.string.doc_editor_title_placeholder),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DocumentMetadata(
    editor: DocEditorUiState,
    maxLines: Int = 2,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(
            R.string.doc_editor_metadata,
            formatFileSize(editor.sizeBytes),
            stringResource(R.string.doc_editor_document_type),
        ),
        modifier = modifier.testTagCompat(UiTags.DOC_EDITOR_METADATA),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
private fun ModeSelector(
    selectedMode: DocEditorViewMode,
    splitAvailable: Boolean,
    onModeChange: (DocEditorViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = buildList {
        add(DocEditorViewMode.EDIT to Icons.Rounded.Edit)
        add(DocEditorViewMode.PREVIEW to Icons.Rounded.Visibility)
        if (splitAvailable) add(DocEditorViewMode.SPLIT to Icons.Rounded.VerticalSplit)
    }
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .testTagCompat(UiTags.DOC_EDITOR_MODE_SELECTOR),
    ) {
        modes.forEachIndexed { index, (mode, icon) ->
            ModeTab(
                mode = mode,
                icon = icon,
                selected = selectedMode == mode,
                index = index,
                count = modes.size,
                onClick = { onModeChange(mode) },
            )
        }
    }
}

@Composable
private fun SingleChoiceSegmentedButtonRowScope.ModeTab(
    mode: DocEditorViewMode,
    icon: ImageVector,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val label = stringResource(
        when (mode) {
            DocEditorViewMode.EDIT -> R.string.doc_editor_edit
            DocEditorViewMode.PREVIEW -> R.string.doc_editor_preview
            DocEditorViewMode.SPLIT -> R.string.doc_editor_split
        },
    )
    val tag = when (mode) {
        DocEditorViewMode.EDIT -> UiTags.DOC_EDITOR_MODE_EDIT
        DocEditorViewMode.PREVIEW -> UiTags.DOC_EDITOR_MODE_PREVIEW
        DocEditorViewMode.SPLIT -> UiTags.DOC_EDITOR_MODE_SPLIT
    }
    this@ModeTab.SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
        modifier = Modifier
            .weight(1f)
            .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
            .fillMaxWidth()
            // Tag 与 Role 必须落在实际 SegmentedButton，而不是由外层容器伪造。
            .testTagCompat(tag)
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorPane(
    content: String,
    onContentChange: (String) -> Unit,
    verticalScrollState: ScrollState,
    horizontalScrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val inputDescription = stringResource(R.string.doc_editor_content_input_description)
    BasicTextField(
        value = content,
        onValueChange = onContentChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
            .testTagCompat(UiTags.DOC_EDITOR_INPUT)
            .semantics { contentDescription = inputDescription }
            .padding(NexaraSpacing.Medium),
        decorationBox = { innerTextField ->
            Box {
                if (content.isEmpty()) {
                    Text(
                        text = stringResource(R.string.doc_editor_typing_placeholder),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun PreviewPane(
    content: String,
    isTruncated: Boolean,
    verticalScrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(verticalScrollState)
            .testTagCompat(UiTags.DOC_EDITOR_PREVIEW)
            .padding(NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        if (isTruncated) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = stringResource(R.string.doc_editor_preview_truncated),
                    modifier = Modifier.padding(
                        horizontal = NexaraSpacing.Medium,
                        vertical = NexaraSpacing.Small,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MarkdownText(
            markdown = content,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize.value.toInt(),
            overrideColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PerformanceProtectedPane(
    previewSnapshot: DocEditorPreviewSnapshot,
    onCopyFullContent: () -> Unit,
    previewScrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(previewScrollState)
            .testTagCompat(UiTags.DOC_EDITOR_STATE_PERFORMANCE_PROTECTED)
            .padding(NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(NexaraSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Text(
                    text = stringResource(R.string.doc_editor_performance_protected_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.doc_editor_performance_protected_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onCopyFullContent,
                    modifier = Modifier
                        .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .testTagCompat(UiTags.DOC_EDITOR_COPY_PROTECTED_FULL),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(NexaraSpacing.Small))
                    Text(stringResource(R.string.doc_editor_copy_full_content))
                }
            }
        }
        Box(Modifier.testTagCompat(UiTags.DOC_EDITOR_PREVIEW)) {
            MarkdownText(
                markdown = previewSnapshot.content,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize.value.toInt(),
                overrideColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    StatePanel(
        icon = null,
        title = stringResource(R.string.doc_editor_loading),
        description = stringResource(R.string.doc_editor_loading_description),
        tag = UiTags.DOC_EDITOR_STATE_LOADING,
        modifier = modifier,
    ) {
        CircularProgressIndicator(Modifier.size(NexaraSpacing.XXLarge), strokeWidth = 3.dp)
    }
}

@Composable
private fun LoadErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StatePanel(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.doc_editor_load_failed_title),
        description = stringResource(R.string.doc_editor_load_failed_description),
        tag = UiTags.DOC_EDITOR_STATE_LOAD_ERROR,
        modifier = modifier,
    ) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
                .testTagCompat(UiTags.DOC_EDITOR_RETRY_LOAD),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(NexaraSpacing.Small))
            Text(stringResource(R.string.doc_editor_retry_load))
        }
    }
}

@Composable
private fun NotFoundState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StatePanel(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.doc_editor_not_found_title),
        description = stringResource(R.string.doc_editor_not_found_description),
        tag = UiTags.DOC_EDITOR_STATE_NOT_FOUND,
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier
                .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
                .testTagCompat(UiTags.DOC_EDITOR_RETRY_LOAD),
        ) {
            Text(stringResource(R.string.doc_editor_retry_load))
        }
    }
}

@Composable
private fun MetadataOnlyState(
    editor: DocEditorUiState,
    onDismissWarning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        DocumentIdentityReadOnly(editor)
        if (!editor.warningDismissed) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = NexaraSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(NexaraSpacing.Small))
                    Text(
                        text = stringResource(
                            if (editor.readonlyReason == DocEditorReadonlyReason.UnsupportedContent) {
                                R.string.doc_editor_unsupported_content_warning
                            } else {
                                R.string.doc_editor_large_file_warning
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismissWarning,
                        modifier = Modifier
                            .size(NexaraSpacing.MinimumTouchTarget)
                            .testTagCompat(UiTags.DOC_EDITOR_WARNING_DISMISS),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.doc_editor_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        StatePanel(
            icon = Icons.Rounded.Description,
            title = stringResource(R.string.doc_editor_large_file_read_only_title),
            description = stringResource(
                R.string.doc_editor_large_file_read_only_description,
                formatFileSize(editor.sizeBytes),
            ),
            tag = UiTags.DOC_EDITOR_STATE_LARGE_FILE,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DocumentIdentityReadOnly(editor: DocEditorUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTagCompat(UiTags.DOC_EDITOR_IDENTITY),
    ) {
        Text(
            text = editor.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.doc_editor_metadata,
                formatFileSize(editor.sizeBytes),
                stringResource(R.string.doc_editor_document_type),
            ),
            modifier = Modifier.testTagCompat(UiTags.DOC_EDITOR_METADATA),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatePanel(
    icon: ImageVector?,
    title: String,
    description: String,
    tag: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 加载/错误信息在短高窗口或 2x 字体下可以完整滚动到行动按钮。
            .verticalScroll(rememberScrollState())
            .testTagCompat(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(NexaraSpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(NexaraSpacing.XXLarge),
                )
                Spacer(Modifier.height(NexaraSpacing.Medium))
            }
            Text(
                text = title,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(NexaraSpacing.Small))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let {
                Spacer(Modifier.height(NexaraSpacing.Large))
                it()
            }
        }
    }
}

@Composable
private fun SaveNotice(
    title: String,
    description: String,
    primaryLabel: String,
    primaryTag: String,
    primaryIcon: ImageVector = Icons.Rounded.ContentCopy,
    onPrimary: () -> Unit,
    tag: String,
    secondaryLabel: String? = null,
    secondaryTag: String? = null,
    onSecondary: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTagCompat(tag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = NexaraSpacing.Medium,
                vertical = if (compact) NexaraSpacing.Small else NexaraSpacing.Medium,
            ),
            // 紧凑恢复态只压缩标题/说明/操作组之间的间隔；两个恢复按钮仍保留 8dp 间隔。
            verticalArrangement = Arrangement.spacedBy(
                if (compact) NexaraSpacing.XSmall else NexaraSpacing.Small,
            ),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val primaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                Button(
                    onClick = onPrimary,
                    modifier = buttonModifier
                        .testTagCompat(primaryTag)
                        .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget),
                ) {
                    Icon(primaryIcon, contentDescription = null)
                    Spacer(Modifier.width(NexaraSpacing.Small))
                    Text(primaryLabel)
                }
            }
            val secondaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                if (secondaryLabel != null && secondaryTag != null && onSecondary != null) {
                    OutlinedButton(
                        onClick = onSecondary,
                        modifier = buttonModifier
                            .testTagCompat(secondaryTag)
                            .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(NexaraSpacing.Small))
                        Text(secondaryLabel)
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackActions = shouldStackDocEditorNoticeActions(
                    availableWidthDp = maxWidth.value,
                    fontScale = LocalDensity.current.fontScale,
                )
                if (stackActions) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    ) {
                        primaryButton(Modifier.fillMaxWidth())
                        secondaryButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    ) {
                        primaryButton(Modifier)
                        secondaryButton(Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(
    editor: DocEditorUiState,
    viewMode: DocEditorViewMode,
    compactVertical: Boolean,
) {
    val modeLabel = when {
        editor.phase == DocEditorPhase.Saving -> stringResource(R.string.doc_editor_saving)
        editor.contentAccess == DocEditorContentAccess.MetadataOnly -> {
            stringResource(R.string.doc_editor_large_file_status)
        }
        editor.contentAccess == DocEditorContentAccess.PerformanceProtected -> {
            stringResource(R.string.doc_editor_performance_protected_status)
        }
        viewMode == DocEditorViewMode.PREVIEW -> stringResource(R.string.doc_editor_readonly)
        else -> stringResource(R.string.doc_editor_editing)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTagCompat(UiTags.DOC_EDITOR_STATUS),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val statistics = stringResource(
            R.string.doc_editor_statistics,
            stringResource(R.string.doc_editor_utf8),
            editor.wordCount,
            editor.content.length,
        )
        if (compactVertical) {
            Row(
                modifier = Modifier.padding(horizontal = NexaraSpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Text(
                    text = statistics,
                    modifier = Modifier
                        .weight(1f)
                        .testTagCompat(UiTags.DOC_EDITOR_STATISTICS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EditorStatusIndicator(editor = editor, modeLabel = modeLabel)
            }
        } else {
            Text(
                text = statistics,
                modifier = Modifier
                    .padding(horizontal = NexaraSpacing.XSmall)
                    .testTagCompat(UiTags.DOC_EDITOR_STATISTICS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(horizontal = NexaraSpacing.XSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                EditorStatusIndicator(editor = editor, modeLabel = modeLabel)
            }
        }
    }
}

@Composable
private fun EditorStatusIndicator(
    editor: DocEditorUiState,
    modeLabel: String,
) {
    Icon(
        imageVector = if (editor.isDirty) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
        contentDescription = null,
        tint = if (editor.isDirty) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.size(18.dp),
    )
    Text(
        text = modeLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PendingIndexNotice(onRetry: () -> Unit) {
    val retryPendingIndexDescription = "${stringResource(R.string.shared_btn_retry)}：" +
        stringResource(R.string.rag_index_retry_hint)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NexaraSpacing.Medium,
                vertical = NexaraSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.rag_index_retry_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = NexaraSpacing.MinimumTouchTarget,
                        minHeight = NexaraSpacing.MinimumTouchTarget,
                    )
                    // 明确这一个“重试”对应的是索引队列，而不是保存/加载重试。
                    // TextButton 会合并内部 Text 的语义，因此该描述也是辅助功能与测试
                    // 可以稳定定位的唯一可操作节点。
                    .semantics { contentDescription = retryPendingIndexDescription },
            ) {
                Text(stringResource(R.string.shared_btn_retry))
            }
        }
    }
}

@Composable
private fun DocEditorConfirmationDialog(
    confirmation: DocEditorConfirmation?,
    actions: DocEditorScreenActions,
) {
    if (confirmation == null) return
    val isDiscard = confirmation == DocEditorConfirmation.DiscardChanges
    AlertDialog(
        onDismissRequest = actions.onDismissConfirmation,
        modifier = Modifier.testTagCompat(
            if (isDiscard) UiTags.DOC_EDITOR_DISCARD_DIALOG else UiTags.DOC_EDITOR_RELOAD_DIALOG,
        ),
        title = {
            Text(
                stringResource(
                    if (isDiscard) {
                        R.string.doc_editor_discard_title
                    } else {
                        R.string.doc_editor_reload_title
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (isDiscard) {
                        R.string.doc_editor_discard_description
                    } else {
                        R.string.doc_editor_reload_description
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = if (isDiscard) actions.onConfirmDiscard else actions.onConfirmReload,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
                    .testTagCompat(
                        if (isDiscard) {
                            UiTags.DOC_EDITOR_DISCARD_CONFIRM
                        } else {
                            UiTags.DOC_EDITOR_RELOAD_CONFIRM
                        },
                    ),
            ) {
                Text(
                    stringResource(
                        if (isDiscard) {
                            R.string.doc_editor_discard_confirm
                        } else {
                            R.string.doc_editor_reload_confirm
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onDismissConfirmation,
                modifier = Modifier
                    .defaultMinSize(minHeight = NexaraSpacing.MinimumTouchTarget)
                    .testTagCompat(
                        if (isDiscard) {
                            UiTags.DOC_EDITOR_DISCARD_CANCEL
                        } else {
                            UiTags.DOC_EDITOR_RELOAD_CANCEL
                        },
                    ),
            ) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
    )
}

@Composable
private fun failureDescription(code: DocEditorFailureCode?): String = when (code) {
    DocEditorFailureCode.TitleRenameFailed -> stringResource(R.string.doc_editor_title_rename_failed)
    DocEditorFailureCode.SaveNotFound -> stringResource(R.string.doc_editor_missing_after_save_description)
    DocEditorFailureCode.SaveCancelled -> stringResource(R.string.doc_editor_save_cancelled_description)
    else -> stringResource(R.string.doc_editor_save_failed_description)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}

private fun Modifier.testTagCompat(tag: String): Modifier =
    testTag(tag)

private const val SPLIT_MIN_WIDTH_DP = 720f
private const val NOTICE_ACTIONS_STACK_MIN_WIDTH_DP = 480f
private const val DOC_EDITOR_IME_MIN_EDITABLE_HEIGHT_DP = 300f
