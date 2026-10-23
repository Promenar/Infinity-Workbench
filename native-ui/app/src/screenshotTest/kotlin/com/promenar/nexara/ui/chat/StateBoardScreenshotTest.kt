package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.state.*
import com.promenar.nexara.ui.chat.components.StateBoardBar
import com.promenar.nexara.ui.chat.components.StateBoardDetails
import com.promenar.nexara.ui.theme.*
import kotlinx.serialization.json.*

@PreviewTest
@Preview(name = "状态 收起 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable fun stateCollapsedDarkChinese() = StatePreview(false, false)

@PreviewTest
@Preview(name = "状态 收起 浅英360 大字号", widthDp = 360, heightDp = 140, locale = "en", fontScale = 1.3f)
@Composable fun stateCollapsedLightEnglish() = StatePreview(true, false)

@PreviewTest
@Preview(name = "状态 展开 深中411", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable fun stateExpandedDarkChinese() = StatePreview(false, true)

@PreviewTest
@Preview(name = "状态 展开 浅英360 大字号", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Composable fun stateExpandedLightEnglish() = StatePreview(true, true)

@Composable private fun StatePreview(english: Boolean, expanded: Boolean) {
    val board = StateBoard(if (english) "Mountain journey" else "山中历险", listOf(
        StateField("hp", if (english) "Health" else "生命值", "meter", buildJsonObject { put("current", 65); put("max", 100) }),
        StateField("place", if (english) "Location" else "位置", "text", JsonPrimitive(if (english) "Hidden cave" else "隐蔽山洞")),
        StateField("bag", if (english) "Inventory" else "背包", "list", JsonArray(listOf(JsonPrimitive(if (english) "Torch" else "火把"), JsonPrimitive(if (english) "Rope" else "绳子")))),
        StateField("gold", if (english) "Gold" else "金币", "number", JsonPrimitive(120), unit = if (english) "coins" else "枚"),
    ))
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (english) NexaraThemeMode.LIGHT else NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            if (expanded) StateBoardDetails(board, {}, {}, {})
            else Box(Modifier.padding(vertical = 20.dp)) { StateBoardBar(board, false, {}) }
        }
    }
}
