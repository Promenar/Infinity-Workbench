package com.promenar.infinity.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.testing.UiTags

/** 智能体预设图标：[id] 是写入数据库的标识，[labelRes] 供编辑页选择器与无障碍使用。 */
data class AgentIconOption(
    val id: String,
    val icon: ImageVector,
    val labelRes: Int,
)

/** 编辑页可选的预设图标；首页、新建会话菜单、内屏侧边栏与编辑页都按这张表映射。 */
val AgentPresetIcons: List<AgentIconOption> = listOf(
    AgentIconOption("✨", Icons.Rounded.AutoAwesome, R.string.settings_retrieval_icon_sparkles),
    AgentIconOption("💻", Icons.Rounded.Code, R.string.settings_retrieval_icon_code),
    AgentIconOption("📝", Icons.Rounded.EditNote, R.string.settings_retrieval_icon_note),
    AgentIconOption("🤖", Icons.Rounded.SmartToy, R.string.settings_retrieval_icon_robot),
    AgentIconOption("🧠", Icons.Rounded.Psychology, R.string.settings_retrieval_icon_brain),
    AgentIconOption("⭐", Icons.Rounded.Star, R.string.settings_retrieval_icon_star),
    AgentIconOption("🔥", Icons.Rounded.LocalFireDepartment, R.string.settings_retrieval_icon_fire),
    AgentIconOption("💡", Icons.Rounded.Lightbulb, R.string.settings_retrieval_icon_light),
    AgentIconOption("⚡", Icons.Rounded.Bolt, R.string.settings_retrieval_icon_bolt),
    AgentIconOption("🎯", Icons.Rounded.TrackChanges, R.string.settings_retrieval_icon_target),
)

/** 内置预置智能体使用、选择器不提供的标识。 */
private val LegacyAgentIcons: Map<String, ImageVector> = mapOf(
    "🌐" to Icons.Rounded.Translate,
    "A" to Icons.Rounded.Translate,
)

/** 未知标识回落到与数据库默认值 ✨ 相同的图标。 */
val DefaultAgentIcon: ImageVector = Icons.Rounded.AutoAwesome

fun agentIconOption(id: String): AgentIconOption? = AgentPresetIcons.firstOrNull { it.id == id }

fun agentIconVector(id: String): ImageVector =
    agentIconOption(id)?.icon ?: LegacyAgentIcons[id] ?: DefaultAgentIcon

/**
 * 解析 `#RRGGBB` 或 `#AARRGGBB`；格式不对返回 null。
 * 不依赖 android.graphics，便于 JVM 单测，也保证各处解析结果一致。
 */
fun parseAgentColor(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    val value = digits.toLongOrNull(16) ?: return null
    return if (digits.length == 6) Color(OPAQUE_ALPHA or value) else Color(value)
}

/** 六位色值补上的不透明 alpha 位。 */
private const val OPAQUE_ALPHA = 0xFF000000L

/** 头像底色：直接使用智能体主题色原值，解析失败时用 [fallback]。 */
fun agentAvatarColor(hex: String, fallback: Color): Color = parseAgentColor(hex) ?: fallback

@Composable
fun agentAvatarColor(hex: String): Color = agentAvatarColor(hex, MaterialTheme.colorScheme.primary)

@Composable
fun AgentAvatar(
    icon: ImageVector? = null,
    customImageUri: String? = null,
    backgroundColor: Color,
    size: Dp = 80.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
    )
    val avatarDescription = contentDescription ?: stringResource(
        if (onClick != null) R.string.agent_avatar_edit else R.string.agent_avatar
    )

    val touchTargetSize = if (onClick != null && size < 48.dp) 48.dp else size
    Box(
        modifier = Modifier
            .size(touchTargetSize)
            .then(
                if (onClick != null) {
                    Modifier
                        .testTag(UiTags.AGENT_AVATAR_ACTION)
                        .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = avatarDescription,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .semantics {
                this.contentDescription = avatarDescription
                if (onClick != null) role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) {
            if (customImageUri != null) {
                AsyncImage(
                    model = customImageUri,
                    contentDescription = null,
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.infinityDomainColors.overlayContent,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
