package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.ToolAllowlistStore
import com.promenar.nexara.data.repository.ToolAllowlistScopes
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.repository.WorkspaceTextContentPolicy
import com.promenar.nexara.data.repository.WorkspaceTextOperation
import com.promenar.nexara.data.repository.WorkspaceFileTooLargeException
import com.promenar.nexara.data.script.ScriptFileApprovalBroker
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.namespaceBindings
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.script.*
import com.promenar.nexara.domain.storage.*
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolArgumentApprovalRules
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.ApprovalPreview
import com.promenar.nexara.ui.chat.manager.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 读使用现有后端，写删调用原工作区技能；只接受模型虚拟路径。 */
class ScriptWorkspaceGateway private constructor(
    private val context: SkillExecutionContext,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val files: IFileOperationRepository,
    private val indexEvents: FileIndexEventSink,
    private val approvals: ScriptFileApprovalBroker,
    private val grants: ToolAllowlistStore,
    private val approveWrites: () -> Boolean,
    private val mountIds: Map<String, String>,
    private val moduleRoot: String?,
    private val sessionWorkspace: (suspend (String) -> SessionWorkspace)?,
) : ScriptCapabilities {
    private val mutex = Mutex()
    /** 打开时会话为自选目录工作区：`/` 下的路径都经工作区挂载读写，私有工作区不可达。 */
    private val rooted = ExternalMountPaths.WORKSPACE_ALIAS in mountIds
    private val repository = MountedWorkspaceRepository(workspace, mounts, context.sessionId, rooted)
    private val privateBackend by lazy { PrivateWorkspaceTransferBackend(workspace, context.workspaceRootUuid, indexEvents) }

    override suspend fun invoke(operation: String, arguments: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw = arguments.stringArgument("path") ?: throw ScriptFailure("INVALID_PATH", "缺少虚拟路径")
            val path = ExternalFolderPaths.normalize(raw)
            val alias = ExternalMountPaths.aliasOf(path, rooted)
            val currentMounts = (mounts.visible(context.sessionId) + listOfNotNull(mounts.workspaceMount(context.sessionId))).associateBy { it.alias }
            if (!rooted && currentMounts.containsKey(ExternalMountPaths.WORKSPACE_ALIAS)) throw ScriptFailure("MOUNT_CHANGED", "会话工作区已改变，请重新执行脚本")
            if (alias != null && (alias !in mountIds || currentMounts[alias]?.id != mountIds[alias])) throw ScriptFailure("MOUNT_CHANGED", "挂载身份已失效，请重新执行脚本")
            if (operation == "module") {
                val root = moduleRoot ?: throw ScriptFailure("IMPORT_DENIED", "内联脚本不允许模块导入")
                if (root != "/" && !path.startsWith("$root/") || root == "/" && ExternalMountPaths.isMountedPath(path) || !path.endsWith(".js") && !path.endsWith(".mjs")) {
                    throw ScriptFailure("IMPORT_DENIED", "模块导入越出根目录")
                }
            }
            withContext(ExternalMountOperationBindings(mountIds)) {
                when (operation) {
                    "read", "module" -> {
                        val bytes = try {
                            if (alias != null) mounts.read(context.sessionId, path, ScriptLimits.FILE_BYTES.toLong())
                            else if (rooted) throw ScriptFailure("NOT_FOUND", "/mnt 是挂载目录，不是文件")
                            else privateBackend.read(path, ScriptLimits.FILE_BYTES.toLong())
                        } catch (failure: ExternalFolderException) {
                            if (failure.code == "TOO_LARGE") throw ScriptFailure("FILE_LIMIT", "文件超过脚本读取上限")
                            throw failure
                        } catch (_: WorkspaceFileTooLargeException) {
                            throw ScriptFailure("FILE_LIMIT", "文件超过脚本读取上限")
                        }
                        if (bytes.size > ScriptLimits.FILE_BYTES) throw ScriptFailure("FILE_LIMIT", "文件超过脚本读取上限")
                        val text = WorkspaceTextContentPolicy().validate(path.substringAfterLast('/'), null, bytes, WorkspaceTextOperation.READ).text
                        if (operation == "module") JsonPrimitive(text) else buildJsonObject { put("content", text); put("sha256", Sha256Utils.hash(bytes)); put("path", path) }
                    }
                    "stat" -> entry(path)?.asJson() ?: JsonNull
                    "list" -> {
                        val entry = entry(path) ?: throw ScriptFailure("NOT_FOUND", "目录不存在")
                        val children = repository.observeChildren(rootUuid(), entry.uuid).first()
                        if (children.size > 256) throw ScriptFailure("FILE_LIMIT", "目录条目超过 256 个，请使用文件工具分页")
                        JsonArray(children.filterNot { it.isSystemEntry() }.map { it.asJson() })
                    }
                    "write", "create", "mkdir", "delete" -> {
                        if (alias != null && currentMounts[alias]?.readOnly == true) throw ScriptFailure("READ_ONLY", "挂载为只读")
                        mutate(operation, path, arguments)
                    }
                    else -> throw ScriptFailure("CAPABILITY_DENIED", "未授权的文件能力")
                }
            }
        }
    }

    private suspend fun entry(path: String) = repository.getByMaterializedPath(rootUuid(), path)
    private suspend fun rootUuid() = if (!rooted) context.workspaceRootUuid
        else mounts.workspaceMount(context.sessionId)?.let { ExternalMountPaths.uuid(it, "/") } ?: throw ScriptFailure("MOUNT_CHANGED", "会话工作区已改变，请重新执行脚本")

    private suspend fun mutate(operation: String, path: String, arguments: JsonObject): JsonElement {
        currentCoroutineContext().ensureActive()
        if (path == "/" || path == "/mnt" || ExternalMountPaths.parse(path)?.second == "/") throw ScriptFailure("INVALID_PATH", "不能修改工作区或挂载根目录")
        val content = arguments.stringArgument("content")
        if (operation in setOf("write", "create") && (content == null || content.toByteArray(Charsets.UTF_8).size > ScriptLimits.FILE_BYTES)) throw ScriptFailure("FILE_LIMIT", "写入内容缺失或超过上限")
        val expected = arguments.stringArgument("expected_sha256")
        if (operation == "write" && expected == null || expected != null && !Regex("[0-9a-fA-F]{64}").matches(expected)) throw ScriptFailure("CONFLICT", "覆盖写入必须提供有效 expected_sha256")
        val skill: SkillDefinition = when (operation) {
            "write" -> FileWriteSkill(files, workspace)
            "create" -> CreateFileSkill(workspace, files)
            "mkdir" -> CreateDirectorySkill(workspace)
            else -> DeleteFileSkill(workspace)
        }
        val args = buildJsonObject {
            if (operation in setOf("create", "mkdir")) {
                put("name", path.substringAfterLast('/')); put("parentPath", path.substringBeforeLast('/').ifEmpty { "/" })
            } else put("path", path)
            content?.let { put("content", it) }
            expected?.let { put("expected_sha256", it.lowercase()) }
        }
        val targetParts = Json.parseToJsonElement(ToolExecutionTargets.extract(skill.toProtocolTool(), args.toString()) ?: throw ScriptFailure("INVALID_PATH", "无法确定文件审批目标")).jsonArray
        val alias = ExternalMountPaths.aliasOf(path, rooted)
        val bindings = if (alias == null) emptyMap() else mapOf(alias to JsonPrimitive(mountIds.getValue(alias)))
        val metadata = targetParts.lastOrNull() as? JsonObject
        val tuple = if (metadata == null) targetParts.toList() else targetParts.dropLast(1)
        val target = if (bindings.isEmpty()) targetParts.toString() else JsonArray(tuple + JsonObject(metadata.orEmpty() + mapOf(
            "mountIds" to JsonObject(bindings), "legacyWorkspaceUuids" to JsonArray(emptyList()),
        ))).toString()
        if (operation == "delete" || approveWrites() || ToolArgumentApprovalRules.requiresExplicitApproval(skill.name, args.toString(), target)) {
            val scope = ToolAllowlistScopes.resolveForSession(context.sessionId, target, sessionWorkspace, mounts)
            if (scope == null || !grants.contains(skill.name, target, scope.scopeKey)) {
                val decision = approvals.request(context.sessionId, skill.name, skill.risk, target, ApprovalPreview.format(skill.name, args)?.detail.orEmpty())
                if (!decision.approved) throw ScriptFailure("APPROVAL_DENIED", "文件操作未获批准")
                if (decision.remember) {
                    ToolAllowlistScopes.resolveForSession(context.sessionId, target, sessionWorkspace, mounts)?.let { current ->
                        // 审批期间存储身份改变时，不把原目标写入新的存储作用域。
                        if (current == scope) grants.add(skill.name, target, current.scopeKey, context.sessionId)
                    }
                }
            }
        }
        // 审批可能等待很久，执行前重新核验身份和读写权限；挂载工具会再做后端校验。
        currentCoroutineContext().ensureActive()
        if (alias != null) {
            val current = (mounts.visible(context.sessionId) + listOfNotNull(mounts.workspaceMount(context.sessionId))).singleOrNull { it.alias == alias }
            if (current?.id != mountIds[alias] || current?.readOnly != false) throw ScriptFailure("MOUNT_CHANGED", "审批后挂载身份或写入权限已改变")
        }
        val boundContext = object : SkillExecutionContext by context {
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        currentCoroutineContext().ensureActive()
        val result = MountedWorkspaceSkill(skill, mounts, workspace, files, indexEvents, captureFileChanges = false).execute(args, boundContext)
        if (result.status != "success") throw ScriptFailure("FILE_ERROR", result.content.take(512))
        return buildJsonObject { put("message", result.content); put("path", path) }
    }

    private fun FileEntry.asJson() = buildJsonObject {
        put("path", materializedPath); put("isDirectory", isDirectory); put("size", sizeBytes); put("sha256", hash)
    }

    companion object {
        suspend fun open(context: SkillExecutionContext, workspace: IWorkspaceRepository, mounts: ExternalWorkspaceAccess, files: IFileOperationRepository, indexEvents: FileIndexEventSink, approvals: ScriptFileApprovalBroker, grants: ToolAllowlistStore, approveWrites: () -> Boolean, entryPath: String?, sessionWorkspace: (suspend (String) -> SessionWorkspace)? = null): ScriptWorkspaceGateway {
            val ids = mounts.namespaceBindings(context.sessionId)
            val approved = context.approvedExecutionTarget?.let { runCatching { Json.parseToJsonElement(it).jsonArray.lastOrNull() as? JsonObject }.getOrNull() }
                ?.get("mountIds")?.jsonObject.orEmpty()
            if (approved.any { (alias, id) -> ids[alias] != id.jsonPrimitive.content }) throw ScriptFailure("MOUNT_CHANGED", "脚本审批的挂载身份已失效")
            if (entryPath != null && context.isApprovalBoundExecution) {
                val alias = ExternalMountPaths.aliasOf(entryPath, ExternalMountPaths.WORKSPACE_ALIAS in ids)
                if (alias != null && alias !in approved) throw ScriptFailure("MOUNT_CHANGED", "脚本审批缺少挂载或工作区身份")
            }
            return ScriptWorkspaceGateway(context, workspace, mounts, files, indexEvents, approvals, grants, approveWrites, ids, entryPath?.let(ScriptModules::root), sessionWorkspace)
        }
    }
}
