package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.data.workspace.SftpLinuxFile
import com.promenar.infinity.data.workspace.SftpWorkspaceFile
import com.promenar.infinity.data.workspace.normalizeSftpWorkspacePath
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 依赖由应用的内置工具注册入口传入，测试可使用纯 JVM 文件端口。 */
data class SftpTransferAccess(
    val workspaceFiles: com.promenar.infinity.data.workspace.SftpWorkspaceFiles? = null,
    val linux: LinuxFileSystem? = null,
    val linuxAvailable: () -> Boolean = { LinuxExecRouting.available },
    val indexEvents: FileIndexEventSink = FileIndexEventSink.None,
)

/** 审批与执行共享注册时的依赖，不要求聊天和子代理分别维护一份文件路由。 */
internal class SftpTransferBinding(val runtime: SshRuntime, val access: SftpTransferAccess)
internal object InstalledSftpTransferAccess {
    private val values = java.util.WeakHashMap<SshHostStore, java.lang.ref.WeakReference<SftpTransferBinding>>()
    @Synchronized fun put(binding: SftpTransferBinding) { values[binding.runtime.hostStore] = java.lang.ref.WeakReference(binding) }
    @Synchronized fun get(store: SshHostStore?) = values[store]?.get()
}

fun sftpTransferTools(runtime: SshRuntime, jobs: BackgroundJobManager?, access: SftpTransferAccess = SftpTransferAccess()): List<SkillDefinition> {
    val binding = SftpTransferBinding(runtime, access)
    InstalledSftpTransferAccess.put(binding)
    return listOf(SftpTransferSkill(binding, jobs, true), SftpTransferSkill(binding, jobs, false))
}

private class SftpTransferSkill(
    private val binding: SftpTransferBinding,
    private val jobs: BackgroundJobManager?,
    private val upload: Boolean,
) : SkillDefinition {
    private val runtime get() = binding.runtime
    private val access get() = binding.access
    override val id = if (upload) "sftp_upload" else "sftp_download"
    override val name = id
    override val risk = if (upload) ToolRisk.EXTERNAL_WRITE else ToolRisk.FILE_WRITE
    override val mcpServerId: String? = null
    override val description = (if (upload) "把本地文件上传到远端。" else "把远端文件下载到本地。") +
        "二进制安全、流式传输，单文件最多 4 GiB（4294967296 字节），SHA-256 双端校验后原子替换，默认不覆盖。" +
        "local_scope 默认 workspace，local_path 是以 / 为根的会话工作区路径；linux 仅限 Linux 会话且环境可用，Play 版不可用，使用 Linux 内绝对路径。" +
        "仅使用用户配置的主机别名；远端父目录必须存在，目录和符号链接不可作为文件。默认 15 秒让出后台，0 不让出；" +
        "background=true 立即返回任务号。用 job_status/job_wait 查看、job_stop 停止。最长运行由 max_runtime_minutes 决定，默认120分钟。"
    override val parametersSchema = """{"type":"object","properties":{"host":{"type":"string"},"local_path":{"type":"string"},"remote_path":{"type":"string"},"local_scope":{"type":"string","enum":["workspace","linux"]},"overwrite":{"type":"boolean"},"yield_after_seconds":{"type":"integer","description":"0–86400 秒，默认15；0 不让出"},$BACKGROUND_EXEC_SCHEMA},"required":["host","local_path","remote_path"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (ToolSchemaValidator().validate(parametersSchema, args) is ToolSchemaValidation.Invalid) throw SshFailure("INVALID_ARGUMENTS")
            val host = args.getValue("host").jsonPrimitive.content.also(SshPolicy::alias)
            val remote = args.getValue("remote_path").jsonPrimitive.content.also(SshPolicy::path)
            val scope = args["local_scope"]?.jsonPrimitive?.content ?: "workspace"
            val rawLocal = args.getValue("local_path").jsonPrimitive.content
            val localPath = if (scope == "workspace") normalizeSftpWorkspacePath(rawLocal) else {
                if (!rawLocal.startsWith('/') || '\u0000' in rawLocal || rawLocal.split('/').any { it == "." || it == ".." }) throw SshFailure("INVALID_LOCAL_PATH")
                rawLocal
            }
            if (!upload && isSftpProtectedLocalPath(localPath)) throw SshFailure("PROTECTED_LOCAL_PATH")
            if (scope == "linux" && context.runMode != SessionRunMode.LINUX) throw SshFailure("LINUX_SCOPE_REQUIRES_LINUX_SESSION")
            if (scope == "linux" && (!access.linuxAvailable() || access.linux == null)) throw SshFailure("LINUX_SCOPE_UNAVAILABLE")
            val overwrite = args["overwrite"]?.jsonPrimitive?.boolean ?: false
            val minutes = ExecToolArguments.integer(args, "max_runtime_minutes", 120, 1..1440)
            val threshold = ExecToolArguments.integer(args, "yield_after_seconds", 15, 0..(minutes * 60))
            val action = BackgroundJobExitAction.fromWire(args["on_exit"]?.jsonPrimitive?.content ?: "notify") ?: throw SshFailure("INVALID_ARGUMENTS")
            val boundLocal = if (scope == "linux") {
                val files = access.linux as? com.promenar.infinity.data.linux.LinuxFileSystemImpl
                com.promenar.infinity.data.workspace.SftpBoundLocalFile(
                    SftpLinuxFile(files?.forAgentTools() ?: requireNotNull(access.linux), localPath), "linux:${context.workspaceRootUuid}")
            } else (access.workspaceFiles ?: throw SshFailure("WORKSPACE_UNAVAILABLE")).resolve(
                context.sessionId, context.workspaceRootUuid, localPath, !upload)
            val snapshot = runtime.hostSnapshot(host)
            if (context.isApprovalBoundExecution || context.approvedExecutionTarget != null) {
                val target = ToolExecutionTargets.extract(toProtocolTool(), args.toString(), resolvedPaths = mapOf("sftp:local-identity" to boundLocal.identity), sshIdentities = mapOf(host to snapshot.executionIdentity()))
                val approved = context.approvedExecutionTarget
                if (target == null || approved == null || target != ToolExecutionTargets.withoutSftpPreview(approved)) throw SshFailure("HOST_AUTHORIZATION_CHANGED")
            }
            val preview = runtime.withHostSnapshot(snapshot) { sftpApprovalSnapshot(runtime, context.sessionId, host, remote,
                boundLocal.file, upload, overwrite) }
            if (context.isApprovalBoundExecution || context.approvedExecutionTarget != null) {
                val target = ToolExecutionTargets.extract(toProtocolTool(), args.toString(),
                    mapOf("sftp:local-identity" to boundLocal.identity), mapOf(host to snapshot.executionIdentity()))
                if (target == null || ToolExecutionTargets.withSftpPreview(target, preview.fields) != context.approvedExecutionTarget) throw SshFailure("TARGET_CONFLICT")
            }
            val transfer = SftpTransfer(if (upload) SftpTransferDirection.UPLOAD else SftpTransferDirection.DOWNLOAD,
                scope, localPath, remote, preview.fields.getValue("bytes_total").jsonPrimitive.long, 0, null)
            val manager = jobs ?: throw JobAccessException("JOB_UNAVAILABLE", "后台任务服务不可用，请重新打开应用。")
            val summary = if (upload) "上传 ${localPath.substringAfterLast('/')} → $remote" else "下载 ${remote.substringAfterLast('/')} → $localPath"
            val run = manager.runSsh(runtime, snapshot, context.sessionId, context.workspaceRootUuid, summary, 600, 32_000,
                threshold, args.backgroundRequested(), minutes, action, context.liveOutput, transfer) { owner, control, progress ->
                try {
                    if (scope == "linux" && !access.linuxAvailable()) throw SshFailure("LINUX_SCOPE_UNAVAILABLE")
                    val value = runtime.transfer(owner, host, remote, boundLocal.file, upload, overwrite, control, SftpLocalExpectation(preview.local), SftpRemoteExpectation(preview.remote), onTemporary = { path ->
                        manager.recordSftpTemporary(context.sessionId, owner.removePrefix("job:"), path, snapshot.executionIdentity().digest)
                    }, progress = progress)
                    CommandToolResult(exitCode = 0, backendFields = value.toJson())
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val code = transferErrorCode(failure)
                    CommandToolResult(errorCode = if (code.startsWith("SSH_")) code else "SSH_$code", guidance = sshGuidance(code), timedOut = code in setOf("TIMEOUT", "SERVICE_TIME_LIMIT"))
                }
            }
            val result = run.job?.let { job -> buildJsonObject {
                backgroundStartJson(job).forEach { (key, value) -> put(key, value) }
                put("guidance", "传输任务已登记；用 job_status/job_wait 查看进度和校验结果，用 job_stop 停止。")
                run.yieldedAfterSeconds?.let { put("yielded_after_seconds", it) }
                put("transfer", transferJson(job.transfer ?: SftpTransferRecord.from(transfer)))
            } } ?: checkNotNull(run.command).toJson()
            ToolResult("${id}_${UUID.randomUUID()}", result.toString(), run.command?.toolStatus ?: "success")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            val code = transferErrorCode(failure)
            ToolResult("${id}_${UUID.randomUUID()}", buildJsonObject {
                put("error_code", if (failure is JobAccessException) failure.code else if (code.startsWith("SSH_")) code else "SSH_$code")
                put("guidance", if (failure is JobAccessException) failure.message else sshGuidance(code))
            }.toString(), "error")
        }
    }
}

internal fun transferErrorCode(failure: Exception): String {
    if (failure is SshFailure) return failure.code
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(12).toList()
    if (causes.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC ||
        it is java.nio.file.FileSystemException && it.reason?.contains("No space left", ignoreCase = true) == true ||
        it is com.jcraft.jsch.SftpException && it.id == 14 }) return "NO_SPACE"
    if (failure is com.promenar.infinity.domain.storage.ExternalFolderException) return when (failure.code) {
        "READ_ONLY" -> "LOCAL_READ_ONLY"
        "INVALID_PATH", "OUTSIDE_ROOT" -> "INVALID_LOCAL_PATH"
        "CONFLICT", "NAME_EXISTS" -> "TARGET_CONFLICT"
        else -> "WORKSPACE_UNAVAILABLE"
    }
    if (failure is LinuxStreamingException) return failure.failureCode
    if (failure is LinuxFileException) {
        generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SshFailure>().firstOrNull()?.let { return it.code }
        return when (failure.code) {
            LinuxFileErrorCode.CHANGED, LinuxFileErrorCode.NAME_EXISTS -> "TARGET_CONFLICT"
            LinuxFileErrorCode.OUTSIDE_ROOTFS, LinuxFileErrorCode.NAME_INVALID -> "INVALID_LOCAL_PATH"
            LinuxFileErrorCode.NOT_READY -> "LINUX_SCOPE_UNAVAILABLE"
            LinuxFileErrorCode.NOT_FOUND -> "LOCAL_NOT_FOUND"
            else -> "LOCAL_IO_FAILED"
        }
    }
    return if (failure is IllegalArgumentException) "INVALID_ARGUMENTS" else "SSH_OPERATION_FAILED"
}

internal fun transferJson(transfer: SftpTransferRecord): JsonObject = buildJsonObject {
    put("direction", transfer.direction.lowercase()); put("local_scope", transfer.localScope)
    put("local_path", transfer.localPath); put("remote_path", transfer.remotePath)
    put("bytes_total", transfer.totalBytes?.let(::JsonPrimitive) ?: JsonNull)
    put("bytes_done", transfer.transferredBytes); put("bytes_per_second", transfer.bytesPerSecond?.let(::JsonPrimitive) ?: JsonNull)
    put("sha256", transfer.sha256?.let(::JsonPrimitive) ?: JsonNull)
    put("failure", transfer.failure?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
}

/** 审批预览为只读元数据；执行开始时重新核验同一快照。 */
internal data class SftpApprovalSnapshot(val local: SftpLocalStat?, val remote: SftpRemoteStat?, val fields: JsonObject)
internal suspend fun sftpApprovalSnapshot(runtime: SshRuntime, sessionId: String, host: String, remotePath: String,
    local: SftpLocalFile, upload: Boolean, overwrite: Boolean): SftpApprovalSnapshot {
    val localStat = local.stat()
    val remoteStat = runtime.transferStat("agent:$sessionId", host, remotePath)
    val size = if (upload) localStat?.size ?: throw SshFailure("LOCAL_NOT_FOUND") else remoteStat?.size ?: throw SshFailure("REMOTE_NOT_FOUND")
    requireSftpSize(size)
    if (remoteStat?.regular == false) throw SshFailure("UNSUPPORTED_TRANSFER_TARGET")
    val exists = if (upload) remoteStat != null else localStat != null
    val targetRevision = if (upload) remoteStat?.let { "${it.modifiedSeconds}:${it.size}" } else localStat?.revision
    val sourceRevision = if (upload) localStat?.revision else remoteStat?.let { "${it.modifiedSeconds}:${it.size}" }
    return SftpApprovalSnapshot(localStat, remoteStat, buildJsonObject {
        put("bytes_total", size); put("target_exists", exists); put("will_overwrite", exists && overwrite)
        put("source_revision", sourceRevision?.let(::JsonPrimitive) ?: JsonNull)
        put("target_revision", targetRevision?.let(::JsonPrimitive) ?: JsonNull)
    })
}
