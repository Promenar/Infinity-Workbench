package com.promenar.nexara.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val type: String = "stdio",
    val isConnected: Boolean = false,
    val isEnabled: Boolean = false,
    val isDefault: Boolean = false,
    val callIntervalMs: Long = 1000,
    val tools: List<String> = emptyList()
)

private val skillIcons: Map<String, ImageVector> = mapOf(
    "web_search" to Icons.Rounded.TravelExplore,
    "code_interpreter" to Icons.Rounded.Code,
    "image_generation" to Icons.Rounded.Image,
    "knowledge_retrieval" to Icons.Rounded.Storage,
    "weather_lookup" to Icons.Rounded.Cloud,
    "calendar" to Icons.Rounded.CalendarMonth
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    val skills by viewModel.skills.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var loopLimit by remember { mutableStateOf(15) }
    val tabs = listOf(
        stringResource(R.string.skills_tab_preset),
        stringResource(R.string.skills_tab_user),
        stringResource(R.string.skills_tab_mcp)
    )

    val mcpServers = remember {
        mutableStateListOf(
            McpServer("s1", "GitHub Integration", "https://api.github.com", "http", isConnected = true, isEnabled = true, tools = listOf("read_repository", "create_commit", "create_pr")),
            McpServer("s2", "Local File System", "file://./workspace", "stdio", isConnected = false, tools = listOf("read_file", "write_file", "list_dir"))
        )
    }
    var showAddMcp by remember { mutableStateOf(false) }
    var showCodeEditor by remember { mutableStateOf(false) }
    var selectedSkillForEdit by remember { mutableStateOf<String?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills_title), style = NexaraTypography.headlineLarge, color = NexaraColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                stringResource(R.string.skills_desc),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.skills_loop_limit), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            Text(stringResource(R.string.skills_loop_limit_desc), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NexaraColors.SurfaceContainer)
                                .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                                .clickable { loopLimit = (loopLimit - 1).coerceAtLeast(1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease", tint = NexaraColors.OnSurface, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            if (loopLimit >= 100) stringResource(R.string.skills_unlimited) else "$loopLimit",
                            style = NexaraTypography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                            color = NexaraColors.Primary
                        )
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NexaraColors.SurfaceContainer)
                                .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                                .clickable { loopLimit = (loopLimit + 1).coerceAtMost(100) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase", tint = NexaraColors.OnSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (loopLimit >= 100) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NexaraColors.StatusWarning.copy(alpha = 0.1f))
                                .border(0.5.dp, NexaraColors.StatusWarning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = NexaraColors.StatusWarning, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.skills_warning_unlimited), style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurface)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = NexaraColors.CanvasBackground,
                contentColor = NexaraColors.Primary,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        with(TabRowDefaults) {
                            SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = NexaraColors.Primary
                            )
                        }
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                style = NexaraTypography.labelMedium,
                                color = if (selectedTab == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    skills.forEach { skill ->
                        SkillCard(
                            skill = skill,
                            icon = skillIcons[skill.id] ?: Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                1 -> {
                    skills.forEach { skill ->
                        UserSkillCard(
                            skill = skill,
                            icon = skillIcons[skill.id] ?: Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) },
                            onEdit = {
                                selectedSkillForEdit = skill.id
                                showCodeEditor = true
                            },
                            onDelete = { }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(NexaraShapes.medium)
                            .background(NexaraColors.SurfaceHigh)
                            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium)
                            .clickable { }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.skills_add_custom), style = NexaraTypography.labelMedium, color = NexaraColors.Primary)
                        }
                    }
                }
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NexaraColors.Primary.copy(alpha = 0.08f))
                                .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .clickable { showAddMcp = true }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                                Text(stringResource(R.string.skills_add_mcp), style = NexaraTypography.labelMedium, color = NexaraColors.Primary)
                            }
                        }

                        mcpServers.forEach { server ->
                            McpServerCard(
                                server = server,
                                isExpanded = expandedServerId == server.id,
                                onToggleExpand = { expandedServerId = if (expandedServerId == server.id) null else server.id },
                                onToggleEnabled = { enabled ->
                                    val idx = mcpServers.indexOf(server)
                                    if (idx >= 0) mcpServers[idx] = server.copy(isEnabled = enabled)
                                },
                                onDelete = { mcpServers.remove(server) },
                                onSync = { },
                                onIntervalChange = { interval ->
                                    val idx = mcpServers.indexOf(server)
                                    if (idx >= 0) mcpServers[idx] = server.copy(callIntervalMs = interval)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    if (showAddMcp) {
        var mcpName by remember { mutableStateOf("") }
        var mcpUrl by remember { mutableStateOf("") }
        var mcpType by remember { mutableStateOf("http") }
        ModalBottomSheet(
            onDismissRequest = { showAddMcp = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.skills_add_mcp), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                NexaraGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    BasicTextField(
                        value = mcpName,
                        onValueChange = { mcpName = it },
                        textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        decorationBox = { inner ->
                            if (mcpName.isEmpty()) Text(stringResource(R.string.skills_mcp_name_placeholder), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                            inner()
                        }
                    )
                }
                NexaraGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    BasicTextField(
                        value = mcpUrl,
                        onValueChange = { mcpUrl = it },
                        textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        decorationBox = { inner ->
                            if (mcpUrl.isEmpty()) Text(stringResource(R.string.skills_mcp_url_placeholder), style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                            inner()
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("http" to "HTTP", "stdio" to "STDIO").forEach { (value, label) ->
                        val selected = mcpType == value
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) NexaraColors.Primary.copy(alpha = 0.1f) else NexaraColors.SurfaceContainer)
                                .border(0.5.dp, if (selected) NexaraColors.Primary.copy(alpha = 0.3f) else NexaraColors.GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { mcpType = value }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, style = NexaraTypography.labelMedium, color = if (selected) NexaraColors.Primary else NexaraColors.OnSurface)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NexaraColors.Primary)
                        .clickable {
                            if (mcpName.isNotBlank() && mcpUrl.isNotBlank()) {
                                mcpServers.add(
                                    McpServer(
                                        id = "s${mcpServers.size + 1}",
                                        name = mcpName,
                                        url = mcpUrl,
                                        type = mcpType
                                    )
                                )
                                showAddMcp = false
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.skills_mcp_add_btn), style = NexaraTypography.labelMedium, color = NexaraColors.OnPrimary)
                }
            }
        }
    }

    if (showCodeEditor && selectedSkillForEdit != null) {
        var codeText by remember { mutableStateOf("// Configure skill: ${selectedSkillForEdit}") }
        ModalBottomSheet(
            onDismissRequest = { showCodeEditor = false },
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.skills_edit_skill), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    BasicTextField(
                        value = codeText,
                        onValueChange = { codeText = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NexaraColors.Primary)
                        .clickable { showCodeEditor = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.shared_btn_save), style = NexaraTypography.labelMedium, color = NexaraColors.OnPrimary)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillInfo,
    icon: ImageVector,
    onToggle: () -> Unit
) {
    var enabled by remember { mutableStateOf(skill.enabled) }

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(NexaraColors.SurfaceHigh, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                }
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(
                            skill.id,
                            style = NexaraTypography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                            color = NexaraColors.Outline
                        )
                    }
                    Text(skill.description, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; onToggle() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NexaraColors.Primary,
                    checkedThumbColor = NexaraColors.OnPrimary
                )
            )
        }
    }
}

@Composable
private fun UserSkillCard(
    skill: SkillInfo,
    icon: ImageVector,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var enabled by remember { mutableStateOf(skill.enabled) }

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).background(NexaraColors.SurfaceHigh, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(skill.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(skill.description, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                    }
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(NexaraColors.SurfaceHigh).clickable { onEdit() }.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = NexaraColors.OnSurface, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.skills_configure), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface)
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(NexaraColors.Error.copy(alpha = 0.1f)).clickable { onDelete() }.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = NexaraColors.Error, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.shared_btn_delete), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.Error)
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServer,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
    onIntervalChange: (Long) -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (server.isConnected) NexaraColors.StatusSuccess else NexaraColors.Error, CircleShape)
                    )
                    Column {
                        Text(server.name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(server.url, style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Sync, contentDescription = "Sync", tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = NexaraColors.Error, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.skills_mcp_call_interval), style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                Text("${server.callIntervalMs}ms", style = NexaraTypography.bodySmall.copy(fontSize = 11.sp), color = NexaraColors.Primary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary)
                )
                Text(stringResource(R.string.skills_mcp_enabled), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface, modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = server.isDefault,
                    onCheckedChange = { },
                    colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary)
                )
                Text(stringResource(R.string.skills_mcp_default), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface, modifier = Modifier.align(Alignment.CenterVertically))
            }

            if (server.tools.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.skills_mcp_tools, server.tools.size), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                if (isExpanded) {
                    server.tools.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(12.dp))
                            Text(tool, style = NexaraTypography.bodyMedium.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = NexaraColors.OnSurface)
                        }
                    }
                }
            }
        }
    }
}
