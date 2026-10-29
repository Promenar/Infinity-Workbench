package com.promenar.infinity.data.export

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 设备验收入口：只写入独立临时会话，不调用模型与网络。 */
@RunWith(AndroidJUnit4::class)
class SessionExportDeviceTest {
    @Test fun tallPageSlicesHaveDistinctPixels() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val directory = app.cacheDir.resolve("session-export-device-${UUID.randomUUID()}").apply { mkdir() }
        val html = """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;padding:0}.message{height:20000px;background:#ff0000}.last{height:10000px;background:#0000ff}</style></head>
            <body><div class="message"></div><div class="last"></div><script>
            const scale=1080/window.innerWidth;
            document.querySelector('.message').style.height=(20000/scale)+'px';
            document.querySelector('.last').style.height=(10000/scale)+'px';
            window.__sessionExportMetrics=function(){return {height:document.body.scrollHeight,width:window.innerWidth,boundaries:[0,document.querySelector('.last').offsetTop],blocks:[]};};
            window.__sessionExportReady=true;
            </script></body></html>
        """.trimIndent()
        try {
            val files = ExportWebViewRenderer(app).render(html, directory, "slices", SessionExportFormat.PNG)
            assertEquals(2, files.size)
            assertEquals(listOf("slices_01.png", "slices_02.png"), files.map { it.displayName })
            files.forEachIndexed { index, file ->
                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.file.path))
                try {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertEquals(if (index == 0) Color.RED else Color.BLUE, pixel)
                } finally { bitmap.recycle() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun richConversationExportsPdfAndPng() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val id = "export-test-${UUID.randomUUID()}"
        val session = Session(id, "export-test", title = "导出验收", modelId = "fixture")
        val body = """
            ## 内容

            ```kotlin
            val answer = 42
            ```

            | 项目 | 数量 |
            | --- | --- |
            | 代码 | 1 |

            ${'$'}${'$'}E=mc^2${'$'}${'$'}

            ```mermaid
            graph TD
            A[输入] --> B[输出]
            ```

            ```echarts
            {"xAxis":{"type":"category","data":["A","B"]},"yAxis":{},"series":[{"type":"bar","data":[1,2]}]}
            ```
        """.trimIndent()
        app.sessionRepository.create(session)
        try {
            app.messageRepository.insert(Message("$id-message", MessageRole.ASSISTANT, body, modelId = "fixture"), id)
            val options = SessionExportOptions()
            val pdf = app.sessionExporter.export(id, SessionExportFormat.PDF, options)
            try {
                assertEquals(1, pdf.files.size)
                assertTrue(pdf.files.first().file.length() > 0)
                ParcelFileDescriptor.open(pdf.files.first().file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { assertTrue(it.pageCount >= 1) }
                }
            } finally { app.sessionExporter.release(pdf) }
            val png = app.sessionExporter.export(id, SessionExportFormat.PNG, options)
            try {
                assertTrue(png.files.isNotEmpty())
                png.files.forEach { output ->
                    assertTrue(output.file.length() > 0)
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(output.file.path, info)
                    assertEquals(1080, info.outWidth)
                    assertTrue(info.outHeight in 1..20_000)
                }
                // 这个短会话完整布局低于上限；纯函数另覆盖超长、多消息和硬切情形。
                assertEquals(1, png.files.size)
                assertFalse(png.files.first().displayName.endsWith("_01.png"))
            } finally { app.sessionExporter.release(png) }
        } finally {
            app.messageRepository.deleteBySessionId(id)
            app.sessionRepository.delete(id)
        }
    }
}
