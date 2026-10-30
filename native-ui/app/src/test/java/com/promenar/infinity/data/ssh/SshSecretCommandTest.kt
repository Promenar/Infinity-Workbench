package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.security.SshCredential
import org.junit.Test

class SshSecretCommandTest {
    @Test fun `捕获stdout的私钥字节保留而stderr继续过滤`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic-private-material\n-----END OPENSSH PRIVATE KEY-----\n"
        SshOutputFilter(SshCredential()).use { filter ->
            val (stdout, stderr) = sshCaptureResult(pem, pem, filter::whole)
            assertThat(stdout).isEqualTo(pem)
            assertThat(stderr).doesNotContain("synthetic-private-material")
        }
    }

    @Test fun `只在发送时增加正确单引号转义的export`() {
        val original = "printf '%s' \"\$TOKEN\""
        val wire = secretSshCommand(original, mapOf("TOKEN" to "fixture'quote"))
        assertThat(wire).isEqualTo("export TOKEN='fixture'\\''quote'; $original")
        assertThat(original).isEqualTo("printf '%s' \"\$TOKEN\"")
    }
}
