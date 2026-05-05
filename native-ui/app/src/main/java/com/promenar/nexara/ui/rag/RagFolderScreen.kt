package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagFolderScreen(
    folderId: String,
    folderName: String,
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit
) {
    val documents by viewModel.documents.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(folderId) {
        viewModel.loadDocumentsForFolder(folderId)
    }

    NexaraPageLayout(
        title = folderName,
        onBack = onNavigateBack,
        actions = {
            IconButton(onClick = {
                if (selectedIds.size == documents.size) {
                    selectedIds.clear()
                } else {
                    selectedIds.clear()
                    selectedIds.addAll(documents.map { it.id })
                }
            }) {
                Text(
                    if (selectedIds.size == documents.size) stringResource(R.string.rag_folder_deselect) else stringResource(R.string.rag_folder_select_all),
                    style = NexaraTypography.labelMedium,
                    color = NexaraColors.Primary
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (documents.isEmpty()) {
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
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = stringResource(R.string.rag_folder_empty),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.rag_folder_empty_subtitle),
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(documents, key = { it.id }) { doc ->
                        val status = when (doc.vectorized) {
                            1 -> RagStatus.READY
                            -1 -> RagStatus.ERROR
                            else -> RagStatus.PENDING
                        }
                        val isSelected = selectedIds.contains(doc.id)

                        RagDocItem(
                            title = doc.title ?: "Untitled",
                            status = status,
                            isSelected = isSelected,
                            showCheckbox = selectedIds.isNotEmpty() || isSelected,
                            onCheckedChange = { checked ->
                                if (checked) selectedIds.add(doc.id)
                                else selectedIds.remove(doc.id)
                            },
                            fileSize = doc.fileSize?.let { formatFileSize(it) },
                            date = doc.updatedAt?.let { formatDate(it) } ?: formatDate(doc.createdAt),
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    if (isSelected) selectedIds.remove(doc.id)
                                    else selectedIds.add(doc.id)
                                }
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaraColors.SurfaceHigh,
                    contentColor = NexaraColors.Primary
                )
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = NexaraColors.Primary)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.rag_folder_upload), style = NexaraTypography.labelMedium, color = NexaraColors.Primary)
            }
        }
    }

    if (selectedIds.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NexaraColors.SurfaceLow.copy(alpha = 0.95f))
                    .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.rag_home_selected_count, selectedIds.size), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                    Text(stringResource(R.string.rag_home_clear_all), style = NexaraTypography.labelMedium, color = NexaraColors.Primary,
                        modifier = Modifier.clickable { selectedIds.clear() })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.weight(1f).height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable { showMoveSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.rag_home_move), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable { },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.rag_home_reindex), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.Error.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clickable { showDeleteConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = NexaraColors.Error, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.Error)
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
                    if (folder.id != folderId) {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
                                Text(folder.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            }
                        }
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
                Text(stringResource(R.string.rag_folder_delete_confirm_title, selectedIds.size), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                Text(stringResource(R.string.shared_action_cannot_undo), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .clickable { showDeleteConfirm = false }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(R.string.common_btn_cancel), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface) }
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Error)
                            .clickable {
                                viewModel.deleteDocuments(selectedIds.toList())
                                selectedIds.clear()
                                showDeleteConfirm = false
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium, color = NexaraColors.OnError) }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
