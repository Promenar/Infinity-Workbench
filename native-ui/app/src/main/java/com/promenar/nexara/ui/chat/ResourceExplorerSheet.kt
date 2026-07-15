package com.promenar.nexara.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
        title = stringResource(R.string.resource_explorer_title)
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
        if (state.selectedTab == ResourceExplorerTab.Files) {
            NexaraSearchBar(
                value = state.searchQuery,
                onValueChange = actions.onSearchQueryChanged,
                placeholder = stringResource(R.string.resource_explorer_search_placeholder),
                modifier = Modifier.testTag(UiTags.RESOURCE_EXPLORER_SEARCH),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = actions.onImportFiles,
                enabled = state.workspaceReady && !state.isImporting,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag(UiTags.RESOURCE_EXPLORER_IMPORT),
            ) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (state.isImporting) R.string.resource_explorer_importing
                        else R.string.resource_explorer_import_files,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (state.importItems.isNotEmpty()) {
            ImportStatusList(
                items = state.importItems,
                isImporting = state.isImporting,
                onRetry = actions.onRetryImport,
                onClear = actions.onClearImportResults,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(
            selectedTabIndex = state.selectedTab.page,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = NexaraColors.Primary,
            divider = { HorizontalDivider(color = NexaraColors.OutlineVariant) },
            indicator = { tabPositions ->
                if (state.selectedTab.page < tabPositions.size) {
                    val pos = tabPositions[state.selectedTab.page]
                    Box(
                        Modifier
                            .tabIndicatorOffset(pos)
                            .height(3.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(NexaraColors.Primary),
                    )
                }
            },
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
                ResourceExplorerTab.Files -> filesContent()
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NexaraColors.SurfaceLow)
            .padding(8.dp)
            .testTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.resource_explorer_import_status),
                style = NexaraTypography.labelMedium,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp)) {
            items(items, key = { it.uri.toString() }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.displayName,
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (item.status) {
                            ShareImportStatus.Pending -> stringResource(R.string.resource_explorer_status_pending)
                            ShareImportStatus.Importing -> stringResource(R.string.resource_explorer_status_importing)
                            ShareImportStatus.Created -> stringResource(R.string.resource_explorer_status_created)
                            ShareImportStatus.Rejected -> stringResource(
                                R.string.resource_explorer_status_rejected,
                                rejectReasonLabel(item.reason),
                            )
                        },
                        style = NexaraTypography.labelSmall,
                        color = if (item.status == ShareImportStatus.Rejected) NexaraColors.Error else NexaraColors.Primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shouldOfferImportRetry(item)) {
                        TextButton(
                            onClick = { onRetry(item.uri) },
                            enabled = !isImporting,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Text(stringResource(R.string.shared_btn_retry)) }
                    }
                }
            }
        }
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
