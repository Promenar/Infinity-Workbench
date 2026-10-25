package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.model.withinHunkBudget
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.isUserSkills
import com.promenar.nexara.data.storage.ExternalTreeTransfer
import com.promenar.nexara.data.storage.externalSnapshotMime
import com.promenar.nexara.domain.repository.*
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.infra.util.MyersDiff
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.diff.FileChangeSummaries
import com.promenar.nexara.ui.chat.diff.FileSnapshot
import com.promenar.nexara.ui.chat.diff.PrivateWorkspaceReader
import com.promenar.nexara.ui.chat.diff.WorkspaceSnapshotReader
import com.promenar.nexara.ui.chat.manager.registry.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import com.promenar.nexara.data.storage.namespaceBindings
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 保留现有工具语义，只在仓库适配层增加虚拟挂载命名空间。 */
class MountedWorkspaceSkill(
    private val original: SkillDefinition,
    private val mounts: ExternalWorkspaceAccess,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
    /** 是否采集界面用的文件改动摘要；脚本网关只取结果文本，关闭以免每次写入多读一次文件。 */
    private val captureFileChanges: Boolean = true,
    private val privateReader: PrivateWorkspaceReader = PrivateWorkspaceReader.Default,
    private val linuxPaths: Boolean = false,
) : SkillDefinition by original {
    override val description = original.description + " 支持用户级技能 /skills/... 与 /mnt/<别名>/... 挂载路径。" + when (original.id) {
        "delete_file" -> " 删除文件时 expectedHash 或 expected_sha256 为可选版本校验，取自 read_file 的 Hash 或 sha256；目录不支持文件版本参数。"
        "move_file" -> " 涉及挂载路径的来源文件可提供 expectedHash 或 expected_sha256（取自 read_file 的 Hash 或 sha256）校验来源版本；私有工作区移动不校验内容版本。"
        else -> ""
    }
    override val parametersSchema: String = run {
        val schema = Json.parseToJsonElement(original.parametersSchema).jsonObject
        val properties = schema["properties"]?.jsonObject.orEmpty().toMutableMap()
        if (original.id in setOf("write_file", "patch_file", "delete_file", "move_file")) {
            val versionDescription = if (original.id in setOf("write_file", "patch_file")) WORKSPACE_VERSION_REQUIREMENT
                else if (original.id == "delete_file") "删除文件的可选版本校验，取自 read_file 的 Hash 或 sha256；目录不支持文件版本参数"
                else "涉及挂载路径的来源文件可选版本校验，取自 read_file 的 Hash 或 sha256；私有工作区移动不校验内容版本"
            for (parameter in listOf("expectedHash", "expected_sha256")) {
                properties[parameter] = buildJsonObject { put("type", "string"); put("description", versionDescription) }
            }
        }
        if (original.id == "delete_file") properties["irreversible"] = buildJsonObject {
            put("type", "boolean"); put("description", "回收区不足时显式永久删除，必须重新 DELETE 审批并显示不可恢复")
        }
        JsonObject(schema.toMutableMap().apply {
            put("properties", JsonObject(properties))
            (schema["required"] as? JsonArray)?.let { put("required", JsonArray(it.filterNot { item -> item.jsonPrimitive.content == "expectedHash" })) }
        }).toString()
    }

    override suspend fun execute(args: JsonObject, invocation: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            // 自选目录工作区以别名为空串的挂载承载 `/`；此时私有工作区的 uuid 与路径都不可达。
            val workspaceMount = mounts.workspaceMount(invocation.sessionId)
            val rooted = workspaceMount != null
            val context = if (workspaceMount == null) invocation else object : SkillExecutionContext by invocation {
                override val workspaceRootUuid = ExternalMountPaths.uuid(workspaceMount, "/")
            }
            fun String.external() = rooted || isMounted()
            fun aliasOf(path: String) = if (linuxPaths) com.promenar.nexara.data.workspace.LinuxSessionPaths.aliasOf(path) else ExternalMountPaths.aliasOf(path, rooted)
            val visibleMountIds = mounts.visible(context.sessionId).associate { it.alias to it.id } +
                listOfNotNull(workspaceMount).associate { it.alias to it.id }
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
                val repository = MountedWorkspaceRepository(workspace, mounts, context.sessionId, rooted, linuxPaths)
                val operations = MountedFileOperations(files, mounts, context.sessionId, rooted)
                val sourcePath = pathArgument(routedArgs, context, repository, "uuid", "path")
                // 根工作区的新建默认落在 `/`，与挂载目录同样走外部写入而不是私有工作区的 Room 事务。
                val parentPath = pathArgument(routedArgs, context, repository, "parentUuid", "parentPath")
                    ?: "/".takeIf { rooted && original.id in setOf("create_file", "create_directory") }
                val destinationParent = pathArgument(routedArgs, context, repository, "newParentUuid", "newParentPath")
                val sourceMounted = rooted || (args.stringArgument("uuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (sourcePath?.isMounted() == true))
                val parentMounted = rooted || (args.stringArgument("parentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (parentPath?.isMounted() == true))
                val destinationMounted = rooted || (args.stringArgument("newParentUuid")?.takeIf(String::isNotBlank)?.let { it.startsWith("external:") }
                    ?: (destinationParent?.isMounted() == true || destinationParent == null && sourceMounted))
                val resolvedPaths = listOf("uuid" to sourcePath, "parentUuid" to parentPath, "newParentUuid" to destinationParent)
                    .mapNotNull { (key, path) -> routedArgs.stringArgument(key)?.trim()?.takeIf { it.isNotEmpty() && path != null }?.let { it to path!! } }.toMap()
                val currentTarget = ToolExecutionTargets.extract(original.toProtocolTool(), routedArgs.toString(), resolvedPaths)
                // 审批侧无法给出目标的变更（如路径写法与审批规范化不一致）不执行，避免批准内容与实际写入位置不同。
                if (ToolExecutionTargets.isPathMutation(original.id) && currentTarget == null) {
                    fail("INVALID_PATH", "无法确定变更的目标路径，请使用以 / 开头的规范路径重新发起")
                }
                if (ToolExecutionTargets.requiresSkillsApproval(original.id, currentTarget)) {
                    fun tuple(target: String?) = target?.let { Json.parseToJsonElement(it).jsonArray }
                        ?.let { if (it.lastOrNull() is JsonObject) JsonArray(it.dropLast(1)) else it }
                    if (!context.isApprovalBoundExecution || tuple(context.approvedExecutionTarget) != tuple(currentTarget)) {
                        fail("APPROVAL_REQUIRED", "技能目录变更需要按当前路径逐次审批，请重新发起操作")
                    }
                }
                if (context.isApprovalBoundExecution || context.approvedExecutionTarget != null) {
                    val usedAliases = listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted })
                        .mapNotNull(::aliasOf)
                    if (usedAliases.any { it !in approvedMountIds }) fail("CONFLICT", "批准目标缺少挂载或工作区身份，请重新审批")
                }
                if (original.id in setOf("write_file", "patch_file")) {
                    workspaceVersionError(original.id, routedArgs)?.let { return@bound it }
                }
                // 改动摘要只用于界面展示：写入前快照在执行前读取，失败不影响工具本身。
                val capture = if (!captureFileChanges) null else FileChangeCapture.begin(
                    toolId = original.id,
                    args = routedArgs,
                    reader = WorkspaceSnapshotReader(repository, workspace, mounts, context.sessionId, context.workspaceRootUuid, rooted, privateReader = privateReader),
                    sourcePath = sourcePath,
                    parentPath = parentPath,
                    destinationParent = destinationParent,
                )
                suspend fun finish(result: ToolResult): ToolResult = capture?.finish(result) ?: result
                if (!linuxPaths && original.id in setOf("create_file", "create_directory") && (parentPath ?: "/") == "/" && args.stringArgument("name")?.trim() in setOf("mnt", "skills")) {
                    fail("INVALID_PATH", "/mnt 与 /skills 为保留虚拟目录")
                }
                if (original.id in setOf("move_file", "delete_file") && sourceMounted && sourcePath in (if (linuxPaths) setOf("/mnt") else setOf("/mnt", "/skills"))) fail("INVALID_PATH", "不能变更保留虚拟根目录")
                if (original.id in setOf("move_file", "delete_file") && rooted && sourcePath == "/") fail("INVALID_PATH", "不能移动或删除工作区根目录")
                if (original.id == "create_file" && parentMounted && parentPath != null) {
                    val name = args.stringArgument("name")?.trim() ?: fail("INVALID_PATH", "缺少文件名")
                    ExternalFolderPaths.validateName(name)
                    val path = parentPath.trimEnd('/') + "/" + name
                    val bytes = args.stringArgument("content").orEmpty().toByteArray(Charsets.UTF_8)
                    WorkspaceTextContentPolicy().validate(name, (mounts as? LinuxSessionExternalWorkspaceAccess)?.textMime(context.sessionId, path), bytes, WorkspaceTextOperation.WRITE)
                    val created = mounts.write(context.sessionId, path, bytes, null, true)
                    return@bound finish(ToolResult("mount_create", "已创建 $path [uuid=${created.uuid}] [sha256=${created.hash}]"))
                }
                if (original.id == "delete_file" && sourceMounted && sourcePath != null) {
                    mounts.delete(context.sessionId, sourcePath, routedArgs.stringArgument("expectedHash"),
                        (args["irreversible"] as? JsonPrimitive)?.booleanOrNull == true)
                    return@bound finish(ToolResult("mount_delete", "已删除 $sourcePath；回收副本可在挂载回收区恢复（显式不可恢复删除除外）"))
                }
                if (original.id == "move_file") {
                    val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
                    val source = sourcePath
                    if (source != null) {
                        val destination = (destinationParent ?: source.substringBeforeLast('/').ifEmpty { "/" }).trimEnd('/') + "/" +
                            (newName ?: source.substringAfterLast('/'))
                        if (destination in (if (linuxPaths) setOf("/mnt") else setOf("/mnt", "/skills"))) fail("INVALID_PATH", "不能覆盖保留虚拟根目录")
                        if (sourceMounted || destinationMounted) {
                            if (newName == null && destinationParent == null) fail("INVALID_PATH", "需要指定新名称或目标目录")
                            return@bound finish(move(context, source, destination, routedArgs.stringArgument("expectedHash"), sourceMounted, destinationMounted))
                        }
                    }
                }
                listOfNotNull(sourcePath?.takeIf { sourceMounted }, parentPath?.takeIf { parentMounted }, destinationParent?.takeIf { destinationMounted }).filter { it.external() }.forEach {
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
                finish(if (sourceMounted && sourcePath != null && result.status != "error" && original.id in setOf("read_file", "write_file", "patch_file", "diff_file")) {
                    val hash = operations.completedHash.orEmpty()
                    val workspaceFile = aliasOf(sourcePath) == ExternalMountPaths.WORKSPACE_ALIAS
                    if (original.id in setOf("write_file", "patch_file")) ToolResult(result.id,
                        if (workspaceFile) "已保存：$sourcePath\nsha256: $hash"
                        else "外部文件已保存：$sourcePath\nsha256: $hash\n资料快照可在文件面板重新导入。")
                    else result.copy(content = result.content + "\nsha256: $hash")
                } else result)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ExternalFolderException) {
            ToolResult("mount_error", "${failure.code}：${failure.message}", "error")
        } catch (failure: WorkspaceTextPolicyException) { workspaceTextFailureResult(original.id, failure) }
        catch (failure: SecurityException) { workspaceMutationFailureResult(original.id, failure) }
        catch (failure: Exception) { workspaceMutationFailureResult(original.id, failure) }
    }

    private suspend fun move(context: SkillExecutionContext, source: String, destination: String, expected: String?, sourceMounted: Boolean, destinationMounted: Boolean): ToolResult {
        ExternalFolderPaths.normalize(source); ExternalFolderPaths.normalize(destination)
        (mounts as? LinuxSessionExternalWorkspaceAccess)?.movePrivate(context.sessionId, source, destination, expected)?.let { return it }
        if (destination != source && destination.startsWith(source.trimEnd('/') + "/")) fail("INVALID_PATH", "不能移动到子目录")
        val privateBackend = PrivateWorkspaceTransferBackend(workspace, context.workspaceRootUuid, indexEvents)
        val bindings = kotlinx.coroutines.currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
            ?: mounts.namespaceBindings(context.sessionId)
        val rooted = ExternalMountPaths.WORKSPACE_ALIAS in bindings
        listOfNotNull(source.takeIf { sourceMounted }, destination.takeIf { destinationMounted }).forEach { path ->
            val alias = if (linuxPaths) com.promenar.nexara.data.workspace.LinuxSessionPaths.aliasOf(path) else ExternalMountPaths.aliasOf(path, rooted)
            if (alias !in bindings) fail("NOT_FOUND", "移动涉及的挂载不可见")
        }
        val externalBackend = mounts.backend(context.sessionId, bindings)
        val sourceBackend = if (sourceMounted) externalBackend else privateBackend
        val targetBackend = if (destinationMounted) externalBackend else privateBackend
        if (expected != null) {
            val bytes = sourceBackend.read(source, ExternalTreeTransfer.MAX_BYTES)
            if (Sha256Utils.hash(bytes) != expected) fail("CONFLICT", "expected_sha256 与来源不一致")
        }
        if (destination == source) {
            sourceBackend.stat(source) ?: fail("NOT_FOUND", "源条目不存在")
            return ToolResult("mount_move", "目标路径未变化：$source")
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

private fun String.isMounted() = ExternalMountPaths.isMountedPath(this)
private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)

/**
 * 私有工作区仓库加挂载命名空间。[rooted] 时 `/` 属于自选目录工作区，私有工作区的条目一律不可见，
 * 防止旧 uuid 或路径穿透到会话私有目录。
 */
internal class MountedWorkspaceRepository(
    private val base: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
    private val rooted: Boolean = false,
    private val linuxPaths: Boolean = false,
) : IWorkspaceRepository by base {
    private fun String.external() = rooted || isMounted()
    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (uuid.startsWith("external:")) mounts.resolveUuid(session, uuid)?.let {
            mounts.entry(session, it)?.takeIf { entry -> entry.uuid == uuid }?.copy(workspaceRootUuid = workspaceRootUuid)
        }
        else if (rooted) null
        else base.getByUuid(workspaceRootUuid, uuid)
    override suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry? =
        if (materializedPath.external()) mounts.entry(session, materializedPath)?.copy(workspaceRootUuid = workspaceRootUuid)
        else base.getByMaterializedPath(workspaceRootUuid, materializedPath)
    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> {
        if (parentUuid.startsWith("external:")) return flow {
            val path = mounts.resolveUuid(session, parentUuid) ?: fail("NOT_FOUND", "挂载条目不可见")
            val children = mounts.list(session, path).mapNotNull { mounts.entry(session, it.path)?.copy(workspaceRootUuid = workspaceRootUuid) }
            emit(children + if (rooted && path == "/") virtualRoots(workspaceRootUuid) else emptyList())
        }
        if (rooted) return flowOf(emptyList())
        val children = base.observeChildren(workspaceRootUuid, parentUuid)
        if (parentUuid != workspaceRootUuid) return children
        return combine(children, mounts.observeMounts(session)) { entries, _ ->
            entries.filterNot { it.name == "skills" } + virtualRoots(workspaceRootUuid)
        }
    }
    private suspend fun virtualRoots(workspaceRootUuid: String): List<FileEntry> {
        if (linuxPaths) return emptyList()
        val visible = mounts.visible(session)
        return listOfNotNull(
            "/mnt".takeIf { visible.any { !it.isUserSkills } },
            "/skills".takeIf { visible.any { it.isUserSkills } },
        ).mapNotNull { mounts.entry(session, it)?.copy(workspaceRootUuid = workspaceRootUuid) }
    }
    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> = flow {
        val matches = if (rooted) mutableListOf() else base.searchByName(workspaceRootUuid, query).first().toMutableList()
        // 自选目录不建索引，按目录逐层遍历；`.git` 内部对象不参与名称搜索。
        val limit = if (rooted) 5_000 else 500
        val pending = ArrayDeque(if (linuxPaths) listOf("/workspace", "/home/session", "/mnt", "/") else if (rooted) listOf("/", "/mnt", "/skills") else listOf("/mnt", "/skills"))
        val visited = mutableSetOf<String>()
        var count = 0
        while (pending.isNotEmpty() && count < limit && matches.size < 100) {
            val directory = pending.removeFirst()
            if (!visited.add(directory)) continue
            mounts.list(session, directory).forEach { entry ->
                count++
                if (entry.name.contains(query, true)) mounts.entry(session, entry.path)?.let { matches.add(it.copy(workspaceRootUuid = workspaceRootUuid)) }
                if (entry.isDirectory && entry.name != ".git" && count < limit) pending.add(entry.path)
            }
        }
        emit(matches)
    }
    override suspend fun createFileInWorkspace(workspaceRootUuid: String, uuid: String, name: String, content: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (rooted || (parentUuid?.startsWith("external:") ?: materializedPath.isMounted())) mounts.write(session, materializedPath, content.toByteArray(Charsets.UTF_8), null, true)
        else base.createFileInWorkspace(workspaceRootUuid, uuid, name, content, parentUuid, materializedPath)
    override suspend fun createDirectoryInWorkspace(workspaceRootUuid: String, uuid: String, name: String, parentUuid: String?, materializedPath: String): FileEntry =
        if (rooted || (parentUuid?.startsWith("external:") ?: materializedPath.isMounted())) mounts.createDirectory(session, materializedPath)
        else base.createDirectoryInWorkspace(workspaceRootUuid, uuid, name, parentUuid, materializedPath)
}

private class MountedFileOperations(
    private val base: IFileOperationRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val session: String,
    private val rooted: Boolean,
) : IFileOperationRepository {
    /** 根工作区时私有工作区的 uuid 不可达。 */
    private fun isPrivate(uuid: String) = !uuid.startsWith("external:") && (!rooted || fail("NOT_FOUND", "条目不在当前工作区，请用 list_files 获取最新路径"))
    var completedHash: String? = null
        private set
    private suspend fun path(uuid: String) = mounts.resolveUuid(session, uuid) ?: fail("NOT_FOUND", "挂载条目不可见")
    private suspend fun text(uuid: String, operation: WorkspaceTextOperation): Pair<String, ByteArray> {
        val path = path(uuid)
        val bytes = mounts.read(session, path, WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes)
        val text = WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), textMime(path), bytes, operation).text
        return text to bytes
    }
    override suspend fun readFileRange(workspaceRootUuid: String, uuid: String, startLine: Int?, endLine: Int?): ReadResult {
        if (isPrivate(uuid)) return base.readFileRange(workspaceRootUuid, uuid, startLine, endLine)
        val (content, bytes) = text(uuid, WorkspaceTextOperation.READ)
        val lines = workspaceTextLines(content)
        val start = (startLine ?: 1).coerceIn(1, lines.size.coerceAtLeast(1))
        val end = (endLine ?: lines.size).coerceIn(1, lines.size.coerceAtLeast(1))
        val selected = if (lines.isEmpty()) "" else lines.subList(start - 1, end).joinToString("\n")
        completedHash = Sha256Utils.hash(bytes)
        return ReadResult(uuid, path(uuid).substringAfterLast('/'), lines.size, start, end, selected, completedHash!!, mounts.entry(session, path(uuid))!!.updatedAt)
    }
    override suspend fun writeFileAtomic(workspaceRootUuid: String, uuid: String, newContent: String, sessionId: String, expectedHash: String): WriteResult {
        if (isPrivate(uuid)) return base.writeFileAtomic(workspaceRootUuid, uuid, newContent, sessionId, expectedHash)
        val path = path(uuid)
        val bytes = newContent.toByteArray(Charsets.UTF_8)
        WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), textMime(path), bytes, WorkspaceTextOperation.WRITE)
        completedHash = mounts.write(session, path, bytes, expectedHash, false).hash
        return WriteResult.Success(completedHash!!)
    }
    override suspend fun diffFile(workspaceRootUuid: String, uuid: String, basisHash: String?): DiffResult {
        if (isPrivate(uuid)) return base.diffFile(workspaceRootUuid, uuid, basisHash)
        (mounts as? LinuxSessionExternalWorkspaceAccess)?.diffPrivate(session, uuid, basisHash)?.let {
            completedHash = it.currentHash
            return it
        }
        val (current, bytes) = text(uuid, WorkspaceTextOperation.DIFF)
        val hash = Sha256Utils.hash(bytes)
        completedHash = hash
        val basis = if (basisHash == null || basisHash == hash) current else {
            val original = mounts.version(session, path(uuid), basisHash) ?: fail("NOT_FOUND", "基准快照已过期，请重新读取当前文件")
            WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), textMime(path(uuid)), original, WorkspaceTextOperation.DIFF).text
        }
        return DiffResult(uuid, basisHash ?: hash, hash, MyersDiff.computeHunks(workspaceTextLines(basis), workspaceTextLines(current)).map {
            DiffHunk(it.oldStart, it.oldCount, it.newStart, it.newCount, it.lines.map { line -> DiffLine(line.type, line.content) })
        })
    }
    override suspend fun patchFile(workspaceRootUuid: String, uuid: String, operations: List<PatchOperation>, expectedHash: String): PatchResult {
        if (isPrivate(uuid)) return base.patchFile(workspaceRootUuid, uuid, operations, expectedHash)
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
        WorkspaceTextContentPolicy().validate(path(uuid).substringAfterLast('/'), textMime(path(uuid)), newBytes, WorkspaceTextOperation.PATCH)
        val updated = mounts.write(session, path(uuid), newBytes, expectedHash, false)
        completedHash = updated.hash
        return PatchResult.Success(updated.hash, operations.size)
    }
    private suspend fun textMime(path: String) = (mounts as? LinuxSessionExternalWorkspaceAccess)?.textMime(session, path)
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

/**
 * 文件工具改动摘要的采集：执行前读取写入前快照，成功后读取写入后快照并计算 [ToolResult.fileChanges]。
 * 只追加字段，不改动工具返回给模型的 `content`；读取或计算失败时原样返回结果。
 */
private class FileChangeCapture private constructor(
    private val toolId: String,
    private val reader: WorkspaceSnapshotReader,
    private val path: String,
    private val fromPath: String?,
    private val before: FileSnapshot?,
) {
    suspend fun finish(result: ToolResult): ToolResult {
        if (result.status != "success") return result
        return try {
            val summary = when (toolId) {
                "write_file", "patch_file" -> {
                    val after = reader.read(path).snapshot
                    withContext(Dispatchers.Default) { FileChangeSummaries.modified(path, before ?: FileSnapshot.Unavailable, after) }
                }
                "create_file" -> {
                    val after = reader.read(path).snapshot
                    withContext(Dispatchers.Default) { FileChangeSummaries.created(path, after) }
                }
                "delete_file" -> withContext(Dispatchers.Default) {
                    FileChangeSummaries.deleted(path, before ?: FileSnapshot.Unavailable)
                }
                "move_file" -> FileChangeSummaries.moved(fromPath ?: return result, path, reader.isDirectory(path) == true)
                else -> return result
            }
            result.copy(fileChanges = listOf(summary).withinHunkBudget())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result
        }
    }

    companion object {
        private val CAPTURED_TOOLS = setOf("write_file", "create_file", "patch_file", "delete_file", "move_file")

        suspend fun begin(
            toolId: String,
            args: JsonObject,
            reader: WorkspaceSnapshotReader,
            sourcePath: String?,
            parentPath: String?,
            destinationParent: String?,
        ): FileChangeCapture? {
            if (toolId !in CAPTURED_TOOLS) return null
            return try {
                when (toolId) {
                    "create_file" -> {
                        val name = args.stringArgument("name")?.trim()?.takeIf(String::isNotEmpty) ?: return null
                        FileChangeCapture(toolId, reader, (parentPath ?: "/").trimEnd('/') + "/" + name, null, null)
                    }
                    "move_file" -> {
                        val source = sourcePath ?: return null
                        val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
                        val destination = (destinationParent ?: source.substringBeforeLast('/').ifEmpty { "/" }).trimEnd('/') +
                            "/" + (newName ?: source.substringAfterLast('/'))
                        if (destination == source) return null
                        FileChangeCapture(toolId, reader, destination, source, null)
                    }
                    else -> {
                        val source = sourcePath ?: return null
                        val captured = reader.read(source)
                        // 写入以 expectedHash 做乐观锁：快照哈希不一致说明读到的不是被覆盖的版本，不用于计数。
                        val expected = args.stringArgument("expectedHash")
                        val before = if (toolId != "delete_file" && expected != null && captured.sha256 != null && captured.sha256 != expected) {
                            FileSnapshot.Unavailable
                        } else captured.snapshot
                        FileChangeCapture(toolId, reader, source, null, before)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}
