package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnowledgeGraphAccessibilitySheet(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    onDismiss: () -> Unit,
) {
    val nodesById = nodes.associateBy { it.id }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTags.KG_ACCESSIBILITY_LIST),
    ) {
        Text(
            text = stringResource(R.string.kg_accessibility_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NexaraSpacing.ScreenHorizontal,
                    vertical = NexaraSpacing.Medium,
                ),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = stringResource(R.string.kg_nodes_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        horizontal = NexaraSpacing.ScreenHorizontal,
                        vertical = NexaraSpacing.Small,
                    ),
                )
            }
            items(nodes, key = { "node:${it.id}" }) { node ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .semantics { contentDescription = "${node.label}, ${node.type}" },
                    headlineContent = { Text(node.label) },
                    supportingContent = { Text(node.type) },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.kg_relations_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(
                        horizontal = NexaraSpacing.ScreenHorizontal,
                        vertical = NexaraSpacing.Small,
                    ),
                )
            }
            itemsIndexed(
                items = edges,
                key = { index, edge ->
                    "edge:$index:${edge.sourceId}:${edge.targetId}:${edge.relation}"
                },
            ) { index, edge ->
                val source = nodesById[edge.sourceId]?.label ?: edge.sourceId
                val target = nodesById[edge.targetId]?.label ?: edge.targetId
                val relationDescription = stringResource(
                    R.string.kg_relation_description,
                    source,
                    edge.relation,
                    target,
                )
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .semantics { contentDescription = relationDescription }
                        .testTag(
                            UiTags.KG_ACCESSIBILITY_RELATION_PREFIX +
                                "$index:${edge.sourceId}-${edge.targetId}",
                        ),
                    headlineContent = { Text(relationDescription) },
                )
            }
        }
    }
}
