package com.promenar.nexara.data.model.catalog

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 不可变版本目录先落盘，最后原子替换指针；更新故障不会覆盖活动目录。 */
internal class ModelCatalogCache(
    private val root: File,
    private val verifier: CatalogEnvelopeVerifier,
    private val beforePointerCommit: () -> Unit = {},
) {
    private val json = Json
    private val pointerFile get() = root.resolve("state.json")

    @Serializable
    private data class Pointer(
        val highestVersion: Long,
        val highestHash: String,
        val current: String,
        val previous: String? = null,
    )

    @Synchronized
    fun load(): VerifiedCatalog? {
        val pointer = readPointer() ?: return null
        return loadVersion(pointer.current) ?: pointer.previous?.let(::loadVersion)
    }

    @Synchronized
    fun checkVersion(manifest: CatalogManifest) {
        val state = readPointer() ?: return
        require(manifest.catalogVersion >= state.highestVersion) { "拒绝旧版目录回放" }
        require(manifest.catalogVersion != state.highestVersion || manifest.catalogSha256 == state.highestHash) {
            "相同目录版本对应不同内容"
        }
    }

    @Synchronized
    fun install(candidate: VerifiedCatalog) {
        checkVersion(candidate.manifest)
        val old = readPointer()
        check(root.isDirectory || root.mkdirs()) { "无法创建目录缓存" }
        val version = "v${candidate.manifest.catalogVersion}-${candidate.manifest.catalogSha256}"
        val directory = root.resolve(version)
        check(directory.isDirectory || directory.mkdir()) { "无法创建目录版本" }
        writeAtomic(directory.resolve("catalog.json"), candidate.catalogBytes)
        writeAtomic(directory.resolve("manifest.json"), candidate.envelopeBytes)
        val previous = old?.let { state ->
            if (state.current == version) state.previous
            else if (loadVersion(state.current) != null) state.current else state.previous
        }
        val pointer = Pointer(candidate.manifest.catalogVersion, candidate.manifest.catalogSha256, version, previous)
        beforePointerCommit()
        writeAtomic(pointerFile, json.encodeToString(pointer).toByteArray())
        // 只清理由本组件命名的无引用目录，保持缓存空间有界。
        root.listFiles().orEmpty().filter {
            it.isDirectory && VERSION_PATTERN.matches(it.name) && it.name != version && it.name != previous
        }.forEach { it.deleteRecursively() }
    }

    private fun readPointer(): Pointer? {
        if (!pointerFile.exists()) return recoverPointer(required = false)
        return try { decodePointer() } catch (_: Exception) { recoverPointer(required = true) }
    }

    private fun decodePointer(): Pointer {
        require(pointerFile.length() in 1..4096) { "目录缓存指针损坏" }
        return json.decodeFromString<Pointer>(pointerFile.readText()).also {
            require(it.highestVersion > 0 && it.highestHash.matches(Regex("[a-f0-9]{64}"))) { "目录缓存版本损坏" }
            require(VERSION_PATTERN.matches(it.current) && (it.previous == null || VERSION_PATTERN.matches(it.previous))) {
                "目录缓存路径损坏"
            }
            require(it.current == "v${it.highestVersion}-${it.highestHash}") { "目录缓存版本不一致" }
        }
    }

    /** 从已验签的信封恢复高水位，即使该版本的数据文件已经损坏也不能降低版本。 */
    private fun recoverPointer(required: Boolean): Pointer? {
        val candidates = root.listFiles().orEmpty().filter { it.isDirectory && VERSION_PATTERN.matches(it.name) }
            .mapNotNull { directory ->
                runCatching {
                    val manifest = verifier.readManifest(readBounded(directory.resolve("manifest.json"), MAX_MANIFEST_BYTES))
                    require(directory.name == "v${manifest.catalogVersion}-${manifest.catalogSha256}")
                    directory.name to manifest
                }.getOrNull()
            }.sortedByDescending { it.second.catalogVersion }
        if (candidates.isEmpty()) {
            require(!required && root.listFiles().orEmpty().none { VERSION_PATTERN.matches(it.name) }) {
                "目录缓存无法恢复可信版本"
            }
            return null
        }
        val (current, manifest) = candidates.first()
        require(candidates.filter { it.second.catalogVersion == manifest.catalogVersion }
            .map { it.second.catalogSha256 }.distinct().size == 1) { "目录缓存版本冲突" }
        val previous = candidates.drop(1).firstOrNull { loadVersion(it.first) != null }?.first
        return Pointer(manifest.catalogVersion, manifest.catalogSha256, current, previous).also {
            writeAtomic(pointerFile, json.encodeToString(it).toByteArray())
        }
    }

    private fun loadVersion(name: String): VerifiedCatalog? = runCatching {
        require(VERSION_PATTERN.matches(name))
        val directory = root.resolve(name)
        val envelope = readBounded(directory.resolve("manifest.json"), MAX_MANIFEST_BYTES)
        val catalog = readBounded(directory.resolve("catalog.json"), MAX_CATALOG_BYTES)
        verifier.verify(envelope, catalog).also {
            require(name == "v${it.manifest.catalogVersion}-${it.manifest.catalogSha256}")
        }
    }.getOrNull()

    private fun readBounded(file: File, limit: Int): ByteArray {
        require(file.length() in 1..limit.toLong())
        return file.inputStream().use { readCatalogLimited(it, limit) }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temp = File.createTempFile("catalog-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { temp.delete() }
    }

    private companion object { val VERSION_PATTERN = Regex("v[1-9][0-9]*-[a-f0-9]{64}") }
}
