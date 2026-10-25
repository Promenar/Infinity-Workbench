package com.promenar.nexara.data.canvas

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 元数据为发布点，源码在元数据发布前写入；同一应用统一使用一个实例。 */
class FileCanvasStore(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxVersions: Int = 10,
    private val maxSourceBytes: Int = 1_048_576,
    private val workspaceFiles: CanvasWorkspaceFiles? = null,
) : CanvasStore {
    init {
        require(maxVersions > 0)
        require(maxSourceBytes > 0)
    }

    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableStateFlow<List<CanvasMetadata>>>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun observe(sessionId: String): Flow<List<CanvasMetadata>> = flow {
        emitAll(locked { load(sessionId) })
    }

    override suspend fun list(sessionId: String): List<CanvasMetadata> = locked { load(sessionId).value.map { presentMetadata(sessionId, it) } }

    override suspend fun create(
        sessionId: String,
        title: String,
        html: String,
        createdByMessageId: String,
    ): CanvasDocument = locked {
        ensureActive(sessionId)
        validateTitle(title)
        invalidIf(createdByMessageId.isBlank(), "创建画布需要有效的消息 ID。")
        com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        validateSource(html)
        val state = load(sessionId)
        val now = System.currentTimeMillis()
        val metadata = CanvasMetadata(UUID.randomUUID().toString(), title.trim(), now, now, 1, createdByMessageId, listOf(1))
        val dir = canvasDirectory(sessionId, metadata.id)
        try {
            publish(dir, metadata, html)
        } catch (failure: Exception) {
            dir.deleteRecursively()
            throw failure
        }
        state.value = sorted(state.value + metadata)
        presentDocument(sessionId, writeToWorkspace(sessionId, CanvasDocument(metadata, html, 1)))
    }

    override suspend fun update(
        sessionId: String,
        id: String,
        html: String?,
        patches: List<CanvasPatch>?,
        expectedVersion: Int?,
    ): CanvasDocument = locked {
        ensureActive(sessionId)
        com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        validateId(id)
        invalidIf((html == null) == (patches == null), "html 与 patches 必须且只能提供其中一种。")
        invalidIf(expectedVersion != null && expectedVersion < 1, "expected_version 必须是正整数。")
        val state = load(sessionId)
        val synced = syncLocked(sessionId, id) ?: throw CanvasException("NOT_FOUND", "本会话中不存在该画布。")
        val current = synced.metadata
        if (expectedVersion != null && current.currentVersion != expectedVersion) {
            throw CanvasException("VERSION_CONFLICT", "画布当前为第 ${current.currentVersion} 版，与 expected_version 不一致。")
        }
        invalidIf(current.currentVersion == Int.MAX_VALUE, "画布版本号已达到上限。")
        val nextHtml = html ?: applyPatches(synced.html, patches!!)
        validateSource(nextHtml)
        val nextVersion = current.currentVersion + 1
        val metadata = current.copy(
            updatedAt = maxOf(current.updatedAt, System.currentTimeMillis()),
            currentVersion = nextVersion,
            workspaceSync = CanvasWorkspaceSync.NOT_WRITTEN,
            versions = (current.versions + nextVersion).takeLast(maxVersions),
            versionSources = current.versionSources.filterKeys { it in (current.versions + nextVersion).takeLast(maxVersions) } + (nextVersion to CanvasVersionSource.CANVAS),
        )
        val dir = canvasDirectory(sessionId, id)
        publish(dir, metadata, nextHtml)
        state.value = sorted(state.value.map { if (it.id == id) metadata else it })
        // 清理失败不撤销已发布版本；下次更新会再次回收不在版本清单里的源码。
        dir.listFiles().orEmpty().filter { file ->
            file.extension == "html" && file.nameWithoutExtension.toIntOrNull() !in metadata.versions
        }.forEach { it.delete() }
        presentDocument(sessionId, writeToWorkspace(sessionId, CanvasDocument(metadata, nextHtml, nextVersion)))
    }

    override suspend fun read(sessionId: String, id: String, version: Int?): CanvasDocument? = locked {
        validateId(id)
        invalidIf(version != null && version < 1, "version 必须是正整数。")
        val metadata = load(sessionId).value.find { it.id == id } ?: return@locked null
        val selected = version ?: metadata.currentVersion
        if (selected !in metadata.versions) return@locked null
        val dir = canvasDirectory(sessionId, id)
        val source = child(dir, "$selected.html")
        if (!source.isFile) return@locked null
        CanvasDocument(presentMetadata(sessionId, metadata), readSource(dir, selected), selected)
    }

    override suspend fun syncFromWorkspace(sessionId: String, id: String): CanvasDocument? = locked {
        validateId(id)
        syncLocked(sessionId, id)?.let { presentDocument(sessionId, it) }
    }

    private suspend fun presentMetadata(sessionId: String, metadata: CanvasMetadata): CanvasMetadata {
        val path = metadata.workspacePath ?: return metadata
        val destination = metadata.workspaceDestination ?: return metadata
        val presented = workspaceFiles?.defaultPath(sessionId, path, destination) ?: return metadata
        return metadata.copy(workspacePath = presented)
    }

    private suspend fun presentDocument(sessionId: String, document: CanvasDocument): CanvasDocument =
        document.copy(metadata = presentMetadata(sessionId, document.metadata))

    override suspend fun deleteSession(sessionId: String) = locked {
        val dir = sessionDirectory(sessionId)
        val marker = tombstone(sessionId)
        if (!marker.exists()) writeAtomic(marker, byteArrayOf(1))
        states.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value = emptyList()
        if (dir.exists() && !dir.deleteRecursively()) throw CanvasException("STORAGE_ERROR", "会话画布已停用，但文件清理失败，请重试删除。")
        Unit
    }

    override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) = locked {
        ensureActive(targetSessionId)
        val target = sessionDirectory(targetSessionId)
        if (target.exists() || sourceSessionId == targetSessionId) {
            throw CanvasException("SESSION_EXISTS", "目标会话已有画布目录，不能覆盖。")
        }
        val source = load(sourceSessionId).value.filter { it.createdByMessageId in messageIdMap }
        invalidIf(source.any { messageIdMap.getValue(it.createdByMessageId).isBlank() }, "分支消息映射不能使用空消息 ID。")
        val staging = child(root, ".${target.name}.tmp-${UUID.randomUUID()}")
        val copied = mutableListOf<CanvasMetadata>()
        try {
            ensureDirectory(staging)
            source.forEach { metadata ->
                val html = readSource(canvasDirectory(sourceSessionId, metadata.id), metadata.currentVersion)
                val remapped = metadata.copy(
                    createdByMessageId = messageIdMap.getValue(metadata.createdByMessageId),
                    versions = listOf(metadata.currentVersion),
                    versionSources = metadata.versionSources.filterKeys { it == metadata.currentVersion },
                    // 分支可能与源会话共用自选工作区，路径在首次写出时重新分配，避免两份画布指向同一文件。
                    workspacePath = null,
                    workspaceSync = CanvasWorkspaceSync.NOT_WRITTEN,
                    workspaceDestination = null,
                )
                publish(child(staging, metadata.id), remapped, html, workspaceHash = null)
                copied += remapped
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        states.getOrPut(targetSessionId) { MutableStateFlow(emptyList()) }.value = sorted(copied)
    }

    private suspend fun syncLocked(sessionId: String, id: String): CanvasDocument? {
        var current = load(sessionId).value.find { it.id == id } ?: return null
        val dir = canvasDirectory(sessionId, id)
        var document = CanvasDocument(current, readSource(dir, current.currentVersion), current.currentVersion)
        val files = workspaceFiles ?: return document
        val hash = workspaceHash(dir)
        if (current.workspaceDestination == null) {
            try {
                val destination = if (current.workspacePath != null && hash != null) files.legacyDestination(sessionId, current.workspacePath!!, hash)
                    else files.defaultDestination(sessionId)
                destination?.let {
                    current = current.copy(workspaceDestination = it)
                    document = setSync(sessionId, document.copy(metadata = current), current.workspaceSync, hash)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                return setSync(sessionId, document, CanvasWorkspaceSync.FAILED, hash)
            }
        }
        if (current.workspacePath == null) return writeToWorkspace(sessionId, document)
        if (hash == null) return writeToWorkspace(sessionId, document)
        try {
            val bytes = files.read(sessionId, current.workspacePath!!, maxSourceBytes, current.workspaceDestination)
                ?: return setSync(sessionId, document, CanvasWorkspaceSync.MISSING, hash)
            val actualHash = canvasSha256(bytes)
            if (actualHash == canvasSha256(document.html.toByteArray(Charsets.UTF_8))) {
                return if (current.workspaceSync == CanvasWorkspaceSync.SYNCED && hash == actualHash) document
                    else setSync(sessionId, document, CanvasWorkspaceSync.SYNCED, actualHash)
            }
            if (actualHash != hash) {
                invalidIf(current.currentVersion == Int.MAX_VALUE, "画布版本号已达到上限。")
                val html = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                validateSource(html)
                val next = current.currentVersion + 1
                val versions = (current.versions + next).takeLast(maxVersions)
                val metadata = current.copy(
                    updatedAt = maxOf(current.updatedAt, System.currentTimeMillis()), currentVersion = next,
                    versions = versions, workspaceSync = CanvasWorkspaceSync.SYNCED,
                    versionSources = current.versionSources.filterKeys { it in versions } + (next to CanvasVersionSource.WORKSPACE),
                )
                publish(dir, metadata, html, actualHash)
                replaceMetadata(sessionId, metadata)
                cleanupVersions(dir, metadata)
                return CanvasDocument(metadata, html, next)
            }
            return if (current.workspaceSync in setOf(CanvasWorkspaceSync.FAILED, CanvasWorkspaceSync.NOT_WRITTEN)) writeToWorkspace(sessionId, document)
                else setSync(sessionId, document, CanvasWorkspaceSync.SYNCED, hash)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return setSync(sessionId, document, CanvasWorkspaceSync.FAILED, hash)
        }
    }

    private suspend fun writeToWorkspace(sessionId: String, document: CanvasDocument): CanvasDocument {
        val files = workspaceFiles ?: return document
        var current = document
        val dir = canvasDirectory(sessionId, document.metadata.id)
        val previousHash = workspaceHash(dir)
        try {
            if (current.metadata.workspaceDestination == null) {
                val destination = if (current.metadata.workspacePath != null && previousHash != null) files.legacyDestination(sessionId, current.metadata.workspacePath!!, previousHash)
                    else files.defaultDestination(sessionId)
                destination?.let {
                    current = current.copy(metadata = current.metadata.copy(workspaceDestination = it))
                    current = setSync(sessionId, current, current.metadata.workspaceSync, previousHash)
                }
            }
            if (current.metadata.workspacePath == null) {
                val base = current.metadata.title.replace(Regex("[^\\p{IsHan}\\p{L}\\p{N}-]+"), "-")
                    .trim('-').codePoints().limit(48).toArray().let { String(it, 0, it.size) }.trimEnd('-').ifEmpty { "canvas" }
                val occupied = load(sessionId).value.mapNotNull { it.workspacePath }.toSet()
                var suffix = 1
                var path: String
                do {
                    path = files.defaultPath(sessionId, "canvas/$base${if (suffix == 1) "" else "-$suffix"}.html", current.metadata.workspaceDestination)
                    suffix++
                } while (path in occupied || files.exists(sessionId, path, current.metadata.workspaceDestination))
                current = current.copy(metadata = current.metadata.copy(workspacePath = path))
                current = setSync(sessionId, current, CanvasWorkspaceSync.NOT_WRITTEN, previousHash)
            }
            val path = current.metadata.workspacePath!!
            // 仅覆盖最后一次已同步的内容；文件工具的并发修改交给下一次同步导入。
            val bytes = files.read(sessionId, path, maxSourceBytes, current.metadata.workspaceDestination)
            val expectedHash = bytes?.let(::canvasSha256)
            val sourceHash = canvasSha256(current.html.toByteArray(Charsets.UTF_8))
            // 允许恢复文件已落盘、私有哈希尚未确认的中断窗口，避免导入自身写出的内容。
            if (expectedHash == sourceHash) return setSync(sessionId, current, CanvasWorkspaceSync.SYNCED, sourceHash)
            if (expectedHash != null && expectedHash != previousHash) throw CanvasException("WORKSPACE_CONFLICT", "画布工作区文件已变化。")
            files.write(sessionId, path, current.html, expectedHash, current.metadata.workspaceDestination)
            return setSync(sessionId, current, CanvasWorkspaceSync.SYNCED, canvasSha256(current.html.toByteArray(Charsets.UTF_8)))
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return setSync(sessionId, current, CanvasWorkspaceSync.FAILED, previousHash)
        }
    }

    private fun setSync(sessionId: String, document: CanvasDocument, sync: CanvasWorkspaceSync, hash: String?): CanvasDocument {
        val metadata = document.metadata.copy(workspaceSync = sync)
        writeAtomic(child(canvasDirectory(sessionId, metadata.id), "metadata.json"), metadataBytes(metadata, hash))
        replaceMetadata(sessionId, metadata)
        return document.copy(metadata = metadata)
    }

    private fun replaceMetadata(sessionId: String, metadata: CanvasMetadata) {
        val state = load(sessionId)
        state.value = sorted(state.value.map { if (it.id == metadata.id) metadata else it })
    }

    private fun workspaceHash(dir: File): String? {
        val file = child(dir, "metadata.json")
        if (!file.isFile) return null
        return json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject["workspaceHash"]
            ?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    }

    private fun metadataBytes(metadata: CanvasMetadata, hash: String?): ByteArray = buildJsonObject {
        json.parseToJsonElement(json.encodeToString(metadata)).jsonObject.forEach { (key, value) -> put(key, value) }
        put("workspaceHash", hash?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().toByteArray(Charsets.UTF_8).also {
        invalidIf(it.size > MAX_METADATA_BYTES, "画布元数据超过 64 KiB 上限。")
    }

    private fun cleanupVersions(dir: File, metadata: CanvasMetadata) {
        dir.listFiles().orEmpty().filter { it.extension == "html" && it.nameWithoutExtension.toIntOrNull() !in metadata.versions }.forEach { it.delete() }
    }

    private fun load(sessionId: String): MutableStateFlow<List<CanvasMetadata>> {
        val dir = sessionDirectory(sessionId)
        if (tombstone(sessionId).exists()) {
            return states.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.also { it.value = emptyList() }
        }
        return states.getOrPut(sessionId) {
            val restored = dir.listFiles().orEmpty().mapNotNull { entry ->
                try {
                    if (!ID_PATTERN.matches(entry.name) || !entry.isDirectory || Files.isSymbolicLink(entry.toPath())) return@mapNotNull null
                    if (Files.isSymbolicLink(File(entry, "metadata.json").toPath())) return@mapNotNull null
                    val metadataFile = child(entry, "metadata.json")
                    if (!metadataFile.isFile || metadataFile.length() > MAX_METADATA_BYTES) return@mapNotNull null
                    val metadata = json.decodeFromString<CanvasMetadata>(metadataFile.readText(Charsets.UTF_8))
                    if (metadata.id != entry.name || !validMetadata(metadata)) return@mapNotNull null
                    if (Files.isSymbolicLink(File(entry, "${metadata.currentVersion}.html").toPath())) return@mapNotNull null
                    if (!child(entry, "${metadata.currentVersion}.html").isFile) return@mapNotNull null
                    metadata
                } catch (_: SerializationException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: CanvasException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            MutableStateFlow(sorted(restored))
        }
    }

    private fun validMetadata(metadata: CanvasMetadata): Boolean = metadata.title.isNotBlank() &&
        metadata.title.length <= MAX_TITLE_CHARS && metadata.createdByMessageId.isNotBlank() &&
        metadata.createdAt >= 0 && metadata.updatedAt >= metadata.createdAt && metadata.currentVersion > 0 &&
        metadata.versions.isNotEmpty() && metadata.versions.all { it > 0 } &&
        metadata.versions == metadata.versions.distinct().sorted() && metadata.versions.last() == metadata.currentVersion &&
        (metadata.workspacePath == null || validCanvasWorkspacePath(metadata.workspacePath)) &&
        metadata.versionSources.keys.all { it in metadata.versions }

    private fun publish(dir: File, metadata: CanvasMetadata, html: String, workspaceHash: String? = workspaceHash(dir)) {
        validateSource(html)
        val metadataBytes = metadataBytes(metadata, workspaceHash)
        invalidIf(metadataBytes.size > MAX_METADATA_BYTES, "画布元数据超过 64 KiB 上限。")
        ensureDirectory(dir)
        val source = child(dir, "${metadata.currentVersion}.html")
        var published = false
        try {
            writeAtomic(source, html.toByteArray(Charsets.UTF_8))
            writeAtomic(child(dir, "metadata.json"), metadataBytes)
            published = true
        } finally {
            if (!published) source.delete()
        }
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        ensureDirectory(target.parentFile!!)
        val temp = child(target.parentFile!!, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun readSource(dir: File, version: Int): String {
        val source = child(dir, "$version.html")
        if (!source.isFile) throw CanvasException("NOT_FOUND", "画布源码文件不存在。")
        if (source.length() > maxSourceBytes) throw CanvasException("SOURCE_TOO_LARGE", "HTML 源码超过 $maxSourceBytes 字节上限。")
        return source.readText(Charsets.UTF_8).also(::validateSource)
    }

    private fun applyPatches(original: String, patches: List<CanvasPatch>): String {
        invalidIf(patches.isEmpty(), "patches 至少需要一条补丁。")
        var updated = original
        patches.forEachIndexed { index, patch ->
            invalidIf(patch.find.isEmpty(), "第 ${index + 1} 条补丁的 find 不能为空。")
            val position = updated.indexOf(patch.find)
            if (position < 0 || updated.indexOf(patch.find, position + 1) >= 0) {
                throw CanvasException("PATCH_NOT_UNIQUE", "第 ${index + 1} 条补丁的 find ${if (position < 0) "未找到" else "出现多次"}，需要唯一匹配。")
            }
            updated = updated.replaceRange(position, position + patch.find.length, patch.replace)
            validateSource(updated)
        }
        return updated
    }

    private fun sessionDirectory(sessionId: String): File {
        invalidIf(sessionId.isBlank(), "sessionId 不能为空。")
        val hash = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return child(root, hash)
    }

    private fun canvasDirectory(sessionId: String, id: String): File {
        validateId(id)
        return child(sessionDirectory(sessionId), id)
    }

    private fun tombstone(sessionId: String): File = child(child(root, ".deleted"), "${sessionDirectory(sessionId).name}.tombstone")

    private fun ensureActive(sessionId: String) {
        if (tombstone(sessionId).exists()) throw CanvasException("SESSION_DELETED", "会话已删除，不能再写入画布。")
    }

    private fun child(parent: File, name: String): File = File(parent, name).also {
        if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != parent.canonicalFile) {
            throw CanvasException("STORAGE_ERROR", "画布存储路径不安全。")
        }
    }

    private fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw CanvasException("STORAGE_ERROR", "无法创建画布存储目录。")
    }

    private fun validateId(id: String) = invalidIf(!ID_PATTERN.matches(id), "canvas_id 格式无效，请使用 canvas_create 或 canvas_list 返回的 ID。")
    private fun validateTitle(title: String) = invalidIf(title.isBlank() || title.length > MAX_TITLE_CHARS, "标题需要 1 到 $MAX_TITLE_CHARS 个字符。")
    private fun validateSource(html: String) {
        if (html.toByteArray(Charsets.UTF_8).size > maxSourceBytes) throw CanvasException("SOURCE_TOO_LARGE", "HTML 源码超过 $maxSourceBytes 字节上限。")
    }
    private fun invalidIf(invalid: Boolean, message: String) { if (invalid) throw CanvasException("INVALID_ARGUMENTS", message) }
    private fun sorted(items: List<CanvasMetadata>) = items.sortedWith(compareByDescending<CanvasMetadata> { it.updatedAt }.thenBy { it.id })

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable) {
        withContext(ioDispatcher) {
            mutex.withLock {
                try { block() } catch (_: IOException) { throw CanvasException("STORAGE_ERROR", "画布文件读写失败，请重试。") }
            }
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 120
        const val MAX_METADATA_BYTES = 65_536
        val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
