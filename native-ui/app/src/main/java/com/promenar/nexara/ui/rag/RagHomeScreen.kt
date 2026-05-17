package com.promenar.nexara.ui.rag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

private enum class PortalTab { DOCUMENTS, MEMORY, GRAPH }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val memoryVectors by viewModel.memoryVectors.collectAsState()
    val kgExtractionStates by viewModel.kgExtractionStates.collectAsState()

    var currentTab by remember { mutableStateOf(PortalTab.DOCUMENTS) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var memoryDeleteTarget by remember { mutableStateOf<MemoryVectorRecord?>(null) }
    var expandedMemoryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentTab) {
        if (currentTab == PortalTab.MEMORY) viewModel.loadMemoryVectors()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.rag_home_title),
                        style = NexaraTypography.headlineLarge,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        // NewFolderDialog
        if (showNewFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                title = { Text(stringResource(R.string.rag_home_new_folder_title), style = NexaraTypography.headlineSmall) },
                text = {
                    OutlinedTextField(value = folderName, onValueChange = { folderName = it },
                        label = { Text(stringResource(R.string.rag_home_folder_name)) },
                        modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    Button(onClick = {
                        if (folderName.isNotBlank()) viewModel.createFolder(folderName.trim())
                        showNewFolderDialog = false
                    }, enabled = folderName.isNotBlank()) {
                        Text(stringResource(R.string.shared_btn_add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) {
                        Text(stringResource(R.string.common_btn_cancel))
                    }
                },
                containerColor = NexaraColors.SurfaceDim,
                titleContentColor = NexaraColors.OnSurface,
                textContentColor = NexaraColors.OnSurfaceVariant
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
            ) {
            // Search bar
            NexaraSearchBar(
                value = searchQuery,
                onValueChange = { searchQuery = it; viewModel.search(it) },
                placeholder = stringResource(R.string.rag_home_search),
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )

            // TabRow
            TabRow(
                selectedTabIndex = currentTab.ordinal,
                containerColor = Color.Transparent,
                contentColor = NexaraColors.Primary,
                divider = { HorizontalDivider(color = NexaraColors.OutlineVariant) }
            ) {
                listOf(
                    PortalTab.DOCUMENTS to (Icons.Rounded.Description to stringResource(R.string.rag_home_documents)),
                    PortalTab.MEMORY to (Icons.Rounded.Psychology to stringResource(R.string.rag_home_memory)),
                    PortalTab.GRAPH to (Icons.Rounded.AccountTree to stringResource(R.string.rag_home_graph))
                ).forEach { (tab, data) ->
                    Tab(
                        selected = currentTab == tab,
                        onClick = { if (tab == PortalTab.GRAPH) onNavigateToGraph() else currentTab = tab },
                        text = { Text(data.second, style = NexaraTypography.labelMedium) },
                        selectedContentColor = NexaraColors.Primary,
                        unselectedContentColor = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Indexing progress — 带入场/退场动画
            AnimatedVisibility(
                visible = isIndexing || lastQueueError != null,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it },
                exit = fadeOut(tween(400)) + slideOutVertically(tween(400)) { -it }
            ) {
                Column {
                    if (isIndexing) {
                        IndexingProgressBar(
                            progress = indexingProgress,
                            statusText = indexingStatus,
                            subStatusText = indexingSubStatus,
                            isError = lastQueueError != null
                        )
                    } else if (lastQueueError != null) {
                        // 带关闭按钮的错误卡片
                        Box {
                            IndexingProgressBar(
                                progress = indexingProgress.coerceAtLeast(0f),
                                statusText = "向量化失败: ${lastQueueError?.take(80) ?: "未知错误"}",
                                subStatusText = "请检查 Embedding 模型配置后重试 | 点击关闭",
                                isError = true
                            )
                            // 覆盖层关闭按钮
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.dismissQueueError() }
                            )
                        }
                    }
                }
            }

            // Tab content
            when (currentTab) {
                PortalTab.DOCUMENTS -> {
                    // Toolbar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 新建文件夹
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NexaraColors.SurfaceHigh)
                                .clickable { showNewFolderDialog = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.CreateNewFolder, null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.rag_home_new), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.Primary)
                            }
                        }
                        // 上传
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NexaraColors.SurfaceHigh)
                                .clickable { filePickerLauncher.launch(arrayOf("*/*")) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.CloudUpload, null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.rag_home_upload_area), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.Primary)
                            }
                        }
                    }

                    // FilesPanel (no LazyColumn nesting — weight(1f) fills remaining space)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        FilesPanel(
                            workspaceRootUuid = viewModel.workspaceRootUuid.collectAsState().value,
                            workspaceRepo = viewModel.getWorkspaceRepo(),
                            searchQuery = searchQuery,
                            useScroll = true,
                            onReindex = { viewModel.reindexFile(it) },
                            onDelete = { viewModel.deleteCollection(it) },
                            onRename = { u, n -> viewModel.renameFolder(u, n) },
                            onMove = { u, t -> viewModel.moveFile(u, t) },
                            onExtractKG = { viewModel.extractKG(it) },
                            onViewKG = { _ -> onNavigateToGraph() },
                            onCopy = { viewModel.copyFile(it) },
                            indexingFileIds = viewModel.indexingDocIds.collectAsState().value,
                            kgExtractionStates = kgExtractionStates
                        )
                    }
                }

                PortalTab.MEMORY -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Text(stringResource(R.string.rag_home_memory_section), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface) }
                        item {
                            NexaraGlassCard(modifier = Modifier.fillMaxWidth(), shape = NexaraShapes.large as RoundedCornerShape) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.rag_home_memory_total_count, memoryVectors.size), style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NexaraColors.OnSurface)
                                    Text(stringResource(R.string.rag_home_memory_est_tokens, memoryVectors.sumOf { it.content.length / 3 }), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.OnSurfaceVariant)
                                }
                            }
                        }
                        if (memoryVectors.isEmpty()) {
                            item {
                                NexaraGlassCard(Modifier.fillMaxWidth(), NexaraShapes.large as RoundedCornerShape) {
                                    Column(Modifier.fillMaxWidth().padding(32.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.Psychology, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(40.dp))
                                        Text(stringResource(R.string.rag_home_memory_empty), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                    }
                                }
                            }
                        } else {
                            items(memoryVectors, key = { it.id }) { memory ->
                                val isExpanded = expandedMemoryId == memory.id
                                NexaraGlassCard(
                                    Modifier.fillMaxWidth().combinedClickable(
                                        onClick = { expandedMemoryId = if (isExpanded) null else memory.id },
                                        onLongClick = { memoryDeleteTarget = memory }
                                    ),
                                    RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(text = if (isExpanded) memory.content else memory.content.take(120) + if (memory.content.length > 120) "…" else "", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurface, maxLines = if (isExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            memory.sessionId?.let { Text("Session: ${it.take(8)}…", style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.Primary.copy(alpha = 0.7f)) }
                                            Text(text = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(memory.createdAt)), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                PortalTab.GRAPH -> {}
            }
        } // end Column

        // Bottom batch bar
        if (selectedIds.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NexaraColors.SurfaceLow.copy(alpha = 0.95f))
                    .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.rag_home_selected_count, selectedIds.size), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                        Text(text = stringResource(R.string.rag_home_clear_all), style = NexaraTypography.labelMedium, color = NexaraColors.Primary, modifier = Modifier.clickable { selectedIds.clear() })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { showMoveSheet = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Icon(Icons.Rounded.Folder, null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                        }
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { viewModel.reindexDocuments(selectedIds.toList()); selectedIds.clear() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Icon(Icons.Rounded.Refresh, null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                        }
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(NexaraColors.Error.copy(alpha = 0.1f)).border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).clickable { showDeleteConfirm = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Icon(Icons.Rounded.Delete, null, tint = NexaraColors.Error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    } // end Box
    }

    // Move sheet
    if (showMoveSheet) {
        ModalBottomSheet(onDismissRequest = { showMoveSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NexaraColors.SurfaceLow, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(24.dp).padding(bottom = 40.dp), Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rag_home_move_to_folder), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NexaraColors.SurfaceContainer).clickable { showMoveSheet = false; selectedIds.clear() }.padding(14.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(folder.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(stringResource(R.string.rag_home_docs_count, folderStats[folder.id] ?: 0), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                    }
                }
            }
        }
    }

    // Delete confirm
    if (showDeleteConfirm) {
        ModalBottomSheet(onDismissRequest = { showDeleteConfirm = false }, containerColor = NexaraColors.SurfaceLow, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(24.dp).padding(bottom = 40.dp), Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.rag_home_delete_confirm_title, selectedIds.size), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(NexaraColors.SurfaceContainer).clickable { showDeleteConfirm = false }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.common_btn_cancel), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                    }
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(NexaraColors.Error).clickable { viewModel.deleteDocuments(selectedIds.toList()); selectedIds.clear(); showDeleteConfirm = false }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.OnError)
                    }
                }
            }
        }
    }

    // Memory delete
    if (memoryDeleteTarget != null) {
        AlertDialog(
            onDismissRequest = { memoryDeleteTarget = null },
            title = { Text(stringResource(R.string.rag_home_memory_delete_confirm_title), style = NexaraTypography.headlineSmall) },
            text = { Text(stringResource(R.string.rag_home_memory_delete_confirm_msg), style = NexaraTypography.bodyMedium) },
            confirmButton = {
                Button(onClick = { memoryDeleteTarget?.let { viewModel.deleteMemoryVector(it.id) }; memoryDeleteTarget = null },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = NexaraColors.Error)) {
                    Text(stringResource(R.string.shared_btn_delete))
                }
            },
            dismissButton = { TextButton(onClick = { memoryDeleteTarget = null }) { Text(stringResource(R.string.common_btn_cancel)) } },
            containerColor = NexaraColors.SurfaceDim, titleContentColor = NexaraColors.OnSurface, textContentColor = NexaraColors.OnSurfaceVariant
        )
    }
}
