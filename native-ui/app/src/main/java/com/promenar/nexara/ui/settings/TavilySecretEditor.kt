package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.SecretField

internal data class TavilySecretActions(
    val onSave: (CharArray) -> Unit = {},
    val onReveal: suspend () -> CharArray? = { null },
    val onClear: () -> Unit = {},
)

@Composable
internal fun TavilySecretEditor(
    viewModel: SearchConfigViewModel,
    state: SearchConfigState,
) {
    TavilySecretEditor(
        actions = TavilySecretActions(
            onSave = { viewModel.saveTavilyApiKey(it) },
            onReveal = viewModel::revealTavilyApiKey,
            onClear = { viewModel.clearTavilyApiKey() },
        ),
        state = state,
    )
}

@Composable
internal fun TavilySecretEditor(
    actions: TavilySecretActions,
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
            onRevealRequest = actions.onReveal,
            onClear = {
                edit = ""
                actions.onClear()
            },
            modifier = Modifier.testTag("search_tavily_secret_field"),
        )
        Button(
            onClick = {
                actions.onSave(edit.toCharArray())
                edit = ""
            },
            enabled = edit.isNotBlank() && state.secretOperation !is SearchSecretOperation.Saving &&
                state.secretOperation !is SearchSecretOperation.Initializing,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("search_tavily_secret_save"),
        ) {
            Text(stringResource(R.string.shared_btn_save))
        }
        when (val operation = state.secretOperation) {
            SearchSecretOperation.Initializing -> Text(
                stringResource(R.string.search_secret_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchSecretOperation.Saving -> Text(
                stringResource(R.string.search_secret_saving),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
