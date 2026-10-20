package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NexaraBottomSheetContractTest {

    @Test
    fun `未指定高度策略时按内容高度显示`() {
        // 内容面板须显式声明 Full；短选择列表默认按内容高度，避免出现大片空白。
        assertThat(NEXARA_BOTTOM_SHEET_DEFAULT_HEIGHT).isEqualTo(NexaraSheetHeight.Content)
    }
}
