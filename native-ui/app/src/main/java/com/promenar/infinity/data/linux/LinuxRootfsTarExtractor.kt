package com.promenar.infinity.data.linux

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** 只接受 minirootfs 使用的 ustar 类型；路径校验与 Android 系统调用解耦。 */
class SafeTarExtractor(
    private val symlink: (String, File) -> Unit = { target, file -> Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target)); Unit },
    private val chmod: (File, Int) -> Unit = { _, _ -> },
) {
    fun extractGzip(input: InputStream, destination: File) {
        val root = destination.toPath().toAbsolutePath().normalize()
        require(!Files.exists(root, NOFOLLOW_LINKS)) { "解包目录必须不存在" }
        Files.createDirectories(root)
        val directories = mutableListOf<Pair<Path, Int>>()
        val hardLinks = mutableListOf<Triple<Path, Path, Int>>()
        var total = 0L
        var entries = 0
        GZIPInputStream(input).use { tar ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val header = ByteArray(512)
                readExactly(tar, header)
                if (header.all { it == 0.toByte() }) {
                    val end = ByteArray(512)
                    readExactly(tar, end)
                    require(end.all { it == 0.toByte() }) { "tar 结束块异常" }
                    // 读到 gzip 尾部以验证 CRC，拒绝结束标记后夹带其它归档。
                    val tail = ByteArray(8192)
                    var count: Int
                    while (tar.read(tail).also { count = it } != -1) {
                        require(tail.take(count).all { it == 0.toByte() }) { "tar 结束后包含数据" }
                        total += count
                        require(total <= MAX_BYTES) { "归档超过大小上限" }
                    }
                    break
                }
                require(++entries <= 50_000) { "归档条目过多" }
                val expected = octal(header, 148, 8)
                val actual = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }.toLong()
                require(expected == actual) { "tar 校验和错误" }
                val name = text(header, 0, 100)
                val prefix = text(header, 345, 155)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                val path = entryPath(root, fullName)
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8).toInt() and 0x1ff
                total += size
                require(size <= MAX_BYTES && total <= MAX_BYTES) { "归档超过大小上限" }
                val type = header[156].toInt().toChar()
                require(type == '0' || type == '\u0000' || size == 0L) { "非文件条目包含数据" }
                if (path == root) {
                    require(type == '5') { "归档根条目必须为目录" }
                    directories += path to mode
                } else {
                    requireNoSymlinks(root, path)
                    Files.createDirectories(path.parent)
                    requireNoSymlinks(root, path)
                    when (type) {
                        '0', '\u0000' -> {
                            require(!Files.exists(path, NOFOLLOW_LINKS)) { "重复文件：$fullName" }
                            Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                                var remaining = size
                                val buffer = ByteArray(8192)
                                while (remaining > 0) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    require(count > 0) { "文件被截断：$fullName" }
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                            chmod(path.toFile(), mode)
                        }
                        '5' -> {
                            require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))
                            Files.createDirectories(path)
                            directories += path to mode
                        }
                        '2' -> {
                            require(!Files.exists(path, NOFOLLOW_LINKS))
                            val target = linkTarget(root, path, text(header, 157, 100))
                            symlink(path.parent.relativize(target).toString(), path.toFile())
                        }
                        '1' -> {
                            require(!Files.exists(path, NOFOLLOW_LINKS))
                            hardLinks += Triple(path, entryPath(root, text(header, 157, 100)), mode)
                        }
                        else -> error("不支持的 tar 类型：$type")
                    }
                }
                val padding = ((512 - size % 512) % 512).toInt()
                if (padding > 0) readExactly(tar, ByteArray(padding))
            }
        }
        // 硬链接复制为独立文件，支持目标在归档后部；禁止复制符号链接。
        while (hardLinks.isNotEmpty()) {
            val ready = hardLinks.filter { (_, target, _) -> Files.isRegularFile(target, NOFOLLOW_LINKS) }
            require(ready.isNotEmpty()) { "硬链接目标缺失或形成循环" }
            ready.forEach { (path, target, mode) ->
                requireNoSymlinks(root, path)
                requireNoSymlinks(root, target)
                total += Files.size(target)
                require(total <= MAX_BYTES)
                Files.copy(target, path)
                chmod(path.toFile(), mode)
                hardLinks.remove(Triple(path, target, mode))
            }
        }
        directories.sortedByDescending { it.first.nameCount }.forEach { (path, mode) -> chmod(path.toFile(), mode) }
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024 * 1024

        internal fun entryPath(root: Path, name: String): Path {
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name) { "非法条目路径" }
            require(name.split('/').none { it == ".." }) { "条目包含上级目录" }
            val path = root.resolve(name).normalize()
            require(path.startsWith(root)) { "条目逃逸" }
            return path
        }

        internal fun linkTarget(root: Path, link: Path, name: String): Path {
            require(name.isNotEmpty() && '\\' !in name && '\u0000' !in name)
            // 绝对链接表示 guest 根目录，改写成等价的相对链接，不指向宿主根目录。
            val target = if (name.startsWith('/')) root.resolve(name.dropWhile { it == '/' }) else link.parent.resolve(name)
            val normalized = target.normalize()
            require(normalized.startsWith(root)) { "符号链接逃逸" }
            return normalized
        }

        internal fun requireNoSymlinks(root: Path, path: Path) {
            require(path.startsWith(root))
            var current = root
            require(!Files.isSymbolicLink(current))
            root.relativize(path).forEach { part ->
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "禁止通过符号链接写入：$current" }
            }
        }

        private fun text(header: ByteArray, offset: Int, length: Int): String =
            header.copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

        private fun octal(header: ByteArray, offset: Int, length: Int): Long {
            val value = text(header, offset, length).trim()
            require(value.all { it in '0'..'7' }) { "tar 数字格式异常" }
            return if (value.isEmpty()) 0 else value.toLong(8)
        }

        private fun readExactly(input: InputStream, bytes: ByteArray) {
            var position = 0
            while (position < bytes.size) {
                val count = input.read(bytes, position, bytes.size - position)
                require(count > 0) { "tar 被截断" }
                position += count
            }
        }
    }
}
