package com.promenar.nexara.ui.rag

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.MarkdownText
import com.promenar.nexara.ui.common.NexaraConfirmDialog
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
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
    LargeFileReadOnly,
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
    if (hasLoadedDocument && isLargeFile) return DocEditorVisibleState.LargeFileReadOnly
    return when (phase) {
        DocEditorPhase.Loading -> DocEditorVisibleState.Loading
        DocEditorPhase.LoadError -> DocEditorVisibleState.LoadError
        DocEditorPhase.Ready -> DocEditorVisibleState.Ready
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
    val editorState by viewModel.uiState.collectAsState()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreenContent(
    state: DocEditorScreenState,
    actions: DocEditorScreenActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val editor = state.editorState
    val visibleState = editor.toVisibleState()
    val savingContentDescription = stringResource(R.string.doc_editor_saving)
    val canSave = editor.hasLoadedDocument &&
        !editor.isLargeFile &&
        editor.isDirty &&
        editor.phase !in setOf(
            DocEditorPhase.Saving,
            DocEditorPhase.SaveConflict,
            DocEditorPhase.NotFound,
        )

    Scaffold(
        modifier = modifier.testTagCompat(UiTags.DOC_EDITOR_ROOT),
        containerColor = NexaraColors.CanvasBackground,
        snackbarHost = {
            snackbarHostState?.let { hostState ->
                SnackbarHost(hostState = hostState)
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = editor.title.ifBlank {
                            stringResource(R.string.doc_editor_screen_title)
                        },
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .zIndex(1f)
                            .testTagCompat(UiTags.DOC_EDITOR_BACK)
                            .clickable(
                                role = Role.Button,
                                onClick = actions.onRequestBack,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DOC_EDITOR_BACK_ICON,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                actions = {
                    if (editor.hasLoadedDocument && !editor.isLargeFile) {
                        if (editor.phase == DocEditorPhase.Saving) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTagCompat(UiTags.DOC_EDITOR_STATE_SAVING)
                                    .semantics {
                                        contentDescription = savingContentDescription
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = actions.onSave,
                                enabled = canSave,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTagCompat(UiTags.DOC_EDITOR_SAVE),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = stringResource(R.string.common_cd_save),
                                    tint = if (canSave) {
                                        NexaraColors.Primary
                                    } else {
                                        NexaraColors.OnSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground,
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
            val effectiveMode = if (!splitAvailable && state.viewMode == DocEditorViewMode.SPLIT) {
                DocEditorViewMode.EDIT
            } else {
                state.viewMode
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    DocEditorVisibleState.LargeFileReadOnly -> LargeFileReadOnlyState(
                        editor = editor,
                        onDismissWarning = actions.onDismissWarning,
                        modifier = Modifier.weight(1f),
                    )
                    else -> LoadedDocumentContent(
                        editor = editor,
                        visibleState = visibleState,
                        viewMode = effectiveMode,
                        splitAvailable = splitAvailable,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                    )
                }

                val noticeOwnsRemainingSpace = visibleState in setOf(
                    DocEditorVisibleState.SaveError,
                    DocEditorVisibleState.SaveConflict,
                    DocEditorVisibleState.NotFoundAfterSave,
                )
                if (editor.hasLoadedDocument && !noticeOwnsRemainingSpace) {
                    EditorStatusBar(
                        editor = editor,
                        viewMode = effectiveMode,
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
    actions: DocEditorScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DocumentIdentity(
            editor = editor,
            editable = viewMode != DocEditorViewMode.PREVIEW,
            onTitleChange = actions.onTitleChange,
        )
        ModeSelector(
            selectedMode = viewMode,
            splitAvailable = splitAvailable,
            onModeChange = actions.onViewModeChange,
        )
        when (visibleState) {
            DocEditorVisibleState.SaveError -> SaveNotice(
                title = stringResource(R.string.doc_editor_save_failed_title),
                description = failureDescription(editor.failureCode),
                primaryLabel = stringResource(R.string.doc_editor_retry_save),
                primaryTag = UiTags.DOC_EDITOR_RETRY_SAVE,
                primaryIcon = Icons.Rounded.Refresh,
                onPrimary = actions.onRetrySave,
                tag = UiTags.DOC_EDITOR_STATE_SAVE_ERROR,
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
            )
            else -> Unit
        }

        NexaraGlassCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape,
        ) {
            when (viewMode) {
                DocEditorViewMode.EDIT -> EditorPane(
                    content = editor.content,
                    onContentChange = actions.onContentChange,
                    lineCount = editor.totalLines,
                )
                DocEditorViewMode.PREVIEW -> PreviewPane(content = editor.content)
                DocEditorViewMode.SPLIT -> Row(Modifier.fillMaxSize()) {
                    EditorPane(
                        content = editor.content,
                        onContentChange = actions.onContentChange,
                        lineCount = editor.totalLines,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(NexaraColors.OutlineVariant),
                    )
                    PreviewPane(
                        content = editor.content,
                        modifier = Modifier.weight(1f),
                    )
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = NexaraColors.Primary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            val titleDescription = stringResource(R.string.doc_editor_title_input_description)
            BasicTextField(
                value = editor.title,
                onValueChange = onTitleChange,
                readOnly = !editable,
                singleLine = true,
                textStyle = NexaraTypography.headlineMedium.copy(color = NexaraColors.OnSurface),
                cursorBrush = SolidColor(NexaraColors.Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTagCompat(UiTags.DOC_EDITOR_TITLE_INPUT)
                    .semantics { contentDescription = titleDescription },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (editor.title.isEmpty()) {
                            Text(
                                text = stringResource(R.string.doc_editor_title_placeholder),
                                style = NexaraTypography.headlineMedium,
                                color = NexaraColors.OnSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Text(
                text = stringResource(
                    R.string.doc_editor_metadata,
                    formatFileSize(editor.sizeBytes),
                    stringResource(R.string.doc_editor_document_type),
                ),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: DocEditorViewMode,
    splitAvailable: Boolean,
    onModeChange: (DocEditorViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceContainer)
            .border(0.5.dp, NexaraColors.OutlineVariant, NexaraShapes.medium),
    ) {
        ModeTab(
            icon = Icons.Rounded.Edit,
            label = stringResource(R.string.doc_editor_edit),
            selected = selectedMode == DocEditorViewMode.EDIT,
            tag = UiTags.DOC_EDITOR_MODE_EDIT,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(DocEditorViewMode.EDIT) },
        )
        ModeTab(
            icon = Icons.Rounded.Visibility,
            label = stringResource(R.string.doc_editor_preview),
            selected = selectedMode == DocEditorViewMode.PREVIEW,
            tag = UiTags.DOC_EDITOR_MODE_PREVIEW,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(DocEditorViewMode.PREVIEW) },
        )
        if (splitAvailable) {
            ModeTab(
                icon = Icons.Rounded.VerticalSplit,
                label = stringResource(R.string.doc_editor_split),
                selected = selectedMode == DocEditorViewMode.SPLIT,
                tag = UiTags.DOC_EDITOR_MODE_SPLIT,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(DocEditorViewMode.SPLIT) },
            )
        }
    }
}

@Composable
private fun ModeTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) NexaraColors.SurfaceBright else Color.Transparent,
        label = "docEditorMode",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(background)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .testTagCompat(tag)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = NexaraTypography.labelMedium,
            color = if (selected) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorPane(
    content: String,
    onContentChange: (String) -> Unit,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val lineNumbers = remember(lineCount) {
        (1..lineCount.coerceAtLeast(1)).joinToString(separator = "\n")
    }
    val inputDescription = stringResource(R.string.doc_editor_content_input_description)
    Row(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(verticalScrollState),
    ) {
        Text(
            text = lineNumbers,
            modifier = Modifier
                .width(48.dp)
                .background(NexaraColors.SurfaceLow)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 22.sp,
                color = NexaraColors.OutlineVariant,
            ),
        )
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = NexaraColors.OnSurface,
            ),
            cursorBrush = SolidColor(NexaraColors.Primary),
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState)
                .defaultMinSize(minHeight = 240.dp)
                .testTagCompat(UiTags.DOC_EDITOR_INPUT)
                .semantics { contentDescription = inputDescription }
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (content.isEmpty()) {
                        Text(
                            text = stringResource(R.string.doc_editor_typing_placeholder),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = NexaraColors.OnSurfaceVariant,
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun PreviewPane(
    content: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTagCompat(UiTags.DOC_EDITOR_PREVIEW)
            .padding(20.dp),
    ) {
        MarkdownText(
            markdown = content,
            fontSize = 15,
            overrideColor = NexaraColors.OnSurface,
        )
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
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
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
                .defaultMinSize(minHeight = 48.dp)
                .testTagCompat(UiTags.DOC_EDITOR_RETRY_LOAD),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
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
                .defaultMinSize(minHeight = 48.dp)
                .testTagCompat(UiTags.DOC_EDITOR_RETRY_LOAD),
        ) {
            Text(stringResource(R.string.doc_editor_retry_load))
        }
    }
}

@Composable
private fun LargeFileReadOnlyState(
    editor: DocEditorUiState,
    onDismissWarning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DocumentIdentityReadOnly(editor)
        if (!editor.warningDismissed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexaraShapes.medium)
                    .background(NexaraColors.ErrorContainer.copy(alpha = 0.22f))
                    .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.35f), NexaraShapes.medium)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = NexaraColors.Error)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.doc_editor_large_file_warning),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissWarning,
                    modifier = Modifier
                        .size(48.dp)
                        .testTagCompat(UiTags.DOC_EDITOR_WARNING_DISMISS),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.doc_editor_dismiss),
                        tint = NexaraColors.OnSurfaceVariant,
                    )
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
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = editor.title,
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.doc_editor_metadata,
                formatFileSize(editor.sizeBytes),
                stringResource(R.string.doc_editor_document_type),
            ),
            style = NexaraTypography.labelMedium,
            color = NexaraColors.OnSurfaceVariant,
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
    NexaraGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTagCompat(tag),
        shape = NexaraShapes.large as RoundedCornerShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = title,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant,
            )
            action?.let {
                Spacer(Modifier.height(16.dp))
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLow)
            .border(0.5.dp, NexaraColors.OutlineVariant, NexaraShapes.medium)
            .testTagCompat(tag)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
            },
            style = NexaraTypography.titleMedium,
            color = NexaraColors.OnSurface,
        )
        Text(description, style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
        val primaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            Button(
                onClick = onPrimary,
                modifier = buttonModifier
                    .testTagCompat(primaryTag)
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Icon(primaryIcon, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(primaryLabel)
            }
        }
        val secondaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            if (secondaryLabel != null && secondaryTag != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = buttonModifier
                        .testTagCompat(secondaryTag)
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    primaryButton(Modifier.fillMaxWidth())
                    secondaryButton(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    primaryButton(Modifier)
                    secondaryButton(Modifier)
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(editor: DocEditorUiState, viewMode: DocEditorViewMode) {
    val wordCount = remember(editor.content) {
        editor.content.split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val modeLabel = when {
        editor.isLargeFile -> stringResource(R.string.doc_editor_large_file_status)
        editor.phase == DocEditorPhase.Saving -> stringResource(R.string.doc_editor_saving)
        viewMode == DocEditorViewMode.PREVIEW -> stringResource(R.string.doc_editor_readonly)
        else -> stringResource(R.string.doc_editor_editing)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLow)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
            .testTagCompat(UiTags.DOC_EDITOR_STATUS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(
                R.string.doc_editor_statistics,
                stringResource(R.string.doc_editor_utf8),
                wordCount,
                editor.content.length,
            ),
            style = NexaraTypography.bodySmall,
            color = NexaraColors.OnSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (editor.isDirty) NexaraColors.StatusWarning else NexaraColors.StatusSuccess,
                        CircleShape,
                    ),
            )
            Text(modeLabel, style = NexaraTypography.bodySmall, color = NexaraColors.OnSurface)
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
    Dialog(onDismissRequest = actions.onDismissConfirmation) {
        Box(
            modifier = Modifier.testTagCompat(
                if (isDiscard) UiTags.DOC_EDITOR_DISCARD_DIALOG else UiTags.DOC_EDITOR_RELOAD_DIALOG,
            ),
        ) {
            NexaraConfirmDialog(
                title = stringResource(
                    if (isDiscard) {
                        R.string.doc_editor_discard_title
                    } else {
                        R.string.doc_editor_reload_title
                    },
                ),
                message = stringResource(
                    if (isDiscard) {
                        R.string.doc_editor_discard_description
                    } else {
                        R.string.doc_editor_reload_description
                    },
                ),
                confirmText = stringResource(
                    if (isDiscard) {
                        R.string.doc_editor_discard_confirm
                    } else {
                        R.string.doc_editor_reload_confirm
                    },
                ),
                cancelText = null,
                onConfirm = if (isDiscard) actions.onConfirmDiscard else actions.onConfirmReload,
                onCancel = actions.onDismissConfirmation,
                isDestructive = true,
                confirmButtonModifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTagCompat(
                        if (isDiscard) {
                            UiTags.DOC_EDITOR_DISCARD_CONFIRM
                        } else {
                            UiTags.DOC_EDITOR_RELOAD_CONFIRM
                        },
                    ),
                content = {
                    TextButton(
                        onClick = actions.onDismissConfirmation,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
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
    }
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
