package com.promenar.nexara.data.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FullContextDocumentReaderTest {

    @Test
    fun `保守估算按UTF8字节计数`() {
        assertThat(DocumentTokenEstimator.estimate("中文")).isEqualTo(6)
        assertThat(DocumentTokenEstimator.estimate("hello")).isEqualTo(5)
    }

    @Test
    fun `读取文本BOM并保留SAF元数据 原始哈希和token估算`() = runTest {
        val utf8 = fixture(
            name = "utf8.txt",
            mimeType = "text/plain",
            bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "第一行".toByteArray(),
        )
        val utf16Le = fixture(
            name = "utf16-le.md",
            mimeType = "text/markdown",
            bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE),
        )
        val utf16Be = fixture(
            name = "utf16-be.markdown",
            mimeType = "text/markdown",
            bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "world".toByteArray(Charsets.UTF_16BE),
        )
        val fixtures = listOf(utf8, utf16Le, utf16Be)
        val resolver = resolver(fixtures)
        val reader = FullContextDocumentReader(resolver)

        fixtures.forEach { fixture ->
            val result = reader.read(fixture.uri)

            assertThat(result).isInstanceOf(DocumentReadResult.Success::class.java)
            val attachment = (result as DocumentReadResult.Success).attachment
            assertThat(attachment.name).isEqualTo(fixture.name)
            assertThat(attachment.sizeBytes).isEqualTo(fixture.bytes.size.toLong())
            assertThat(attachment.content).isEqualTo(fixture.expectedContent)
            assertThat(attachment.sha256).isEqualTo(sha256(fixture.bytes))
            assertThat(attachment.estimatedTokens).isEqualTo(
                DocumentTokenEstimator.estimate(fixture.expectedContent),
            )
        }
    }

    @Test
    fun `声明通用MIME时接受txt md和markdown扩展名并规范化MIME`() = runTest {
        val markdown = fixture(
            name = "architecture.MARKDOWN",
            mimeType = "application/octet-stream",
            bytes = "# Design".toByteArray(),
        )
        val result = FullContextDocumentReader(resolver(listOf(markdown))).read(markdown.uri)

        assertThat(result).isInstanceOf(DocumentReadResult.Success::class.java)
        assertThat((result as DocumentReadResult.Success).attachment.mimeType).isEqualTo("text/markdown")
    }

    @Test
    fun `不支持类型 空文档 二进制和无效UTF8会被明确拒绝`() = runTest {
        val unsupported = fixture("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val disguised = fixture("report.pdf", "text/plain", "plain text".toByteArray())
        val empty = fixture("empty.txt", "text/plain", byteArrayOf())
        val binary = fixture("binary.txt", "text/plain", byteArrayOf(0x41, 0x00, 0x42))
        val invalidUtf8 = fixture("broken.txt", "text/plain", byteArrayOf(0xC3.toByte(), 0x28))
        val inaccessibleType = fixture("inaccessible.txt", "text/plain", "text".toByteArray())
        val reader = FullContextDocumentReader(
            resolver(listOf(unsupported, disguised, empty, binary, invalidUtf8)),
        )
        val inaccessibleReader = FullContextDocumentReader(
            resolver(listOf(inaccessibleType)) { throw SecurityException("permission revoked") },
        )

        assertReject(reader.read(unsupported.uri), DocumentReadRejectReason.UnsupportedDocument)
        assertReject(reader.read(disguised.uri), DocumentReadRejectReason.UnsupportedDocument)
        assertReject(reader.read(empty.uri), DocumentReadRejectReason.EmptyDocument)
        assertReject(reader.read(binary.uri), DocumentReadRejectReason.BinaryContent)
        assertReject(reader.read(invalidUtf8.uri), DocumentReadRejectReason.InvalidTextEncoding)
        assertReject(inaccessibleReader.read(inaccessibleType.uri), DocumentReadRejectReason.ReadFailed)
    }

    @Test
    fun `声明和实际大小越限均在构建附件前拒绝`() = runTest {
        val declaredOversize = fixture(
            name = "declared.txt",
            mimeType = "text/plain",
            bytes = "small".toByteArray(),
            reportedSize = 17,
        )
        val streamedOversize = fixture(
            name = "streamed.txt",
            mimeType = "text/plain",
            bytes = "0123456789abcdefg".toByteArray(),
            reportedSize = null,
        )
        val reader = FullContextDocumentReader(
            resolver(listOf(declaredOversize, streamedOversize)),
            maxDocumentBytes = 16,
        )

        assertReject(reader.read(declaredOversize.uri), DocumentReadRejectReason.DocumentTooLarge)
        assertReject(reader.read(streamedOversize.uri), DocumentReadRejectReason.DocumentTooLarge)
    }

    private fun assertReject(result: DocumentReadResult, reason: DocumentReadRejectReason) {
        assertThat(result).isEqualTo(DocumentReadResult.Rejected(reason))
    }

    private fun resolver(
        fixtures: List<Fixture>,
        declaredMime: (Fixture) -> String? = Fixture::mimeType,
    ): ContentResolver {
        val byUri = fixtures.associateBy { it.uri }
        return mockk<ContentResolver>().also { resolver ->
            every { resolver.getType(any()) } answers {
                declaredMime(checkNotNull(byUri[firstArg()]))
            }
            every { resolver.query(any(), any(), null, null, null) } answers {
                val fixture = checkNotNull(byUri[firstArg()])
                android.database.MatrixCursor(
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                ).apply {
                    addRow(arrayOf<Any?>(fixture.name, fixture.reportedSize))
                }
            }
            every { resolver.openInputStream(any()) } answers {
                ByteArrayInputStream(checkNotNull(byUri[firstArg()]).bytes)
            }
        }
    }

    private fun fixture(
        name: String,
        mimeType: String,
        bytes: ByteArray,
        reportedSize: Long? = bytes.size.toLong(),
    ): Fixture = Fixture(
        uri = Uri.parse("content://fixture/$name"),
        name = name,
        mimeType = mimeType,
        bytes = bytes,
        reportedSize = reportedSize,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val bytes: ByteArray,
        val reportedSize: Long?,
    ) {
        val expectedContent: String
            get() = when {
                hasPrefix(bytes, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                    bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
                hasPrefix(bytes, byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                    bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
                hasPrefix(bytes, byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                    bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
                else -> bytes.toString(Charsets.UTF_8)
            }
    }

    private companion object {
        fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
    }
}
