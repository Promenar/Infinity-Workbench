package com.promenar.nexara.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text("开发者面板", style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground,
                    titleContentColor = NexaraColors.OnSurface,
                    navigationIconContentColor = NexaraColors.OnSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "系统诊断与调试",
                    style = NexaraTypography.labelLarge,
                    color = NexaraColors.Primary,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Share,
                    title = "导出运行日志",
                    subtitle = "分享 nexara_logs.txt 文件以进行故障诊断",
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
                            context.startActivity(Intent.createChooser(intent, "分享日志文件"))
                        }
                    }
                )
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.BugReport,
                    title = "清除运行日志",
                    subtitle = "重置日志文件大小",
                    onClick = {
                        val logFile = File(context.filesDir, "nexara_logs.txt")
                        if (logFile.exists()) {
                            logFile.writeText("")
                        }
                    }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "设备信息",
                    style = NexaraTypography.labelLarge,
                    color = NexaraColors.Primary
                )
            }
            
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        InfoRow("OS Version", android.os.Build.VERSION.RELEASE)
                        InfoRow("Model", android.os.Build.MODEL)
                        InfoRow("Manufacturer", android.os.Build.MANUFACTURER)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = NexaraTypography.bodySmall, color = NexaraColors.OnSurfaceVariant)
        Text(value, style = NexaraTypography.bodySmall, color = NexaraColors.OnSurface)
    }
}
