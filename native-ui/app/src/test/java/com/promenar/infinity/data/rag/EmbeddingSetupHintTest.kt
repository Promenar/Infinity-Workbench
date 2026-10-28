package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.skills.InMemoryPreferences
import org.junit.Test

/** 未选择嵌入模型的配置引导只出现一次，重启后不再出现。 */
class EmbeddingSetupHintTest {
    @Test
    fun `首次检测到未配置时进入待显示`() {
        val hint = EmbeddingSetupHint(InMemoryPreferences())

        assertThat(hint.pending.value).isFalse()
        hint.onEmbeddingNotConfigured()

        assertThat(hint.pending.value).isTrue()
    }

    @Test
    fun `展示后不再进入待显示且跨实例保持`() {
        val prefs = InMemoryPreferences()
        val hint = EmbeddingSetupHint(prefs)
        hint.onEmbeddingNotConfigured()

        hint.markShown()
        hint.onEmbeddingNotConfigured()

        assertThat(hint.pending.value).isFalse()
        val restarted = EmbeddingSetupHint(prefs)
        restarted.onEmbeddingNotConfigured()
        assertThat(restarted.pending.value).isFalse()
    }
}
