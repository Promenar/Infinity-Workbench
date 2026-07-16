package com.promenar.nexara.ui.rag.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.R

@Composable
fun RagDocItem(
    title: String,
    status: RagStatus,
    isSelected: Boolean = false,
    showCheckbox: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    fileSize: String? = null,
    date: String? = null,
    onClick: (() -> Unit)? = null
) {
    val openActionLabel = stringResource(R.string.rag_folder_cd_open_document, title)
    val rowModifier = Modifier
        .clip(MaterialTheme.shapes.medium)
        .testTag(UiTags.RAG_FOLDER_DOCUMENT_ITEM)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    onClickLabel = openActionLabel,
                    role = Role.Button,
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        )
    val metadata = listOfNotNull(fileSize, date).joinToString(" • ")

    if (LocalDensity.current.fontScale >= 1.5f) {
        RagDocItemLargeFont(
            title = title,
            status = status,
            isSelected = isSelected,
            showCheckbox = showCheckbox,
            onCheckedChange = onCheckedChange,
            metadata = metadata,
            modifier = rowModifier,
        )
        return
    }

    ListItem(
        modifier = rowModifier,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        leadingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showCheckbox) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                )
            }
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            }
        },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = metadata.takeIf(String::isNotEmpty)?.let { resolvedMetadata ->
            {
                Text(
                    text = resolvedMetadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = { RagStatusChip(status = status) },
    )
}

@Composable
private fun RagDocItemLargeFont(
    title: String,
    status: RagStatus,
    isSelected: Boolean,
    showCheckbox: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    metadata: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCheckbox) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = onCheckedChange,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RagStatusChip(status = status)
            }
        }
    }
}
