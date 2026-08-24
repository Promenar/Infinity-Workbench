package com.promenar.nexara.ui.rag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.promenar.nexara.R
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

internal object RagSearchContract {
    const val DEBOUNCE_MILLIS = 250L
    const val SNIPPET_MAX_CHARS = 180
}

enum class RagSearchMatchKind {
    FtsBody,
    TitleFallback,
}

data class RagSearchResult(
    val document: Document,
    val snippet: String? = null,
    val matchKind: RagSearchMatchKind,
)

enum class RagSearchErrorCode {
    FtsUnavailable,
}

sealed interface RagSearchUiState {
    data object Idle : RagSearchUiState

    data class Loading(val query: String) : RagSearchUiState

    data class Results(
        val query: String,
        val items: List<RagSearchResult>,
    ) : RagSearchUiState

    data class Warning(
        val query: String,
        val code: RagSearchErrorCode,
        val fallbackItems: List<RagSearchResult>,
        val technical: String?,
    ) : RagSearchUiState
}

internal fun buildRagSearchSnippet(
    content: String,
    query: String,
    maxChars: Int = RagSearchContract.SNIPPET_MAX_CHARS,
): String {
    if (content.length <= maxChars) return content
    val matchIndex = content.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val available = (maxChars - 2).coerceAtLeast(1)
    val start = (matchIndex - available / 3).coerceIn(0, (content.length - available).coerceAtLeast(0))
    val end = (start + available).coerceAtMost(content.length)
    return buildString(maxChars) {
        if (start > 0) append('…')
        append(content, start, end)
        if (end < content.length) append('…')
    }
}

@Composable
internal fun RagSearchResults(
    state: RagSearchUiState,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RagSearchUiState.Idle -> Unit
        is RagSearchUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is RagSearchUiState.Results -> {
            RagSearchResultList(
                items = state.items,
                onOpenDocument = onOpenDocument,
                modifier = modifier,
            )
        }
        is RagSearchUiState.Warning -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.RAG_SEARCH_WARNING),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.rag_search_fts_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(NexaraSpacing.Medium),
                    )
                }
                RagSearchResultList(
                    items = state.fallbackItems,
                    onOpenDocument = onOpenDocument,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RagSearchResultList(
    items: List<RagSearchResult>,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier,
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.rag_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.RAG_SEARCH_RESULTS),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
    ) {
        items(items, key = { it.document.id }) { item ->
            val matchLabel = when (item.matchKind) {
                RagSearchMatchKind.FtsBody -> stringResource(R.string.rag_search_body_match)
                RagSearchMatchKind.TitleFallback -> stringResource(R.string.rag_search_title_fallback)
            }
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                    .semantics { contentDescription = "${item.document.title}, $matchLabel" }
                    .clickable(role = Role.Button) { onOpenDocument(item.document.id) }
                    .testTag(UiTags.RAG_SEARCH_RESULT_PREFIX + item.document.id),
                headlineContent = {
                    Text(item.document.title, style = MaterialTheme.typography.bodyLarge)
                },
                supportingContent = item.snippet?.let { snippet ->
                    {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    Text(
                        text = matchLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(
                            when (item.matchKind) {
                                RagSearchMatchKind.FtsBody -> UiTags.RAG_SEARCH_BODY_MATCH
                                RagSearchMatchKind.TitleFallback -> UiTags.RAG_SEARCH_TITLE_FALLBACK
                            },
                        ),
                    )
                },
            )
        }
    }
}
