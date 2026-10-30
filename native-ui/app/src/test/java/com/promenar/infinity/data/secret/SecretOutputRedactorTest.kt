package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import org.junit.Assert.*
import org.junit.Test

class SecretOutputRedactorTest {
    private val vault = SessionSecretVault()
    private val redactor = SecretOutputRedactor(vault)

    @Test fun `原文和五种编码均在本会话遮蔽`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test?token".toByteArray())
        val text = "test?token dGVzdD90b2tlbg== dGVzdD90b2tlbg %74%65%73%74%3f%74%6f%6b%65%6e 746573743f746f6b656e test%3Ftoken"
        assertEquals(List(6) { "‹保密:TOKEN›" }.joinToString(" "), redactor.redact("s", text))
        assertEquals(text, redactor.redact("other", text))
    }

    @Test fun `URL安全Base64和大写百分号编码遮蔽`() {
        vault.put("s", "BINARY", "文件", SecretSource.File("file"), byteArrayOf(-5, -1, -1, -6))
        assertEquals("‹保密:BINARY› ‹保密:BINARY› ‹保密:BINARY›", redactor.redact("s", "+///+g== -___-g== %FB%FF%FF%FA"))
    }

    @Test fun `百分号编码中十六进制大小写可混合`() {
        vault.put("s", "BINARY", "文件", SecretSource.File("file"), byteArrayOf(-5, -1, -1, -6))
        assertEquals("‹保密:BINARY›", redactor.redact("s", "%fB%FF%fF%Fa"))
    }

    @Test fun `RFC3986百分号编码保留波浪号并转义星号`() {
        vault.put("s", "STAR", "星号令牌", SecretSource.UserInput, "test*token?".toByteArray())
        vault.put("s", "TILDE", "波浪号令牌", SecretSource.UserInput, "test~?token".toByteArray())
        assertEquals("‹保密:STAR› ‹保密:TILDE›", redactor.redact("s", "test%2Atoken%3F test~%3Ftoken"))
    }

    @Test fun `RFC3986百分号编码跨任意分块并支持混合大小写`() {
        vault.put("s", "STAR", "星号令牌", SecretSource.UserInput, "test*token?".toByteArray())
        vault.put("s", "TILDE", "波浪号令牌", SecretSource.UserInput, "test~?token".toByteArray())
        val text = "before test%2atoken%3F test~%3ftoken after"
        for (split in 0..text.length) {
            val stream = redactor.stream("s")
            val first = stream.accept(text.take(split))
            val second = stream.accept(text.drop(split))
            assertEquals("before ‹保密:STAR› ‹保密:TILDE› after", first + second + stream.finish())
        }
    }

    @Test fun `每个分块位置都不能泄漏完整值`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "synthetic-secret".toByteArray())
        val raw = "before synthetic-secret after"
        for (split in 0..raw.length) {
            val stream = redactor.stream("s")
            val first = stream.accept(raw.take(split))
            val second = stream.accept(raw.drop(split))
            assertFalse(first.contains("synthetic-secret"))
            assertFalse(second.contains("synthetic-secret"))
            assertEquals("before ‹保密:TOKEN› after", first + second + stream.finish())
            assertEquals("", stream.finish())
        }
    }

    @Test fun `逐字输入编码也遮蔽且结束保留普通短尾`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test?token".toByteArray())
        val stream = redactor.stream("s")
        val output = "%74%65%73%74%3F%74%6F%6B%65%6E ok".map { stream.accept(it.toString()) }.joinToString("") + stream.finish()
        assertEquals("‹保密:TOKEN› ok", output)
    }

    @Test fun `短值不遮蔽任务输入按任务生命周期清除`() {
        vault.put("s", "SHORT", "短值", SecretSource.UserInput, "abc".toByteArray())
        val bytes = "job-token".toByteArray()
        redactor.registerJobInput("s", "job", bytes)
        bytes.fill(0)
        assertEquals("abc ‹保密›", redactor.redact("s", "abc job-token"))
        redactor.clearJob("s", "job")
        assertEquals("job-token", redactor.redact("s", "job-token"))
    }

    @Test fun `同任务多次输入直到任务清除前全部遮蔽`() {
        redactor.registerJobInput("s", "job", "first-token".toByteArray())
        redactor.registerJobInput("s", "job", "second-token".toByteArray())
        assertEquals("‹保密› ‹保密›", redactor.redact("s", "first-token second-token"))
    }

    @Test fun `流打开后新输入登记会更新匹配且销毁不泄漏未刷尾部`() {
        val stream = redactor.stream("s")
        redactor.registerJobInput("s", "job", "job-token".toByteArray())
        val start = stream.accept("job-")
        redactor.clearJob("s", "job")
        assertEquals("‹保密›", start + stream.accept("token") + stream.finish())
    }

    @Test fun `流建立后首次输出前销毁槽位也遮蔽执行中的旧值`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        val stream = redactor.stream("s")
        vault.destroy("s", "TOKEN")
        assertEquals("‹保密:TOKEN›", stream.accept("test-token") + stream.finish())
    }

    @Test fun `实时sink分离两个通道并在finish刷出尾部`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        val output = mutableMapOf<ToolOutputStream, String>()
        val delegate = object : ToolLiveOutputSink {
            override fun emit(stream: ToolOutputStream, text: String) { output[stream] = output[stream].orEmpty() + text }
            override fun onStop(stop: () -> Unit) = Unit
            override fun unsupported() = Unit
            override val stopRequested = false
        }
        val sink = redactor.sink("s", delegate)
        sink.emit(ToolOutputStream.STDOUT, "test-")
        sink.emit(ToolOutputStream.STDERR, "ordinary")
        sink.emit(ToolOutputStream.STDOUT, "token")
        redactor.finish(sink)
        assertEquals("‹保密:TOKEN›", output[ToolOutputStream.STDOUT])
        assertEquals("ordinary", output[ToolOutputStream.STDERR])
    }

    @Test fun `PTY快照之后的增量不能在界面拼出原文`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        var output = ""
        val delegate = object : ToolLiveOutputSink {
            override fun emit(stream: ToolOutputStream, text: String) { output += text }
            override fun replace(stream: ToolOutputStream, text: String) { output = text }
            override fun onStop(stop: () -> Unit) = Unit
            override fun unsupported() = Unit
            override val stopRequested = false
        }
        val sink = redactor.sink("s", delegate)
        sink.replace(ToolOutputStream.STDOUT, "test-")
        sink.emit(ToolOutputStream.STDOUT, "token")
        redactor.finish(sink)
        assertEquals("‹保密:TOKEN›", output)
    }
}
