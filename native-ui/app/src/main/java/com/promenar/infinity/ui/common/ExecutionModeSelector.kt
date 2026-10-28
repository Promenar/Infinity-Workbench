package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTypography

enum class ExecutionMode(@param:StringRes val labelRes: Int, @param:StringRes val descriptionRes: Int) {
    WORKSPACE(R.string.common_mode_workspace, R.string.common_mode_workspace_desc),
    FULL_ACCESS(R.string.common_mode_full_access, R.string.common_mode_full_access_desc),
}

@Composable
fun ExecutionModeSelector(
    selected: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ExecutionMode.entries
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)

    InfinityTabSwitcher(
        itemCount = modes.size,
        selectedIndex = selectedIndex,
        onTabSelected = { index -> onSelect(modes[index]) },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { index, isSelected, contentColor ->
        val mode = modes[index]
        Text(
            text = stringResource(mode.labelRes),
            style = InfinityTypography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}
