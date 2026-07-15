package com.promenar.nexara.ui.rag.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IndexingProgressBarStateTest {

    @Test
    fun `进度语义限制在合法范围`() {
        assertThat(resolveIndexingProgressAccessibility(progress = -1f, isError = false).progress).isEqualTo(0f)
        assertThat(resolveIndexingProgressAccessibility(progress = 2f, isError = false).progress).isEqualTo(1f)
    }

    @Test
    fun `错误使用紧急播报而普通状态使用礼貌播报`() {
        assertThat(resolveIndexingProgressAccessibility(progress = 0.5f, isError = true).assertive).isTrue()
        assertThat(resolveIndexingProgressAccessibility(progress = 0.5f, isError = false).assertive).isFalse()
    }
}
