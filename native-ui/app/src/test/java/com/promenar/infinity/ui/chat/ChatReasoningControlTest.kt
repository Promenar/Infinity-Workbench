package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.params.ReasoningEffort
import com.promenar.infinity.data.remote.protocol.ProtocolType
import org.junit.Test

class ChatReasoningControlTest {
    private val providers = listOf(
        ProviderListItem(id = "ds", name = "DeepSeek", protocolType = ProtocolType.DeepSeek),
        ProviderListItem(id = "claude", name = "Anthropic", protocolType = ProtocolType.Anthropic_Messages),
    )
    private val models = listOf(
        ModelInfo(name = "V4 Pro", id = "ds::deepseek-v4-pro", description = "", enabled = true, providerId = "ds", remoteModelId = "deepseek-v4-pro"),
        ModelInfo(name = "Chat", id = "ds::deepseek-chat", description = "", enabled = true, providerId = "ds", remoteModelId = "deepseek-chat"),
    )

    @Test
    fun `会话未设置时沿用助手档位，会话的模型默认标记不回退`() {
        assertThat(storedReasoningEffort(null, "high")).isEqualTo(ReasoningEffort.HIGH)
        assertThat(storedReasoningEffort("low", "high")).isEqualTo(ReasoningEffort.LOW)
        assertThat(storedReasoningEffort(ReasoningEffort.MODEL_DEFAULT_WIRE_NAME, "high")).isNull()
        assertThat(storedReasoningEffort(null, null)).isNull()
    }

    @Test
    fun `选择模型默认时只在助手有档位时写入显式标记`() {
        assertThat(sessionReasoningEffortFor(ReasoningEffort.MAX, "low")).isEqualTo("max")
        assertThat(sessionReasoningEffortFor(null, null)).isNull()
        assertThat(sessionReasoningEffortFor(null, "high")).isEqualTo(ReasoningEffort.MODEL_DEFAULT_WIRE_NAME)
        assertThat(sessionReasoningEffortFor(null, "unknown")).isNull()
    }

    @Test
    fun `胶囊按会话模型的服务商协议解析档位，模型不支持或服务商未知时隐藏`() {
        val control = chatReasoningControl("ds::deepseek-v4-pro", providers, models, null, "max")!!
        assertThat(control.support.levels).containsExactly(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX).inOrder()
        assertThat(control.effective).isEqualTo(ReasoningEffort.MAX)
        assertThat(control.isCustomized).isTrue()

        assertThat(chatReasoningControl("ds::deepseek-chat", providers, models, null, null)).isNull()
        assertThat(chatReasoningControl("missing::deepseek-v4-pro", providers, models, null, null)).isNull()
        assertThat(chatReasoningControl(null, providers, models, null, null)).isNull()
        // 模型列表尚未加载时按稳定 id 中的远端模型名识别。
        assertThat(chatReasoningControl("claude::claude-opus-5-5", providers, emptyList(), null, null)!!.shown)
            .isEqualTo(ReasoningEffort.MEDIUM)
    }

    @Test
    fun `模型默认时胶囊显示文档默认档位，只能开关的模型在开与关之间切换`() {
        val opus = chatReasoningControl("claude::claude-opus-5-5", providers, emptyList(), null, null)!!
        assertThat(opus.isCustomized).isFalse()
        assertThat(opus.shown).isEqualTo(ReasoningEffort.MEDIUM)

        val haiku = chatReasoningControl("claude::claude-haiku-4-5", providers, emptyList(), null, null)!!
        assertThat(haiku.support.isToggleOnly).isTrue()
        val next = haiku.toggled()
        val toggled = haiku.copy(stored = next)
        assertThat(toggled.isThinking).isNotEqualTo(haiku.isThinking)
        assertThat(toggled.toggled()).isNull()
    }
}
