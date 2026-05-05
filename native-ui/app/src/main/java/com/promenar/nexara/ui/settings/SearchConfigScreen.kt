package com.promenar.nexara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun SearchConfigScreen(
    onNavigateBack: () -> Unit
) {
    var webSearchEnabled by remember { mutableStateOf(true) }
    var searchDepth by remember { mutableStateOf("advanced") }
    var resultCount by remember { mutableStateOf(10f) }
    var includeDomains by remember { mutableStateOf(listOf<String>()) }
    var excludeDomains by remember { mutableStateOf(listOf<String>()) }
    var newIncludeDomain by remember { mutableStateOf("") }
    var newExcludeDomain by remember { mutableStateOf("") }

    NexaraPageLayout(
        title = stringResource(R.string.search_config_title),
        onBack = onNavigateBack
    ) {
        Text(
            text = stringResource(R.string.search_config_desc),
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as RoundedCornerShape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.search_config_web_search),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface
                    )
                    Text(
                        text = stringResource(R.string.search_config_web_search_desc),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
                Switch(
                    checked = webSearchEnabled,
                    onCheckedChange = { webSearchEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = NexaraColors.Primary,
                        checkedThumbColor = NexaraColors.OnPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.search_config_search_depth),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("basic", "advanced").forEach { depth ->
                val isSelected = searchDepth == depth
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(NexaraShapes.medium)
                        .background(
                            if (isSelected) NexaraColors.PrimaryContainer.copy(alpha = 0.15f)
                            else NexaraColors.SurfaceContainer
                        )
                        .border(
                            1.dp,
                            if (isSelected) NexaraColors.Primary else NexaraColors.GlassBorder,
                            NexaraShapes.medium
                        )
                        .clickable { searchDepth = depth }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (depth == "basic") stringResource(R.string.search_config_depth_basic) else stringResource(R.string.search_config_depth_advanced),
                        style = NexaraTypography.labelMedium,
                        color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.search_config_result_count),
            style = NexaraTypography.headlineMedium,
            color = NexaraColors.OnSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.search_config_results, resultCount.toInt()),
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant
            )
        }

        Slider(
            value = resultCount,
            onValueChange = { resultCount = it },
            valueRange = 1f..20f,
            steps = 18,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.OutlineVariant
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        DomainListSection(
            title = stringResource(R.string.search_config_include_domains),
            domains = includeDomains,
            newValue = newIncludeDomain,
            onNewValueChange = { newIncludeDomain = it },
            onAdd = {
                if (newIncludeDomain.isNotBlank() && newIncludeDomain !in includeDomains) {
                    includeDomains = includeDomains + newIncludeDomain
                    newIncludeDomain = ""
                }
            },
            onRemove = { includeDomains = includeDomains - it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        DomainListSection(
            title = stringResource(R.string.search_config_exclude_domains),
            domains = excludeDomains,
            newValue = newExcludeDomain,
            onNewValueChange = { newExcludeDomain = it },
            onAdd = {
                if (newExcludeDomain.isNotBlank() && newExcludeDomain !in excludeDomains) {
                    excludeDomains = excludeDomains + newExcludeDomain
                    newExcludeDomain = ""
                }
            },
            onRemove = { excludeDomains = excludeDomains - it }
        )
    }
}

@Composable
private fun DomainListSection(
    title: String,
    domains: List<String>,
    newValue: String,
    onNewValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Text(
        text = title,
        style = NexaraTypography.headlineMedium,
        color = NexaraColors.OnSurface
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(NexaraShapes.medium)
                .background(NexaraColors.SurfaceContainer)
                .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = newValue,
                onValueChange = onNewValueChange,
                singleLine = true,
                textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                cursorBrush = SolidColor(NexaraColors.Primary),
                modifier = Modifier.fillMaxWidth()
            )
            if (newValue.isEmpty()) {
                Text(
                    text = "example.com",
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(NexaraShapes.medium)
                .background(NexaraColors.SurfaceHigh)
                .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Add",
                tint = NexaraColors.Primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (domains.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        domains.forEach { domain ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = domain,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurface
                )
                IconButton(
                    onClick = { onRemove(domain) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove",
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
