package com.promenar.nexara.ui.rag

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.rag.components.FolderItem
import com.promenar.nexara.ui.rag.components.IndexingProgressBar
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

private enum class PortalView { DOCUMENTS, MEMORY, GRAPH }

@OptIn(ExperimentalMaterial3Api::class)
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
    val documents by viewModel.documents.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    var currentView by remember { mutableStateOf(PortalView.DOCUMENTS) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                            color = NexaraColors.OnSurface
                        )
                    } else {
                        Text(stringResource(R.string.rag_home_title), style = NexaraTypography.headlineLarge, color = NexaraColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = 8.dp, bottom = 160.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.rag_home_subtitle),
                        style = NexaraTypography.bodyLarge,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.search(it)
                            },
                            textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.rag_home_search),
                                        style = NexaraTypography.bodyMedium,
                                        color = NexaraColors.OnSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(
                            PortalView.DOCUMENTS to Triple(Icons.Rounded.Description, stringResource(R.string.rag_home_documents), "${stats.documentCount}"),
                            PortalView.MEMORY to Triple(Icons.Rounded.Psychology, stringResource(R.string.rag_home_memory), formatBytes(stats.memoryBytes)),
                            PortalView.GRAPH to Triple(Icons.Rounded.AccountTree, stringResource(R.string.rag_home_graph), "${stats.graphEntityCount}")
                        ).forEach { (view, data) ->
                            val (icon, title, subtitle) = data
                            Box(modifier = Modifier.weight(1f)) {
                                val isActive = currentView == view
                                val borderColor by animateColorAsState(
                                    if (isActive) NexaraColors.Primary else NexaraColors.GlassBorder,
                                    label = "portalBorder"
                                )
                                val bgColor by animateColorAsState(
                                    if (isActive) NexaraColors.Primary.copy(alpha = 0.06f) else NexaraColors.GlassSurface,
                                    label = "portalBg"
                                )
                                NexaraGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            1.dp,
                                            borderColor,
                                            NexaraShapes.large as RoundedCornerShape
                                        ),
                                    shape = NexaraShapes.large as RoundedCornerShape,
                                    onClick = {
                                        when (view) {
                                            PortalView.DOCUMENTS -> currentView = PortalView.DOCUMENTS
                                            PortalView.MEMORY -> currentView = PortalView.MEMORY
                                            PortalView.GRAPH -> onNavigateToGraph()
                                        }
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bgColor)
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(NexaraColors.Primary.copy(alpha = 0.1f))
                                                .border(1.dp, NexaraColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = NexaraColors.Primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = title,
                                                style = NexaraTypography.labelMedium,
                                                color = NexaraColors.OnSurface
                                            )
                                            Text(
                                                text = subtitle,
                                                style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                                color = NexaraColors.OnSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isIndexing) {
                    item {
                        IndexingProgressBar(progress = indexingProgress)
                    }
                }

                when (currentView) {
                    PortalView.DOCUMENTS -> {
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
                                            .clickable { }
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
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(NexaraColors.SurfaceHigh)
                                            .clickable { onNavigateToGraph() }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(16.dp))
                                            Text(stringResource(R.string.rag_home_graph_btn), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface)
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
                                    .clickable { }
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
                                        Text(stringResource(R.string.rag_home_empty_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurfaceVariant)
                                        Text(stringResource(R.string.rag_home_empty_subtitle), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
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
                            items(searchResults, key = { it.id }) { doc ->
                                DocListItem(doc = doc, isSelected = selectedIds.contains(doc.id), onSelect = { checked ->
                                    if (checked) selectedIds.add(doc.id) else selectedIds.remove(doc.id)
                                }, showCheckbox = selectedIds.isNotEmpty(), onClick = { onNavigateToDocEditor(doc.id) })
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
                            if (shownDocs.isEmpty() && searchQuery.isBlank()) {
                                items(documents.take(10), key = { it.id }) { doc ->
                                    DocListItem(doc = doc, isSelected = selectedIds.contains(doc.id), onSelect = { checked ->
                                        if (checked) selectedIds.add(doc.id) else selectedIds.remove(doc.id)
                                    }, showCheckbox = selectedIds.isNotEmpty(), onClick = { onNavigateToDocEditor(doc.id) })
                                }
                            }
                        }
                    }
                    PortalView.MEMORY -> {
                        item {
                            Text(stringResource(R.string.rag_home_memory_section), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                        }
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
                                    Text(stringResource(R.string.rag_home_memory_empty), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurfaceVariant)
                                }
                            }
                        }
                    }
                    PortalView.GRAPH -> {}
                }
            }

            if (isIndexing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp, start = 20.dp, end = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NexaraColors.SurfaceContainer.copy(alpha = 0.9f))
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(NexaraColors.Primary.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.RotateRight, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.rag_home_vectorizing), style = NexaraTypography.labelMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurface)
                                Text("${(indexingProgress * 100).toInt()}%", style = NexaraTypography.bodySmall.copy(fontSize = 11.sp), color = NexaraColors.Primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { indexingProgress },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                color = NexaraColors.Primary,
                                trackColor = NexaraColors.SurfaceHighest
                            )
                        }
                    }
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
}

@Composable
private fun DocListItem(
    doc: DocumentEntity,
    isSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    showCheckbox: Boolean,
    onClick: () -> Unit
) {
    val status = when (doc.vectorized) {
        1 -> RagStatus.READY
        -1 -> RagStatus.ERROR
        else -> RagStatus.PENDING
    }
    RagDocItem(
        title = doc.title ?: "Untitled",
        status = status,
        isSelected = isSelected,
        showCheckbox = showCheckbox,
        onCheckedChange = onSelect,
        fileSize = doc.fileSize?.let { formatFileSize(it) },
        date = doc.updatedAt?.let { java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(it)) },
        onClick = {
            if (showCheckbox) onSelect(!isSelected)
            else onClick()
        }
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
