package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.R

/**
 * 协议类型选择器组件
 */
@Composable
fun ProtocolSelector(
    selected: ProtocolType,
    onSelect: (ProtocolType) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = ProtocolType.entries.filter { it !is ProtocolType.Local }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { proto ->
            val isSelected = selected == proto
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(NexaraShapes.medium)
                    .background(
                        if (isSelected) NexaraColors.Primary.copy(alpha = 0.1f) 
                        else NexaraColors.SurfaceContainer.copy(alpha = 0.3f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) NexaraColors.Primary else NexaraColors.GlassBorder,
                        shape = NexaraShapes.medium
                    )
                    .clickable { onSelect(proto) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 品牌图标
                    proto.iconRes?.let { iconId ->
                        Icon(
                            painter = painterResource(id = iconId),
                            contentDescription = null,
                            tint = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = proto.displayName,
                            style = NexaraTypography.bodyMedium,
                            color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface
                        )
                        if (proto.defaultPath.isNotEmpty()) {
                            Text(
                                text = proto.defaultPath,
                                style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                    
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Selected",
                            tint = NexaraColors.Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
