package com.promenar.nexara.data.local.db.recovery

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Base64
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LegacyAttachmentCodecTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testPreviewUriFactory: (Context, File) -> Uri = { _, file -> Uri.fromFile(file) }

    @Test
    fun `解析公开旧版完整字段和缺省字段并保留通配mime`() {
        val items = LegacyAttachmentCodec.parse(
            """[
                {"uri":"data:image/*;base64,QQ==","mimeType":"image/*","fileName":"旧图","sizeBytes":1,"type":"IMAGE"},
                {"uri":"data:application/octet-stream;base64,Qg==","mimeType":"application/octet-stream"}
            ]""".trimIndent(),
        )

        assertThat(items).hasSize(2)
        assertThat(items[0]).isEqualTo(
            LegacyAttachmentItem("data:image/*;base64,QQ==", "image/*", "旧图", 1, "IMAGE"),
        )
        assertThat(items[1].type).isEqualTo("IMAGE")
        assertThat(items[1].fileName).isEqualTo("历史附件 2")
    }

    @Test
    fun `未知类型和损坏payload保持可见但不可打开`() {
        val unknown = LegacyAttachmentCodec.parse(
            """[{"uri":"data:application/octet-stream;base64,QQ==","mimeType":"application/octet-stream","type":"ARCHIVE"}]""",
        ).single()
        val damaged = LegacyAttachmentCodec.parse("{not-json").single()

        assertThat(unknown.unreadableReason).contains("未知")
        assertThat(damaged.unreadableReason).contains("损坏")
        assertThat(LegacyAttachmentCodec.open(context, unknown))
            .isInstanceOf(LegacyAttachmentOpenResult.Unreadable::class.java)
    }

    @Test
    fun `超过条目预算时保留单一明确不可读入口而非静默截断`() {
        val payload = (1..33).joinToString(prefix = "[", postfix = "]") {
            "{\"uri\":\"data:image/png;base64,QQ==\",\"mimeType\":\"image/png\"}"
        }

        val result = LegacyAttachmentCodec.parse(payload)

        assertThat(result).hasSize(1)
        assertThat(result.single().unreadableReason).contains("数量")
    }

    @Test
    fun `用户点开后才把有界data附件发布到内容认证缓存`() {
        val bytes = "旧版附件".toByteArray()
        val item = LegacyAttachmentItem(
            uri = "data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(bytes)}",
            mimeType = "application/octet-stream",
            fileName = "legacy.bin",
            sizeBytes = bytes.size.toLong(),
            type = "DOCUMENT",
        )

        previewDirectory().deleteRecursively()
        val result = LegacyAttachmentCodec.open(context, item, testPreviewUriFactory)
        if (result is LegacyAttachmentOpenResult.Unreadable) throw AssertionError(result.reason)
        val ready = result as LegacyAttachmentOpenResult.Ready

        assertThat(ready.intent.data?.scheme).isEqualTo("file")
        val restored = File(requireNotNull(ready.intent.data?.path)).readBytes()
        assertThat(restored).isEqualTo(bytes)
    }

    @Test
    fun `私有文件只允许files目录并拒绝数据库和偏好目录`() {
        val allowed = context.filesDir.resolve("legacy-readable.txt").apply { writeText("ok") }
        val denied = context.dataDir.resolve("shared_prefs/secret.xml").apply {
            parentFile?.mkdirs()
            writeText("secret")
        }

        previewDirectory().deleteRecursively()
        val allowedResult = LegacyAttachmentCodec.open(context, fileItem(allowed.absolutePath), testPreviewUriFactory)
        val deniedResult = LegacyAttachmentCodec.open(context, fileItem(denied.absolutePath))

        if (allowedResult is LegacyAttachmentOpenResult.Unreadable) throw AssertionError(allowedResult.reason)
        assertThat(allowedResult).isInstanceOf(LegacyAttachmentOpenResult.Ready::class.java)
        assertThat(deniedResult).isInstanceOf(LegacyAttachmentOpenResult.Unreadable::class.java)
    }

    @Test
    fun `重复点开同一附件复用内容认证缓存且总量不增长`() {
        previewDirectory().deleteRecursively()
        val bytes = "same-preview".toByteArray()
        val item = dataItem(bytes, "same.bin")

        val first = LegacyAttachmentCodec.open(context, item, testPreviewUriFactory) as LegacyAttachmentOpenResult.Ready
        val firstInventory = previewDirectory().listFiles().orEmpty().associate { it.name to it.length() }
        val second = LegacyAttachmentCodec.open(context, item, testPreviewUriFactory) as LegacyAttachmentOpenResult.Ready
        val secondInventory = previewDirectory().listFiles().orEmpty().associate { it.name to it.length() }

        assertThat(second.intent.data).isEqualTo(first.intent.data)
        assertThat(secondInventory).isEqualTo(firstInventory)
        assertThat(secondInventory.values.sum()).isEqualTo(bytes.size.toLong())
    }

    @Test
    fun `同一file地址内容变化时生成新的内容身份而不返回旧缓存`() {
        previewDirectory().deleteRecursively()
        val source = context.filesDir.resolve("mutable-legacy.txt").apply { writeText("first") }
        val item = fileItem(source.absolutePath)

        val first = LegacyAttachmentCodec.open(context, item, testPreviewUriFactory) as LegacyAttachmentOpenResult.Ready
        source.writeText("second")
        val second = LegacyAttachmentCodec.open(context, item, testPreviewUriFactory) as LegacyAttachmentOpenResult.Ready

        assertThat(second.intent.data).isNotEqualTo(first.intent.data)
        assertThat(File(requireNotNull(first.intent.data?.path)).readText()).isEqualTo("first")
        assertThat(File(requireNotNull(second.intent.data?.path)).readText()).isEqualTo("second")
        assertThat(previewDirectory().listFiles().orEmpty()).hasLength(2)
    }

    @Test
    fun `缓存按文件数和总字节预算收敛且不随打开次数无限增长`() {
        val directory = previewDirectory().apply { deleteRecursively(); check(mkdir()) }
        repeat(17) { index ->
            val file = directory.resolve(index.toString(16).padStart(64, '0') + ".bin")
            RandomAccessFile(file, "rw").use { it.setLength(4L * 1024L * 1024L) }
            file.setLastModified(index.toLong() + 1)
        }
        val tiny = dataItem("bounded".toByteArray(), "bounded.bin")

        val result = LegacyAttachmentCodec.open(context, tiny, testPreviewUriFactory)

        assertThat(result).isInstanceOf(LegacyAttachmentOpenResult.Ready::class.java)
        val files = directory.listFiles().orEmpty()
        assertThat(files.size).isAtMost(16)
        assertThat(files.sumOf(File::length)).isAtMost(64L * 1024L * 1024L)
    }

    @Test
    fun `缓存总字节预算独立于文件数量生效`() {
        val directory = previewDirectory().apply { deleteRecursively(); check(mkdir()) }
        repeat(5) { index ->
            val file = directory.resolve((index + 32).toString(16).padStart(64, '0') + ".bin")
            RandomAccessFile(file, "rw").use { it.setLength(16L * 1024L * 1024L) }
            file.setLastModified(index.toLong() + 1)
        }

        val result = LegacyAttachmentCodec.open(
            context,
            dataItem("byte-budget".toByteArray(), "bounded.bin"),
            testPreviewUriFactory,
        )

        assertThat(result).isInstanceOf(LegacyAttachmentOpenResult.Ready::class.java)
        val files = directory.listFiles().orEmpty()
        assertThat(files.size).isLessThan(5)
        assertThat(files.sumOf(File::length)).isAtMost(64L * 1024L * 1024L)
    }

    @Test
    fun `批量读取零进展时单字节回退且不会死循环`() {
        val source = object : InputStream() {
            private val bytes = "abc".toByteArray()
            private var offset = 0
            private var firstBulk = true
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (firstBulk) {
                    firstBulk = false
                    return 0
                }
                if (offset == bytes.size) return -1
                val count = minOf(len, bytes.size - offset)
                bytes.copyInto(buffer, off, offset, offset + count)
                offset += count
                return count
            }
            override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff
        }
        val output = ByteArrayOutputStream()

        assertThat(LegacyAttachmentCodec.copyBounded(source, output)).isEqualTo(3)
        assertThat(output.toByteArray()).isEqualTo("abc".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `读取超过单附件预算会失败`() {
        val source = object : InputStream() {
            private var remaining = LegacyAttachmentCodec.MAX_ATTACHMENT_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(len.toLong(), remaining).toInt()
                remaining -= count
                return count
            }
        }
        LegacyAttachmentCodec.copyBounded(source, object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, off: Int, len: Int) = Unit
        })
    }

    private fun fileItem(path: String) = LegacyAttachmentItem(
        uri = Uri.fromFile(java.io.File(path)).toString(),
        mimeType = "text/plain",
        fileName = "legacy.txt",
        sizeBytes = 2,
        type = "DOCUMENT",
    )

    private fun dataItem(bytes: ByteArray, fileName: String) = LegacyAttachmentItem(
        uri = "data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(bytes)}",
        mimeType = "application/octet-stream",
        fileName = fileName,
        sizeBytes = bytes.size.toLong(),
        type = "DOCUMENT",
    )

    private fun previewDirectory() = context.cacheDir.resolve("legacy-attachment-preview")
}
