package com.promenar.nexara.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxFileArchiveTest {
    private val root = Files.createTempDirectory("linux-archive").toFile().canonicalFile
    private val home = File(root, "root").apply { mkdir() }
    private val fs = LinuxFileSystemImpl(root, { true }, TestLinuxFileMetadata)
    @After fun cleanup() { deleteLinuxFixture(root) }

    @Test fun `tar归档可读取权限目录符号链接和长UTF8名称`() = runBlocking<Unit> {
        val folder = File(home, "src").apply { mkdir() }
        File(folder, "file").writeText("payload")
        fs.chmod("/root/src/file", 0x1e8)
        Files.createSymbolicLink(File(folder, "link").toPath(), File("/root/src/file").toPath())
        val longName = "长".repeat(50)
        File(folder, longName).writeText("long")
        val archive = fs.compress(listOf("/root/src"), "bundle.tar.gz")
        val entries = readTar(File(home, archive.name))
        assertThat(entries["src/file"]!!.bytes.toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(entries["src/file"]!!.mode).isEqualTo(0x1e8)
        assertThat(entries["src/"]!!.type).isEqualTo('5')
        assertThat(entries["src/link"]!!.type).isEqualTo('2')
        assertThat(entries["src/link"]!!.link).isEqualTo("/root/src/file")
        assertThat(entries["src/$longName"]!!.bytes.toString(Charsets.UTF_8)).isEqualTo("long")
    }

    @Test fun `归档不覆盖原文件也不把归档递归装入来源`() = runBlocking<Unit> {
        File(home, "existing.tar.gz").writeText("keep")
        assertThrows(LinuxFileException::class.java) { runBlocking { fs.compress(listOf("/root/existing.tar.gz"), "existing.tar.gz") } }
        val failure = assertThrows(LinuxFileException::class.java) { runBlocking { fs.compress(listOf("/root/existing.tar.gz", "/root"), "nested.tar.gz") } }
        assertThat(failure.code).isEqualTo(LinuxFileErrorCode.TARGET_INSIDE_SOURCE)
        assertThat(File(home, "existing.tar.gz").readText()).isEqualTo("keep")
    }

    private data class TarItem(val mode: Int, val type: Char, val link: String, val bytes: ByteArray)
    private fun readTar(file: File): Map<String, TarItem> {
        val entries = linkedMapOf<String, TarItem>()
        GZIPInputStream(file.inputStream()).use { input ->
            var longName: String? = null
            var longLink: String? = null
            while (true) {
                val header = input.readNBytes(512)
                if (header.isEmpty() || header.all { it == 0.toByte() }) break
                assertThat(header.size).isEqualTo(512)
                fun field(at: Int, count: Int) = header.copyOfRange(at, at + count).toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
                val checksum = field(148, 8).trim().toInt(8)
                val calculated = header.mapIndexed { index, b -> if (index in 148..155) 32 else b.toInt() and 255 }.sum()
                assertThat(checksum).isEqualTo(calculated)
                val size = field(124, 12).trim().toInt(8)
                val body = input.readNBytes(size)
                val padding = (512 - size % 512) % 512
                assertThat(input.readNBytes(padding).size).isEqualTo(padding)
                val type = header[156].toInt().toChar()
                when (type) {
                    'L' -> longName = body.toString(Charsets.UTF_8).trimEnd('\u0000')
                    'K' -> longLink = body.toString(Charsets.UTF_8).trimEnd('\u0000')
                    else -> {
                        entries[longName ?: field(0, 100)] = TarItem(field(100, 8).toInt(8), type, longLink ?: field(157, 100), body)
                        longName = null; longLink = null
                    }
                }
            }
        }
        return entries
    }
}
