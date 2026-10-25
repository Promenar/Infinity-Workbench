package com.promenar.nexara.ui.linux.files

import com.promenar.nexara.domain.linux.LinuxDiagnostics
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.linux.LinuxFileType
import com.promenar.nexara.domain.linux.LinuxInstallOptions
import com.promenar.nexara.domain.linux.LinuxLaunchSpec
import com.promenar.nexara.domain.linux.LinuxLineEnding
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import com.promenar.nexara.domain.linux.LinuxTextContent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

internal val READY = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.22", "aarch64", "5.4.0", 0L, null))

internal class FakeLinuxRuntime(initial: LinuxRuntimeState = READY) : LinuxRuntime {
    override val state = MutableStateFlow(initial)
    override val agentEnabled = MutableStateFlow(true)
    override suspend fun setAgentEnabled(enabled: Boolean) = Unit
    override fun startInstall(options: LinuxInstallOptions) = Unit
    override fun cancelInstall() = Unit
    override suspend fun storageUsage() = LinuxStorageUsage(0, 0, 0, 0)
    override suspend fun clearDownloadCache() = 0L
    override suspend fun runDiagnostics(): LinuxDiagnostics = error("unused")
    override suspend fun reset() = Unit
    override suspend fun uninstall() = Unit
    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = error("unused")
    override val paths = LinuxPaths(File("rootfs"), File("rootfs/root"))
}

/** 内存中的 rootfs：路径到条目与内容；记录各操作的调用参数，可为指定路径注入失败。 */
internal class FakeLinuxFileSystem(var nowMs: Long = 1_000_000L) : LinuxFileSystem {
    val entries = linkedMapOf<String, LinuxFileEntry>()
    val contents = mutableMapOf<String, ByteArray>()
    val failures = mutableMapOf<String, LinuxFileErrorCode>()
    var listError: LinuxFileErrorCode? = null
    val listCalls = mutableListOf<String>()
    val copyCalls = mutableListOf<Pair<List<String>, String>>()
    val moveCalls = mutableListOf<Pair<List<String>, String>>()
    val deleteCalls = mutableListOf<List<String>>()
    val compressCalls = mutableListOf<Pair<List<String>, String>>()
    val chmodCalls = mutableListOf<Pair<String, Int>>()
    val writeCalls = mutableListOf<Triple<String, String, Long?>>()
    val readBytesCalls = mutableListOf<Pair<Long, Int>>()

    fun dir(path: String, modifiedMs: Long = nowMs, mode: Int = 0b111_101_101) = put(entry(path, LinuxFileType.DIRECTORY, 4096, modifiedMs, mode))

    fun file(path: String, text: String, modifiedMs: Long = nowMs, mode: Int = 0b110_100_100) {
        val bytes = text.toByteArray()
        put(entry(path, LinuxFileType.FILE, bytes.size.toLong(), modifiedMs, mode))
        contents[path] = bytes
    }

    fun binary(path: String, bytes: ByteArray, modifiedMs: Long = nowMs) {
        put(entry(path, LinuxFileType.FILE, bytes.size.toLong(), modifiedMs, 0b110_100_100))
        contents[path] = bytes
    }

    fun link(path: String, target: String, targetIsDirectory: Boolean, modifiedMs: Long = nowMs) =
        put(entry(path, LinuxFileType.SYMLINK, target.length.toLong(), modifiedMs, 0b111_111_111).copy(symlinkTarget = target, targetIsDirectory = targetIsDirectory))

    /** 模拟别处修改文件：内容与修改时间都变。 */
    fun touch(path: String, text: String, modifiedMs: Long) {
        contents[path] = text.toByteArray()
        entries[path] = entries.getValue(path).copy(size = text.length.toLong(), modifiedMs = modifiedMs)
    }

    private fun put(entry: LinuxFileEntry) {
        entries[entry.path] = entry
    }

    private fun entry(path: String, type: LinuxFileType, size: Long, modifiedMs: Long, mode: Int) = LinuxFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        type = type,
        size = size,
        modifiedMs = modifiedMs,
        mode = mode,
        owner = "root",
        group = "root",
    )

    private fun fail(path: String) = failures[path]?.let { throw LinuxFileException(it) }

    override suspend fun list(path: String): List<LinuxFileEntry> {
        listCalls += path
        listError?.let { throw LinuxFileException(it) }
        fail(path)
        if (path != "/" && entries[path]?.type != LinuxFileType.DIRECTORY) throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
        return entries.values.filter { linuxParentPath(it.path) == path }
    }

    override suspend fun stat(path: String): LinuxFileEntry? = entries[path]

    override suspend fun readText(path: String, maxBytes: Int): LinuxTextContent {
        fail(path)
        val entry = entries[path] ?: throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
        val all = contents[path] ?: ByteArray(0)
        val shown = all.copyOf(minOf(all.size, maxBytes))
        val binary = all.any { it == 0.toByte() }
        val text = if (binary) "" else String(shown)
        return LinuxTextContent(
            bytes = shown,
            totalSize = all.size.toLong(),
            isBinary = binary,
            encoding = if (binary) null else "UTF-8",
            lineEnding = if (binary) null else LinuxLineEnding.LF,
            lineCount = if (binary) null else countLines(text),
            modifiedMs = entry.modifiedMs,
        )
    }

    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray {
        readBytesCalls += offset to length
        val all = contents[path] ?: throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
        val start = offset.toInt().coerceAtMost(all.size)
        return all.copyOfRange(start, minOf(all.size, start + length))
    }

    override suspend fun writeText(path: String, text: String, expectedModifiedMs: Long?): LinuxFileEntry {
        writeCalls += Triple(path, text, expectedModifiedMs)
        fail(path)
        val entry = entries[path] ?: throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
        if (expectedModifiedMs != null && expectedModifiedMs != entry.modifiedMs) throw LinuxFileException(LinuxFileErrorCode.CHANGED)
        nowMs += 1_000
        val updated = entry.copy(size = text.toByteArray().size.toLong(), modifiedMs = nowMs)
        entries[path] = updated
        contents[path] = text.toByteArray()
        return updated
    }

    override suspend fun createFile(parent: String, name: String): LinuxFileEntry = create(parent, name, folder = false)

    override suspend fun createDirectory(parent: String, name: String): LinuxFileEntry = create(parent, name, folder = true)

    private fun create(parent: String, name: String, folder: Boolean): LinuxFileEntry {
        if (name.isBlank() || '/' in name) throw LinuxFileException(LinuxFileErrorCode.NAME_INVALID)
        val path = linuxChildPath(parent, name)
        if (path in entries) throw LinuxFileException(LinuxFileErrorCode.NAME_EXISTS)
        if (folder) dir(path) else file(path, "")
        return entries.getValue(path)
    }

    override suspend fun rename(path: String, newName: String): LinuxFileEntry {
        val entry = entries.remove(path) ?: throw LinuxFileException(LinuxFileErrorCode.NOT_FOUND)
        val target = linuxChildPath(linuxParentPath(path)!!, newName)
        val renamed = entry.copy(path = target, name = newName)
        entries[target] = renamed
        return renamed
    }

    override suspend fun copy(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode> {
        copyCalls += paths to targetDirectory
        return paths.mapNotNull { path -> failures[path]?.let { path to it } }.toMap()
    }

    override suspend fun move(paths: List<String>, targetDirectory: String): Map<String, LinuxFileErrorCode> {
        moveCalls += paths to targetDirectory
        return paths.mapNotNull { path -> failures[path]?.let { path to it } }.toMap()
    }

    override suspend fun delete(paths: List<String>): Map<String, LinuxFileErrorCode> {
        deleteCalls += paths
        val failed = paths.mapNotNull { path -> failures[path]?.let { path to it } }.toMap()
        (paths - failed.keys).forEach { entries.remove(it) }
        return failed
    }

    override suspend fun compress(paths: List<String>, archiveName: String): LinuxFileEntry {
        compressCalls += paths to archiveName
        val path = linuxChildPath(linuxParentPath(paths.first())!!, archiveName)
        binary(path, byteArrayOf(0x1f, 0x8b.toByte()))
        return entries.getValue(path)
    }

    override suspend fun chmod(path: String, mode: Int): LinuxFileEntry {
        chmodCalls += path to mode
        val updated = entries.getValue(path).copy(mode = mode)
        entries[path] = updated
        return updated
    }

    override fun hostFile(path: String): File? = null
}
