package com.promenar.nexara.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 审计缺陷 A：整数除法把数百 token 截断成 0K，显示改为自适应单位。 */
class ChatViewModelFormatTokenCountTest {

    @Test
    fun `below 1k shows raw token count`() {
        assertEquals("0", ChatViewModel.formatTokenCount(0))
        assertEquals("756", ChatViewModel.formatTokenCount(756))
        assertEquals("999", ChatViewModel.formatTokenCount(999))
    }

    @Test
    fun `1k range drops trailing zero but keeps one decimal`() {
        assertEquals("1K", ChatViewModel.formatTokenCount(1000))
        assertEquals("1.2K", ChatViewModel.formatTokenCount(1207))
        assertEquals("15K", ChatViewModel.formatTokenCount(15000))
        assertEquals("75.4K", ChatViewModel.formatTokenCount(75400))
    }

    @Test
    fun `100k range drops decimal`() {
        assertEquals("100K", ChatViewModel.formatTokenCount(100000))
        assertEquals("999K", ChatViewModel.formatTokenCount(999499))
    }

    @Test
    fun `near 1m rolls into millions instead of 1000k`() {
        assertEquals("1M", ChatViewModel.formatTokenCount(999999))
        assertEquals("1M", ChatViewModel.formatTokenCount(999500))
    }

    @Test
    fun `1m and above shows millions`() {
        assertEquals("1M", ChatViewModel.formatTokenCount(1000000))
        assertEquals("1.1M", ChatViewModel.formatTokenCount(1100000))
    }

    @Test
    fun `negative guarded to zero`() {
        assertEquals("0", ChatViewModel.formatTokenCount(-5))
    }
}
