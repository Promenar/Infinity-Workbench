package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.SshFailure
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpWorkspacePathTest {
    @Test fun `模型路径归一且拒绝越界与内部目录`() {
        assertThat(normalizeSftpWorkspacePath("docs\\a.bin")).isEqualTo("/docs/a.bin")
        for (path in listOf("/../a", "/docs/../a", "/./a", "/.infinity/a", "C:/a", "\\\\host\\a", "/", "a\u0000b")) {
            assertThat(assertThrows(SshFailure::class.java) { normalizeSftpWorkspacePath(path) }.code).isEqualTo("INVALID_LOCAL_PATH")
        }
    }
}
