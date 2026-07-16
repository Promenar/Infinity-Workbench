package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NexaraBottomSheetContractTest {

    @Test
    fun `未指定高度策略时保持既有七成高度`() {
        assertThat(NEXARA_BOTTOM_SHEET_DEFAULT_HEIGHT_FRACTION).isEqualTo(0.7f)
    }
}
