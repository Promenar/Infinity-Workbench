package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset

private data class WorkspaceTask(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val progress: Float = 0f,
    val time: String = ""
)

private data class WorkspaceArtifact(
    val id: String,
    val name: String,
    val type: String,
    val icon: ImageVector
)

// WorkspaceTask and WorkspaceArtifact are kept here as they are UI-specific mappings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    sessionId: String
) {
    if (!show) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(context.applicationContext as android.app.Application))
    val uiState by chatViewModel.uiState.collectAsState()
    val session = uiState.session

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    
    val workspaceViewModel: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.factory(context.applicationContext as android.app.Application))
    val workspaceFiles by workspaceViewModel.files.collectAsState()
    val isFilesLoading by workspaceViewModel.isLoading.collectAsState()
    
    LaunchedEffect(session?.workspacePath) {
        workspaceViewModel.loadWorkspaceFiles(session?.workspacePath)
    }
    val tabTitles = listOf(
        stringResource(R.string.workspace_tab_tasks),
        stringResource(R.string.workspace_tab_artifacts),
        stringResource(R.string.workspace_tab_files)
    )

    // Real data mapping
    val tasks = remember(session) {
        val activeTask = session?.activeTask
        val list = mutableListOf<WorkspaceTask>()
        
        // Add active task if present
        activeTask?.let { state ->
            state.steps.forEach { step ->
                list.add(WorkspaceTask(
                    id = step.id,
                    title = step.title,
                    subtitle = step.description,
                    status = step.status,
                    progress = if (step.status == "in_progress") state.progress / 100f else 0f
                ))
            }
        }
        
        // If no active task, try to find the latest planning task from messages
        if (list.isEmpty()) {
            session?.messages?.lastOrNull { it.planningTask != null }?.planningTask?.let { state ->
                state.steps.forEach { step ->
                    list.add(WorkspaceTask(
                        id = step.id,
                        title = step.title,
                        subtitle = step.description,
                        status = step.status,
                        progress = if (step.status == "in_progress") state.progress / 100f else 0f
                    ))
                }
            }
        }
        
        list.toList()
    }

    val artifacts = remember(session) {
        session?.messages?.flatMap { it.toolResults ?: emptyList() }?.mapIndexed { index, artifact ->
            WorkspaceArtifact(
                id = "art_$index",
                name = artifact.name ?: "Artifact ${index + 1}",
                type = artifact.type,
                icon = when (artifact.type.lowercase()) {
                    "image" -> Icons.Rounded.Image
                    "pdf" -> Icons.Rounded.Description
                    "code" -> Icons.Rounded.InsertDriveFile
                    else -> Icons.Rounded.InsertDriveFile
                }
            )
        } ?: emptyList()
    }

    val files = workspaceFiles

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexaraColors.SurfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .background(NexaraColors.OutlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.workspace_title), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "/workspace",
                            style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(NexaraColors.GlassSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Close, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = NexaraColors.OnSurface,
                divider = {
                    HorizontalDivider(thickness = 0.5.dp, color = NexaraColors.GlassBorder)
                },
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        val pos = tabPositions[pagerState.currentPage]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .padding(horizontal = 32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NexaraColors.Primary)
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (index) {
                                    0 -> Icons.Rounded.TaskAlt
                                    1 -> Icons.Rounded.ViewInAr
                                    else -> Icons.Rounded.FolderOpen
                                }
                                Icon(icon, null, modifier = Modifier.size(18.dp), tint = if (pagerState.currentPage == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(title, style = NexaraTypography.labelMedium, color = if (pagerState.currentPage == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant)
                            }
                        },
                        selectedContentColor = NexaraColors.Primary,
                        unselectedContentColor = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> TasksPanel(tasks)
                    1 -> ArtifactsPanel(artifacts)
                    2 -> {
                        if (isFilesLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NexaraColors.Primary)
                            }
                        } else {
                            FilesPanel(files, workspaceViewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksPanel(tasks: List<WorkspaceTask>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val completedCount = tasks.count { it.status == "completed" }
        item {
            NexaraGlassCard(shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource(R.string.workspace_sprint_pipeline), style = NexaraTypography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = NexaraColors.OnSurface)
                        Text(if (tasks.isNotEmpty()) stringResource(R.string.workspace_x_of_y_completed, completedCount, tasks.size) else stringResource(R.string.workspace_no_tasks), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                    }
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (tasks.isNotEmpty()) completedCount.toFloat() / tasks.size.toFloat() else 0f },
                            modifier = Modifier.size(64.dp),
                            color = NexaraColors.Primary,
                            strokeWidth = 4.dp,
                            trackColor = NexaraColors.SurfaceHighest,
                            strokeCap = StrokeCap.Round
                        )
                        val totalTasks = tasks.size
                        val progressText = if (totalTasks > 0) "${(completedCount * 100 / totalTasks)}%" else "0%"
                        Text(progressText, style = NexaraTypography.labelMedium.copy(color = NexaraColors.Primary))
                    }
                }
            }
        }

        items(tasks, key = { it.id }) { task ->
            TaskCard(task)
        }
    }
}

@Composable
private fun TaskCard(task: WorkspaceTask) {
    val modifier = when (task.status) {
        "completed" -> Modifier.alpha(0.7f)
        "in_progress" -> Modifier.border(2.dp, NexaraColors.Primary, RoundedCornerShape(12.dp))
        else -> Modifier
    }

    NexaraGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            when (task.status) {
                "completed" -> Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                "in_progress" -> Icon(
                    Icons.Rounded.Sync,
                    null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                else -> Icon(
                    Icons.Rounded.RadioButtonUnchecked,
                    null,
                    tint = NexaraColors.Outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = NexaraTypography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (task.status == "completed") TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (task.status == "completed") NexaraColors.OnSurfaceVariant else NexaraColors.OnSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(task.subtitle, style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)

                if (task.status == "in_progress" && task.progress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = NexaraColors.Primary,
                        trackColor = NexaraColors.SurfaceHighest,
                        strokeCap = StrokeCap.Round
                    )
                }
            }

            if (task.time.isNotEmpty()) {
                Text(task.time, style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.OutlineVariant)
            } else if (task.status == "in_progress") {
                Text(stringResource(R.string.workspace_task_running), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.Primary)
            }
        }
    }
}

@Composable
private fun ArtifactsPanel(artifacts: List<WorkspaceArtifact>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(artifacts, key = { it.id }) { artifact ->
            NexaraGlassCard(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(artifact.icon, null, tint = NexaraColors.Primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        artifact.name,
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(artifact.type.uppercase(), style = NexaraTypography.bodyMedium.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.OnSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FilesPanel(
    files: List<WorkspaceFile>,
    viewModel: WorkspaceViewModel = viewModel()
) {
    var selectedFile by remember { mutableStateOf<WorkspaceFile?>(null) }
    val content by viewModel.selectedFileContent.collectAsState()

    if (selectedFile != null) {
        LaunchedEffect(selectedFile) {
            viewModel.loadFileContent(selectedFile!!.path)
        }
        
        FilePreviewModal(
            file = selectedFile!!,
            content = content,
            onDismiss = { 
                selectedFile = null
                viewModel.clearFileContent()
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(files, key = { it.id }) { file ->
            FileTreeItem(
                file = file,
                depth = 0,
                onClick = {
                    if (!file.isDirectory) selectedFile = file
                }
            )
        }
    }
}

@Composable
private fun FileTreeItem(
    file: WorkspaceFile,
    depth: Int,
    onClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    if (file.isDirectory) expanded = !expanded else onClick()
                }
                .padding(start = (depth * 16).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                null,
                tint = if (file.isDirectory) NexaraColors.Tertiary else NexaraColors.OnSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(file.name, style = NexaraTypography.bodyMedium.copy(fontSize = 14.sp), color = NexaraColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (file.isDirectory && expanded) {
            file.children.forEach { child ->
                FileTreeItem(file = child, depth = depth + 1, onClick = onClick)
            }
        }
    }
}

@Composable
private fun FilePreviewModal(
    file: WorkspaceFile,
    content: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NexaraColors.SurfaceContainer)
                .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.5f))
                    .border(0.5.dp, NexaraColors.GlassBorder)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(file.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                }
                IconButton(onClick = onDismiss) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(NexaraColors.GlassSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NexaraColors.SurfaceLowest.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(file.path, style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.OutlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        content ?: stringResource(R.string.shared_loading),
                        style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }
}
