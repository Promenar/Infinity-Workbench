package com.promenar.infinity.data.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class TermuxProtocolTest {
    @Test fun `解析官方结果键及字符串长度并分别标记截断`() {
        val result = TermuxResultParser.parse(mapOf(
            "stdout" to "尾部", "stderr" to "err", "exitCode" to 0, "err" to -1,
            "stdout_original_length" to "120000", "stderr_original_length" to "3",
        ))
        assertThat(result.stdout).isEqualTo("尾部")
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdoutOriginalLength).isEqualTo(120000L)
        assertThat(result.stderrOriginalLength).isEqualTo(3L)
        assertThat(result.stdoutTruncated).isTrue()
        assertThat(result.stderrTruncated).isFalse()
        assertThat(result.toJson()["truncated"]!!.jsonPrimitive.booleanOrNull).isTrue()
        assertThat(result.errorCode).isNull()
    }

    @Test fun `解析整数长度和非零命令退出码且保留输出`() {
        val result = TermuxResultParser.parse(mapOf(
            "stdout" to "a", "stderr" to "b", "exitCode" to 7, "err" to -1,
            "stdout_original_length" to 1, "stderr_original_length" to 90L,
        ))
        assertThat(result.errorCode).isEqualTo("TERMUX_COMMAND_FAILED")
        assertThat(result.stderrTruncated).isTrue()
        assertThat(result.stderrOriginalLength).isEqualTo(90L)
        assertThat(result.stdout).isEqualTo("a")
    }

    @Test fun `内部错误不当作零退出码且外部应用开关错误给出操作提示`() {
        val result = TermuxResultParser.parse(mapOf(
            "err" to 2, "errmsg" to "allow-external-apps is not enabled",
        ))
        assertThat(result.errorCode).isEqualTo("TERMUX_EXTERNAL_APPS_DISABLED")
        assertThat(result.guidance).contains("termux.properties")
        assertThat(result.err).isEqualTo(2)
        assertThat(result.exitCode).isNull()
        assertThat(result.errmsg).isEqualTo("allow-external-apps is not enabled")
        val generic = TermuxResultParser.parse(mapOf("err" to 8, "errmsg" to "Failed to start"))
        assertThat(generic.errorCode).isEqualTo("TERMUX_INTERNAL_ERROR")
    }

    @Test fun `缺失或错误类型结果包拒绝成功且长度缺省不伪造截断`() {
        assertThat(TermuxResultParser.parse(null).errorCode).isEqualTo("TERMUX_RESULT_MALFORMED")
        assertThat(TermuxResultParser.parse(emptyMap()).errorCode).isEqualTo("TERMUX_RESULT_MALFORMED")
        assertThat(TermuxResultParser.parse(mapOf("err" to -1, "exitCode" to "oops")).errorCode)
            .isEqualTo("TERMUX_RESULT_MALFORMED")
        val value = TermuxResultParser.parse(mapOf("err" to -1, "exitCode" to 0, "stdout" to "ok", "stdout_original_length" to "-9"))
        assertThat(value.stdoutOriginalLength).isEqualTo(2L)
        assertThat(value.stdoutTruncated).isFalse()
    }

    @Test fun `可用性按安装版本权限顺序失败关闭`() {
        assertThat(TermuxAvailability(false, null, false).errorCode).isEqualTo("TERMUX_NOT_INSTALLED")
        assertThat(TermuxAvailability(true, "0.108", true).errorCode).isEqualTo("TERMUX_VERSION_UNSUPPORTED")
        assertThat(TermuxAvailability(true, "unknown", true).errorCode).isEqualTo("TERMUX_VERSION_UNSUPPORTED")
        assertThat(TermuxAvailability(true, "0.109", false).errorCode).isEqualTo("TERMUX_PERMISSION_DENIED")
        listOf("0.109", "0.118.3", "v0.118.3+github", "1.0").forEach { version ->
            assertThat(TermuxAvailability(true, version, true).errorCode).isNull()
        }
    }
}
