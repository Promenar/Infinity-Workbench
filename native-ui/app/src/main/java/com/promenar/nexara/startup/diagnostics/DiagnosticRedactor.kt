package com.promenar.nexara.startup.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** 不保留任意正文；只有完整匹配的固定错误原因和结构化元数据可以进入诊断。 */
object DiagnosticRedactor {
    private const val OMITTED = "[内容已隐藏]"
    private const val APP_PACKAGE = "com.promenar.nexara."
    private const val MAX_CAUSES = 4
    private const val MAX_CAUSE_FRAMES = 4
    private val safeMessages = setOf(
        "移动类 journal 文件布局冲突", "移动类 journal 的数据库路径冲突",
        "移动类 journal 的数据库目标不存在", "文件 journal 恢复失败",
        "文件 journal payload 摘要不匹配", "文件 journal 缺少数据库目标",
        "文件 journal 缺少根身份", "文件 journal 缺少目标路径", "文件 journal 工作区根不存在",
        "文件 journal 根身份与数据库不匹配", "文件 journal 无法清理",
        "文件 journal 相对路径无效", "文件 journal 路径越界", "文件 journal 数据库路径无效",
        "创建类 journal 的 ownership manifest 损坏", "创建类 journal 的 ownership target 不匹配",
        "创建类 journal 缺少归属 manifest", "旧创建 journal 缺少持续归属证明，节点已保留",
        "PREPARED 创建 journal 意外存在数据库目标", "创建类 journal 的物理目标缺少 operation 归属证明",
        "创建回滚存在多个隔离节点", "DB_COMMITTED 创建 journal 缺少数据库目标",
        "DB_COMMITTED 创建 journal 数据库路径不匹配", "DB_COMMITTED 创建 journal 缺少物理目标",
        "DB_COMMITTED 创建 journal 物理目标不属于原 staged node",
        "DB_COMMITTED 创建 journal 物理身份与数据库不匹配", "删除类 journal tombstone 布局冲突",
        "PREPARED journal 文件布局冲突", "DB_COMMITTED journal 同时存在 source 与 target",
        "工作区 tombstone 恢复存在冲突", "多个工作区根记录指向同一规范物理目录",
        "多个 Session 指向同一规范工作区根", "工作区根不能是符号链接", "会话工作区绑定已变化",
        "会话根身份或路径不一致", "会话根路径不一致",
        "工作区根数据库结构无效", "工作区根记录不唯一", "工作区根不是普通目录",
        "工作区存在未收尾的内部暂存，不能放弃 journal", "工作区数据库路径重复",
        "工作区数据库节点的物理根不一致", "工作区数据库父子关系不一致",
        "工作区数据库节点缺少物理对应", "工作区数据库节点的类型或内容不一致",
        "工作区物理布局在检查期间发生变化", "工作区包含符号链接或非普通节点",
        "工作区节点缺少稳定身份", "工作区数据库路径无效", "工作区数据库路径越界",
        "工作区根标识不能为空", "该 journal 类型需要保留内部恢复证据，不能安全放弃",
        "文件 journal 的根身份或数据库目标无效", "文件 journal 或工作区数据库在检查后发生变化",
        "工作区文件在检查后发生变化", "文件 journal 无法安全放弃", "会话标识不能为空",
        com.promenar.nexara.data.repository.LEGACY_ROOT_UNVERIFIED, "工作区根在身份格式迁移前发生变化",
        "文件 journal 在身份格式迁移期间发生变化", "工作区新旧身份的私有区同时存在，拒绝合并",
        "DELETE journal 无法确定恢复范围", "journal payload 摘要不匹配", "journal payload 无效",
        "journal 缺少根身份", "journal 缺少目标路径", "journal stage 目标与 operationId 不匹配",
        "会话 journal 源不是专用根", "会话 journal 仍有暂存节点，不能安全放弃",
        "PREPARED journal 的会话或源目录不存在", "DB_COMMITTED journal 的会话或源目录仍存在",
        "会话 journal 的物理布局在检查后发生变化", "会话 journal 或数据库状态发生变化，未完成安全放弃",
        "不支持自动恢复的 journal 类型", "journal 缺少会话标识", "journal stage 目标不在保留目录",
        "PREPARED journal 的会话已消失", "DB_COMMITTED journal 的会话仍存在", "工作区父目录无效",
        "journal 相对路径无效", "journal 路径越界", "journal source 使用保留目录",
        "journal 路径经过符号链接或非目录节点", "journal 目标不是普通目录", "工作区根身份验证失败",
        "数据库恢复失败", "备份恢复失败", "启动恢复失败", "工具台账恢复失败", "向量队列恢复失败",
        // 工作区文件端口、根身份与创建归属的固定原因，均不含路径或用户内容。
        "创建 ownership 维护清理失败", "创建回滚隔离节点已存在", "创建归属证明版本不可安全恢复，节点已保留", "创建持久身份只接受普通文件", "创建文件FD与目录绑定不匹配",
        "创建文件FD缺少稳定身份", "创建文件删除阶段令牌不匹配", "创建文件持久身份属性不匹配", "创建目录FD与目录绑定不匹配", "创建目录FD缺少稳定身份", "创建目录删除阶段令牌不匹配",
        "创建目录删除阶段证明不匹配", "创建目录包含额外内容，拒绝回滚删除", "创建目录持久标记不匹配", "创建目录标记已变化", "创建节点内容或身份发生变化",
        "创建身份锚点不支持 SecureDirectoryStream", "创建身份锚点不是目录", "工作区内部目录不是普通目录", "工作区文件无法按数据库哈希恢复", "工作区根目录FD身份与旧标记冲突",
        "工作区根目录FD身份与私有索引冲突", "工作区根目录在打开期间发生替换", "工作区根目录身份无效", "工作区根缺少受信父目录", "工作区根缺少持久身份标记", "工作区根缺少目录FD身份，拒绝从私有索引重建",
        "工作区根缺少稳定fileKey", "工作区根缺少进程内数据库身份绑定", "工作区根身份与数据库认领不一致", "工作区根身份标记与目录 fileKey 不一致", "工作区根身份标记过大",
        "工作区目录缺少稳定 fileKey", "工作区目录路径绑定发生变化", "工作区目录路径绑定无法复核", "工作区目标不可为符号链接", "工作区相对路径无效", "工作区节点缺少 fileKey",
        "已认领工作区根目录不存在", "当前文件系统不支持 SecureDirectoryStream，拒绝降低路径安全保证", "当前文件系统不支持工作区文件 fsync", "文件端口不支持安全跨根移动",
        "清理目标不是普通节点", "清理目标不能是符号链接", "私有元数据不支持fsync", "私有元数据目录不能是符号链接", "私有元数据目录无效", "私有工作区元数据目录在打开期间发生替换",
        "私有工作区元数据缺少安全目录能力", "私有工作区元数据过大", "私有根身份与旧标记冲突", "私有根身份并发记录冲突", "私有根身份记录冲突",
    )
    /** 系统调用失败只保留函数名、errno 与系统描述，不含路径。 */
    private val errnoMessage = Regex("[A-Za-z_][A-Za-z0-9_]{0,40} failed: E[A-Z0-9]{1,15} \\([A-Za-z ,'-]{1,60}\\)")
    private val exceptionTypes = setOf(
        "Throwable", "Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException",
        "UnsupportedOperationException", "SecurityException", "IOException", "FileNotFoundException",
        "EOFException", "SocketException", "SocketTimeoutException", "UnknownHostException",
        "TimeoutException", "CancellationException", "TimeoutCancellationException", "SQLiteException",
        "SQLiteDatabaseCorruptException", "OutOfMemoryError", "AssertionError", "LinkageError",
        "WorkspaceMutationRecoveryException", "BackupStartupException", "BackupValidationException",
        "StartupCriticalRecoveryException", "StartupIsolationException",
        "SnapshotPublicationUncertainException", "SnapshotStagingRetainedException",
        "ErrnoException", "FileAlreadyExistsException", "NoSuchFileException", "AccessDeniedException",
        "FileSystemException", "ExternalFolderException",
    )
    private val startupSteps = setOf(
        "DUAL_DATABASE", "BACKUP_RESTORE", "WRITER_INITIALIZATION", "WORKSPACE_INVENTORY",
        "LEGACY_ADOPTION", "WORKSPACE_JOURNAL", "SESSION_JOURNAL", "TOMBSTONES", "TOOL_LEDGER",
        "VECTOR_QUEUE", "BACKGROUND_JOBS", "HEADLESS_INTERACTIONS", "SCHEDULED_TASKS", "MODEL_CATALOG",
        "MEMORY_CLEANUP",
    )
    private val scopeKinds = setOf("GLOBAL", "WORKSPACE_ROOT", "SESSION", "SUBSYSTEM")
    private val safeIdentifiers = startupSteps + scopeKinds + setOf(
        "application", "WORKSPACE_RECOVERY", "WorkspaceJournal", "ToolLedger", "BackupStartupRecovery",
        "SessionMountNormalization", "SystemTriggerResume", "SystemTriggerNotification",
        "VectorizationQueue.handoff", "VectorizationQueue.resumeAfterHandoff", "VectorQueue.processNext",
        "WorkspaceCommand.clearMarker", "WorkspaceCommand.reconcile", "WorkspaceCommand.recover",
        "WorkspaceCommand.afterDelete", "WorkspaceCommand.publishIndex", "WorkspaceDelete.afterCommitted",
        "WorkspaceRecycle.afterCommitted", "WorkspaceRestore.afterCommitted", "WorkspaceCreate.cleanupOwnership",
        "SessionListViewModel.loadSessions",
    )
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val reference = Regex("ref-[0-9a-f]{16}")
    private val className = Regex("com\\.promenar\\.nexara\\.[A-Za-z_$][A-Za-z0-9_.$]{0,175}")
    private val methodName = Regex("(?:[A-Za-z_$][A-Za-z0-9_$-]{0,95}|<init>|<clinit>)")
    private val sourceName = Regex("[A-Za-z_$][A-Za-z0-9_$.-]{0,95}\\.(?:kt|java)")
    private val storedFrame = Regex("(.+)\\.([^.(]+)\\(([^:()]+):(\\d{1,10})\\)")
    private val issueLine = Regex(
        "issue=(\\S{1,240}) type=(\\S{1,80}) severity=(\\S{1,40}) scope=(\\S{1,160}) " +
            "step=(\\S{1,80}) summary=(.{0,240}) actions=(\\S{1,40}) occurredAt=(\\d{1,19})",
    )

    fun redact(text: String): String = when {
        text in safeMessages -> text
        safeSummary(text) != null -> text
        else -> OMITTED
    }

    fun summary(error: Throwable): String {
        val type = exceptionType(error)
        val message = error.message
        val safeMessage = when {
            message in safeMessages -> message
            message != null && errnoMessage.matches(message) -> message
            message != null -> safeSummary(message)?.substringAfter(": ") ?: OMITTED
            else -> OMITTED
        }
        return "$type: $safeMessage"
    }

    /** 内部范围标识保留既有 UUID；未知值只保留稳定关联码。 */
    fun safeIdentifier(value: String): String {
        if (isSafeIdentifier(value)) return value
        return referenceFor(value)
    }

    private fun referenceFor(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return "ref-" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun snapshot(step: String, error: Throwable, scopeKind: String? = null, scopeId: String? = null): DiagnosticEvent = DiagnosticEvent(
        timestamp = System.currentTimeMillis(),
        step = step.takeIf { it in safeIdentifiers } ?: referenceFor(step),
        exceptionType = exceptionType(error),
        summary = summary(error),
        frames = appFrames(error, 8),
        scopeKind = scopeKind?.takeIf { it in scopeKinds },
        scopeId = scopeId?.takeIf { scopeKind in scopeKinds }?.let(::safeIdentifier),
        causes = causeChain(error).map { cause ->
            DiagnosticCause(exceptionType(cause), summary(cause), appFrames(cause, MAX_CAUSE_FRAMES))
        },
    )

    /** 外层恢复器常把原因包装成统一异常；保留有限的 cause 链才能定位真正失败的检查。 */
    private fun causeChain(error: Throwable): List<Throwable> {
        val seen = mutableSetOf<Throwable>(error)
        return generateSequence(error.cause) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES).toList()
    }

    private fun appFrames(error: Throwable, limit: Int): List<String> =
        error.stackTrace.asSequence().filter { it.className.startsWith(APP_PACKAGE) }
            .mapNotNull { frame -> safeFrame(frame.className, frame.methodName, frame.fileName, frame.lineNumber) }
            .take(limit).toList()

    private fun sanitizeFrames(frames: List<String>, limit: Int): List<String> =
        frames.asSequence().take(limit).mapNotNull { value ->
            if (value.length > 320) return@mapNotNull null
            val match = storedFrame.matchEntire(value) ?: return@mapNotNull null
            val (clazz, method, source, line) = match.destructured
            safeFrame(clazz, method, source.takeUnless { it == "<source>" }, line.toIntOrNull() ?: 0)
        }.toList()

    internal fun sanitize(event: DiagnosticEvent): DiagnosticEvent = event.copy(
        timestamp = event.timestamp.coerceAtLeast(0),
        step = event.step.takeIf { it in safeIdentifiers || reference.matches(it) } ?: referenceFor(event.step),
        exceptionType = event.exceptionType.takeIf { it in exceptionTypes } ?: "Throwable",
        summary = safeSummary(event.summary) ?: OMITTED,
        scopeKind = event.scopeKind?.takeIf { it in scopeKinds },
        scopeId = event.scopeId?.takeIf { event.scopeKind in scopeKinds }?.let(::safeIdentifier),
        frames = sanitizeFrames(event.frames, 8),
        causes = event.causes.take(MAX_CAUSES).map { cause ->
            DiagnosticCause(
                exceptionType = cause.exceptionType.takeIf { it in exceptionTypes } ?: "Throwable",
                summary = safeSummary(cause.summary) ?: OMITTED,
                frames = sanitizeFrames(cause.frames, MAX_CAUSE_FRAMES),
            )
        },
    )

    internal fun sanitizeIssueSummary(text: String): String {
        if (text.isBlank()) return "无启动问题"
        return text.lineSequence().joinToString("\n") { line ->
            val match = if (line.length <= 1_200) issueLine.matchEntire(line) else null
            if (match == null) return@joinToString OMITTED
            val values = match.groupValues
            val id = safeIssueId(values[1])
            val type = values[2].takeIf { it in startupSteps } ?: OMITTED
            val severity = values[3].takeIf { it == "BLOCKING" || it == "ISOLATED" } ?: OMITTED
            val scope = safeIdentifier(values[4])
            val step = values[5].takeIf { it in startupSteps } ?: OMITTED
            val summary = safeSummary(values[6]) ?: OMITTED
            val actions = values[7].split(',').distinct().takeIf { items ->
                items.isNotEmpty() && items.all { it == "RETRY" || it == "ABANDON" }
            }?.joinToString(",") ?: OMITTED
            val occurredAt = values[8].toLongOrNull()?.toString() ?: "0"
            "issue=$id type=$type severity=$severity scope=$scope step=$step summary=$summary " +
                "actions=$actions occurredAt=$occurredAt"
        }
    }

    private fun safeIssueId(value: String): String {
        if (uuid.matches(value)) return value
        val parts = value.split(':')
        return value.takeIf {
            parts.size == 3 && parts[0] in startupSteps && parts[1] in scopeKinds && isSafeIdentifier(parts[2])
        } ?: OMITTED
    }

    private fun isSafeIdentifier(value: String): Boolean =
        value in safeIdentifiers || uuid.matches(value) || reference.matches(value)

    private fun safeSummary(value: String): String? {
        val separator = value.indexOf(": ")
        if (separator < 0 || value.substring(0, separator) !in exceptionTypes) return null
        val message = value.substring(separator + 2)
        return value.takeIf { message == OMITTED || message in safeMessages || errnoMessage.matches(message) }
    }

    private fun exceptionType(error: Throwable): String {
        val name = error.javaClass.simpleName
        if (name in exceptionTypes) return name
        return when (error) {
            is IllegalStateException -> "IllegalStateException"
            is IllegalArgumentException -> "IllegalArgumentException"
            is java.io.IOException -> "IOException"
            is SecurityException -> "SecurityException"
            is RuntimeException -> "RuntimeException"
            is Exception -> "Exception"
            else -> "Throwable"
        }
    }

    private fun safeFrame(clazz: String, method: String, source: String?, line: Int): String? {
        if (clazz.length > 200 || !className.matches(clazz) || !methodName.matches(method)) return null
        val safeSource = source?.takeIf { sourceName.matches(it) } ?: "<source>"
        return "$clazz.$method($safeSource:${line.coerceAtLeast(0)})"
    }
}

@Serializable
internal data class DiagnosticEvent(
    val timestamp: Long,
    val step: String,
    val exceptionType: String,
    val summary: String,
    val frames: List<String>,
    val scopeKind: String? = null,
    val scopeId: String? = null,
    val causes: List<DiagnosticCause> = emptyList(),
)

@Serializable
internal data class DiagnosticCause(
    val exceptionType: String,
    val summary: String,
    val frames: List<String>,
)
