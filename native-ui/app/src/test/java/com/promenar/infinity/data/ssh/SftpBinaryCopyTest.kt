package com.promenar.infinity.data.ssh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpBinaryCopyTest {
    @Test fun `空文件及跨多个分块的二进制字节和摘要一致`() {
        for (size in listOf(0, SFTP_TRANSFER_BUFFER_BYTES * 3 + 17)) {
            val source = ByteArray(size) { (it * 31).toByte() }
            val output = ByteArrayOutputStream()
            var last = 0L
            val hash = copySftpBytes(ByteArrayInputStream(source), output, size.toLong(), {}, { last = it })
            assertThat(output.toByteArray()).isEqualTo(source)
            assertThat(hash).isEqualTo(MessageDigest.getInstance("SHA-256").digest(source).toHex())
            assertThat(last).isEqualTo(size.toLong())
        }
    }
    @Test fun `超过4GiB在读取首个字节前拒绝`() {
        val input = object : java.io.InputStream() { override fun read(): Int = error("不得读取") }
        val failure = assertThrows(SshFailure::class.java) {
            copySftpBytes(input, ByteArrayOutputStream(), MAX_SFTP_TRANSFER_BYTES + 1, {}, {})
        }
        assertThat(failure.code).isEqualTo("TRANSFER_TOO_LARGE")
        requireSftpSize(MAX_SFTP_TRANSFER_BYTES)
    }
    @Test fun `源文件变长和缩短均拒绝发布`() {
        for ((actual, expected) in listOf(4 to 3L, 2 to 3L)) {
            val failure = assertThrows(SshFailure::class.java) {
                copySftpBytes(ByteArrayInputStream(ByteArray(actual)), ByteArrayOutputStream(), expected, {}, {})
            }
            assertThat(failure.code).isEqualTo("SOURCE_CHANGED")
        }
    }
    @Test fun `每个分块检查取消且停止读取后续数据`() {
        var checks = 0
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            copySftpBytes(ByteArrayInputStream(ByteArray(SFTP_TRANSFER_BUFFER_BYTES * 4)), output,
                SFTP_TRANSFER_BUFFER_BYTES * 4L, { if (++checks > 1) throw CancellationException("停止") }, {})
        }
        assertThat(output.size()).isEqualTo(SFTP_TRANSFER_BUFFER_BYTES)
    }
    @Test fun `单次读取请求有固定内存边界`() {
        val input = object : ByteArrayInputStream(ByteArray(SFTP_TRANSFER_BUFFER_BYTES * 4)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                assertThat(length).isAtMost(SFTP_TRANSFER_BUFFER_BYTES)
                return super.read(bytes, offset, length)
            }
        }
        sftpHash(input, SFTP_TRANSFER_BUFFER_BYTES * 4L)
    }
    @Test fun `远端空间不足与本地明确错误保持传输分类`() {
        assertThat((classifySftpFailure(com.jcraft.jsch.SftpException(14, "合成空间不足")) as SshFailure).code).isEqualTo("NO_SPACE")
        val local = com.promenar.infinity.domain.linux.LinuxStreamingException("CHECKSUM_MISMATCH")
        assertThat(classifySftpFailure(local)).isSameInstanceAs(local)
    }

    @Test fun `提交后停止保留成功但索引失败保持可见`() {
        val result = SftpTransferResult(1, "abc", 1.0)
        assertThat(committedSftpResult(result, kotlinx.coroutines.CancellationException())).isSameInstanceAs(result)
        assertThat(committedSftpResult(result, SshFailure("STOPPED"))).isSameInstanceAs(result)
        assertThat(committedSftpResult(result, SshFailure("WORKSPACE_INDEX_FAILED"))).isNull()
        assertThat(committedSftpResult(result, java.io.IOException("合成进度持久化失败"))).isNull()
        assertThat(committedSftpResult(null, SshFailure("STOPPED"))).isNull()
    }

    @Test fun `小文件和未知速度使用真实字节且按1024换单位`() {
        assertThat(sftpProgressText(14)).isEqualTo("\r已传 0 B / 14 B")
        assertThat(sftpProgressText(14, 14, 7)).isEqualTo("\r已传 14 B / 14 B · 7 B/s")
        assertThat(sftpProgressText(2048, 1024, 0)).isEqualTo("\r已传 1.0 KiB / 2.0 KiB")
        assertThat(sftpProgressText(1073741824, 1048576, 1048576)).isEqualTo("\r已传 1.0 MiB / 1.0 GiB · 1.0 MiB/s")
        assertThat(sftpProgressText(null)).isEqualTo("\r已传 0 B / 未知")
    }

}
