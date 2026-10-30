package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.sftpHash
import com.promenar.infinity.domain.linux.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxStreamingTransferTest {
    private val base = Files.createTempDirectory("sftp-linux-stream").toFile().canonicalFile
    private val root = File(base, "root").apply { mkdir() }
    private val fs = LinuxFileSystemImpl(root, { true }, TestLinuxFileMetadata).forAgentTools()
    @After fun cleanup() { deleteLinuxFixture(base) }
    private fun remaining() = root.listFiles()!!.map { it.name }
    @Test fun `流式新建空文件与二进制覆盖均校验真实目标内容`() = runBlocking<Unit> {
        for (size in listOf(0, 196_625)) {
            val bytes = ByteArray(size) { (it * 17).toByte() }
            val checksum = sftpHash(bytes.inputStream(), size.toLong())
            val before = fs.stat("/data")
            val result = fs.writeStreaming("/data", size.toLong(), before) { it.write(bytes); checksum }
            assertThat(result.size).isEqualTo(size.toLong())
            assertThat(File(root, "data").readBytes()).isEqualTo(bytes)
            assertThat(remaining()).containsExactly("data")
        }
    }
    @Test fun `摘要不一致不替换目标且删除临时文件`() = runBlocking<Unit> {
        File(root, "data").writeText("原文")
        val expected = fs.stat("/data")
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking {
            fs.writeStreaming("/data", 3, expected) { it.write(byteArrayOf(1, 2, 3)); "0".repeat(64) }
        } }
        assertThat((failure as LinuxStreamingException).failureCode).isEqualTo("CHECKSUM_MISMATCH")
        assertThat(File(root, "data").readText()).isEqualTo("原文")
        assertThat(remaining()).containsExactly("data")
    }
    @Test fun `取消及IO异常清理半成品并保留原目标`() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) {
            File(root, "data").writeText("原文")
            val before = fs.stat("/data")
            assertThrows(Exception::class.java) { runBlocking { fs.writeStreaming("/data", 3, before) {
                it.write(1)
                if (cancel) throw CancellationException("停止") else throw java.io.IOException("断流")
            } } }
            assertThat(File(root, "data").readText()).isEqualTo("原文")
            assertThat(remaining()).containsExactly("data")
        }
    }
    @Test fun `目标在写入期间被其它作者改动则保留其它作者内容`() = runBlocking<Unit> {
        File(root, "data").writeText("原文")
        val before = fs.stat("/data")
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking { fs.writeStreaming("/data", 3, before) {
            it.write(byteArrayOf(1, 2, 3)); File(root, "data").writeText("其它作者")
            sftpHash(byteArrayOf(1, 2, 3).inputStream(), 3)
        } } }
        assertThat(failure.code).isEqualTo(LinuxFileErrorCode.CHANGED)
        assertThat(File(root, "data").readText()).isEqualTo("其它作者")
        assertThat(remaining()).containsExactly("data")
    }
    @Test fun `目录符号链接与越界路径均不写入`() = runBlocking<Unit> {
        File(root, "dir").mkdir()
        Files.createSymbolicLink(File(root, "link").toPath(), File(base, "outside").toPath())
        for (path in listOf("/dir", "/link", "/../outside")) {
            assertThrows(LinuxFileException::class.java) { runBlocking { fs.writeStreaming(path, 1, null) { error("不得传输") } } }
        }
        assertThat(File(base, "outside").exists()).isFalse()
    }
    @Test fun `空间不足在writer调用前拒绝`() = runBlocking<Unit> {
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking {
            fs.writeStreaming("/data", Long.MAX_VALUE, null) { error("不得写入") }
        } }
        assertThat((failure as LinuxStreamingException).failureCode).isEqualTo("NO_SPACE")
        assertThat(remaining()).isEmpty()
    }

    @Test fun `校验阶段停止或超时不发布且清理临时文件`() = runBlocking<Unit> {
        for (timeout in listOf(false, true)) {
            File(root, "data").writeText("原文")
            val before = fs.stat("/data")
            var verifying = false
            assertThrows(Exception::class.java) { runBlocking {
                fs.writeStreaming("/data", 200_000, before, check = {
                    if (verifying) {
                        if (timeout) throw com.promenar.infinity.data.ssh.SshFailure("TIMEOUT")
                        else throw CancellationException("停止")
                    }
                }) {
                    val bytes = ByteArray(200_000)
                    it.write(bytes)
                    verifying = true
                    sftpHash(bytes.inputStream(), bytes.size.toLong())
                }
            } }
            assertThat(File(root, "data").readText()).isEqualTo("原文")
            assertThat(remaining()).containsExactly("data")
        }
    }
    @Test fun `默认不覆盖时保留传输期间新出现的目标`() = runBlocking<Unit> {
        val bytes = byteArrayOf(1, 2, 3)
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking {
            fs.writeStreaming("/data", bytes.size.toLong(), null) {
                it.write(bytes)
                File(root, "data").writeText("并发新文件")
                sftpHash(bytes.inputStream(), bytes.size.toLong())
            }
        } }
        assertThat(failure.code).isEqualTo(LinuxFileErrorCode.CHANGED)
        assertThat(File(root, "data").readText()).isEqualTo("并发新文件")
        assertThat(remaining()).containsExactly("data")
    }
    @Test fun `提交前身份检查失败时原文件保持不变`() = runBlocking<Unit> {
        val bytes = byteArrayOf(1, 2, 3)
        File(root, "data").writeText("原文")
        val before = fs.stat("/data")
        assertThrows(LinuxFileException::class.java) { runBlocking {
            fs.writeStreaming("/data", bytes.size.toLong(), before, beforeCommit = { throw com.promenar.infinity.data.ssh.SshFailure("LOCAL_AUTHORIZATION_CHANGED") }) {
                it.write(bytes); sftpHash(bytes.inputStream(), bytes.size.toLong())
            }
        } }
        assertThat(File(root, "data").readText()).isEqualTo("原文")
        assertThat(remaining()).containsExactly("data")
    }
    @Test fun `发布身份检查期间收到停止时禁止替换目标`() = runBlocking<Unit> {
        File(root, "data").writeText("原文")
        var stopped = false
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking {
            fs.writeStreaming("/data", 1, fs.stat("/data"),
                check = { if (stopped) throw com.promenar.infinity.data.ssh.SshFailure("STOPPED") },
                beforeCommit = { stopped = true }) { it.write(7); sftpHash(byteArrayOf(7).inputStream(), 1) }
        } }
        assertThat(com.promenar.infinity.ui.chat.manager.skills.transferErrorCode(failure)).isEqualTo("STOPPED")
        assertThat(File(root, "data").readText()).isEqualTo("原文")
        assertThat(remaining()).containsExactly("data")
    }

}
