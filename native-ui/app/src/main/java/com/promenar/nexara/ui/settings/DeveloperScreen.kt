package com.promenar.nexara.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val shareLogsChooser = stringResource(R.string.developer_share_logs_chooser)

    NexaraSettingsPageLayout(
        title = stringResource(R.string.developer_title),
        onBack = onNavigateBack,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            item {
                SettingsSectionHeader(stringResource(R.string.developer_diagnostics_section))
            }

            item {
                NexaraSettingsItem(
                    title = stringResource(R.string.developer_export_logs),
                    subtitle = stringResource(R.string.developer_export_logs_desc),
                    showChevron = false,
                    onClick = {
                            val logFile = File(context.filesDir, "nexara_logs.txt")
                            if (logFile.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logFile
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        shareLogsChooser,
                                    )
                                )
                            }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item {
                NexaraSettingsItem(
                    title = stringResource(R.string.developer_clear_logs),
                    subtitle = stringResource(R.string.developer_clear_logs_desc),
                    showChevron = false,
                    onClick = {
                        val logFile = File(context.filesDir, "nexara_logs.txt")
                        if (logFile.exists()) {
                            logFile.writeText("")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item {
                SettingsSectionHeader(stringResource(R.string.developer_device_section))
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(stringResource(R.string.developer_os_version), android.os.Build.VERSION.RELEASE)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(stringResource(R.string.developer_model), android.os.Build.MODEL)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(stringResource(R.string.developer_manufacturer), android.os.Build.MANUFACTURER)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val stackContent = LocalDensity.current.fontScale >= 1.5f
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = if (stackContent) {
            { Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        } else {
            null
        },
        trailingContent = if (stackContent) {
            null
        } else {
            { Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
