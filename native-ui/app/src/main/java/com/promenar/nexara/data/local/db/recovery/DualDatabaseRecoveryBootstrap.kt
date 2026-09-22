package com.promenar.nexara.data.local.db.recovery

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal data class DualDatabaseSourcePair(
    val legacy: RecoveryDatabaseSource,
    val current: RecoveryDatabaseSource,
) {
    fun offerToken(): String = recoverySha256(
        "${legacy.manifestSha256}\n${current.manifestSha256}\n${legacy.userVersion}\n${current.userVersion}"
            .toByteArray(Charsets.UTF_8),
    )
}

internal interface DualDatabaseRecoverySourceProbe {
    /** 单库或完全无库返回 null；任一已存在来源不符合精确发布合同时抛错。 */
    fun inspectDualSources(): DualDatabaseSourcePair?
    fun startupBlocker(): String?
}

internal interface DualDatabaseRecoveryExecutor {
    fun begin(sources: DualDatabaseSourcePair): DualDatabaseRecoveryRecord
    fun resume(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord
}

/**
 * 正常 Room 构造前的唯一双库入口。锁覆盖探测、journal恢复和发布，调用方不得在此期间触发业务数据库 lazy。
 */
internal class DualDatabaseRecoveryBootstrap(
    lockDirectory: Path,
    private val sourceProbe: DualDatabaseRecoverySourceProbe,
    private val journal: DualDatabaseRecoveryJournal,
    private val executorProvider: () -> DualDatabaseRecoveryExecutor,
) {
    constructor(
        lockDirectory: Path,
        sourceProbe: DualDatabaseRecoverySourceProbe,
        journal: DualDatabaseRecoveryJournal,
        executor: DualDatabaseRecoveryExecutor,
    ) : this(lockDirectory, sourceProbe, journal, { executor })

    private val executor: DualDatabaseRecoveryExecutor by lazy(executorProvider)
    private val lockPath = lockDirectory.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it))
    }.resolve(".dual-database-recovery-v1.lock")

    fun inspectOrResume(): DualDatabaseBootstrapResult = withExclusiveLock {
        val persisted = journal.read()
        if (persisted != null) {
            if (persisted.stage == DualDatabaseRecoveryStage.COMPLETE) {
                return@withExclusiveLock DualDatabaseBootstrapResult.Completed(persisted.transactionId)
            }
            sourceProbe.startupBlocker()?.let { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it) }
            return@withExclusiveLock runCatching { executor.resume(persisted) }
                .fold(
                    onSuccess = { completed ->
                        if (completed.stage == DualDatabaseRecoveryStage.COMPLETE) {
                            DualDatabaseBootstrapResult.Completed(completed.transactionId)
                        } else DualDatabaseBootstrapResult.Blocked("双库恢复未收敛到完成状态")
                    },
                    onFailure = { DualDatabaseBootstrapResult.Blocked(it.safeDetail()) },
                )
        }
        val sources = runCatching { sourceProbe.inspectDualSources() }
            .getOrElse { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it.safeDetail()) }
            ?: return@withExclusiveLock DualDatabaseBootstrapResult.Normal
        sourceProbe.startupBlocker()?.let { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it) }
        DualDatabaseBootstrapResult.RecoveryRequired(
            DualDatabaseRecoveryOffer(
                offerToken = sources.offerToken(),
                legacyVersion = sources.legacy.userVersion,
                currentVersion = sources.current.userVersion,
                summary = "检测到公开 v${sources.legacy.userVersion} 与当前 v${sources.current.userVersion} 两份数据库。两份原始数据都会永久保留。",
            ),
        )
    }

    fun recover(offerToken: String): DualDatabaseBootstrapResult = withExclusiveLock {
        journal.read()?.let { persisted ->
            if (persisted.stage != DualDatabaseRecoveryStage.COMPLETE) {
                sourceProbe.startupBlocker()?.let { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it) }
            }
            return@withExclusiveLock runCatching { executor.resume(persisted) }
                .fold(
                    onSuccess = { result ->
                        if (result.stage == DualDatabaseRecoveryStage.COMPLETE) {
                            DualDatabaseBootstrapResult.Completed(result.transactionId)
                        } else DualDatabaseBootstrapResult.Blocked("双库恢复未收敛到完成状态")
                    },
                    onFailure = { DualDatabaseBootstrapResult.Blocked(it.safeDetail()) },
                )
        }
        val sources = runCatching { sourceProbe.inspectDualSources() }
            .getOrElse { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it.safeDetail()) }
            ?: return@withExclusiveLock DualDatabaseBootstrapResult.Normal
        sourceProbe.startupBlocker()?.let { return@withExclusiveLock DualDatabaseBootstrapResult.Blocked(it) }
        if (sources.offerToken() != offerToken) {
            return@withExclusiveLock DualDatabaseBootstrapResult.Blocked("双库来源在用户确认后发生变化，请重新检查")
        }
        runCatching { executor.begin(sources) }.fold(
            onSuccess = { result ->
                if (result.stage == DualDatabaseRecoveryStage.COMPLETE) {
                    DualDatabaseBootstrapResult.Completed(result.transactionId)
                } else DualDatabaseBootstrapResult.Blocked("双库恢复未收敛到完成状态")
            },
            onFailure = { DualDatabaseBootstrapResult.Blocked(it.safeDetail()) },
        )
    }

    private inline fun <T> withExclusiveLock(block: () -> T): T {
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw IllegalStateException("另一进程正在执行双库恢复")
            lock.use { return block() }
        }
    }
}

private fun Throwable.safeDetail(): String = message?.take(500)?.takeIf(String::isNotBlank) ?: "双库恢复发生未知错误"
