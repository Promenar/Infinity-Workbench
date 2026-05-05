package com.promenar.nexara.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    onNavigateBack: () -> Unit
) {
    var serverEnabled by remember { mutableStateOf(false) }
    var accessCode by remember { mutableStateOf("849201") }
    var clientCount by remember { mutableStateOf(3) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workbench_title), style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 24.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.workbench_desc),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(NexaraColors.SurfaceContainer, CircleShape())
                                        .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), CircleShape()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Monitor,
                                        contentDescription = null,
                                        tint = if (serverEnabled) NexaraColors.StatusSuccess else NexaraColors.Outline,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Primary Cluster",
                                        style = NexaraTypography.headlineMedium,
                                        color = NexaraColors.OnSurface
                                    )
                                    Text(
                                        text = if (serverEnabled) stringResource(R.string.workbench_status_online) else stringResource(R.string.workbench_status_offline),
                                        style = NexaraTypography.labelMedium.copy(
                                            color = if (serverEnabled) NexaraColors.StatusSuccess else NexaraColors.OnSurfaceVariant
                                        )
                                    )
                                }
                            }
                            Switch(
                                checked = serverEnabled,
                                onCheckedChange = { serverEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = NexaraColors.Primary,
                                    checkedThumbColor = NexaraColors.OnPrimary
                                )
                            )
                        }

                        AnimatedVisibility(visible = serverEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.workbench_endpoint_url),
                                    style = NexaraTypography.labelMedium,
                                    color = NexaraColors.OnSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(NexaraShapes.medium)
                                        .background(NexaraColors.SurfaceLowest.copy(alpha = 0.5f))
                                        .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), NexaraShapes.medium)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ws://192.168.1.100:8443",
                                            style = NexaraTypography.bodyMedium.copy(
                                                fontFamily = SpaceGrotesk,
                                                fontSize = 13.sp
                                            ),
                                            color = NexaraColors.Secondary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentCopy,
                                                contentDescription = stringResource(R.string.workbench_copy),
                                                tint = NexaraColors.OnSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.TertiaryContainer.copy(alpha = 0.05f))
                            .border(0.5.dp, NexaraColors.TertiaryContainer.copy(alpha = 0.2f), NexaraShapes.large)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = NexaraColors.TertiaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.workbench_section_stability),
                                style = NexaraTypography.headlineMedium,
                                color = NexaraColors.Tertiary
                            )
                        }
                        Text(
                            text = stringResource(R.string.workbench_stability_intro),
                            style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = NexaraColors.OnSurfaceVariant
                        )
                        StabilityGuideItem(stringResource(R.string.workbench_notification_perm))
                        StabilityGuideItem(stringResource(R.string.workbench_battery_opt))
                        StabilityGuideItem(
                            stringResource(R.string.workbench_recent_apps),
                            description = stringResource(R.string.workbench_recent_apps_desc)
                        )
                    }
                }
            }

            if (serverEnabled) {
                item { SettingsSectionHeader(stringResource(R.string.workbench_section_connection)) }

                item {
                    NexaraGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaraShapes.large as RoundedCornerShape
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                tint = NexaraColors.Outline,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.workbench_access_code),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(NexaraShapes.medium)
                                    .background(NexaraColors.SurfaceLowest.copy(alpha = 0.8f))
                                    .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), NexaraShapes.medium)
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = accessCode,
                                    style = NexaraTypography.headlineLarge.copy(
                                        fontFamily = SpaceGrotesk,
                                        letterSpacing = 0.25.sp
                                    ),
                                    color = NexaraColors.Primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "14:32 后刷新",
                                    style = NexaraTypography.labelMedium.copy(
                                        fontSize = 10.sp, letterSpacing = 0.1.sp
                                    ),
                                    color = NexaraColors.Outline
                                )
                                IconButton(onClick = { accessCode = (100000..999999).random().toString() }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.workbench_refresh),
                                        tint = NexaraColors.Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item { SettingsSectionHeader(stringResource(R.string.workbench_connected_clients)) }

                item {
                    NexaraGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NexaraShapes.large as RoundedCornerShape
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.2f))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.workbench_connected_clients),
                                    style = NexaraTypography.headlineMedium,
                                    color = NexaraColors.OnSurface
                                )
                                Text(
                                    text = stringResource(R.string.workbench_active_count, clientCount),
                                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }

                            ClientItem("Terminal-Alpha", "192.168.1.104", "12ms", active = true)
                            ClientItem("Mobile-Proxy-B", "10.0.4.22", "45ms", active = true)
                            ClientItem("Integration-Bot", "Internal Subnet", "--", active = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StabilityGuideItem(
    title: String,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceContainer.copy(alpha = 0.3f))
            .clickable { }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurface
            )
            if (description != null) {
                Text(
                    text = description,
                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.AddCircle,
            contentDescription = null,
            tint = NexaraColors.Tertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ClientItem(
    name: String,
    ip: String,
    latency: String,
    active: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(NexaraColors.SurfaceHigh, CircleShape())
                    .border(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.3f), CircleShape()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (active) Icons.Rounded.Monitor else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (active) NexaraColors.Primary else NexaraColors.Outline,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = name,
                    style = NexaraTypography.labelMedium,
                    color = if (active) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
                )
                Text(
                    text = ip,
                    style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = SpaceGrotesk),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = if (active) stringResource(R.string.workbench_latency, latency) else "--",
                style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                color = NexaraColors.Outline
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NexaraColors.SurfaceHighest)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (active) NexaraColors.StatusSuccess else NexaraColors.Tertiary,
                                CircleShape()
                            )
                    )
                    Text(
                        text = if (active) "Active" else "Idle",
                        style = NexaraTypography.labelMedium.copy(fontSize = 9.sp),
                        color = if (active) NexaraColors.OnSurfaceVariant else NexaraColors.Outline
                    )
                }
            }
        }
    }
}

private fun CircleShape() = RoundedCornerShape(50)
