package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import java.util.Properties

data class LinuxRootfsMetadata(
    val version: String,
    val sha256: String,
    val asset: String,
    val prootVersion: String,
    val unpackedBytes: Long,
) {
    companion object {
        fun read(input: InputStream): LinuxRootfsMetadata = input.use {
            val properties = Properties().apply { load(it) }
            LinuxRootfsMetadata(properties.getProperty("alpine.version"), properties.getProperty("alpine.sha256"),
                properties.getProperty("alpine.asset"), properties.getProperty("proot.version"),
                properties.getProperty("alpine.unpackedBytes").toLong())
        }
    }
}

data class LinuxRootfsRecord(
    val baseBytes: Long,
    val installedAtMs: Long,
    val steps: Map<LinuxInstallStep, LinuxStepStatus>,
)

class LinuxRootfsException(val failure: LinuxFailure, cause: Throwable? = null) :
    java.io.IOException(failure.detail ?: failure.code.name, cause)

/** 仅在 IO 线程调用；发布前校验摘要，previous 保证两次 rename 之间断电也可恢复。 */
class LinuxRootfsInstaller(
    val paths: LinuxPaths,
    val metadata: LinuxRootfsMetadata,
    private val openAsset: () -> InputStream,
    private val availableBytes: () -> Long,
    private val extractor: SafeTarExtractor = SafeTarExtractor(),
    private val now: () -> Long = System::currentTimeMillis,
    private val messages: LinuxRuntimeMessages = LinuxRuntimeMessages(),
) {
    private val parent get() = paths.rootfs.parentFile!!
    private val staging get() = File(parent, "alpine.staging")
    private val previous get() = File(parent, "alpine.previous")

    fun freeBytes(): Long = availableBytes()

    fun readRecord(): LinuxRootfsRecord? {
        recover()
        if (!Files.exists(paths.rootfs.toPath(), NOFOLLOW_LINKS)) return null
        return validatedRecord()
    }

    private fun validatedRecord(): LinuxRootfsRecord {
        try {
            require(Files.isDirectory(paths.rootfs.toPath(), NOFOLLOW_LINKS))
            require(LinuxRootfsFiles.isGuestFile(paths.rootfs, File(paths.rootfs, "bin/sh")))
            val properties = readProperties()
            require(properties.getProperty("version") == metadata.version && properties.getProperty("sha256") == metadata.sha256)
            val baseBytes = properties.getProperty("baseBytes").toLong()
            val installedAt = properties.getProperty("installedAtMs").toLong()
            require(baseBytes >= 0 && installedAt > 0)
            return LinuxRootfsRecord(baseBytes, installedAt,
                LinuxInstallStep.entries.mapNotNull { step -> properties.getProperty("step.${step.name}")?.let { step to LinuxStepStatus.valueOf(it) } }.toMap())
        } catch (error: Exception) {
            throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED, messages(LinuxRuntimeMessage.ROOTFS_DAMAGED)), error)
        }
    }

    fun install(force: Boolean = false, preserveConfiguration: Boolean = false): LinuxRootfsRecord {
        recover()
        if (!force) {
            runCatching { readRecord() }.getOrNull()?.let { return it }
            if (Files.exists(paths.rootfs.toPath(), NOFOLLOW_LINKS)) return repairMissingBaseFiles()
        }
        ensureParent()
        LinuxRootfsFiles.deleteTree(staging)
        val required = metadata.unpackedBytes + STORAGE_MARGIN
        if (availableBytes() < required) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.INSUFFICIENT_STORAGE, messages(LinuxRuntimeMessage.STORAGE_REQUIRED, required)))
        val preserved = if (preserveConfiguration) generatedConfiguration() else emptyMap()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openAsset().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    interrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (hex(digest.digest()) != metadata.sha256) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ASSET_CORRUPT, messages(LinuxRuntimeMessage.ASSET_CORRUPT)))
            try { openAsset().use { extractor.extractGzip(it, staging) } }
            catch (error: InterruptedException) { throw error }
            catch (error: Exception) { throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.EXTRACT_FAILED, error.message), error) }
            require(LinuxRootfsFiles.isGuestFile(staging, File(staging, "bin/sh"))) { messages(LinuxRuntimeMessage.SHELL_MISSING) }
            if (preserveConfiguration) {
                LinuxRootfsFiles.deleteTree(LinuxRootfsFiles.safePath(staging, "root"))
                Files.createDirectories(File(staging, "root").toPath())
            }
            val bytes = LinuxRootfsFiles.size(staging) - LinuxRootfsFiles.size(File(staging, "root")) - LinuxRootfsFiles.size(File(staging, "var/cache/apk"))
            preserved.forEach { (name, text) ->
                LinuxRootfsFiles.safePath(staging, name).apply { parentFile!!.mkdirs(); writeText(text) }
            }
            val properties = Properties().apply {
                setProperty("version", metadata.version); setProperty("sha256", metadata.sha256)
                setProperty("baseBytes", bytes.toString()); setProperty("installedAtMs", now().toString())
                setProperty("step.UNPACK", LinuxStepStatus.DONE.name)
                preserved.forEach { (name, text) -> setProperty("generated.$name", hash(text)) }
            }
            writeProperties(properties, staging)
            interrupted()
            LinuxRootfsFiles.deleteTree(previous)
            if (Files.exists(paths.rootfs.toPath(), NOFOLLOW_LINKS)) Files.move(paths.rootfs.toPath(), previous.toPath(), ATOMIC_MOVE)
            try { Files.move(staging.toPath(), paths.rootfs.toPath(), ATOMIC_MOVE) }
            catch (error: Exception) {
                if (Files.exists(previous.toPath(), NOFOLLOW_LINKS)) Files.move(previous.toPath(), paths.rootfs.toPath(), ATOMIC_MOVE)
                throw error
            }
            LinuxRootfsFiles.deleteTree(previous)
            return checkNotNull(readRecord())
        } finally { LinuxRootfsFiles.deleteTree(staging) }
    }

    /** 只补缺失的内置文件；不覆盖配置、用户目录或 apk 的已装状态。可中断后重复执行。 */
    fun repairMissingBaseFiles(): LinuxRootfsRecord {
        recover()
        ensureParent()
        val root = paths.rootfs.toPath()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED, "Linux 根目录不存在或不是普通目录"))
        }
        val repair = File(parent, "alpine.repair")
        LinuxRootfsFiles.deleteTree(repair)
        val required = metadata.unpackedBytes + STORAGE_MARGIN
        if (availableBytes() < required) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.INSUFFICIENT_STORAGE, messages(LinuxRuntimeMessage.STORAGE_REQUIRED, required)))
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openAsset().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    interrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (hex(digest.digest()) != metadata.sha256) throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ASSET_CORRUPT, messages(LinuxRuntimeMessage.ASSET_CORRUPT)))
            try { openAsset().use { extractor.extractGzip(it, repair) } }
            catch (error: InterruptedException) { throw error }
            catch (error: Exception) { throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.EXTRACT_FAILED, error.message), error) }
            require(LinuxRootfsFiles.isGuestFile(repair, File(repair, "bin/sh"))) { messages(LinuxRuntimeMessage.SHELL_MISSING) }
            Files.walk(repair.toPath()).use { entries ->
                entries.forEach { source ->
                    interrupted()
                    val relative = repair.toPath().relativize(source).toString()
                    if (relative.isEmpty() || REPAIR_PROTECTED.any { relative == it || relative.startsWith("$it/") }) return@forEach
                    val base = root.toAbsolutePath().normalize()
                    val target = SafeTarExtractor.entryPath(base, relative)
                    SafeTarExtractor.requireNoSymlinks(base, target.parent)
                    if (!Files.exists(target, NOFOLLOW_LINKS)) {
                        Files.copy(source, target, NOFOLLOW_LINKS, COPY_ATTRIBUTES)
                    }
                }
            }
            require(LinuxRootfsFiles.isGuestFile(paths.rootfs, File(paths.rootfs, "bin/sh"))) { messages(LinuxRuntimeMessage.SHELL_MISSING) }
            val old = runCatching { readProperties() }.getOrNull()
            val bytes = LinuxRootfsFiles.size(repair) - LinuxRootfsFiles.size(File(repair, "root")) - LinuxRootfsFiles.size(File(repair, "var/cache/apk"))
            val properties = old ?: Properties()
            properties.setProperty("version", metadata.version)
            properties.setProperty("sha256", metadata.sha256)
            properties.setProperty("baseBytes", bytes.toString())
            properties.setProperty("installedAtMs", old?.getProperty("installedAtMs")?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: now().toString())
            LinuxInstallStep.entries.forEach { properties.remove("step.${it.name}") }
            properties.setProperty("step.UNPACK", LinuxStepStatus.DONE.name)
            writeProperties(properties)
            return validatedRecord()
        } catch (error: InterruptedException) { throw error }
        catch (error: LinuxRootfsException) { throw error }
        catch (error: Exception) { throw LinuxRootfsException(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED, error.message), error) }
        finally { LinuxRootfsFiles.deleteTree(repair) }
    }

    fun completeStep(step: LinuxInstallStep, status: LinuxStepStatus) {
        require(status == LinuxStepStatus.DONE || status == LinuxStepStatus.SKIPPED)
        val properties = readProperties()
        properties.setProperty("step.${step.name}", status.name)
        writeProperties(properties)
    }

    fun rememberGeneratedConfiguration(name: String) {
        require(name in CONFIGURATION)
        val file = LinuxRootfsFiles.safePath(paths.rootfs, name)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > 64 * 1024) return
        val properties = readProperties()
        properties.setProperty("generated.$name", hash(file.readText()))
        writeProperties(properties)
    }

    fun createGuestDirectories() {
        listOf("workspace", "mnt", "root", "home/session").forEach { Files.createDirectories(LinuxRootfsFiles.safePath(paths.rootfs, it).toPath()) }
    }

    fun storageUsage(): LinuxStorageUsage {
        val record = runCatching { readRecord() }.getOrNull()
        if (!Files.isDirectory(paths.rootfs.toPath(), NOFOLLOW_LINKS)) return LinuxStorageUsage(0, 0, 0, 0)
        val home = LinuxRootfsFiles.size(LinuxRootfsFiles.safePath(paths.rootfs, "root"))
        val cache = LinuxRootfsFiles.size(LinuxRootfsFiles.safePath(paths.rootfs, "var/cache/apk"))
        val total = LinuxRootfsFiles.size(paths.rootfs)
        val base = minOf(record?.baseBytes ?: 0, (total - home - cache).coerceAtLeast(0))
        return LinuxStorageUsage(base, (total - home - cache - base).coerceAtLeast(0), home, cache)
    }

    fun clearDownloadCache(): Long {
        if (!Files.isDirectory(paths.rootfs.toPath(), NOFOLLOW_LINKS)) return 0
        // 拒绝父目录链接；最终 apk 目录若是链接，只删除链接本身。
        val parent = LinuxRootfsFiles.safePath(paths.rootfs, "var/cache")
        val directory = File(parent, "apk")
        val before = LinuxRootfsFiles.size(directory)
        LinuxRootfsFiles.deleteTree(directory)
        Files.createDirectories(directory.toPath())
        return before
    }

    fun uninstall() {
        ensureParent()
        listOf(paths.rootfs, staging, previous, File(parent, "alpine.repair")).forEach(LinuxRootfsFiles::deleteTree)
    }

    private fun generatedConfiguration(): Map<String, String> {
        val properties = runCatching { readProperties() }.getOrNull() ?: return emptyMap()
        return CONFIGURATION.mapNotNull { name ->
            val file = runCatching { LinuxRootfsFiles.safePath(paths.rootfs, name) }.getOrNull() ?: return@mapNotNull null
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > 64 * 1024) return@mapNotNull null
            val text = file.readText()
            val generated = if (name == "etc/resolv.conf") LinuxLaunchDns.isGenerated(file) else hash(text) == properties.getProperty("generated.$name")
            if (generated) name to text else null
        }.toMap()
    }

    private fun recover() {
        ensureParent()
        if (!Files.exists(paths.rootfs.toPath(), NOFOLLOW_LINKS) && Files.exists(previous.toPath(), NOFOLLOW_LINKS)) {
            Files.move(previous.toPath(), paths.rootfs.toPath(), ATOMIC_MOVE)
        }
        if (Files.exists(previous.toPath(), NOFOLLOW_LINKS) && runCatching { validatedRecord() }.isSuccess) {
            LinuxRootfsFiles.deleteTree(previous)
        }
    }

    private fun ensureParent() {
        require(!Files.isSymbolicLink(parent.toPath())) { messages(LinuxRuntimeMessage.DATA_SYMLINK) }
        Files.createDirectories(parent.toPath())
    }

    private fun readProperties(): Properties {
        val marker = LinuxRootfsFiles.safePath(paths.rootfs, MARKER)
        require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() < 64 * 1024)
        return Properties().apply { marker.inputStream().use { load(it) } }
    }

    private fun writeProperties(properties: Properties, root: File = paths.rootfs) {
        val marker = LinuxRootfsFiles.safePath(root, MARKER)
        val temporary = Files.createTempFile(parent.toPath(), "linux-marker-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, null) }
            Files.move(temporary, marker.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private companion object {
        const val STORAGE_MARGIN = 32L * 1024 * 1024
        const val MARKER = ".infinity-rootfs-version"
        val CONFIGURATION = listOf("etc/resolv.conf", "etc/apk/repositories")
        val REPAIR_PROTECTED = listOf("root", "home", "etc/apk/world", "lib/apk/db", "var/lib/apk", ".infinity-rootfs-version")
        fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}
