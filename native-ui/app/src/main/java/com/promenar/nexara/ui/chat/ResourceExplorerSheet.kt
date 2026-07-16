package com.promenar.nexara.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.chat.components.RecycleBinPanel
import com.promenar.nexara.ui.chat.components.ResourceExplorerViewModel
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.ShareImportStatus
import com.promenar.nexara.share.core.ShareRejectReason
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ResourceExplorerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    sessionId: String,
    viewModel: ResourceExplorerViewModel = viewModel(
        factory = ResourceExplorerViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recycleBinCount by viewModel.recycleBinCount.collectAsState()
    val workspaceRootUuid by viewModel.workspaceRootUuid.collectAsState()
    val rootFiles by viewModel.rootFiles.collectAsState()
    val recycledFiles by viewModel.recycledFiles.collectAsState()
    val recycleOperationState by viewModel.recycleOperationState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val importItems by viewModel.importItems.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    var selectedTab by rememberSaveable(sessionId) { mutableStateOf(ResourceExplorerTab.Files) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    NexaraBottomSheet(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.resource_explorer_title),
        modifier = Modifier.fillMaxHeight(),
    ) {
        ResourceExplorerSheetContent(
            state = ResourceExplorerSheetState(
                selectedTab = selectedTab,
                searchQuery = searchQuery,
                recycleBinCount = recycleBinCount,
                workspaceReady = workspaceRootUuid != null,
                importItems = importItems,
                isImporting = isImporting,
                isLoading = isLoading,
                hasLoadError = loadError != null,
            ),
            actions = ResourceExplorerSheetActions(
                onSearchQueryChanged = viewModel::updateSearchQuery,
                onImportFiles = {
                    filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray())
                },
                onRetryImport = viewModel::retryImport,
                onClearImportResults = viewModel::clearImportResults,
                onTabSelected = { selectedTab = it },
                onRetryLoad = viewModel::retryLoadSession,
            ),
            filesContent = {
                FilesPanel(
                    workspaceRootUuid = workspaceRootUuid,
                    workspaceRepo = viewModel.workspaceRepo,
                    searchQuery = searchQuery,
                    rootFiles = rootFiles,
                    isLoading = isLoading,
                    hasLoadError = loadError != null,
                    onRetryLoad = viewModel::retryLoadSession,
                )
            },
            recycleBinContent = {
                RecycleBinPanel(
                    files = recycledFiles,
                    operationState = recycleOperationState,
                    onRestoreFiles = viewModel::restoreRecycledFiles,
                    onPermanentlyDeleteFiles = viewModel::permanentlyDeleteRecycledFiles,
                    onEmptyRecycleBin = viewModel::emptyRecycleBin,
                    onRetryOperation = viewModel::retryFailedRecycleOperation,
                    onClearOperationState = viewModel::clearRecycleOperationState,
                )
            },
        )
    }
}

internal enum class ResourceExplorerTab(val page: Int) {
    Files(0),
    RecycleBin(1),
    ;

    companion object {
        fun fromPage(page: Int): ResourceExplorerTab =
            entries.firstOrNull { it.page == page } ?: Files
    }
}

internal data class ResourceExplorerSheetState(
    val selectedTab: ResourceExplorerTab = ResourceExplorerTab.Files,
    val searchQuery: String = "",
    val recycleBinCount: Int = 0,
    val workspaceReady: Boolean = false,
    val importItems: List<ShareImportItem> = emptyList(),
    val isImporting: Boolean = false,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
)

internal data class ResourceExplorerSheetActions(
    val onSearchQueryChanged: (String) -> Unit = {},
    val onImportFiles: () -> Unit = {},
    val onRetryImport: (android.net.Uri) -> Unit = {},
    val onClearImportResults: () -> Unit = {},
    val onTabSelected: (ResourceExplorerTab) -> Unit = {},
    val onRetryLoad: () -> Unit = {},
)

@Composable
internal fun ResourceExplorerSheetContent(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
    recycleBinContent: @Composable () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = state.selectedTab.page,
        pageCount = { ResourceExplorerTab.entries.size },
    )
    val currentSelectedTab by rememberUpdatedState(state.selectedTab)
    val currentOnTabSelected by rememberUpdatedState(actions.onTabSelected)

    LaunchedEffect(state.selectedTab) {
        if (pagerState.currentPage != state.selectedTab.page) {
            pagerState.animateScrollToPage(state.selectedTab.page)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val tab = ResourceExplorerTab.fromPage(page)
                if (tab != currentSelectedTab) currentOnTabSelected(tab)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_ROOT),
    ) {
        PrimaryTabRow(
            selectedTabIndex = state.selectedTab.page,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = NexaraColors.Primary,
            divider = { HorizontalDivider(color = NexaraColors.OutlineVariant) },
        ) {
            ResourceExplorerTab.entries.forEach { tab ->
                val isFiles = tab == ResourceExplorerTab.Files
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { actions.onTabSelected(tab) },
                    icon = {
                        Icon(
                            if (isFiles) Icons.Rounded.Folder else Icons.Rounded.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    text = {
                        Text(
                            if (isFiles) {
                                stringResource(R.string.resource_explorer_tab_files)
                            } else if (state.recycleBinCount > 0) {
                                stringResource(
                                    R.string.resource_explorer_recycle_bin_count,
                                    state.recycleBinCount,
                                )
                            } else {
                                stringResource(R.string.resource_explorer_recycle_bin)
                            },
                            style = NexaraTypography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selectedContentColor = NexaraColors.Primary,
                    unselectedContentColor = NexaraColors.OnSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(
                            if (isFiles) UiTags.RESOURCE_EXPLORER_TAB_FILES
                            else UiTags.RESOURCE_EXPLORER_TAB_RECYCLE_BIN,
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(UiTags.RESOURCE_EXPLORER_PAGER),
        ) { page ->
            when (ResourceExplorerTab.fromPage(page)) {
                ResourceExplorerTab.Files -> ResourceExplorerFilesPage(
                    state = state,
                    actions = actions,
                    filesContent = filesContent,
                )
                ResourceExplorerTab.RecycleBin -> when {
                    state.isLoading -> ResourceExplorerSessionState(isLoading = true)
                    state.hasLoadError -> ResourceExplorerSessionState(
                        isLoading = false,
                        onRetry = actions.onRetryLoad,
                    )
                    else -> recycleBinContent()
                }
            }
        }
    }
}

@Composable
private fun ResourceExplorerFilesPage(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    filesContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactLandscape = maxHeight < 480.dp && LocalDensity.current.fontScale < 1.5f
            if (compactLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResourceExplorerSearch(state, actions, Modifier.weight(1f))
                    ResourceExplorerImportAction(state, actions)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResourceExplorerSearch(state, actions, Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ResourceExplorerImportAction(state, actions)
                    }
                }
            }
        }

        if (state.importItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            ImportStatusList(
                items = state.importItems,
                isImporting = state.isImporting,
                onRetry = actions.onRetryImport,
                onClear = actions.onClearImportResults,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            filesContent()
        }
    }
}

@Composable
private fun ResourceExplorerSearch(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
    modifier: Modifier,
) {
    NexaraSearchBar(
        value = state.searchQuery,
        onValueChange = actions.onSearchQueryChanged,
        placeholder = stringResource(R.string.resource_explorer_search_placeholder),
        modifier = modifier.testTag(UiTags.RESOURCE_EXPLORER_SEARCH),
    )
}

@Composable
private fun ResourceExplorerImportAction(
    state: ResourceExplorerSheetState,
    actions: ResourceExplorerSheetActions,
) {
    FilledTonalButton(
        onClick = actions.onImportFiles,
        enabled = state.workspaceReady && !state.isImporting,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(UiTags.RESOURCE_EXPLORER_IMPORT),
    ) {
        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            stringResource(
                if (state.isImporting) R.string.resource_explorer_importing
                else R.string.resource_explorer_import_files,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResourceExplorerSessionState(
    isLoading: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val message = stringResource(
        if (isLoading) R.string.shared_loading
        else R.string.resource_explorer_error_workspace_load_failed,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .semantics {
                liveRegion = if (isLoading) LiveRegionMode.Polite else LiveRegionMode.Assertive
                stateDescription = message
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = message,
            style = NexaraTypography.bodyMedium,
            color = if (isLoading) NexaraColors.OnSurfaceVariant else NexaraColors.Error,
        )
        if (!isLoading && onRetry != null) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD),
            ) {
                Text(stringResource(R.string.shared_btn_retry))
            }
        }
    }
}

@Composable
private fun ImportStatusList(
    items: List<ShareImportItem>,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NexaraColors.SurfaceLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.resource_explorer_import_status),
                    style = NexaraTypography.titleMedium,
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClear,
                    enabled = !isImporting,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS),
                ) {
                    Text(
                        stringResource(R.string.resource_explorer_clear_results),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    ImportStatusRow(
                        item = item,
                        isImporting = isImporting,
                        onRetry = onRetry,
                    )
                    HorizontalDivider(color = NexaraColors.OutlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ImportStatusRow(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
) {
    val statusText = when (item.status) {
        ShareImportStatus.Pending -> stringResource(R.string.resource_explorer_status_pending)
        ShareImportStatus.Importing -> stringResource(R.string.resource_explorer_status_importing)
        ShareImportStatus.Created -> stringResource(R.string.resource_explorer_status_created)
        ShareImportStatus.Rejected -> stringResource(
            R.string.resource_explorer_status_rejected,
            rejectReasonLabel(item.reason),
        )
    }
    val statusColor = if (item.status == ShareImportStatus.Rejected) {
        NexaraColors.Error
    } else {
        NexaraColors.OnSurfaceVariant
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val stack = maxWidth < 420.dp || LocalDensity.current.fontScale >= 1.5f
        val content: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = when (item.status) {
                        ShareImportStatus.Pending, ShareImportStatus.Importing -> Icons.Rounded.HourglassTop
                        ShareImportStatus.Created -> Icons.Rounded.CheckCircle
                        ShareImportStatus.Rejected -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.displayName,
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText,
                        style = NexaraTypography.bodySmall,
                        color = statusColor,
                        modifier = Modifier.testTag(
                            UiTags.resourceExplorerImportStatus(item.uri.toString()),
                        ),
                    )
                }
            }
        }

        if (stack) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content(Modifier.fillMaxWidth())
                if (shouldOfferImportRetry(item)) {
                    ImportRetryAction(item, isImporting, onRetry, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content(Modifier.weight(1f))
                if (shouldOfferImportRetry(item)) {
                    ImportRetryAction(item, isImporting, onRetry, Modifier)
                }
            }
        }
    }
}

@Composable
private fun ImportRetryAction(
    item: ShareImportItem,
    isImporting: Boolean,
    onRetry: (android.net.Uri) -> Unit,
    modifier: Modifier,
) {
    val actionLabel = stringResource(R.string.resource_explorer_retry_document, item.displayName)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = !isImporting,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = { onRetry(item.uri) },
            )
            .testTag(UiTags.resourceExplorerImportRetry(item.uri.toString()))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.shared_btn_retry),
            style = NexaraTypography.labelLarge,
            color = if (isImporting) {
                NexaraColors.OnSurfaceVariant.copy(alpha = 0.38f)
            } else {
                NexaraColors.Primary
            },
        )
    }
}

@Composable
private fun rejectReasonLabel(reason: ShareRejectReason?): String = stringResource(
    when (reason) {
        ShareRejectReason.TargetRequired -> R.string.share_import_reason_target
        ShareRejectReason.UnsupportedMime -> R.string.share_import_reason_mime
        ShareRejectReason.MimeMismatch -> R.string.share_import_reason_mismatch
        ShareRejectReason.InvalidName -> R.string.share_import_reason_name
        ShareRejectReason.PermissionDenied -> R.string.share_import_reason_permission
        ShareRejectReason.EmptyFile -> R.string.share_import_reason_empty
        ShareRejectReason.ItemTooLarge -> R.string.share_import_reason_item_large
        ShareRejectReason.BatchTooLarge -> R.string.share_import_reason_batch_large
        ShareRejectReason.ReadFailed -> R.string.share_import_reason_read
        ShareRejectReason.WriteFailed -> R.string.share_import_reason_write
        ShareRejectReason.IndexScheduleFailed -> R.string.share_import_reason_index
        null -> R.string.resource_explorer_reason_unknown
    },
)

internal fun shouldOfferImportRetry(item: ShareImportItem): Boolean =
    item.status == ShareImportStatus.Rejected && item.reason?.retryable == true
