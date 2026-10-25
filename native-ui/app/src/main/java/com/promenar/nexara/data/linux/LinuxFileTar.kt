package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** USTAR 加 GNU 长名称扩展；归档真实链接，不递归跟随链接。 */
internal class LinuxFileTar(private val output: OutputStream, private val metadata: LinuxFileMetadata, private val paths: LinuxFilePaths) {
    private val access = paths.access
    suspend fun add(source: Path, name: String) {
        currentCoroutineContext().ensureActive()
        val actual = if (paths.isEmulatedHardLink(source)) paths.resolve(paths.guest(source)) else source
        val attributes = access.attributes(actual)
        val identity = access.identity(actual, metadata)
        val type = when {
            attributes.isDirectory -> '5'
            attributes.isSymbolicLink -> '2'
            attributes.isRegularFile -> '0'
            else -> throw LinuxFileException(LinuxFileErrorCode.IO)
        }
        val entryName = if (attributes.isDirectory) "$name/" else name
        val link = if (attributes.isSymbolicLink) access.readLink(actual).toString() else ""
        val size = if (attributes.isRegularFile) attributes.size() else 0
        if (entryName.toByteArray().size > 100) longField(entryName, 'L')
        if (link.toByteArray().size > 100) longField(link, 'K')
        header(entryName, identity.mode, size, attributes.lastModifiedTime().toMillis() / 1000, type, link)
        if (attributes.isRegularFile) {
            access.openRead(actual).use { opened ->
                val input = opened.channel
                val revision = opened.revision()
                var remaining = size
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear().limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    val count = input.read(buffer)
                    if (count < 0) throw LinuxFileException(LinuxFileErrorCode.CHANGED)
                    output.write(buffer.array(), 0, count)
                    remaining -= count
                }
                if (opened.revision() != revision) throw LinuxFileException(LinuxFileErrorCode.CHANGED)
            }
            val after = access.attributes(actual)
            if (after.size() != size || after.lastModifiedTime() != attributes.lastModifiedTime() || after.fileKey() != attributes.fileKey()) throw LinuxFileException(LinuxFileErrorCode.CHANGED)
            padding(size)
        }
        if (attributes.isDirectory) run {
            access.children(actual).filterNot { LinuxFilePaths.isLink2SymlinkName(it.fileName.toString()) }.sortedBy { it.fileName.toString() }.forEach {
                add(it, "$name/${it.fileName}")
            }
        }
    }

    fun finish() { output.write(ByteArray(1024)) }

    private fun longField(value: String, type: Char) {
        val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        header("././@LongLink", 0x1a4, bytes.size.toLong(), 0, type, "")
        output.write(bytes)
        padding(bytes.size.toLong())
    }

    private fun header(name: String, mode: Int, size: Long, modified: Long, type: Char, link: String) {
        val bytes = ByteArray(512)
        fun text(at: Int, count: Int, value: String) { value.toByteArray(Charsets.UTF_8).copyInto(bytes, at, 0, minOf(count, value.toByteArray(Charsets.UTF_8).size)) }
        fun octal(at: Int, count: Int, value: Long) {
            val number = value.coerceAtLeast(0).toString(8)
            if (number.length > count - 1) throw LinuxFileException(LinuxFileErrorCode.IO)
            text(at, count - 1, number.padStart(count - 1, '0'))
        }
        text(0, 100, name)
        octal(100, 8, mode.toLong())
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, size)
        octal(136, 12, modified)
        (148..155).forEach { bytes[it] = 32 }
        bytes[156] = type.code.toByte()
        text(157, 100, link)
        text(257, 6, "ustar\u0000")
        text(263, 2, "00")
        text(265, 32, "root")
        text(297, 32, "root")
        val checksum = bytes.sumOf { it.toInt() and 255 }
        text(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        output.write(bytes)
    }

    private fun padding(size: Long) { output.write(ByteArray(((512 - size % 512) % 512).toInt())) }
}
