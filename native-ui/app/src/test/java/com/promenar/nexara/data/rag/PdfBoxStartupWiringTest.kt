package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PdfBoxStartupWiringTest {
    private val source: String by lazy {
        Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/NexaraApplication.kt"))
            .toString(Charsets.UTF_8)
    }

    @Test
    fun `主业务进程在relay提前退出之后且恢复启动之前初始化PDFBox资源`() {
        val onCreate = functionBody("override fun onCreate()")
        val relayGuard = onCreate.indexOf("RestoreRelayActivity.PROCESS_SUFFIX")
        val relayExit = onCreate.indexOf("return", startIndex = relayGuard)
        val pdfBoxInit = onCreate.indexOf(
            "PDFBoxResourceLoader.init(applicationContext)",
            startIndex = relayExit,
        )
        val recovery = onCreate.indexOf("startBackupRecovery()")

        assertThat(relayGuard).isAtLeast(0)
        assertThat(relayExit).isGreaterThan(relayGuard)
        assertThat(pdfBoxInit).isGreaterThan(relayExit)
        assertThat(pdfBoxInit).isLessThan(recovery)
        assertThat(onCreate.split("PDFBoxResourceLoader.init(")).hasSize(2)
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
