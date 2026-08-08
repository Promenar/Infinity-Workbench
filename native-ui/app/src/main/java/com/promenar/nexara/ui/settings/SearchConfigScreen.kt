package com.promenar.nexara.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.common.SettingsSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: SearchConfigViewModel = viewModel(
        factory = SearchConfigViewModel.factory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    SearchConfigScreenContent(
        state = uiState,
        actions = SearchConfigScreenActions(
            onBack = onNavigateBack,
            onWebSearchEnabledChange = viewModel::updateWebSearchEnabled,
            onSearchEngineChange = viewModel::updateSearchEngine,
            onSearXngUrlChange = viewModel::updateSearXngUrl,
            onSearchDepthChange = viewModel::updateSearchDepth,
            onResultCountChange = viewModel::updateResultCount,
            onAddIncludeDomain = viewModel::addIncludeDomain,
            onRemoveIncludeDomain = viewModel::removeIncludeDomain,
            onAddExcludeDomain = viewModel::addExcludeDomain,
            onRemoveExcludeDomain = viewModel::removeExcludeDomain,
            onSaveTavilySecret = { viewModel.saveTavilyApiKey(it) },
            onRevealTavilySecret = viewModel::revealTavilyApiKey,
            onClearTavilySecret = { viewModel.clearTavilyApiKey() },
        ),
    )
}

internal data class SearchConfigScreenActions(
    val onBack: () -> Unit = {},
    val onWebSearchEnabledChange: (Boolean) -> Unit = {},
    val onSearchEngineChange: (String) -> Unit = {},
    val onSearXngUrlChange: (String) -> Unit = {},
    val onSearchDepthChange: (String) -> Unit = {},
    val onResultCountChange: (Int) -> Unit = {},
    val onAddIncludeDomain: (String) -> Unit = {},
    val onRemoveIncludeDomain: (String) -> Unit = {},
    val onAddExcludeDomain: (String) -> Unit = {},
    val onRemoveExcludeDomain: (String) -> Unit = {},
    val onSaveTavilySecret: (CharArray) -> Unit = {},
    val onRevealTavilySecret: suspend () -> CharArray? = { null },
    val onClearTavilySecret: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchConfigScreenContent(
    state: SearchConfigState,
    actions: SearchConfigScreenActions = SearchConfigScreenActions(),
) {
    val uiState = state
    var newIncludeDomain by remember { mutableStateOf("") }
    var newExcludeDomain by remember { mutableStateOf("") }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.search_config_title),
        onBack = actions.onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
        Text(
            text = stringResource(R.string.search_config_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_web_enabled_row")
                .toggleable(
                    value = uiState.webSearchEnabled,
                    role = Role.Switch,
                    onValueChange = actions.onWebSearchEnabledChange,
                ),
            headlineContent = {
                Text(
                    text = stringResource(R.string.search_config_web_search),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.search_config_web_search_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = uiState.webSearchEnabled,
                    onCheckedChange = null,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("search_web_enabled_switch"),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Search Engine Selection
        SettingsSectionHeader(stringResource(R.string.search_config_engine_label))
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val engines = listOf(
                "duckduckgo" to stringResource(R.string.search_config_engine_duckduckgo),
                "searxng" to stringResource(R.string.search_config_engine_searxng),
                "tavily" to stringResource(R.string.search_config_engine_tavily)
            )
            
            engines.forEach { (id, label) ->
                val isSelected = uiState.searchEngine == id
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_engine_row_$id")
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { actions.onSearchEngineChange(id) },
                        ),
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            modifier = Modifier.testTag("search_engine_$id"),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Engine Specific Config
        if (uiState.searchEngine == "searxng") {
            OutlinedTextField(
                value = uiState.searXngUrl,
                onValueChange = actions.onSearXngUrlChange,
                label = { Text(stringResource(R.string.search_config_searxng_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uiState.searchEngine == "tavily") {
            Text(
                text = stringResource(R.string.search_config_tavily_key_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            TavilySecretEditor(actions, uiState)
            Spacer(modifier = Modifier.height(16.dp))
        }

        SettingsSectionHeader(stringResource(R.string.search_config_search_depth))

        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            val depths = listOf("basic", "advanced")
            depths.forEachIndexed { index, depth ->
                val isSelected = uiState.searchDepth == depth
                SegmentedButton(
                    selected = isSelected,
                    onClick = { actions.onSearchDepthChange(depth) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depths.size),
                    label = {
                        Text(
                            text = if (depth == "basic") stringResource(R.string.search_config_depth_basic) else stringResource(R.string.search_config_depth_advanced),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.search_config_result_count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.search_config_results, uiState.resultCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                modifier = Modifier.testTag("search_result_count_slider"),
                value = uiState.resultCount.toFloat(),
                onValueChange = { actions.onResultCountChange(it.toInt()) },
                valueRange = 1f..20f,
                steps = 18
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        DomainListSection(
            title = stringResource(R.string.search_config_include_domains),
            testTagPrefix = "search_include_domain",
            domains = uiState.includeDomains,
            newValue = newIncludeDomain,
            onNewValueChange = { newIncludeDomain = it },
            onAdd = {
                if (newIncludeDomain.isNotBlank() && newIncludeDomain !in uiState.includeDomains) {
                    actions.onAddIncludeDomain(newIncludeDomain)
                    newIncludeDomain = ""
                }
            },
            onRemove = actions.onRemoveIncludeDomain
        )

        Spacer(modifier = Modifier.height(24.dp))

        DomainListSection(
            title = stringResource(R.string.search_config_exclude_domains),
            testTagPrefix = "search_exclude_domain",
            domains = uiState.excludeDomains,
            newValue = newExcludeDomain,
            onNewValueChange = { newExcludeDomain = it },
            onAdd = {
                if (newExcludeDomain.isNotBlank() && newExcludeDomain !in uiState.excludeDomains) {
                    actions.onAddExcludeDomain(newExcludeDomain)
                    newExcludeDomain = ""
                }
            },
            onRemove = actions.onRemoveExcludeDomain
        )
        }
    }
}

@Composable
internal fun TavilySecretEditor(
    viewModel: SearchConfigViewModel,
    state: SearchConfigState,
) {
    TavilySecretEditor(
        actions = SearchConfigScreenActions(
            onSaveTavilySecret = { viewModel.saveTavilyApiKey(it) },
            onRevealTavilySecret = viewModel::revealTavilyApiKey,
            onClearTavilySecret = { viewModel.clearTavilyApiKey() },
        ),
        state = state,
    )
}

@Composable
internal fun TavilySecretEditor(
    actions: SearchConfigScreenActions,
    state: SearchConfigState,
) {
    var edit by remember { mutableStateOf("") }
    LaunchedEffect(state.secretOperation) {
        if (state.secretOperation is SearchSecretOperation.Saved) edit = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecretField(
            value = edit,
            onValueChange = { edit = it },
            hasStoredSecret = state.hasTavilyApiKey,
            onRevealRequest = actions.onRevealTavilySecret,
            onClear = {
                edit = ""
                actions.onClearTavilySecret()
            },
            modifier = Modifier.testTag("search_tavily_secret_field"),
        )
        Button(
            onClick = {
                actions.onSaveTavilySecret(edit.toCharArray())
                edit = ""
            },
            enabled = edit.isNotBlank() && state.secretOperation !is SearchSecretOperation.Saving &&
                state.secretOperation !is SearchSecretOperation.Initializing,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("search_tavily_secret_save")
        ) {
            Text(stringResource(R.string.shared_btn_save))
        }
        when (val operation = state.secretOperation) {
            SearchSecretOperation.Initializing -> Text(stringResource(R.string.search_secret_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SearchSecretOperation.Saving -> Text(stringResource(R.string.search_secret_saving), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SearchSecretOperation.Saved -> Text(
                stringResource(R.string.search_secret_saved),
                color = MaterialTheme.colorScheme.primary,
            )
            is SearchSecretOperation.Error -> Text(
                stringResource(
                    when (operation.code) {
                        SearchSecretErrorCode.LOAD_FAILED -> R.string.search_secret_load_failed
                        SearchSecretErrorCode.SAVE_FAILED -> R.string.search_secret_save_failed
                        SearchSecretErrorCode.CLEAR_FAILED -> R.string.search_secret_clear_failed
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            SearchSecretOperation.Idle -> Unit
        }
    }
}

@Composable
private fun DomainListSection(
    title: String,
    testTagPrefix: String,
    domains: List<String>,
    newValue: String,
    onNewValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    SettingsSectionHeader(title)

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = newValue,
        onValueChange = onNewValueChange,
        placeholder = { Text("example.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("${testTagPrefix}_input"),
        trailingIcon = {
            IconButton(
                onClick = onAdd,
                enabled = newValue.isNotBlank(),
                modifier = Modifier.testTag("${testTagPrefix}_add"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "${stringResource(R.string.common_cd_add)} $title ${newValue.trim()}",
                    tint = if (newValue.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    )

    if (domains.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            domains.forEach { domain ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onRemove(domain) },
                            modifier = Modifier.size(48.dp).testTag("${testTagPrefix}_remove_$domain")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "${stringResource(R.string.common_cd_remove)} $title $domain",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}
