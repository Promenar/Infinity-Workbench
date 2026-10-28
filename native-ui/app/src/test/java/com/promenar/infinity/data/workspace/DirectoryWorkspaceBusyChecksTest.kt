package com.promenar.infinity.data.workspace

import org.junit.Assert.*
import org.junit.Test

class DirectoryWorkspaceBusyChecksTest {
    @Test fun `发送准备状态按会话生效且关闭注册后释放`() {
        val checks = DirectoryWorkspaceBusyChecks()
        var preparing = true
        val registration = checks.register { it == "s" && preparing }
        assertTrue(checks.isBusy("s"))
        assertFalse(checks.isBusy("other"))
        preparing = false
        assertFalse(checks.isBusy("s"))
        preparing = true
        registration.close()
        registration.close()
        assertFalse(checks.isBusy("s"))
    }
}
