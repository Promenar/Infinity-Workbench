package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.TailnetWaitState
import com.promenar.infinity.data.tailnet.TailnetConnectProgress
import com.promenar.infinity.data.tailnet.TailnetConnectStep
import org.junit.Test

class TailnetConnectOverlayStateTest {
    private val waiting = TailnetWaitState.Waiting(TailnetConnectProgress("gpu.tail1.ts.net", TailnetConnectStep.JOINING, 30_000))

    @Test
    fun `等待中跟随上游进度，连上后保留确认行直到计时结束`() {
        assertThat(nextTailnetOverlayContent(null, waiting)).isEqualTo(waiting)
        assertThat(nextTailnetOverlayContent(waiting, TailnetWaitState.Connected)).isEqualTo(TailnetWaitState.Connected)
        // 第一个生成快照会清掉上游的 Connected，确认行由界面计时收起。
        assertThat(nextTailnetOverlayContent(TailnetWaitState.Connected, null)).isEqualTo(TailnetWaitState.Connected)
    }

    @Test
    fun `失败或取消时立即关闭，从未等待过的连接确认不显示`() {
        assertThat(nextTailnetOverlayContent(waiting, null)).isNull()
        assertThat(nextTailnetOverlayContent(null, TailnetWaitState.Connected)).isNull()
        assertThat(nextTailnetOverlayContent(null, null)).isNull()
    }
}
