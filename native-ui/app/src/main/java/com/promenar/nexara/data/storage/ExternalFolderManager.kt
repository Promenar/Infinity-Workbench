package com.promenar.nexara.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.promenar.nexara.data.local.db.dao.ExternalMountDao
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/** 挂载独立于私有工作区的日志与文件身份；所有外部 I/O 均在 IO 调度器执行。 */
class ExternalFolderManager(
    private val context: Context,
    private val dao: ExternalMountDao,
    private val workspace: IWorkspaceRepository,
    private val indexEvents: FileIndexEventSink,
    /** 为 null 时不支持自选目录工作区，所有会话都使用私有工作区。 */
    private val workspaces: SessionWorkspaceResolver? = null,
    /** 测试替身；返回 null 时使用真实来源。 */
    private val backendOverride: ((ExternalMount) -> ExternalFolderBackend?)? = null,
) : ExternalWorkspaceAccess {
    private val mutex = Mutex()
    private val recycle = ExternalRecycleStore(File(context.noBackupFilesDir, "external-recycle"))
    private val versions = File(context.noBackupFilesDir, "external-versions")
    override fun observeMounts(sessionId: String) = dao.observeVisible(sessionId).map { it.map(ExternalMountEntity::model) }
    override suspend fun visible(sessionId: String) = withContext(Dispatchers.IO) {
        val models = dao.visible(sessionId).map(ExternalMountEntity::model)
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound == null) models else models.filter { bound[it.alias] == it.id }
    }
    override suspend fun resolveUuid(sessionId: String, uuid: String) = ExternalMountPaths.uuidPath(uuid, namespace(sessionId))

    override suspend fun workspaceMount(sessionId: String): ExternalMount? = withContext(Dispatchers.IO) {
        val mount = currentWorkspaceMount(sessionId) ?: return@withContext null
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        mount.takeIf { bound == null || bound[it.alias] == it.id }
    }
    private suspend fun currentWorkspaceMount(sessionId: String): ExternalMount? =
        when (val current = workspaces?.resolve(sessionId) ?: return null) {
            SessionWorkspace.Private -> null
            is SessionWorkspace.Directory -> workspaceMount(current.workspace.id, sessionId, current.workspace.createdAt, null)
            is SessionWorkspace.Unavailable -> workspaceMount(current.workspaceId, sessionId, current.workspace?.createdAt ?: 0, current.reason)
        }
    private fun workspaceMount(workspaceId: String, sessionId: String, createdAt: Long, unavailable: String?) = ExternalMount(
        ExternalMountPaths.workspaceMountId(workspaceId), ExternalMountPaths.WORKSPACE_ALIAS, ExternalMountPaths.WORKSPACE_SOURCE,
        workspaceId, sessionId, readOnly = false, createdAt = createdAt, unavailableReason = unavailable,
    )
    /** 可见挂载加当前工作区挂载，用于 uuid 解析与回收区归属。 */
    private suspend fun namespace(sessionId: String) = visible(sessionId) + listOfNotNull(workspaceMount(sessionId))

    override suspend fun addSaf(alias: String, sessionId: String?, treeUri: String, grantFlags: Int, readOnly: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val uri = Uri.parse(treeUri)
            if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) fail("INVALID_PATH", "请选择文件夹授权")
            val mount = newMount(alias, "saf_tree", uri.toString(), sessionId, readOnly)
            checkAlias(mount)
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            takeGrant(uri, grantFlags, readOnly)
            try {
                requireDirectory(sourceBackend(mount).stat("/"))
                dao.insert(mount.entity())
                mount
            } catch (failure: Exception) {
                if (!held) releaseUnused(uri.toString())
                throw failure
            }
        } }

    override suspend fun addRealPath(alias: String, sessionId: String?, source: String, readOnly: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val canonical = RealPathMountSupport.normalizeSource(context, source)
            val mount = newMount(alias, "real_path", canonical, sessionId, readOnly)
            checkAlias(mount)
            requireDirectory(sourceBackend(mount).stat("/"))
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun remove(mountId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val mount = dao.get(mountId) ?: return@withLock
        dao.delete(mountId)
        if (mount.sourceType == "saf_tree") releaseUnused(mount.sourceId)
    } }

    override suspend fun deleteSessionMounts(sessionId: String) = withContext(Dispatchers.IO) {
        if (sessionId.isNotBlank()) dao.all().filter { it.scopeKey == sessionId }.forEach { remove(it.id) }
    }

    override suspend fun reauthorize(mountId: String, treeUri: String, grantFlags: Int) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = dao.get(mountId) ?: fail("NOT_FOUND", "挂载点不存在")
        if (old.sourceType != "saf_tree") fail("UNSUPPORTED", "请在系统设置中授予所有文件访问权限")
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri) || uri.scheme != "content") fail("INVALID_PATH", "请选择文件夹")
        // 不允许重新授权悄悄把既有审批路径指向另一来源。
        if (treeUri != old.sourceId) fail("CONFLICT", "请选择原挂载文件夹；不同来源请先移除挂载并使用新别名")
        takeGrant(uri, grantFlags, old.readOnly)
        requireDirectory(sourceBackend(old.model()).stat("/"))
        dao.update(old.copy(unavailableReason = null))
    } }

    override suspend fun refresh(sessionId: String) = withContext(Dispatchers.IO) {
        visible(sessionId).forEach { mount ->
            try { access(sessionId, "/mnt/${mount.alias}") { _, backend, _ -> requireDirectory(backend.stat("/")) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 可用性已由 access 写入，不把单个离线挂载扩散到整个面板。 */ }
        }
    }

    override suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry> = withContext(Dispatchers.IO) {
        if (virtualPath == "/mnt") return@withContext visible(sessionId).map {
            ExternalFolderEntry("/mnt/${it.alias}", it.alias, true, 0, it.createdAt)
        }
        access(sessionId, virtualPath) { mount, backend, relative -> visibleChildren(mount, relative, backend.list(relative)) }
    }

    override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        access(sessionId, virtualPath) { mount, backend, path -> backend.read(path, maxBytes).also { remember(mount.id, path, it) } }
    }

    override suspend fun entry(sessionId: String, path: String): FileEntry? = withContext(Dispatchers.IO) {
        if (path == "/mnt") return@withContext FileEntry(
            ExternalMountPaths.ROOT_UUID, "", null, "mnt", "", isDirectory = true,
            physicalRootPath = "", materializedPath = "/mnt", createdAt = 0, updatedAt = 0,
        )
        access(sessionId, path) { mount, backend, relative ->
            val stat = backend.stat(relative) ?: return@access null
            val hash = stat.sha256 ?: if (!stat.isDirectory && stat.size <= 1024L * 1024) {
                try { externalSha256(backend.read(relative, 1024L * 1024).also { remember(mount.id, relative, it) }) }
                catch (failure: ExternalFolderException) { if (failure.code == "TOO_LARGE") "" else throw failure }
            } else ""
            val parent = when {
                relative != "/" -> ExternalMountPaths.uuid(mount, relative.substringBeforeLast('/').ifEmpty { "/" })
                mount.isWorkspaceRoot -> null
                else -> ExternalMountPaths.ROOT_UUID
            }
            FileEntry(
                uuid = ExternalMountPaths.uuid(mount, relative), parentUuid = parent,
                name = if (relative == "/") mount.alias else stat.name, hash = hash,
                sizeBytes = stat.size, isDirectory = stat.isDirectory, physicalRootPath = "",
                materializedPath = path, createdAt = mount.createdAt, updatedAt = stat.lastModified,
            )
        }
    }

    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry =
        withContext(Dispatchers.IO) {
            val bound = writeReceipt(sessionId, path, bytes, expectedSha256, createOnly)
            val receipt = bound.receipt
            val mount = bound.mount
            val relative = bound.relativePath
            FileEntry(
                ExternalMountPaths.uuid(mount, relative), parentUuid = ExternalMountPaths.uuid(mount, relative.substringBeforeLast('/').ifEmpty { "/" }),
                name = receipt.name, hash = externalSha256(bytes), sizeBytes = bytes.size.toLong(),
                physicalRootPath = "", materializedPath = path, createdAt = mount.createdAt, updatedAt = receipt.lastModified,
            )
        }

    private data class BoundWrite(val mount: ExternalMount, val relativePath: String, val receipt: ExternalFolderEntry)
    private suspend fun writeReceipt(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean, bindings: MutableMap<String, String>? = null): BoundWrite =
        withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, true, bindings) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能覆盖挂载根目录")
                val receipt = backend.write(relative, bytes, expectedSha256, createOnly).copy(path = path, sha256 = externalSha256(bytes))
                remember(mount.id, relative, bytes)
                BoundWrite(mount, relative, receipt)
            }
        } }

    override suspend fun createDirectory(sessionId: String, path: String): FileEntry = withContext(Dispatchers.IO) { mutex.withLock {
        access(sessionId, path, true) { _, backend, relative ->
            if (relative == "/") fail("INVALID_PATH", "不能创建挂载根目录")
            backend.createDirectory(relative)
        }
        entry(sessionId, path) ?: fail("NOT_FOUND", "创建后目录不可用")
    } }

    override suspend fun delete(sessionId: String, path: String, expectedSha256: String?, irreversible: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, true) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能删除挂载根目录，请在管理界面移除挂载")
                var tree: ExternalTree? = null
                try {
                    tree = ExternalTreeTransfer.capture(backend, relative)
                    if (expectedSha256 != null && tree.nodes.singleOrNull()?.let { externalSha256(it.bytes) } != expectedSha256) {
                        fail("CONFLICT", "expected_sha256 与当前文件版本不一致")
                    }
                    recycle.save(mount.id, path, tree)
                } catch (failure: ExternalFolderException) {
                    if (failure.code !in setOf("RECYCLE_LIMIT", "TOO_LARGE") || !irreversible) throw failure
                }
                if (tree != null && ExternalTreeTransfer.capture(backend, relative).digest() != tree.digest()) {
                    fail("CONFLICT", "回收备份期间来源发生变化，删除已取消")
                }
                val backedUpHash = tree?.nodes?.singleOrNull()?.takeUnless { it.isDirectory }?.let { externalSha256(it.bytes) }
                backend.delete(relative, expectedSha256 ?: backedUpHash)
            }
        } }

    override suspend fun backupBeforeMove(sessionId: String, path: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        access(sessionId, path, true) { mount, backend, relative ->
            if (relative == "/") fail("INVALID_PATH", "不能移动挂载根目录")
            recycle.save(mount.id, path, ExternalTreeTransfer.capture(backend, relative)).id
        }
    } }

    override suspend fun completeMoveBackup(sessionId: String, backupId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        if (recycleItemsUnlocked(sessionId).none { it.id == backupId }) fail("NOT_FOUND", "移动补偿副本不属于当前会话")
        recycle.remove(backupId)
    } }

    override suspend fun recycleItems(sessionId: String) = withContext(Dispatchers.IO) {
        val ids = namespace(sessionId).map { it.id }.toSet()
        mutex.withLock { recycle.items().filter { it.mountId in ids } }
    }
    override suspend fun restore(sessionId: String, recycleId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val item = recycleItemsUnlocked(sessionId).firstOrNull { it.id == recycleId } ?: fail("NOT_FOUND", "回收副本已过期或不属于当前会话")
        access(sessionId, item.originalPath, true) { _, backend, relative ->
            ExternalTreeTransfer.copy(recycle.load(item.id), backend, relative)
            recycle.remove(item.id)
        }
    } }
    private suspend fun recycleItemsUnlocked(sessionId: String): List<ExternalRecycleItem> {
        val ids = namespace(sessionId).map { it.id }.toSet()
        return recycle.items().filter { it.mountId in ids }
    }

    override suspend fun importSnapshot(sessionId: String, workspaceRootUuid: String, virtualPath: String): String = withContext(Dispatchers.IO) {
        val (mount, relative) = resolve(sessionId, virtualPath)
        withContext(ExternalMountOperationBindings(mapOf(mount.alias to mount.id))) {
            val original = entry(sessionId, virtualPath) ?: fail("NOT_FOUND", "源文件不可用")
            if (original.isDirectory) fail("IS_DIRECTORY", "请选择单个文件加入知识库")
            val mime = externalSnapshotMime(original.name)
            if (mime !in com.promenar.nexara.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) fail("UNSUPPORTED", "知识库支持 TXT、Markdown、CSV、JSON、HTML、PDF 和 DOCX")
            val bytes = read(sessionId, virtualPath, 8L * 1024 * 1024)
            val after = entry(sessionId, virtualPath) ?: fail("UNAVAILABLE", "读取后来源不可用")
            if (after.uuid != original.uuid || after.updatedAt != original.updatedAt || after.sizeBytes != original.sizeBytes || after.hash.isNotEmpty() && after.hash != externalSha256(bytes)) fail("CONFLICT", "读取期间源文件发生变化，请重试")
            val uuid = UUID.randomUUID().toString()
            val safeName = "${mount.alias.ifEmpty { "workspace" }}-${uuid.take(8)}-${original.name}"
            val created = workspace.createFileInWorkspaceStreaming(
                workspaceRootUuid, uuid, safeName, mime,
                workspaceRootUuid, "/$safeName", 8L * 1024 * 1024,
            ) { it.write(bytes) }
            try {
                indexEvents.publish(FileIndexEvent.Changed(workspaceRootUuid, uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
                dao.putSnapshot(ExternalSnapshotEntity(mount.id, relative, workspaceRootUuid, uuid, after.updatedAt, externalSha256(bytes), System.currentTimeMillis()))
                workspace.confirmCreatedEntry(workspaceRootUuid, uuid)
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try { workspace.rollbackCreatedEntry(workspaceRootUuid, uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
            uuid
        }
    }
    override suspend fun snapshotChanged(sessionId: String, virtualPath: String): Boolean = withContext(Dispatchers.IO) {
        val (mount, relative) = resolve(sessionId, virtualPath)
        val snapshots = dao.snapshots(mount.id, relative)
        if (snapshots.isEmpty()) false else snapshots.any { it.sourceModified != entry(sessionId, virtualPath)?.updatedAt }
    }

    override fun backend(sessionId: String, mountIds: Map<String, String>): ExternalFolderBackend = object : ExternalFolderBackend {
        private val bindings = mountIds.toMutableMap()
        override suspend fun stat(path: String) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { _, backend, relative -> backend.stat(relative)?.copy(path = path) }
        }
        override suspend fun list(path: String) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { mount, backend, relative -> visibleChildren(mount, relative, backend.list(relative)) }
        }
        override suspend fun read(path: String, maxBytes: Long) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { mount, backend, relative -> backend.read(relative, maxBytes).also { remember(mount.id, relative, it) } }
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) = writeReceipt(sessionId, path, content, expectedSha256, createOnly, bindings).receipt
        override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, true, bindings) { _, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能创建挂载根目录")
                backend.createDirectory(relative).copy(path = path)
            }
        } }
        override suspend fun delete(path: String, expectedSha256: String?) = withContext(Dispatchers.IO) {
            access(sessionId, path, true, bindings) { _, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能移动挂载根目录")
                backend.delete(relative, expectedSha256)
            }
        }
    }

    override suspend fun version(sessionId: String, path: String, hash: String): ByteArray? = withContext(Dispatchers.IO) {
        val (mount, relative) = resolve(sessionId, path)
        if (!Regex("[0-9a-f]{64}").matches(hash)) null else File(versions, versionKey(mount.id, relative, hash)).takeIf { it.isFile }?.readBytes()
    }
    private fun versionKey(mountId: String, path: String, hash: String) = externalSha256("$mountId\u0000$path\u0000$hash".toByteArray(Charsets.UTF_8))
    private fun remember(mountId: String, path: String, bytes: ByteArray) {
        if (bytes.size > 1024 * 1024) return
        synchronized(versions.path.intern()) {
            versions.mkdirs()
            val file = File(versions, versionKey(mountId, path, externalSha256(bytes)))
            if (!file.exists()) file.writeBytes(bytes)
            var total = versions.listFiles().orEmpty().sumOf { it.length() }
            versions.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
                if (total > 32L * 1024 * 1024) { total -= it.length(); it.delete() }
            }
        }
    }

    /** 列表路径改写为虚拟路径；工作区根下的 `mnt` 被虚拟挂载目录遮蔽，不对文件工具列出。 */
    private fun visibleChildren(mount: ExternalMount, relative: String, children: List<ExternalFolderEntry>) = children
        .filterNot { mount.isWorkspaceRoot && relative == "/" && it.name == "mnt" }
        .map { it.copy(path = ExternalMountPaths.virtualPath(mount, it.path)) }

    private suspend fun resolve(sessionId: String, path: String): Pair<ExternalMount, String> {
        val parsed = ExternalMountPaths.parse(path)
        if (parsed == null) {
            val normalized = ExternalFolderPaths.normalize(path)
            val root = currentWorkspaceMount(sessionId) ?: fail("INVALID_PATH", "不是挂载路径")
            if (normalized == "/mnt") fail("INVALID_PATH", "/mnt 为挂载保留目录")
            val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
            if (bound != null && bound[root.alias] != root.id) fail("CONFLICT", "操作期间会话工作区发生变化，请重新读取目标")
            return root to normalized
        }
        val (alias, relative) = parsed
        val mount = dao.visible(sessionId).singleOrNull { it.alias == alias }?.model() ?: fail("NOT_FOUND", "挂载点不属于当前会话或已被移除")
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound != null && bound[alias] != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，请重新读取目标")
        return mount to relative
    }
    private suspend fun <T> access(sessionId: String, path: String, write: Boolean = false, bindings: MutableMap<String, String>? = null, block: suspend (ExternalMount, ExternalFolderBackend, String) -> T): T {
        val (mount, relative) = resolve(sessionId, path)
        if (bindings != null) synchronized(bindings) {
            val previous = bindings.putIfAbsent(mount.alias, mount.id)
            if (previous != null && previous != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，已取消后续访问")
        }
        if (write && mount.readOnly) fail("READ_ONLY", "挂载点为只读，拒绝写入")
        try {
            val result = block(mount, sourceBackend(mount), relative)
            if (mount.unavailableReason != null && !mount.isWorkspaceRoot) dao.update(mount.copy(unavailableReason = null).entity())
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val unavailable = failure is SecurityException || failure is ExternalFolderException && failure.code == "UNAVAILABLE"
            if (unavailable && mount.isWorkspaceRoot) {
                val reason = (failure as? ExternalFolderException)?.message ?: "目录访问被拒绝"
                fail("UNAVAILABLE", "当前工作区不可用（$reason），不会改用会话私有工作区；请检查目录与授权，或在会话设置中更换工作区")
            }
            if (unavailable) {
                dao.update(mount.copy(unavailableReason = "授权已失效、提供商不可用或存储已卸载，请重新授权").entity())
                fail("UNAVAILABLE", "挂载不可用，请重新授权或检查存储连接")
            }
            throw failure
        }
    }
    private suspend fun sourceBackend(mount: ExternalMount): ExternalFolderBackend {
        if (mount.sourceType == ExternalMountPaths.WORKSPACE_SOURCE) {
            val resolver = workspaces ?: fail("UNAVAILABLE", "工作区不可用")
            return when (val current = resolver.resolveWorkspace(mount.sourceId)) {
                is SessionWorkspace.Directory -> backendOverride?.invoke(mount) ?: resolver.backend(current)
                is SessionWorkspace.Unavailable -> fail("UNAVAILABLE", current.reason)
                SessionWorkspace.Private -> fail("UNAVAILABLE", "工作区不可用")
            }
        }
        backendOverride?.invoke(mount)?.let { return it }
        return when (mount.sourceType) {
            "saf_tree" -> {
                val uri = Uri.parse(mount.sourceId)
                val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                if (grant?.isReadPermission != true || !mount.readOnly && !grant.isWritePermission) fail("UNAVAILABLE", "文件夹授权已失效")
                SafExternalFolderBackend(context.contentResolver, uri)
            }
            "real_path" -> RealPathMountSupport.createBackend(context, mount.sourceId)
            else -> fail("UNSUPPORTED", "不支持的挂载来源")
        }
    }
    private fun takeGrant(uri: Uri, flags: Int, readOnly: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags and read == 0 || !readOnly && flags and write == 0) fail("READ_ONLY", "提供商未授予所需的读写权限")
        val permissions = context.contentResolver.persistedUriPermissions
        if (permissions.size >= 512 && permissions.none { it.uri == uri }) fail("GRANT_LIMIT", "持久文件夹授权已达上限，请移除不再使用的挂载")
        context.contentResolver.takePersistableUriPermission(uri, flags and (read or write))
    }
    private suspend fun releaseUnused(source: String) {
        if (dao.all().any { it.sourceType == "saf_tree" && it.sourceId == source }) return
        val uri = Uri.parse(source)
        val held = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try { context.contentResolver.releasePersistableUriPermission(uri, flags) } catch (_: SecurityException) { /* 已撤销的授权无需重复释放。 */ }
    }
    private suspend fun checkAlias(mount: ExternalMount) {
        if (dao.all().any { ExternalMountPaths.conflicts(mount, it.model()) }) fail("CONFLICT", "别名在会话可见范围内已存在")
    }
    private fun newMount(alias: String, type: String, source: String, session: String?, readOnly: Boolean) = ExternalMount(
        UUID.randomUUID().toString(), ExternalMountPaths.validateAlias(alias), type, source,
        session?.takeIf(String::isNotBlank), readOnly, System.currentTimeMillis(),
    )
    private fun requireDirectory(entry: ExternalFolderEntry?) {
        if (entry?.isDirectory != true) fail("UNAVAILABLE", "来源文件夹不可用")
    }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
}

private fun ExternalMountEntity.model() = ExternalMount(id, alias, sourceType, sourceId, scopeKey.takeIf(String::isNotEmpty), readOnly, createdAt, unavailableReason)
private fun ExternalMount.entity() = ExternalMountEntity(id, alias, sourceType, sourceId, sessionId.orEmpty(), readOnly, createdAt, unavailableReason)

internal fun externalSnapshotMime(name: String): String = com.promenar.nexara.share.core.AndroidSafImportRequestFactory.resolveMime(null, name)
