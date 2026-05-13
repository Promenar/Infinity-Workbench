package com.promenar.nexara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.font.FontWeight
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.settings.SearchConfigViewModel
import com.promenar.nexara.ui.settings.SearchConfigState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))
    
    val skillIcons = remember {
        mapOf(
            "web_search" to Icons.Rounded.Search,
            "search_tavily" to Icons.Rounded.Search,
            "search_searxng" to Icons.Rounded.Search,
            "calculator" to Icons.Rounded.Build,
            "current_time" to Icons.Rounded.Info,
            "create_tool" to Icons.Rounded.Build
        )
    }
    val presetSkills by viewModel.skills.collectAsState()
    val userSkills by viewModel.userSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val loopLimit by viewModel.loopLimit.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.skills_tab_preset),
        stringResource(R.string.skills_tab_user),
        stringResource(R.string.skills_tab_mcp)
    )

    var showAddMcp by remember { mutableStateOf(false) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var showSearchConfig by remember { mutableStateOf<String?>(null) }
    var selectedSkillForEdit by remember { mutableStateOf<String?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    NexaraPageLayout(
        title = stringResource(R.string.skills_title),
        onBack = onNavigateBack,
        scrollable = true
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp).navigationBarsPadding()) {
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
                                .clickable { viewModel.updateLoopLimit((loopLimit - 1).coerceAtLeast(1)) },
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
                                .clickable { viewModel.updateLoopLimit((loopLimit + 1).coerceAtMost(100)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase", tint = NexaraColors.OnSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = NexaraColors.OnSurface,
                divider = {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = NexaraColors.GlassBorder
                    )
                },
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        val pos = tabPositions[selectedTab]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .padding(horizontal = 48.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NexaraColors.Primary)
                        )
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
                                style = NexaraTypography.labelMedium.copy(
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (selectedTab == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                            )
                        },
                        selectedContentColor = NexaraColors.Primary,
                        unselectedContentColor = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    // Preset skills
                    presetSkills.forEach { skill ->
                        SkillCard(
                            skill = skill,
                            icon = skillIcons[skill.id] ?: Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) },
                            onConfig = if (skill.id in listOf("web_search", "search_tavily", "search_searxng")) {
                                { showSearchConfig = skill.id }
                            } else null
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                1 -> {
                    // User skills
                    userSkills.forEach { skill ->
                        UserSkillCard(
                            id = skill.id,
                            name = skill.name,
                            description = skill.description,
                            enabled = skill.enabled,
                            icon = Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) },
                            onEdit = {
                                selectedSkillForEdit = skill.id
                                showCreateSkill = true
                            },
                            onDelete = { viewModel.deleteCustomSkill(skill.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    if (userSkills.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.skills_user_empty),
                                style = NexaraTypography.bodyMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(NexaraShapes.medium)
                            .background(NexaraColors.Primary.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f), NexaraShapes.medium)
                            .clickable { 
                                selectedSkillForEdit = null
                                showCreateSkill = true 
                            }
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
                    // MCP servers
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

                        if (mcpServers.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.skills_mcp_empty),
                                    style = NexaraTypography.bodyMedium,
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }
                        }

                        mcpServers.forEach { server ->
                            McpServerCard(
                                server = server,
                                isExpanded = expandedServerId == server.id,
                                onToggleExpand = { expandedServerId = if (expandedServerId == server.id) null else server.id },
                                onToggleEnabled = { enabled -> viewModel.toggleMcpServer(server.id, enabled) },
                                onDelete = { viewModel.deleteMcpServer(server.id) },
                                onSync = { viewModel.syncMcpServer(server.id) },
                                onUpdateDefault = { isDefault -> viewModel.updateMcpServerDefault(server.id, isDefault) }
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
                                viewModel.addMcpServer(mcpName, mcpUrl, mcpType)
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

    if (showCreateSkill) {
        val skillToEdit = selectedSkillForEdit?.let { id -> userSkills.find { it.id == id } }
        var skillName by remember { mutableStateOf(skillToEdit?.name ?: "") }
        var skillDesc by remember { mutableStateOf(skillToEdit?.description ?: "") }
        var skillCode by remember { mutableStateOf(skillToEdit?.code ?: "") }
        
        ModalBottomSheet(
            onDismissRequest = { showCreateSkill = false },
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
                Text(
                    if (selectedSkillForEdit == null) stringResource(R.string.skills_add_custom) 
                    else stringResource(R.string.skills_edit_custom), 
                    style = NexaraTypography.headlineMedium, 
                    color = NexaraColors.OnSurface
                )
                
                Text("Metadata", style = NexaraTypography.labelSmall, color = NexaraColors.Primary)
                
                NexaraGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    BasicTextField(
                        value = skillName,
                        onValueChange = { skillName = it },
                        textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        decorationBox = { inner ->
                            if (skillName.isEmpty()) Text("Tool Name (e.g. my_custom_tool)", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                            inner()
                        }
                    )
                }
                NexaraGlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    BasicTextField(
                        value = skillDesc,
                        onValueChange = { skillDesc = it },
                        textStyle = NexaraTypography.bodyMedium.copy(color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        decorationBox = { inner ->
                            if (skillDesc.isEmpty()) Text("Description", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                            inner()
                        }
                    )
                }
                
                Text("Implementation (JS/Kotlin Sandbox)", style = NexaraTypography.labelSmall, color = NexaraColors.Primary)
                
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    BasicTextField(
                        value = skillCode,
                        onValueChange = { skillCode = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NexaraColors.OnSurface),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        decorationBox = { inner ->
                            if (skillCode.isEmpty()) Text("// Example:\n// return \"Result from my tool\";", style = NexaraTypography.bodySmall, color = NexaraColors.OnSurfaceVariant)
                            inner()
                        }
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NexaraColors.Primary)
                        .clickable {
                            if (skillName.isNotBlank()) {
                                viewModel.addCustomSkill(
                                    name = skillName,
                                    description = skillDesc,
                                    schema = "{}",
                                    code = skillCode,
                                    id = selectedSkillForEdit
                                )
                                showCreateSkill = false
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.shared_btn_save), style = NexaraTypography.labelMedium, color = NexaraColors.OnPrimary)
                }
            }
        }
    }

    if (showSearchConfig != null) {
        SearchConfigBottomSheet(
            skillId = showSearchConfig!!,
            onDismiss = { showSearchConfig = null }
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillInfo,
    icon: ImageVector,
    onToggle: () -> Unit,
    onConfig: (() -> Unit)? = null
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
            
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onConfig != null) {
                    IconButton(onClick = { onConfig.invoke() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Config", tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
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
}

@Composable
private fun UserSkillCard(
    id: String,
    name: String,
    description: String,
    enabled: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }

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
                        Text(name, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                        Text(description, style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                    }
                }
                Switch(checked = isEnabled, onCheckedChange = { isEnabled = it; onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary))
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
    server: McpServerUiModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
    onUpdateDefault: (Boolean) -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary)
                )
                Text(stringResource(R.string.skills_mcp_enabled), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface)
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = server.isDefault,
                    onCheckedChange = onUpdateDefault,
                    colors = SwitchDefaults.colors(checkedTrackColor = NexaraColors.Primary, checkedThumbColor = NexaraColors.OnPrimary)
                )
                Text(stringResource(R.string.skills_mcp_default), style = NexaraTypography.labelMedium.copy(fontSize = 11.sp), color = NexaraColors.OnSurface)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchConfigBottomSheet(
    skillId: String,
    onDismiss: () -> Unit
) {
    val searchViewModel: SearchConfigViewModel = viewModel()
    val searchState by searchViewModel.uiState.collectAsState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaraColors.CanvasBackground,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(NexaraColors.Outline.copy(alpha = 0.2f), CircleShape)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (skillId) {
                    "web_search" -> "Web 搜索全局配置"
                    "search_tavily" -> "Tavily 搜索配置"
                    "search_searxng" -> "SearXNG 搜索配置"
                    else -> "配置"
                },
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )

            if (skillId == "web_search") {
                Text(stringResource(R.string.search_engine_select), style = NexaraTypography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineOption("duckduckgo", stringResource(R.string.search_engine_duckduckgo), searchState.searchEngine == "duckduckgo") {
                        searchViewModel.updateSearchEngine("duckduckgo")
                    }
                    EngineOption("tavily", stringResource(R.string.search_engine_tavily), searchState.searchEngine == "tavily") {
                        searchViewModel.updateSearchEngine("tavily")
                    }
                    EngineOption("searxng", stringResource(R.string.search_engine_searxng), searchState.searchEngine == "searxng") {
                        searchViewModel.updateSearchEngine("searxng")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Common settings for generic web_search
                SettingsSlider(
                    label = stringResource(R.string.search_count_label),
                    value = searchState.resultCount.toFloat(),
                    range = 1f..20f,
                    onValueChange = { searchViewModel.updateResultCount(it.toInt()) }
                )
            }

            if (skillId == "search_tavily" || (skillId == "web_search" && searchState.searchEngine == "tavily")) {
                Text(stringResource(R.string.search_api_key), style = NexaraTypography.labelMedium)
                BasicTextField(
                    value = searchState.tavilyApiKey,
                    onValueChange = { searchViewModel.updateTavilyApiKey(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(NexaraColors.SurfaceContainer, RoundedCornerShape(12.dp))
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    textStyle = NexaraTypography.bodyLarge.copy(color = NexaraColors.OnSurface, fontFamily = FontFamily.Monospace),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchState.tavilyApiKey.isEmpty()) Text("Paste API Key here", color = NexaraColors.Outline, style = NexaraTypography.bodyLarge)
                            innerTextField()
                        }
                    }
                )
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_depth_label), style = NexaraTypography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepthChip("basic", stringResource(R.string.search_depth_basic), searchState.searchDepth == "basic") {
                            searchViewModel.updateSearchDepth("basic")
                        }
                        DepthChip("advanced", stringResource(R.string.search_depth_advanced), searchState.searchDepth == "advanced") {
                            searchViewModel.updateSearchDepth("advanced")
                        }
                    }
                }
            }

            if (skillId == "search_searxng" || (skillId == "web_search" && searchState.searchEngine == "searxng")) {
                Text(stringResource(R.string.search_instance_url), style = NexaraTypography.labelMedium)
                BasicTextField(
                    value = searchState.searXngUrl,
                    onValueChange = { searchViewModel.updateSearXngUrl(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(NexaraColors.SurfaceContainer, RoundedCornerShape(12.dp))
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    textStyle = NexaraTypography.bodyLarge.copy(color = NexaraColors.OnSurface, fontFamily = FontFamily.Monospace),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchState.searXngUrl.isEmpty()) Text("https://...", color = NexaraColors.Outline, style = NexaraTypography.bodyLarge)
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EngineOption(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f) else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) NexaraColors.Primary else NexaraColors.GlassBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = NexaraTypography.bodyLarge, color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface)
        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DepthChip(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isSelected) NexaraColors.Primary else NexaraColors.SurfaceContainer)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(label, style = NexaraTypography.labelSmall, color = if (isSelected) NexaraColors.OnPrimary else NexaraColors.OnSurfaceVariant)
    }
}

@Composable
private fun SettingsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = NexaraTypography.labelMedium)
            Text("${value.toInt()}", style = NexaraTypography.labelMedium, color = NexaraColors.Primary)
        }
        NexaraSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

