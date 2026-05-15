package com.promenar.nexara.ui.rag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.rag.components.FolderItem
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton

private enum class PortalTab { DOCUMENTS, MEMORY, GRAPH }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RagHomeScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as android.app.Application)),
    onNavigateToFolder: (String, String) -> Unit = { _, _ -> },
    onNavigateToConfig: () -> Unit = {},
    onNavigateToGraph: () -> Unit = {},
    onNavigateToDocEditor: (String) -> Unit = {}
) {
    val stats by viewModel.stats.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val folderStats by viewModel.folderStats.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val indexingStatus by viewModel.indexingStatus.collectAsState()
    val indexingSubStatus by viewModel.indexingSubStatus.collectAsState()
    val lastQueueError by viewModel.lastQueueError.collectAsState()
    val documents by viewModel.documents.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val memoryVectors by viewModel.memoryVectors.collectAsState()

    var currentTab by remember { mutableStateOf(PortalTab.DOCUMENTS) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var memoryDeleteTarget by remember { mutableStateOf<MemoryVectorRecord?>(null) }
    var expandedMemoryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentTab) {
        if (currentTab == PortalTab.MEMORY) {
            viewModel.loadMemoryVectors()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importDocuments(uris)
        }
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = {
                    if (selectedIds.isNotEmpty()) {
                        Text(
                            stringResource(R.string.rag_home_selected_count, selectedIds.size),
                            style = NexaraTypography.headlineLarge,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    } else {
                        Text(
                            stringResource(R.string.rag_home_title),
                            style = NexaraTypography.headlineLarge,
                            color = NexaraColors.OnSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        if (showNewFolderDialog) {
            NewFolderDialog(
                onDismiss = { showNewFolderDialog = false },
                onConfirm = { name ->
                    viewModel.createFolder(name)
                    showNewFolderDialog = false
                }
            )
        }
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = 8.dp, bottom = 160.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stickyHeader {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.CanvasBackground.copy(alpha = 0.9f))
                            .padding(bottom = 12.dp)
                    ) {
                        NexaraSearchBar(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.search(it)
                            },
                            placeholder = stringResource(R.string.rag_home_search)
                        )
                    }
                }

                item {
                    TabRow(
                        selectedTabIndex = currentTab.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = NexaraColors.Primary,
                        divider = {
                            HorizontalDivider(color = NexaraColors.OutlineVariant)
                        }
                    ) {
                        listOf(
                            PortalTab.DOCUMENTS to (Icons.Rounded.Description to stringResource(R.string.rag_home_documents)),
                            PortalTab.MEMORY to (Icons.Rounded.Psychology to stringResource(R.string.rag_home_memory)),
                            PortalTab.GRAPH to (Icons.Rounded.AccountTree to stringResource(R.string.rag_home_graph))
                        ).forEach { (tab, data) ->
                            Tab(
                                selected = currentTab == tab,
                                onClick = {
                                    when (tab) {
                                        PortalTab.GRAPH -> onNavigateToGraph()
                                        else -> currentTab = tab
                                    }
                                },
                                icon = {
                                    Icon(
                                        data.first,
                                        contentDescription = data.second,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        data.second,
                                        style = NexaraTypography.labelMedium
                                    )
                                },
                                selectedContentColor = NexaraColors.Primary,
                                unselectedContentColor = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                }

                if (isIndexing) {
                    item {
                        IndexingProgressBar(
                            progress = indexingProgress,
                            statusText = indexingStatus,
                            subStatusText = indexingSubStatus
                        )
                    }
                }

                if (lastQueueError != null && !isIndexing) {
                    item {
                        IndexingProgressBar(
                            progress = 0f,
                            statusText = lastQueueError,
                            subStatusText = "请检查 Embedding 模型配置后重新导入"
                        )
                    }
                }

                when (currentTab) {
                    PortalTab.DOCUMENTS -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.rag_home_section_collections),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(NexaraColors.SurfaceHigh)
                                            .clickable { showNewFolderDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                                            Text(stringResource(R.string.rag_home_new), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.Primary)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.5f))
                                    .border(1.dp, NexaraColors.OutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(NexaraColors.SurfaceHigh, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(24.dp))
                                    }
                                    Text(stringResource(R.string.rag_home_upload_area), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                    Text(stringResource(R.string.rag_home_upload_hint), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                                }
                            }
                        }

                        if (folders.isEmpty()) {
                            item {
                                NexaraGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = NexaraShapes.large as RoundedCornerShape
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(40.dp))
                                        Text(stringResource(R.string.rag_home_empty_title), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                        Text(stringResource(R.string.rag_home_empty_subtitle), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                                    }
                                }
                            }
                        } else {
                            items(folders, key = { it.id }) { folder ->
                                FolderItem(
                                    name = folder.name,
                                    documentCount = folderStats[folder.id] ?: 0,
                                    onClick = { onNavigateToFolder(folder.id, folder.name) }
                                )
                            }
                        }

                        if (searchResults.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.rag_home_search_results),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                            }
                            items(searchResults, key = { it.document.id }) { result ->
                                DocListItem(
                                    doc = result.document,
                                    isSelected = selectedIds.contains(result.document.id),
                                    onSelect = { checked ->
                                        if (checked) selectedIds.add(result.document.id) else selectedIds.remove(result.document.id)
                                    },
                                    showCheckbox = selectedIds.isNotEmpty(),
                                    onClick = { onNavigateToDocEditor(result.document.id) },
                                    onExtractKG = { strategy -> viewModel.extractKnowledgeGraph(result.document.id, strategy) },
                                    snippet = result.snippet
                                )
                            }
                        } else {
                            item {
                                Text(
                                    stringResource(R.string.rag_home_recent_docs),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                            }
                            val shownDocs = if (searchQuery.isBlank()) documents.take(10) else emptyList()
                            if (shownDocs.isNotEmpty()) {
                                items(shownDocs, key = { it.id }) { doc ->
                                    DocListItem(doc = doc, isSelected = selectedIds.contains(doc.id), onSelect = { checked ->
                                        if (checked) selectedIds.add(doc.id) else selectedIds.remove(doc.id)
                                    }, showCheckbox = selectedIds.isNotEmpty(), onClick = { onNavigateToDocEditor(doc.id) },
                                        onExtractKG = { strategy -> viewModel.extractKnowledgeGraph(doc.id, strategy) })
                                }
                            }
                        }
                    }
                    PortalTab.MEMORY -> {
                        item {
                            Text(stringResource(R.string.rag_home_memory_section), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NexaraGlassCard(
                                    modifier = Modifier.weight(1f),
                                    shape = NexaraShapes.large as RoundedCornerShape
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.rag_home_memory_total_count, memoryVectors.size),
                                            style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = NexaraColors.OnSurface
                                        )
                                        val estTokens = memoryVectors.sumOf { it.content.length / 3 }
                                        Text(
                                            stringResource(R.string.rag_home_memory_est_tokens, estTokens),
                                            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                            color = NexaraColors.OnSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (memoryVectors.isEmpty()) {
                            item {
                                NexaraGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = NexaraShapes.large as RoundedCornerShape
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Rounded.Psychology, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(40.dp))
                                        Text(stringResource(R.string.rag_home_memory_empty), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                    }
                                }
                            }
                        } else {
                            items(memoryVectors, key = { it.id }) { memory ->
                                val isExpanded = expandedMemoryId == memory.id
                                NexaraGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                expandedMemoryId = if (isExpanded) null else memory.id
                                            },
                                            onLongClick = { memoryDeleteTarget = memory }
                                        ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (isExpanded) memory.content else memory.content.take(120) + if (memory.content.length > 120) "…" else "",
                                            style = NexaraTypography.bodyMedium,
                                            color = NexaraColors.OnSurface,
                                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            memory.sessionId?.let { sid ->
                                                Text(
                                                    "Session: ${sid.take(8)}…",
                                                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                                                    color = NexaraColors.Primary.copy(alpha = 0.7f)
                                                )
                                            }
                                            Text(
                                                java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(memory.createdAt)),
                                                style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                                                color = NexaraColors.OnSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PortalTab.GRAPH -> {}
                }
            }


            if (selectedIds.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = 100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(NexaraColors.SurfaceLow.copy(alpha = 0.95f))
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.rag_home_selected_count, selectedIds.size),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.rag_home_clear_all),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary,
                                modifier = Modifier.clickable { selectedIds.clear() }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NexaraColors.SurfaceContainer)
                                    .clickable { showMoveSheet = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.rag_home_move), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NexaraColors.SurfaceContainer)
                                    .clickable { },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.rag_home_reindex), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NexaraColors.Error.copy(alpha = 0.1f))
                                    .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    .clickable { showDeleteConfirm = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = NexaraColors.Error, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.Error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMoveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.rag_home_move_to_folder), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                folders.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable {
                                showMoveSheet = false
                                selectedIds.clear()
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
                            Text(folder.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                        Text(
                            stringResource(R.string.rag_home_docs_count, folderStats[folder.id] ?: 0),
                            style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ModalBottomSheet(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.rag_home_delete_confirm_title, selectedIds.size), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                Text(stringResource(R.string.shared_action_cannot_undo), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable { showDeleteConfirm = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.common_btn_cancel), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Error)
                            .clickable {
                                viewModel.deleteDocuments(selectedIds.toList())
                                selectedIds.clear()
                                showDeleteConfirm = false
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.OnError)
                    }
                }
            }
        }
    }

    if (memoryDeleteTarget != null) {
        AlertDialog(
            onDismissRequest = { memoryDeleteTarget = null },
            title = { Text(stringResource(R.string.rag_home_memory_delete_confirm_title), style = NexaraTypography.headlineSmall) },
            text = { Text(stringResource(R.string.rag_home_memory_delete_confirm_msg), style = NexaraTypography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        memoryDeleteTarget?.let { viewModel.deleteMemoryVector(it.id) }
                        memoryDeleteTarget = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NexaraColors.Error)
                ) {
                    Text(stringResource(R.string.shared_btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDeleteTarget = null }) {
                    Text(stringResource(R.string.common_btn_cancel))
                }
            },
            containerColor = NexaraColors.SurfaceDim,
            titleContentColor = NexaraColors.OnSurface,
            textContentColor = NexaraColors.OnSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocListItem(
    doc: Document,
    isSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    showCheckbox: Boolean,
    onClick: () -> Unit,
    onExtractKG: ((String) -> Unit)? = null,
    snippet: String? = null
) {
    val status = when (doc.vectorized) {
        1 -> RagStatus.READY
        -1 -> RagStatus.ERROR
        else -> RagStatus.PENDING
    }
    var showContextMenu by remember { mutableStateOf(false) }

    Box {
        NexaraGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onExtractKG != null) {
                        Modifier.combinedClickable(
                            onClick = {
                                if (showCheckbox) onSelect(!isSelected)
                                else onClick()
                            },
                            onLongClick = { showContextMenu = true }
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            onClick = if (onExtractKG == null) {
                {
                    if (showCheckbox) onSelect(!isSelected)
                    else onClick()
                }
            } else null
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCheckbox) {
                    androidx.compose.material3.Checkbox(
                        checked = isSelected,
                        onCheckedChange = onSelect,
                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                            checkedColor = NexaraColors.Primary,
                            uncheckedColor = NexaraColors.Outline
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NexaraColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = doc.title.ifBlank { "Untitled" },
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        doc.fileSize?.let { fs ->
                            Text(formatFileSize(fs), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                        }
                        doc.updatedAt?.let { ua ->
                            Text(
                                java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ua)),
                                style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                    if (snippet != null) {
                        Text(
                            text = snippet,
                            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                            color = NexaraColors.OnSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                com.promenar.nexara.ui.rag.components.RagStatusChip(status = status)
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(NexaraColors.SurfaceContainer)
        ) {
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountTree, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.rag_home_extract_kg), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                    }
                },
                onClick = { /* 标题不可点击 */ },
                enabled = false
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.rag_home_extract_kg_full),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.padding(start = 26.dp)
                    )
                },
                onClick = {
                    showContextMenu = false
                    onExtractKG?.invoke("full")
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.rag_home_extract_kg_summary),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.padding(start = 26.dp)
                    )
                },
                onClick = {
                    showContextMenu = false
                    onExtractKG?.invoke("summary-first")
                }
            )
        }
    }
}

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rag_home_new_folder_title), style = NexaraTypography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rag_home_folder_name)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
        containerColor = NexaraColors.SurfaceDim,
        titleContentColor = NexaraColors.OnSurface,
        textContentColor = NexaraColors.OnSurfaceVariant
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}
