package com.promenar.nexara.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import java.io.File
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxFileTransactionsTest {
    private val root = Files.createTempDirectory("linux-transactions").toFile().canonicalFile
    private val home = File(root, "root").apply { mkdir() }
    @After fun cleanup() { deleteLinuxFixture(root) }

    private fun fixture(): File {
        val source = File(home, "src").apply { mkdir() }
        val data = File(source, ".l2s.data0001.0002").apply { writeText("shared") }
        val middle = File(source, ".l2s.data0001")
        Files.createSymbolicLink(middle.toPath(), data.toPath())
        Files.createSymbolicLink(File(source, "data").toPath(), middle.toPath())
        Files.createSymbolicLink(File(home, "external").toPath(), middle.toPath())
        return source
    }

    @Test fun `隔离前并发写入保留新内容且返回冲突`() = runBlocking<Unit> {
        val file = File(home, "file").apply { writeText("before") }
        var injected = false
        val access = object : LinuxFileIo() {
            override fun move(source: Path, target: Path, vararg options: CopyOption) {
                if (source == file.toPath() && target.fileName.toString().startsWith(".nexara-linux-old-") && !injected) {
                    injected = true
                    file.writeText("concurrent content")
                }
                super.move(source, target, *options)
            }
        }
        val fs = LinuxFileSystemImpl(root, { true }, object : LinuxFileMetadata by TestLinuxFileMetadata { override val access = access })
        val before = fs.stat("/root/file")!!
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking { fs.writeText("/root/file", "candidate", before.modifiedMs) } }
        assertThat(failure.code).isEqualTo(LinuxFileErrorCode.CHANGED)
        assertThat(file.readText()).isEqualTo("concurrent content")
        assertThat(home.list()!!.toList()).containsExactly("file")
    }

    @Test fun `修复别名前出现真实文件不会被覆盖`() {
        val source = fixture()
        val external = File(home, "external")
        var injected = false
        val access = object : LinuxFileIo() {
            override fun move(from: Path, to: Path, vararg options: CopyOption) {
                if (from == external.toPath() && to.fileName.toString().startsWith(".nexara-linux-link-old-") && !injected) {
                    injected = true
                    Files.delete(from)
                    external.writeText("new user file")
                }
                super.move(from, to, *options)
            }
        }
        val failure = assertThrows(LinuxFileException::class.java) { LinuxFileLinks(LinuxFilePaths(root, access)).preserveBacking(source.toPath()) }
        assertThat(failure.code).isEqualTo(LinuxFileErrorCode.CHANGED)
        assertThat(external.readText()).isEqualTo("new user file")
        assertThat(File(source, "data").readText()).isEqualTo("shared")
    }

    @Test fun `移动已提交后抛错仍按实际归属恢复backing`() {
        val source = fixture()
        var injected = false
        val access = object : LinuxFileIo() {
            override fun move(from: Path, to: Path, vararg options: CopyOption) {
                super.move(from, to, *options)
                if (from.fileName.toString().startsWith(".l2s.") && to.startsWith(File(root, ".l2s").toPath()) && !injected) {
                    injected = true
                    throw IOException("模拟提交后同步失败")
                }
            }
        }
        assertThrows(IOException::class.java) { LinuxFileLinks(LinuxFilePaths(root, access)).preserveBacking(source.toPath()) }
        assertThat(File(source, "data").readText()).isEqualTo("shared")
        assertThat(File(home, "external").readText()).isEqualTo("shared")
    }
}
