package com.promenar.nexara.debug.linuxprobe

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProbeFinalizerTest {
    @Test fun `清理命令被取消后仍执行宿主兜底并发布终态`() {
        val calls = mutableListOf<String>()
        assertThrows(InterruptedException::class.java) {
            finalizeProbe(
                guestCleanup = { calls += "guest"; throw InterruptedException() },
                hostCleanup = { calls += "host" },
                publishFinal = { calls += "publish" },
            )
        }
        assertEquals(listOf("guest", "host", "publish"), calls)
    }

    @Test fun `宿主兜底失败仍发布终态`() {
        var published = false
        assertThrows(java.io.IOException::class.java) {
            finalizeProbe({}, { throw java.io.IOException() }, { published = true })
        }
        assertTrue(published)
    }
}
