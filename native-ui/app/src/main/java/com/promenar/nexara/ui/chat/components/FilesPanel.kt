package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.common.FileIndexStatus
import com.promenar.nexara.ui.common.IndexStatusBadge
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesPanel(
    workspaceRootUuid: String?,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = "",
    useScroll: Boolean = true
) {
    val roots by workspaceRepo.observeRoots().collectAsState(initial = emptyList())

    val filteredRoots = if (searchQuery.isBlank()) roots else {
        roots.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    if (filteredRoots.isEmpty() && searchQuery.isBlank()) {
        EmptyFilesState()
    } else if (useScroll) {
        // 独立使用场景：带 LazyColumn 滚动
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredRoots, key = { it.uuid }) { root ->
                FileTreeNode(file = root, depth = 0, workspaceRepo = workspaceRepo, searchQuery = searchQuery)
            }
        }
    } else {
        // 嵌套在外部 LazyColumn 中使用：无滚动，纯列表渲染
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            filteredRoots.forEach { root ->
                FileTreeNode(file = root, depth = 0, workspaceRepo = workspaceRepo, searchQuery = searchQuery)
            }
        }
    }
}

@Composable
private fun FileTreeNode(
    file: FileEntry,
    depth: Int,
    workspaceRepo: IWorkspaceRepository,
    searchQuery: String = ""
) {
    var expanded by rememberSaveable { mutableStateOf(depth < 2) }

    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        shape = RoundedCornerShape(12.dp),
        onClick = { if (file.isDirectory) expanded = !expanded }
    ) {
        FileRow(file = file)
    }

    if (file.isDirectory && expanded) {
        val children by workspaceRepo.observeChildren(file.uuid)
            .collectAsState(initial = emptyList())

        val filteredChildren = if (searchQuery.isBlank()) children else {
            children.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        filteredChildren.forEach { child ->
            FileTreeNode(
                file = child,
                depth = depth + 1,
                workspaceRepo = workspaceRepo,
                searchQuery = searchQuery
            )
        }
    }
}

@Composable
private fun FileRow(file: FileEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = fileIcon(file),
            contentDescription = null,
            tint = if (file.isDirectory) NexaraColors.Tertiary else NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = file.name,
                style = NexaraTypography.bodyLarge,
                color = NexaraColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!file.isDirectory) {
                Text(
                    text = formatFileMetadata(file),
                    style = NexaraTypography.labelSmall,
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }

        IndexStatusBadge(status = resolveIndexStatus(file))
    }
}

@Composable
private fun EmptyFilesState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = NexaraColors.OnSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "暂无文件",
            style = NexaraTypography.labelMedium,
            color = NexaraColors.OnSurface
        )
        Text(
            text = "工作区文件将在 AI 操作时自动创建",
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )
    }
}

private fun fileIcon(file: FileEntry): ImageVector {
    if (file.isDirectory) return Icons.Rounded.Folder
    return when (file.mimeType?.substringBefore("/")?.lowercase()) {
        "image" -> Icons.Rounded.Image
        "audio" -> Icons.Rounded.AudioFile
        "video" -> Icons.Rounded.Movie
        "text", "application" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun resolveIndexStatus(file: FileEntry): FileIndexStatus {
    if (file.isDirectory) return FileIndexStatus.NOT_INDEXED
    if (file.vectorizedAt == null) return FileIndexStatus.NOT_INDEXED
    return FileIndexStatus.INDEXED
}

private fun formatFileMetadata(file: FileEntry): String {
    val size = formatFileSize(file.sizeBytes)
    val time = formatRelativeTime(file.updatedAt)
    return "$size · $time"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0))
        .toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.0f".format(value)} ${units[digitGroups]}"
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 604_800_000L -> "${diff / 86_400_000L} 天前"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
