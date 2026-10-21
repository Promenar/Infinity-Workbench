package com.promenar.nexara.ui.hub

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.params.ParameterScheme
import com.promenar.nexara.data.model.params.ReasoningEffort
import com.promenar.nexara.data.model.params.SamplingValues
import com.promenar.nexara.data.model.params.resolveModelParameterProfile
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.ui.chat.ChatReasoningControl
import com.promenar.nexara.ui.common.params.SamplingEditorActions
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "助手默认参数 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "助手默认参数 深中360 字号130", widthDp = 360, heightDp = 1100, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentDefaultParamsPreview() {
    val profile = resolveModelParameterProfile(ProtocolType.DeepSeek, "deepseek-v4-pro")
    val reasoning = ChatReasoningControl(requireNotNull(profile.reasoning), ReasoningEffort.HIGH)
    val schemes = listOf(ParameterScheme("s1", "ds::deepseek-v4-pro", "写代码", SamplingValues(temperature = 0.2), 1, 1))
    NexaraTheme(preferences = NexaraThemePreferences(mode = NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            AgentDefaultParamsContent(
                reasoning = reasoning,
                onReasoningChange = {},
                editor = agentSamplingEditorState(
                    SamplingValues(temperature = 0.2),
                    baseSchemeId = null,
                    unsupported = profile.unsupportedParams(reasoning.stored),
                    schemes = schemes,
                ),
                actions = SamplingEditorActions(),
                modelName = "DeepSeek V4 Pro",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}
