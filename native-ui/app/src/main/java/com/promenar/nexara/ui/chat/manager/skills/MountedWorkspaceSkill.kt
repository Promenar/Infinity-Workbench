package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.ExternalTreeTransfer
import com.promenar.nexara.data.storage.externalSnapshotMime
import com.promenar.nexara.domain.repository.*
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.infra.util.MyersDiff
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.registry.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 保留现有工具语义，只在仓库适配层增加虚拟挂载命名空间。 */
class MountedWorkspaceSkill(
    private val original: SkillDefinition,
    private val mounts: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
) : SkillDefinition by original {
    override val description = original.description + " 支持 /mnt/<别名>/... 挂载路径；挂载文件使用 expected_sha256（兼容 expectedHash）校验版本。"
    override val parametersSchema: String = run {
        val schema = Json.parseToJsonElement(original.parametersSchema).jsonObject
        val properties = schema["properties"]?.jsonObject.orEmpty().toMutableMap()
        properties["expected_sha256"] = buildJsonObject { put("type", "string"); put("description", "读取返回的 sha256；与 expectedHash 等价") }
        if (original.id == "delete_file") properties["irreversible"] = buildJsonObject {
            put("type", "boolean"); put("description", "回收区不足时显式永久删除，必须重新 DELETE 审批并显示不可恢复")
        }
        JsonObject(schema.toMutableMap().apply {
            put("properties", JsonObject(properties))
            (schema["required"] as? JsonArray)?.let { put("required", JsonArray(it.filterNot { item -> item.jsonPrimitive.content == "expectedHash" })) }
        }).toString()
    }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val visibleMountIds = mounts.visible(context.sessionId).associate { it.alias to it.id }
            val approvedMountIds = context.approvedExecutionTarget?.let {
                (Json.parseToJsonElement(it) as? JsonArray)?.lastOrNull() as? JsonObject
            }?.get("mountIds")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            if (approvedMountIds.any { (alias, id) -> visibleMountIds[alias] != id }) {
                fail("CONFLICT", "批准的挂载身份已失效，请重新审批")
            }
            withContext(ExternalMountOperationBindings(visibleMountIds)) bound@ {
                val normalized = args.toMutableMap()
                args.stringArgument("expected_sha256")?.let { hash ->
                    if (args.stringArgument("expectedHash")?.let { it != hash } == true) fail("CONFLICT", "两个版本参数不一致")
                    normalized["expectedHash"] = JsonPrimitive(hash)
                }
                val routedArgs = JsonObject(normalized)
                val repository = MountedWorkspaceRepository(workspace, mounts, context.sessionId)
                val operations = MountedFileOperations(files, mounts, context.sessionId)
                val sourcePath = pathArgument(routedArgs, context, repository, "uuid", "path")
                val parentPath = pathArgument(routedArgs, context, repository, "parentUuid", "parentPath")
                val destinationParent = pathArgument(routedArgs, context, repository, "newParentUuid", "newParentPath")
                val sourceMounted = args.stringArgument("uuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (sourcePath?.isMounted() == true)
                val parentMounted = args.stringArgument("parentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (parentPath?.isMounted() == true)
                val destinationMounted = args.stringArgument("newParentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (destinationParent?.isMounted() == true || destinationParent == null && sourceMounted)
                if (context.isApprovalBoundExecution || context.approvedExecutionTarget != null) {
                    val usedAliases = listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted })
                        .mapNotNull { ExternalMountPaths.parse(it)?.first }
                    if (usedAliases.any { it !in approvedMountIds }) fail("CONFLICT", "批准目标缺少挂载身份，请重新审批")
                }
                if (original.id in setOf("create_file", "create_directory") && (parentPath ?: "/") == "/" && args.stringArgument("name")?.trim() == "mnt") {
                    fail("INVALID_PATH", "/mnt 为挂载保留目录，请在文件面板添加挂载")
                }
                if (original.id in setOf("move_file", "delete_file") && sourceMounted && sourcePath == "/mnt") fail("INVALID_PATH", "不能变更虚拟挂载根目录")
                if (original.id == "create_file" && parentMounted && parentPath != null) {
                    val name = args.stringArgument("name")?.trim() ?: fail("INVALID_PATH", "缺少文件名")
                    ExternalFolderPaths.validateName(name)
                    val path = parentPath.trimEnd('/') + "/" + name
                    val bytes = args.stringArgument("content").orEmpty().toByteArray(Charsets.UTF_8)
                    WorkspaceTextContentPolicy().validate(name, null, bytes, WorkspaceTextOperation.WRITE)
                    val created = mounts.write(context.sessionId, path, bytes, null, true)
                    return@bound ToolResult("mount_create", "已创建 $path [uuid=${created.uuid}] [sha256=${created.hash}]")
                }
                if (original.id == "delete_file" && sourceMounted && sourcePath != null) {
                    mounts.delete(context.sessionId, sourcePath, routedArgs.stringArgument("expectedHash"),
                        (args["irreversible"] as? JsonPrimitive)?.booleanOrNull == true)
                    return@bound ToolResult("mount_delete", "已删除 $sourcePath；回收副本可在挂载回收区恢复（显式不可恢复删除除外）")
                }
                if (original.id == "move_file") {
                    val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
                    val source = sourcePath
                    if (source != null) {
                        val destination = (destinationParent ?: source.substringBeforeLast('/').ifEmpty { "/" }).trimEnd('/') + "/" +
                            (newName ?: source.substringAfterLast('/'))
                        if (destination == "/mnt") fail("INVALID_PATH", "不能覆盖虚拟挂载根目录")
                        if (sourceMounted || destinationMounted) {
                            if (newName == null && destinationParent == null) fail("INVALID_PATH", "需要指定新名称或目标目录")
                            return@bound move(context, source, destination, routedArgs.stringArgument("expectedHash"), sourceMounted, destinationMounted)
                        }
                    }
                }
                listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted }).filter { it.isMounted() }.forEach {
                    if (it != "/mnt") mounts.entry(context.sessionId, it)
                }
                val delegate = when (original.id) {
                    "read_file" -> FileReadSkill(operations, repository)
                    "write_file" -> FileWriteSkill(operations, repository)
                    "patch_file" -> FilePatchSkill(operations, repository)
                    "diff_file" -> FileDiffSkill(operations, repository)
                    "list_files" -> FileListSkill(repository)
                    "search_files" -> FileSearchSkill(repository, operations)
                    "create_file" -> CreateFileSkill(repository, operations)
                    "create_directory" -> CreateDirectorySkill(repository)
                    "move_file" -> MoveFileSkill(repository, indexEvents)
                    "delete_file" -> DeleteFileSkill(repository)
                    else -> original
                }
                val result = delegate.execute(routedArgs, context)
                if (sourceMounted && sourcePath != null && result.status != "error" && original.id in setOf("read_file", "write_file", "patch_file", "diff_file")) {
                    val hash = operations.completedHash.orEmpty()
                    if (original.id in setOf("write_file", "patch_file")) ToolResult(result.id,
                        "外部文件已保存：$sourcePath\nsha256: $hash\n知识库快照可在文件面板重新导入。")
                    else result.copy(content = result.content + "\nsha256: $hash")
                } else result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ExternalFolderException) {
            ToolResult("mount_error", "${failure.code}：${failure.message}", "error")
        } catch (failure: WorkspaceTextPolicyException) { workspaceTextFailureResult(original.id, failure) }
        catch (_: SecurityException) { workspaceToolError(original.id, "路径或文件夹授权不可用，请检查挂载状态") }
    }

    private suspend fun move(context: SkillExecutionContext, source: String, destination: String, expected: String?, sourceMounted: Boolean, destinationMounted: Boolean): ToolResult {
        ExternalFolderPaths.normalize(source); ExternalFolderPaths.normalize(destination)
        if (destination == source || destination.startsWith(source.trimEnd('/') + "/")) fail("INVALID_PATH", "不能移动到自身或子目录")
        val privateBackend = PrivateWorkspaceTransferBackend(workspace, context.workspaceRootUuid, indexEvents)
        val bindings = kotlinx.coroutines.currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
            ?: mounts.visible(context.sessionId).associate { it.alias to it.id }
        listOfNotNull(source.takeIf { sourceMounted }, destination.takeIf { destinationMounted }).forEach { path ->
            if (ExternalMountPaths.parse(path)?.first !in bindings) fail("NOT_FOUND", "移动涉及的挂载不可见")
        }
        val externalBackend = mounts.backend(context.sessionId, bindings)
        val sourceBackend = if (sourceMounted) externalBackend else privateBackend
        val targetBackend = if (destinationMounted) externalBackend else privateBackend
        if (expected != null) {
            val bytes = sourceBackend.read(source, ExternalTreeTransfer.MAX_BYTES)
            if (Sha256Utils.hash(bytes) != expected) fail("CONFLICT", "expected_sha256 与来源不一致")
        }
        val backupId = if (sourceMounted) mounts.backupBeforeMove(context.sessionId, source) else null
        ExternalTreeTransfer.move(sourceBackend, source, targetBackend, destination, expected)
        val indexed = privateBackend.confirmCreated()
        if (backupId != null) mounts.completeMoveBackup(context.sessionId, backupId)
        return ToolResult("mount_move", "已复制、校验并移动：$source → $destination" + if (indexed) "" else "；文件已保存，索引等待重试")
    }

    private suspend fun pathArgument(args: JsonObject, context: SkillExecutionContext, repository: IWorkspaceRepository, uuidKey: String, pathKey: String): String? {
        val uuid = args.stringArgument(uuidKey)?.trim()?.takeIf(String::isNotEmpty)
        return if (uuid != null) repository.getByUuid(context.workspaceRootUuid, uuid)?.materializedPath
        else args.stringArgument(pathKey)?.takeIf(String::isNotBlank)?.let(::normalizeWorkspaceToolPath)
    }
}

private fun String.isMounted() = this == "/mnt" || startsWith("/mnt/")
private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)

private class MountedWorkspaceRepository(
    private val base: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
) : IWorkspaceRepository by base {
    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (uuid.startsWith("external:")) mounts.resolveUuid(session, uuid)?.let {
            mounts.entry(session, it)?.takeIf { entry -> entry.uuid == uuid }?.copy(workspaceRootUuid = workspaceRootUuid)
        }
        else base.getByUuid(workspaceRootUuid, uuid)
    override suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry? =
        if (materializedPath.isMounted()) mounts.entry(session, materializedPath)?.copy(workspaceRootUuid = workspaceRootUuid)
        else base.getByMaterializedPath(workspaceRootUuid, materializedPath)
    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> {
        if (parentUuid.startsWith("external:")) return flow {
            val path = mounts.resolveUuid(session, parentUuid) ?: fail("NOT_FOUND", "挂载条目不可见")
            emit(mounts.list(session, path).mapNotNull { mounts.entry(session, it.path)?.copy(workspaceRootUuid = workspaceRootUuid) })
        }
        val children = base.observeChildren(workspaceRootUuid, parentUuid)
        if (parentUuid != workspaceRootUuid) return children
        return combine(children, mounts.observeMounts(session)) { entries, visible ->
            if (visible.isEmpty()) entries else entries + listOfNotNull(mounts.entry(session, "/mnt")?.copy(workspaceRootUuid = workspaceRootUuid))
        }
    }
    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> = flow {
        val matches = base.searchByName(workspaceRootUuid, query).first().toMutableList()
        val pending = ArrayDeque(listOf("/mnt"))
        var count = 0
        while (pending.isNotEmpty() && count < 500 && matches.size < 100) {
            mounts.list(session, pending.removeFirst()).forEach { entry ->
                count++
                if (entry.name.contains(query, true)) mounts.entry(session, entry.path)?.let { matches.add(it.copy(workspaceRootUuid = workspaceRootUuid)) }
                if (entry.isDirectory && count < 500) pending.add(entry.path)
            }
        }
        emit(matches)
    }
    override suspend fun createFileInWorkspace(workspaceRootUuid: String, uuid: String, name: String, content: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (parentUuid?.startsWith("external:") ?: materializedPath.isMounted()) mounts.write(session, materializedPath, content.toByteArray(Charsets.UTF_8), null, true)
        else base.createFileInWorkspace(workspaceRootUuid, uuid, name, content, parentUuid, materializedPath)
    override suspend fun createDirectoryInWorkspace(workspaceRootUuid: String, uuid: String, name: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (parentUuid?.startsWith("external:") ?: materializedPath.isMounted()) mounts.createDirectory(session, materializedPath)
        else base.createDirectoryInWorkspace(workspaceRootUuid, uuid, name, parentUuid, materializedPath)
}

private class MountedFileOperations(
    private val base: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
) : IFileOperationRepository {
    var completedHash: String? = null
        private set
    private suspend fun path(uuid: String) = mounts.resolveUuid(session, uuid) ?: fail("NOT_FOUND", "挂载条目不可见")
    private suspend fun text(uuid: String, operation: WorkspaceTextOperation): Pair<String, ByteArray> {
        val path = path(uuid)
        val bytes = mounts.read(session, path, WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes)
        val text = WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), null, bytes, operation).text
        return text to bytes
    }
    override suspend fun readFileRange(workspaceRootUuid: String, uuid: String, startLine: Int?, endLine: Int?): ReadResult {
        if (!uuid.startsWith("external:")) return base.readFileRange(workspaceRootUuid, uuid, startLine, endLine)
        val (content, bytes) = text(uuid, WorkspaceTextOperation.READ)
        val lines = workspaceTextLines(content)
        val start = (startLine ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
        val end = (endLine ?: lines.size).coerceIn(1, lines.size.coerceAtLeast(1))
        val selected = if (lines.isEmpty()) "" else lines.subList(start - 1, end).joinToString("\n")
        completedHash = Sha256Utils.hash(bytes)
        return ReadResult(uuid, path(uuid).substringAfterLast('/'), lines.size, start, end, selected, completedHash!!, mounts.entry(session, path(uuid))!!.updatedAt)
    }
    override suspend fun writeFileAtomic(workspaceRootUuid: String, uuid: String, newContent: String, sessionId: String, expectedHash: String): WriteResult {
        if (!uuid.startsWith("external:")) return base.writeFileAtomic(workspaceRootUuid, uuid, newContent, sessionId, expectedHash)
        val path = path(uuid)
        val bytes = newContent.toByteArray(Charsets.UTF_8)
        WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), null, bytes, WorkspaceTextOperation.WRITE)
        completedHash = mounts.write(session, path, bytes, expectedHash, false).hash
        return WriteResult.Success(completedHash!!)
    }
    override suspend fun diffFile(workspaceRootUuid: String, uuid: String, basisHash: String?): DiffResult {
        if (!uuid.startsWith("external:")) return base.diffFile(workspaceRootUuid, uuid, basisHash)
        val (current, bytes) = text(uuid, WorkspaceTextOperation.DIFF)
        val hash = Sha256Utils.hash(bytes)
        completedHash = hash
        val basis = if (basisHash == null || basisHash == hash) current else {
            val original = mounts.version(session, path(uuid), basisHash) ?: fail("NOT_FOUND", "基准快照已过期，请重新读取当前文件")
            WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), null, original, WorkspaceTextOperation.DIFF).text
        }
        return DiffResult(uuid, basisHash ?: hash, hash, MyersDiff.computeHunks(workspaceTextLines(basis), workspaceTextLines(current)).map {
            DiffHunk(it.oldStart, it.oldCount, it.newStart, it.newCount, it.lines.map { line -> DiffLine(line.type, line.content) })
        })
    }
    override suspend fun patchFile(workspaceRootUuid: String, uuid: String, operations: List<PatchOperation>, expectedHash: String): PatchResult {
        if (!uuid.startsWith("external:")) return base.patchFile(workspaceRootUuid, uuid, operations, expectedHash)
        val (content, bytes) = text(uuid, WorkspaceTextOperation.PATCH)
        if (Sha256Utils.hash(bytes) != expectedHash) fail("CONFLICT", "expected_sha256 与当前文件不一致")
        val patched = when (val patch = applyWorkspaceLinePatch(content, operations, uuid)) {
            is WorkspaceLinePatchResult.Applied -> patch.content
            is WorkspaceLinePatchResult.Rejected -> {
                val code = when (patch.error.code) {
                    "LINE_OUT_OF_RANGE", "INVALID_ACTION" -> "INVALID_PATCH"
                    "CONTEXT_MISMATCH" -> "CONFLICT"
                    else -> patch.error.code
                }
                fail(code, patch.error.message)
            }
        }
        val newBytes = patched.toByteArray(Charsets.UTF_8)
        WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), null, newBytes, WorkspaceTextOperation.PATCH)
        val updated = mounts.write(session, path(uuid), newBytes, expectedHash, false)
        completedHash = updated.hash
        return PatchResult.Success(updated.hash, operations.size)
    }
}

/** 私有工作区只通过现有仓库创建、回收和确认，读取继续使用安全文件操作与根身份。 */
internal class PrivateWorkspaceTransferBackend(private val workspace: IWorkspaceRepository, private val rootUuid: String, private val indexEvents: FileIndexEventSink = FileIndexEventSink.None) : ExternalFolderBackend {
    private val created = mutableListOf<String>()
    private suspend fun find(path: String) = workspace.getByMaterializedPath(rootUuid, ExternalFolderPaths.normalize(path))
    override suspend fun stat(path: String) = find(path)?.let { ExternalFolderEntry(path, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash, identity = it.uuid) }
    override suspend fun list(path: String) = workspace.observeChildren(rootUuid, find(path)?.uuid ?: fail("NOT_FOUND", "目录不存在")).first()
        .filterNot(FileEntry::isSystemEntry).map { ExternalFolderEntry(it.materializedPath, it.name, it.isDirectory, it.sizeBytes, it.updatedAt, it.hash) }
    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val entry = find(path) ?: fail("NOT_FOUND", "文件不存在")
        if (entry.isDirectory) fail("IS_DIRECTORY", "目标为目录")
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("NOT_FOUND", "工作区不可用")
        return WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            SecureWorkspaceFileOps().readLimited(File(root.physicalRootPath).toPath(), ExternalFolderPaths.normalize(path).trim('/').split('/'), maxBytes)
        }
    }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
        if (!createOnly) fail("UNSUPPORTED", "跨工作区复制只允许新建")
        val parent = find(path.substringBeforeLast('/').ifEmpty { "/" }) ?: fail("NOT_FOUND", "目标目录不存在")
        val id = UUID.randomUUID().toString()
        val entry = workspace.createFileInWorkspaceStreaming(rootUuid, id, path.substringAfterLast('/'), externalSnapshotMime(path.substringAfterLast('/')), parent.uuid, path, ExternalTreeTransfer.MAX_BYTES) { it.write(content) }
        created.add(entry.uuid)
        return stat(path)!!
    }
    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        val parent = find(path.substringBeforeLast('/').ifEmpty { "/" }) ?: fail("NOT_FOUND", "目标目录不存在")
        val entry = workspace.createDirectoryInWorkspace(rootUuid, UUID.randomUUID().toString(), path.substringAfterLast('/'), parent.uuid, path)
        created.add(entry.uuid)
        return stat(path)!!
    }
    override suspend fun delete(path: String, expectedSha256: String?) {
        val entry = find(path) ?: return
        if (expectedSha256 != null && Sha256Utils.hash(read(path, ExternalTreeTransfer.MAX_BYTES)) != expectedSha256) fail("CONFLICT", "删除前来源已改变")
        if (entry.uuid in created) {
            if (entry.isDirectory) workspace.moveToRecycleBin(rootUuid, entry.uuid) else workspace.rollbackCreatedEntry(rootUuid, entry.uuid)
            created.remove(entry.uuid)
        }
        else workspace.moveToRecycleBin(rootUuid, entry.uuid)
    }
    suspend fun confirmCreated(): Boolean {
        var indexed = true
        created.forEach { uuid ->
            workspace.confirmCreatedEntry(rootUuid, uuid)
            val entry = workspace.getByUuid(rootUuid, uuid)
            if (entry != null && !entry.isDirectory && externalSnapshotMime(entry.name) in com.promenar.nexara.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) {
                try { indexEvents.publish(com.promenar.nexara.data.rag.FileIndexEvent.Changed(rootUuid, uuid, entry.hash, entry.updatedAt.coerceAtLeast(1))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { indexed = false }
            }
        }
        created.clear()
        return indexed
    }
}
