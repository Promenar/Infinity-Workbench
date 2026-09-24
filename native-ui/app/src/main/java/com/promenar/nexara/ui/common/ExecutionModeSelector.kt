package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraTypography

enum class ExecutionMode(@param:StringRes val labelRes: Int) {
    AUTO(R.string.common_mode_auto),
    SEMI(R.string.common_mode_semi),
    MANUAL(R.string.common_mode_manual)
}

@Composable
fun ExecutionModeSelector(
    selected: ExecutionMode,
    onSelect: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = ExecutionMode.entries
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)

    NexaraTabSwitcher(
        itemCount = modes.size,
        selectedIndex = selectedIndex,
        onTabSelected = { index -> onSelect(modes[index]) },
        modifier = modifier,
        height = 44.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { index, isSelected, contentColor ->
        val mode = modes[index]
        Text(
            text = stringResource(mode.labelRes),
            style = NexaraTypography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}
