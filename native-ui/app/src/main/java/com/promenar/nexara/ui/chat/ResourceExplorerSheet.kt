package com.promenar.nexara.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch

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
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val importItems by viewModel.importItems.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
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
        Column {
            NexaraSearchBar(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = stringResource(R.string.resource_explorer_search_placeholder)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { filePicker.launch(SharedFileImporter.SUPPORTED_MIME_TYPES.toTypedArray()) },
                    enabled = workspaceRootUuid != null && !isImporting,
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (isImporting) R.string.resource_explorer_importing
                            else R.string.resource_explorer_import_files,
                        ),
                    )
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            loadError?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.Error.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.Error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::retryLoadSession) {
                        Text(stringResource(R.string.shared_btn_retry))
                    }
                }
            }
            if (importItems.isNotEmpty()) {
                ImportStatusList(
                    items = importItems,
                    isImporting = isImporting,
                    onRetry = viewModel::retryImport,
                    onClear = viewModel::clearImportResults,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = NexaraColors.Primary,
                divider = {
                    HorizontalDivider(color = NexaraColors.OutlineVariant)
                },
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        val pos = tabPositions[pagerState.currentPage]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NexaraColors.Primary)
                        )
                    }
                }
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.resource_explorer_tab_files),
                            style = NexaraTypography.labelMedium,
                        )
                    },
                    selectedContentColor = NexaraColors.Primary,
                    unselectedContentColor = NexaraColors.OnSurfaceVariant
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = {
                        Icon(
                            Icons.Rounded.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            if (recycleBinCount > 0) {
                                stringResource(R.string.resource_explorer_recycle_bin_count, recycleBinCount)
                            } else {
                                stringResource(R.string.resource_explorer_recycle_bin)
                            },
                            style = NexaraTypography.labelMedium
                        )
                    },
                    selectedContentColor = NexaraColors.Primary,
                    unselectedContentColor = NexaraColors.OnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> FilesPanel(
                        workspaceRootUuid = workspaceRootUuid,
                        workspaceRepo = viewModel.workspaceRepo,
                        searchQuery = searchQuery,
                        rootFiles = rootFiles,
                    )
                    1 -> RecycleBinPanel(
                        workspaceRootUuid = workspaceRootUuid,
                        workspaceRepo = viewModel.workspaceRepo,
                        scopedFiles = recycledFiles,
                    )
                }
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
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.resource_explorer_import_status),
                style = NexaraTypography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, enabled = !isImporting) {
                Text(stringResource(R.string.resource_explorer_collapse))
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
                    )
                    if (shouldOfferImportRetry(item)) {
                        TextButton(
                            onClick = { onRetry(item.uri) },
                            enabled = !isImporting,
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
