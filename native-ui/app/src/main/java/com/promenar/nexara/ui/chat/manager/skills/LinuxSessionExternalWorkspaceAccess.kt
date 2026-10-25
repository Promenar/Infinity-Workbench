package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.WorkspaceTextContentPolicy
import com.promenar.nexara.data.repository.WorkspaceTextOperation
import com.promenar.nexara.data.repository.WorkspaceTextPolicyException
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.ExternalTreeTransfer
import com.promenar.nexara.data.storage.externalSnapshotMime
import com.promenar.nexara.data.storage.isUserSkills
import com.promenar.nexara.data.workspace.*
import com.promenar.nexara.domain.repository.*
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import java.util.UUID
import java.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Linux 文件工具命名空间；私有目录继续使用 Room 事务、版本与索引，不直接写宿主文件。 */
class LinuxSessionExternalWorkspaceAccess(
    private val original: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val rootfs: ExternalFolderBackend,
    private val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
    private val sessions: com.promenar.nexara.data.repository.ISessionRepository? = null,
) : ExternalWorkspaceAccess by original {
    private data class Namespace(val root: FileEntry, val workspace: SessionWorkspace, val mount: ExternalMount)
    private val versions = linkedMapOf<String, ByteArray>()

    override fun observeMounts(sessionId: String) = original.observeMounts(sessionId).map { mounts -> mounts.filter(::allowed) }
    override suspend fun visible(sessionId: String): List<ExternalMount> = operation {
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        val raw = if (bound == null) original.visible(sessionId) else withContext(ExternalMountOperationBindings(bound.mapValues { (alias, id) -> if (alias.isEmpty()) id else originalMountId(id) })) {
            original.visible(sessionId)
        }
        raw.filter(::allowed).map { mount ->
            if (mount.sourceType != "real_path") mount else {
                val prefix = "linux-mount:${Base64.getUrlEncoder().withoutPadding().encodeToString(mount.id.toByteArray())}:"
                try { mount.copy(id = prefix + Sha256Utils.hash(directoryIdentity(File(mount.sourceId)))) }
                catch (_: ExternalFolderException) { mount.copy(id = prefix + "unavailable", unavailableReason = "Linux 目录来源不可用或缺少访问权限") }
            }
        }.filter { bound == null || bound[it.alias] == it.id }
    }
    override suspend fun addSaf(alias: String, sessionId: String, treeUri: String, grantFlags: Int): ExternalMount =
        fail("REAL_PATH_ONLY", "Linux 模式只接受具有真实路径的外部文件夹")

    override suspend fun workspaceMount(sessionId: String): ExternalMount = namespace(sessionId).mount

    override suspend fun resolveUuid(sessionId: String, uuid: String): String? = operation {
        val current = namespace(sessionId)
        ExternalMountPaths.uuidPath(uuid, listOf(current.mount))?.let { return@operation LinuxSessionPaths.normalize(it) }
        if (uuid.startsWith("external:")) {
            val mounts = withOriginalBindings(current) { original.visible(sessionId).filter(::allowed) + listOfNotNull(original.workspaceMount(sessionId)) }
            return@operation ExternalMountPaths.uuidPath(uuid, mounts)?.let(::fromOriginal)
        }
        workspace.getByUuid(current.root.uuid, uuid)?.materializedPath?.let { LinuxSessionPaths.PRIVATE_ROOT + it.takeUnless { it == "/" }.orEmpty() }
    }

    override suspend fun entry(sessionId: String, path: String): FileEntry? = operation {
        val normalized = LinuxSessionPaths.normalize(path)
        val current = namespace(sessionId)
        val stat = stat(sessionId, current, normalized) ?: return@operation null
        val hash = stat.sha256 ?: if (!stat.isDirectory && stat.size <= VERSION_BYTES) {
            Sha256Utils.hash(read(sessionId, normalized, VERSION_BYTES))
        } else ""
        FileEntry(
            uuid = ExternalMountPaths.uuid(current.mount, normalized),
            parentUuid = normalized.takeUnless { it == "/" }?.let { ExternalMountPaths.uuid(current.mount, parent(it)) },
            name = if (normalized == "/") "" else normalized.substringAfterLast('/'),
            hash = hash, sizeBytes = stat.size, isDirectory = stat.isDirectory,
            physicalRootPath = "", materializedPath = normalized, createdAt = 0, updatedAt = stat.lastModified,
        )
    }

    override suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry> = operation {
        val path = LinuxSessionPaths.normalize(virtualPath)
        val current = namespace(sessionId)
        val target = route(current, path)
        val entries = when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> privateBackend(current).list(target.path).map { it.copy(path = child(path, it.name)) }
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.list(sessionId, target.path) }
                .filterNot { it.path == "/mnt" || it.path == "/skills" }.map { it.copy(path = child(path, it.name)) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.list(sessionId, it) }
                .map { it.copy(path = child(path, it.name)) }
            LinuxSessionPathKind.MOUNTS -> visible(sessionId).map { ExternalFolderEntry("/mnt/${it.alias}", it.alias, true, 0, it.createdAt) }
            LinuxSessionPathKind.ROOTFS -> {
                val existing = if (path == "/home" && rootfs.stat(path) == null) emptyList() else rootfs.list(path)
                when (path) {
                    "/" -> existing.filterNot { it.name in setOf("workspace", "mnt", "home") } +
                        listOf(directory("/workspace"), directory("/mnt"), directory("/home"))
                    "/home" -> existing.filterNot { it.name == "session" } + directory(LinuxSessionPaths.PRIVATE_ROOT)
                    else -> existing
                }
            }
        }
        entries.filter { runCatching { LinuxSessionPaths.normalize(it.path) }.isSuccess }
    }

    override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = operation {
        val path = LinuxSessionPaths.normalize(virtualPath)
        val current = namespace(sessionId)
        val target = route(current, path)
        val bytes = when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> privateBackend(current).read(target.path, maxBytes)
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.read(sessionId, target.path, maxBytes) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.read(sessionId, it, maxBytes) }
            LinuxSessionPathKind.ROOTFS -> rootfs.read(target.path, maxBytes)
            LinuxSessionPathKind.MOUNTS -> fail("IS_DIRECTORY", "目标是目录")
        }
        remember(current, path, bytes)
        bytes
    }

    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry = operation {
        val normalized = mutation(path)
        val current = namespace(sessionId)
        val target = route(current, normalized)
        requireWritable(sessionId, current, normalized)
        when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> writePrivate(sessionId, current, target.path, bytes, expectedSha256, createOnly)
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.write(sessionId, target.path, bytes, expectedSha256, createOnly) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.write(sessionId, it, bytes, expectedSha256, createOnly) }
            LinuxSessionPathKind.ROOTFS -> rootfs.write(target.path, bytes, expectedSha256, createOnly)
            LinuxSessionPathKind.MOUNTS -> fail("INVALID_PATH", "不能覆盖挂载根目录")
        }
        remember(current, normalized, bytes)
        entry(sessionId, normalized) ?: fail("NOT_FOUND", "写入后文件不可用")
    }

    override suspend fun createDirectory(sessionId: String, path: String): FileEntry = operation {
        val normalized = mutation(path)
        val current = namespace(sessionId)
        val target = route(current, normalized)
        requireWritable(sessionId, current, normalized)
        when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> privateBackend(current).let { backend -> backend.createDirectory(target.path).also { backend.confirmCreated() } }
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.createDirectory(sessionId, target.path) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.createDirectory(sessionId, it) }
            LinuxSessionPathKind.ROOTFS -> rootfs.createDirectory(target.path)
            LinuxSessionPathKind.MOUNTS -> fail("INVALID_PATH", "不能创建挂载根目录")
        }
        entry(sessionId, normalized) ?: fail("NOT_FOUND", "创建后目录不可用")
    }

    override suspend fun delete(sessionId: String, path: String, expectedSha256: String?, irreversible: Boolean) = operation {
        val normalized = mutation(path)
        val current = namespace(sessionId)
        val target = route(current, normalized)
        requireWritable(sessionId, current, normalized)
        when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> {
                val entry = workspace.getByMaterializedPath(current.root.uuid, target.path) ?: fail("NOT_FOUND", "文件不存在")
                if (expectedSha256 != null) workspace.moveToRecycleBinIfHash(current.root.uuid, entry.uuid, expectedSha256)
                else workspace.moveToRecycleBin(current.root.uuid, entry.uuid)
            }
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.delete(sessionId, target.path, expectedSha256, irreversible) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.delete(sessionId, it, expectedSha256, irreversible) }
            LinuxSessionPathKind.ROOTFS -> rootfs.delete(target.path, expectedSha256)
            LinuxSessionPathKind.MOUNTS -> fail("INVALID_PATH", "不能删除挂载根目录")
        }
    }

    override suspend fun backupBeforeMove(sessionId: String, path: String): String = operation {
        val current = namespace(sessionId)
        val normalized = mutation(path)
        val target = route(current, normalized)
        requireWritable(sessionId, current, normalized)
        when (target.kind) {
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.backupBeforeMove(sessionId, target.path) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.backupBeforeMove(sessionId, it) }
            else -> "linux-session:$sessionId"
        }
    }

    override suspend fun completeMoveBackup(sessionId: String, backupId: String) {
        if (backupId == "linux-session:$sessionId") return
        withOriginalBindings(namespace(sessionId)) { original.completeMoveBackup(sessionId, backupId) }
    }

    override suspend fun version(sessionId: String, path: String, hash: String): ByteArray? = operation {
        val normalized = LinuxSessionPaths.normalize(path)
        val current = namespace(sessionId)
        val target = route(current, normalized)
        when (target.kind) {
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.version(sessionId, target.path, hash) }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.version(sessionId, it, hash) }
            else -> synchronized(versions) { versions[versionKey(current, normalized, hash)]?.copyOf() }
        }
    }

    override fun backend(sessionId: String, mountIds: Map<String, String>): ExternalFolderBackend = object : ExternalFolderBackend {
        private suspend fun <T> bound(block: suspend () -> T): T = if (mountIds.isEmpty()) block() else withContext(ExternalMountOperationBindings(mountIds)) { block() }
        override suspend fun stat(path: String) = bound { entry(sessionId, path)?.let { ExternalFolderEntry(it.materializedPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash, it.uuid) } }
        override suspend fun list(path: String) = bound { this@LinuxSessionExternalWorkspaceAccess.list(sessionId, path) }
        override suspend fun read(path: String, maxBytes: Long) = bound { this@LinuxSessionExternalWorkspaceAccess.read(sessionId, path, maxBytes) }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) = bound {
            this@LinuxSessionExternalWorkspaceAccess.write(sessionId, path, content, expectedSha256, createOnly).let { ExternalFolderEntry(it.materializedPath, it.name, false, it.sizeBytes, it.updatedAt, it.hash, it.uuid) }
        }
        override suspend fun createDirectory(path: String) = bound {
            this@LinuxSessionExternalWorkspaceAccess.createDirectory(sessionId, path).let { ExternalFolderEntry(it.materializedPath, it.name, true, it.sizeBytes, it.updatedAt, it.hash, it.uuid) }
        }
        override suspend fun delete(path: String, expectedSha256: String?) = bound { this@LinuxSessionExternalWorkspaceAccess.delete(sessionId, path, expectedSha256, false) }
    }

    /** 与审批解析器共用，确保别名存在且来源不为 SAF。 */
    suspend fun validatePath(sessionId: String, path: String): String = operation {
        val normalized = LinuxSessionPaths.normalize(path)
        val current = namespace(sessionId)
        val target = route(current, normalized)
        if (target.kind == LinuxSessionPathKind.EXTERNAL) checkedExternal(sessionId, target.path)
        normalized
    }

    /** 审批与执行使用同一 Linux 路径元组和命名空间身份，不先转换成其它目标。 */
    suspend fun executionTarget(sessionId: String, tool: ProtocolTool, args: JsonObject): String? = operation {
        val normalized = LinuxSessionPaths.normalizeArguments(tool.function.name, args)
        val current = namespace(sessionId)
        val resolved = mutableMapOf<String, String>()
        for (key in listOf("uuid", "parentUuid", "newParentUuid")) {
            val uuid = (normalized[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (uuid.isNotEmpty()) resolved[uuid] = resolveUuid(sessionId, uuid) ?: return@operation null
        }
        val target = ToolExecutionTargets.extract(tool, normalized.toString(), resolved, usesLinux = true) ?: return@operation null
        val parts = Json.parseToJsonElement(target) as? JsonArray ?: return@operation null
        val metadata = parts.lastOrNull() as? JsonObject
        val tuple = if (metadata == null) parts.toList() else parts.dropLast(1)
        if ((tuple.firstOrNull() as? JsonPrimitive)?.content !in setOf("path", "move")) return@operation target
        val paths = tuple.drop(1).map { (it as? JsonPrimitive)?.content ?: return@operation null }
        paths.forEach { validatePath(sessionId, it) }
        val ids = visible(sessionId).associate { it.alias to it.id } + ("" to current.mount.id)
        val aliases = paths.map(LinuxSessionPaths::aliasOf).toSet() + ""
        val rootfsMutation = ToolExecutionTargets.isPathMutation(tool.function.name) && paths.any { route(current, it).kind == LinuxSessionPathKind.ROOTFS }
        val skillMutation = ToolExecutionTargets.isPathMutation(tool.function.name) && paths.any(::isSkillsPath)
        JsonArray(tuple + JsonObject(metadata.orEmpty() + buildMap {
            put("linuxMode", JsonPrimitive(true))
            put("mountIds", JsonObject(aliases.sorted().associateWith { JsonPrimitive(ids[it] ?: return@operation null) }))
            if (rootfsMutation) put("linuxRootfs", JsonPrimitive(true))
            if (skillMutation) put("linuxSkills", JsonPrimitive(true))
            if (rootfsMutation || skillMutation) put("oneShot", JsonPrimitive(true))
        })).toString()
    }

    internal suspend fun invocationArguments(sessionId: String, tool: String, args: JsonObject): JsonObject = operation {
        val values = LinuxSessionPaths.normalizeArguments(tool, args).toMutableMap()
        for (key in listOf("uuid", "parentUuid", "newParentUuid")) {
            val uuid = (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (uuid.isNotEmpty()) {
                val path = resolveUuid(sessionId, uuid) ?: fail("NOT_FOUND", "条目不属于当前 Linux 会话")
                values[key] = JsonPrimitive(entry(sessionId, path)?.uuid ?: fail("NOT_FOUND", "条目不存在"))
            }
        }
        JsonObject(values)
    }

    /** 私有历史由 Room 文件版本表提供，不能退化成挂载的进程内快照。 */
    internal suspend fun diffPrivate(sessionId: String, uuid: String, basisHash: String?): DiffResult? = operation {
        val current = namespace(sessionId)
        val path = resolveUuid(sessionId, uuid) ?: fail("NOT_FOUND", "条目不属于当前 Linux 会话")
        val target = route(current, path)
        if (target.kind != LinuxSessionPathKind.PRIVATE) return@operation null
        val entry = workspace.getByMaterializedPath(current.root.uuid, target.path) ?: fail("NOT_FOUND", "私有文件不存在")
        files.diffFile(current.root.uuid, entry.uuid, basisHash).copy(uuid = uuid)
    }

    /** 同一私有树内移动沿用 Room 身份和版本链；跨来源移动仍使用校验与补偿传输。 */
    internal suspend fun movePrivate(sessionId: String, source: String, destination: String, expectedHash: String?): com.promenar.nexara.data.model.ToolResult? = operation {
        val current = namespace(sessionId)
        val from = route(current, mutation(source))
        val to = route(current, mutation(destination))
        requireWritable(sessionId, current, source)
        requireWritable(sessionId, current, destination)
        if (from.kind != LinuxSessionPathKind.PRIVATE || to.kind != LinuxSessionPathKind.PRIVATE) return@operation null
        val target = workspace.getByMaterializedPath(current.root.uuid, from.path) ?: fail("NOT_FOUND", "源条目不存在")
        if (expectedHash != null && (target.isDirectory || target.hash != expectedHash)) fail("CONFLICT", "expected_sha256 与来源不一致")
        if (from.path == to.path) return@operation com.promenar.nexara.data.model.ToolResult("linux_private_move", "目标路径未变化：$destination")
        if (workspace.getByMaterializedPath(current.root.uuid, to.path) != null) fail("NAME_EXISTS", "目标路径已存在，未执行移动")
        if (target.isDirectory && to.path.startsWith(from.path.trimEnd('/') + "/")) fail("INVALID_PATH", "不能移动到自身子目录")
        val parent = workspace.getByMaterializedPath(current.root.uuid, parent(to.path)) ?: fail("NOT_FOUND", "目标目录不存在")
        if (!parent.isDirectory) fail("INVALID_PATH", "目标父条目不是目录")
        val name = to.path.substringAfterLast('/')
        when (val renamed = workspace.relocate(current.root.uuid, target.uuid, parent.uuid, name, expectedName = target.name, expectedHash = expectedHash)) {
            is RenameResult.Success -> renamed.affectedTargets.forEach { affected ->
                try { indexEvents.publish(FileIndexEvent.Changed(current.root.uuid, affected.fileUuid, affected.targetHash, affected.targetEpoch)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* 待处理索引协调器负责补偿已提交的重命名。 */ }
            }
            is RenameResult.Conflict -> fail("CONFLICT", "来源名称已改变，请重新定位")
            RenameResult.NotFound -> fail("NOT_FOUND", "来源已不存在")
        }
        val moved = entry(sessionId, destination) ?: fail("NOT_FOUND", "移动后条目不可用")
        com.promenar.nexara.data.model.ToolResult("linux_private_move", "已完成：$destination [uuid=${moved.uuid}] [hash=${moved.hash}]")
    }

    /** MIME 只选择文本类型，UTF-8、NUL 与预算门禁继续核验真实字节。 */
    internal suspend fun textMime(sessionId: String, path: String): String? = operation {
        when (route(namespace(sessionId), path).kind) {
            LinuxSessionPathKind.PRIVATE, LinuxSessionPathKind.ROOTFS -> "text/plain"
            else -> null
        }
    }

    private suspend fun namespace(sessionId: String): Namespace {
        if (sessionId.isBlank()) fail("NOT_FOUND", "会话不存在")
        val root = sessions?.getById(sessionId)?.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
            ?: workspace.ensureSessionRoot(sessionId)
        val current = sessionWorkspace(sessionId)
        val rootIdentity = rootfs.stat("/")?.identity ?: fail("UNAVAILABLE", "无法绑定 Linux 根目录身份")
        val mainIdentity = when (current) {
            SessionWorkspace.Private -> "private"
            is SessionWorkspace.Directory -> "${current.workspace.id}:${current.workspace.kind}:${current.workspace.location}:${current.root.path}:${directoryIdentity(current.root)}"
            is SessionWorkspace.Unavailable -> "unavailable:${current.workspaceId}"
        }
        val identity = Sha256Utils.hash("$sessionId|${root.uuid}|${root.hash}|${root.physicalRootPath}|$mainIdentity|$rootIdentity")
        val mount = ExternalMount("linux:$identity", "", "linux_session", sessionId, sessionId, 0)
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound != null && bound[""] != mount.id) fail("CONFLICT", "Linux 会话目录或工作区身份已改变，请重新审批")
        return Namespace(root, current, mount)
    }

    private fun route(current: Namespace, path: String): LinuxSessionPath = LinuxSessionPaths.route(path, current.workspace == SessionWorkspace.Private).also {
        if (it.kind == LinuxSessionPathKind.WORKSPACE && current.workspace is SessionWorkspace.Unavailable) fail("UNAVAILABLE", "会话主工作区不可用，请检查目录与授权")
    }

    private suspend fun requireWritable(sessionId: String, current: Namespace, path: String) {
        com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = current.root.uuid,
            path = current.root.physicalRootPath)
        val target = route(current, path)
        (current.workspace as? SessionWorkspace.Directory)?.let {
            com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = it.root.canonicalPath)
            if (target.kind == LinuxSessionPathKind.WORKSPACE) {
                com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = File(it.root, target.path.removePrefix("/")).canonicalPath)
            }
        }
        if (target.kind == LinuxSessionPathKind.ROOTFS) {
            (rootfs as? LinuxSessionRootfsBackend)?.requireWritable(target.path)
        }
        if (target.kind == LinuxSessionPathKind.EXTERNAL) {
            val parsed = ExternalMountPaths.parse(target.path)
            visible(sessionId).singleOrNull { it.alias == parsed?.first && it.sourceType == "real_path" }?.let {
                com.promenar.nexara.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = File(it.sourceId, parsed!!.second.removePrefix("/")).canonicalPath)
            }
        }
    }

    private suspend fun checkedExternal(sessionId: String, path: String): String {
        val alias = ExternalMountPaths.parse(path)?.first ?: fail("INVALID_PATH", "挂载路径无效")
        val mount = visible(sessionId).singleOrNull { it.alias == alias } ?: fail("NOT_FOUND", "挂载不存在或在 Linux 模式不可见")
        mount.unavailableReason?.let { fail("UNAVAILABLE", "挂载当前不可用") }
        return path
    }

    private suspend fun stat(sessionId: String, current: Namespace, path: String): ExternalFolderEntry? {
        val target = route(current, path)
        return when (target.kind) {
            LinuxSessionPathKind.PRIVATE -> privateBackend(current).stat(target.path)?.copy(path = path)
            LinuxSessionPathKind.WORKSPACE -> withOriginalBindings(current) { original.entry(sessionId, target.path) }?.let {
                ExternalFolderEntry(path, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            LinuxSessionPathKind.EXTERNAL -> withExternal(current, sessionId, target.path) { original.entry(sessionId, it) }?.let {
                ExternalFolderEntry(path, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash)
            }
            LinuxSessionPathKind.MOUNTS -> directory(path)
            LinuxSessionPathKind.ROOTFS -> if (path == "/home") directory(path) else rootfs.stat(path)
        }
    }

    private suspend fun writePrivate(sessionId: String, current: Namespace, path: String, bytes: ByteArray, expected: String?, createOnly: Boolean) {
        val existing = workspace.getByMaterializedPath(current.root.uuid, path)
        if (createOnly) {
            if (existing != null) fail("NAME_EXISTS", "同名文件已存在")
            val parent = workspace.getByMaterializedPath(current.root.uuid, parent(path)) ?: fail("NOT_FOUND", "父目录不存在")
            val mime = try {
                WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), "text/plain", bytes, WorkspaceTextOperation.WRITE)
                "text/plain"
            } catch (_: WorkspaceTextPolicyException) { externalSnapshotMime(path.substringAfterLast('/')) }
            val entry = workspace.createFileInWorkspaceStreaming(current.root.uuid, UUID.randomUUID().toString(), path.substringAfterLast('/'), mime,
                parent.uuid, path, ExternalTreeTransfer.MAX_BYTES) { it.write(bytes) }
            workspace.confirmCreatedEntry(current.root.uuid, entry.uuid)
            if (mime in com.promenar.nexara.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) {
                indexEvents.publish(FileIndexEvent.Changed(current.root.uuid, entry.uuid, entry.hash, entry.updatedAt.coerceAtLeast(1)))
            }
            return
        }
        if (existing == null) fail("NOT_FOUND", "文件不存在，请先创建")
        if (existing.isDirectory) fail("IS_DIRECTORY", "目标是目录")
        if (expected == null) fail("CONFLICT", "写入私有文件必须提供当前 sha256")
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) fail("INVALID_ENCODING", "私有文件覆盖写入要求 UTF-8 文本")
        when (files.writeFileAtomicExact(current.root.uuid, existing.uuid, text, sessionId, expected)) {
            is WriteResult.Success -> Unit
            is WriteResult.Conflict -> fail("CONFLICT", "文件版本已经改变")
            WriteResult.NotFound -> fail("NOT_FOUND", "文件不存在")
        }
    }

    private fun privateBackend(current: Namespace) = PrivateWorkspaceTransferBackend(workspace, current.root.uuid, indexEvents)
    private suspend fun <T> withExternal(current: Namespace, sessionId: String, path: String, action: suspend (String) -> T): T {
        val checked = checkedExternal(sessionId, path)
        return withOriginalBindings(current) { action(checked) }
    }
    private suspend fun <T> withOriginalBindings(current: Namespace, action: suspend () -> T): T {
        val ids = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds.orEmpty().toMutableMap()
        ids.keys.filter(String::isNotEmpty).forEach { alias -> ids[alias] = originalMountId(ids.getValue(alias)) }
        when (val selected = current.workspace) {
            is SessionWorkspace.Directory -> ids[""] = ExternalMountPaths.workspaceMountId(selected.workspace.id)
            is SessionWorkspace.Unavailable -> ids[""] = ExternalMountPaths.workspaceMountId(selected.workspaceId)
            SessionWorkspace.Private -> ids.remove("")
        }
        if (currentCoroutineContext()[ExternalMountOperationBindings] == null) {
            original.visible(current.mount.sessionId).filter(::allowed).forEach { ids[it.alias] = it.id }
        }
        return withContext(ExternalMountOperationBindings(ids)) { action() }
    }
    private fun fromOriginal(path: String): String = when {
        path == "/skills" || path.startsWith("/skills/") -> LinuxSessionPaths.SKILLS_ROOT + path.removePrefix("/skills")
        path == "/mnt" || path.startsWith("/mnt/") -> path
        else -> LinuxSessionPaths.WORKSPACE_ROOT + path.takeUnless { it == "/" }.orEmpty()
    }
    private fun isSkillsPath(path: String): Boolean {
        if (path == LinuxSessionPaths.SKILLS_ROOT || path.startsWith("${LinuxSessionPaths.SKILLS_ROOT}/")) return true
        val relative = when {
            path == "/workspace" || path.startsWith("/workspace/") -> path.removePrefix("/workspace")
            path == "/home/session" || path.startsWith("/home/session/") -> path.removePrefix("/home/session")
            else -> return false
        }.lowercase(java.util.Locale.ROOT)
        return relative == "/.agents" || relative == "/.agents/skills" || relative.startsWith("/.agents/skills/")
    }
    private fun mutation(path: String): String = LinuxSessionPaths.normalize(path).also {
        if (it in setOf("/", "/home", "/home/session", "/workspace", "/mnt") || it.startsWith("/mnt/") && it.count { c -> c == '/' } == 2) {
            fail("INVALID_PATH", "不能修改 Linux 或挂载根目录")
        }
    }
    private fun remember(current: Namespace, path: String, bytes: ByteArray) {
        if (bytes.size > VERSION_BYTES) return
        synchronized(versions) {
            versions[versionKey(current, path, Sha256Utils.hash(bytes))] = bytes.copyOf()
            while (versions.size > 64 || versions.values.sumOf { it.size.toLong() } > 16L * VERSION_BYTES) versions.remove(versions.keys.first())
        }
    }
    private fun versionKey(current: Namespace, path: String, hash: String): String {
        val target = route(current, path)
        return if (target.kind == LinuxSessionPathKind.PRIVATE) "${current.root.uuid}|${current.root.hash}|${target.path}|$hash"
        else "${current.mount.id}|$path|$hash"
    }
    private fun originalMountId(id: String): String = if (id.startsWith("linux-mount:")) {
        try { String(Base64.getUrlDecoder().decode(id.removePrefix("linux-mount:").substringBefore(':')), Charsets.UTF_8) }
        catch (_: IllegalArgumentException) { fail("CONFLICT", "挂载身份格式无效") }
    } else id
    private fun directoryIdentity(directory: File): String {
        val attributes = try { Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: Exception) { fail("UNAVAILABLE", "Linux 目录来源不可用或缺少访问权限") }
        if (!attributes.isDirectory || attributes.isSymbolicLink) fail("INVALID_PATH", "Linux 目录来源不能是符号链接")
        // 目录工作区可能位于共享存储（FUSE 不支持用户属性），不向用户目录写 nonce；与工作区 id、路径一起只取 inode，不含设备号。
        return com.promenar.nexara.data.repository.PersistentFileIdentity.inode(attributes.fileKey())
    }
    private fun allowed(mount: ExternalMount) = mount.sourceType == "real_path" || mount.isUserSkills
    private fun directory(path: String) = ExternalFolderEntry(path, path.substringAfterLast('/'), true, 0, 0)
    private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
    private fun child(path: String, name: String) = path.trimEnd('/') + "/$name"
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
    companion object { private const val VERSION_BYTES = 1024L * 1024 }
}
