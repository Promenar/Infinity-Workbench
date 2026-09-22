package com.promenar.nexara.data.local.db.recovery

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.promenar.nexara.data.local.db.MIGRATION_1_2
import com.promenar.nexara.data.local.db.MIGRATION_2_3
import com.promenar.nexara.data.local.db.MIGRATION_3_4
import com.promenar.nexara.data.local.db.MIGRATION_4_5
import com.promenar.nexara.data.local.db.MIGRATION_5_18
import com.promenar.nexara.data.local.db.MIGRATION_17_18
import com.promenar.nexara.data.local.db.NexaraDatabase
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val MAX_RECOVERY_ASSET_FILES = 10_000
private const val MAX_RECOVERY_JSON_CHARS = 24 * 1024 * 1024
private const val MAX_RECOVERY_JSON_TOTAL_CHARS = 64L * 1024L * 1024L

internal class AndroidDualDatabaseRecoveryExecutor(
    context: Context,
    private val journal: DualDatabaseRecoveryJournal,
    private val snapshotStore: RecoverySnapshotStore,
    private val faultHook: ((DualDatabaseRecoveryFaultPoint) -> Unit)? = null,
) : DualDatabaseRecoveryExecutor {
    private val appContext = context.applicationContext
    private val databaseDirectory = requireNotNull(appContext.getDatabasePath(CURRENT_DATABASE_NAME).parentFile).toPath()
    private val noBackup = requireNotNull(androidx.core.content.ContextCompat.getNoBackupFilesDir(appContext)).toPath()
    private val archiveRoot = noBackup.resolve("dual-db-source-archives-v1")
    private val treeArchiveRoot = noBackup.resolve("dual-db-tree-archives-v1")
    private val workingRoot = noBackup.resolve("dual-db-working-v1")
    private val retainedRoot = noBackup.resolve("dual-db-retained-live-v1")
    private val publishedTreeRoot = noBackup.resolve("dual-db-recovered-files-v1")
    private val publishedWorkspaceParent = appContext.filesDir.toPath()

    override fun begin(sources: DualDatabaseSourcePair): DualDatabaseRecoveryRecord {
        val txId = UUID.randomUUID().toString()
        ensureDirectory(archiveRoot)
        val snapshot = snapshotStore.createSnapshot(
            transactionId = txId,
            sourceRoot = databaseDirectory,
            databaseFile = databaseDirectory.resolve(LEGACY_DATABASE_NAME),
            archiveRoot = archiveRoot,
        )
        val record = DualDatabaseRecoveryRecord(
            transactionId = txId,
            stage = DualDatabaseRecoveryStage.SNAPSHOTTED,
            legacy = sources.legacy,
            current = sources.current,
            snapshotManifestSha256 = snapshot.sourceManifestSha256,
        )
        journal.write(record)
        return resume(record)
    }

    override fun resume(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
        var current = record
        if (current.stage == DualDatabaseRecoveryStage.SNAPSHOTTED) current = stageWorkingCopies(current)
        if (current.stage == DualDatabaseRecoveryStage.STAGED) current = publishFileTrees(current)
        if (current.stage == DualDatabaseRecoveryStage.FILES_PUBLISHED) current = buildCandidate(current)
        if (current.stage == DualDatabaseRecoveryStage.DB_PUBLISHING) current = publishDatabase(current)
        if (current.stage == DualDatabaseRecoveryStage.DB_PUBLISHED) {
            current = current.copy(stage = DualDatabaseRecoveryStage.COMPLETE)
            journal.write(current)
        }
        return current
    }

    private fun stageWorkingCopies(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
        val attemptId = "attempt-${UUID.randomUUID()}"
        val attemptRoot = workingRoot.resolve(record.transactionId).resolve(attemptId)
        ensureDirectory(attemptRoot)
        val snapshotFiles = archiveRoot.resolve(record.transactionId).resolve("files")
        val legacy = attemptRoot.resolve("legacy-v18.db")
        val current = attemptRoot.resolve("current-v18.db")
        materializeDatabase(snapshotFiles, record.legacy, legacy)
        materializeDatabase(snapshotFiles, record.current, current)
        migrateToV18(legacy)
        normalizeTrustedAppPathAliases(legacy)
        ensureRagWorkspaceSession(legacy, recoveredTitle = "恢复的旧版知识库")
        checkpointAndVerifyV18(legacy)
        faultHook?.invoke(DualDatabaseRecoveryFaultPoint.AFTER_LEGACY_MIGRATION)
        migrateToV18(current)
        normalizeTrustedAppPathAliases(current)
        ensureRagWorkspaceSession(current, recoveredTitle = "当前知识库")
        checkpointAndVerifyV18(current)
        faultHook?.invoke(DualDatabaseRecoveryFaultPoint.BEFORE_STAGED_JOURNAL)
        val next = record.copy(
            stage = DualDatabaseRecoveryStage.STAGED,
            workingAttemptId = attemptId,
            workingLegacySha256 = digest(legacy),
            workingCurrentSha256 = digest(current),
        )
        journal.write(next)
        return next
    }

    private fun publishFileTrees(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
        val txRoot = workingAttemptRoot(record)
        val legacyDb = txRoot.resolve("legacy-v18.db")
        val currentDb = txRoot.resolve("current-v18.db")
        check(digest(legacyDb) == record.workingLegacySha256) { "旧侧working副本摘要变化" }
        var staged = record
        if (!staged.treeArchivesComplete) {
            ensureDirectory(treeArchiveRoot)
            val currentHasRag = databaseHasSession(currentDb, RAG_WORKSPACE_SESSION_ID)
            val discovered = discoverRoots("legacy", legacyDb, record.transactionId, preserveFixedRagId = !currentHasRag) +
                discoverRoots("current", currentDb, record.transactionId, preserveFixedRagId = true)
            discovered.forEach { plan ->
                val existing = staged.treePublications.firstOrNull {
                    it.role == plan.role && it.sourcePath == plan.source.toString() &&
                        it.activeWorkspace == plan.activeWorkspace && it.assetBundle == plan.assetBundle
                }
                if (existing != null) {
                    check(existing.role == plan.role && existing.sourcePath == plan.source.toString() &&
                        existing.activeWorkspace == plan.activeWorkspace && existing.assetBundle == plan.assetBundle &&
                        existing.ownerSessionId == plan.ownerSessionId &&
                        existing.selectedRelativeFiles == plan.selectedRelativeFiles) {
                        "恢复树归档回执与发现结果不一致"
                    }
                    return@forEach
                }
                if (!Files.isDirectory(plan.source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(plan.source)) {
                    throw IllegalStateException("恢复引用的工作区根缺失或不是安全目录")
                }
                val targetParent = if (plan.activeWorkspace || plan.assetBundle) publishedWorkspaceParent else publishedTreeRoot
                val targetRelative = targetParent.relativize(plan.target).toString().replace('\\', '/')
                val attempt = RecoveryTreeArchiveAttempt(
                    role = plan.role,
                    sourcePath = plan.source.toString(),
                    targetRelativePath = targetRelative,
                    activeWorkspace = plan.activeWorkspace,
                    assetBundle = plan.assetBundle,
                    ownerSessionId = plan.ownerSessionId,
                    selectedRelativeFiles = plan.selectedRelativeFiles,
                    snapshotTransactionId = plan.snapshotId,
                )
                staged = staged.copy(treeArchiveAttempts = staged.treeArchiveAttempts + attempt)
                journal.write(staged)
                val manifest = snapshotStore.createTreeSnapshot(plan.snapshotId, plan.source, treeArchiveRoot)
                faultHook?.invoke(DualDatabaseRecoveryFaultPoint.AFTER_TREE_ARCHIVE_BEFORE_JOURNAL)
                val publication = RecoveryTreePublication(
                    role = plan.role,
                    activeWorkspace = plan.activeWorkspace,
                    assetBundle = plan.assetBundle,
                    ownerSessionId = plan.ownerSessionId,
                    snapshotTransactionId = plan.snapshotId,
                    sourcePath = plan.source.toString(),
                    sourceRootIdentity = manifest.sourceIdentitySha256,
                    sourceTreeSha256 = manifest.sourceManifestSha256,
                    selectedRelativeFiles = plan.selectedRelativeFiles,
                    targetRelativePath = targetRelative,
                )
                staged = staged.copy(treePublications = staged.treePublications + publication)
                journal.write(staged)
            }
            check(staged.treePublications.size == discovered.size && discovered.all { plan ->
                staged.treePublications.any {
                    it.role == plan.role && it.sourcePath == plan.source.toString() &&
                        it.activeWorkspace == plan.activeWorkspace && it.assetBundle == plan.assetBundle
                }
            }) { "恢复树归档回执不完整" }
            staged = staged.copy(treeArchivesComplete = true)
            journal.write(staged)
        }
        val plans = staged.treePublications.map { publication ->
            RootPlan(
                publication.role,
                Paths.get(publication.sourcePath).toAbsolutePath().normalize(),
                (if (publication.activeWorkspace || publication.assetBundle) publishedWorkspaceParent else publishedTreeRoot)
                    .resolve(publication.targetRelativePath).normalize(),
                publication.snapshotTransactionId,
                publication.activeWorkspace,
                publication.assetBundle,
                publication.ownerSessionId,
                publication.selectedRelativeFiles,
            ).also {
                val parent = if (publication.activeWorkspace || publication.assetBundle) publishedWorkspaceParent else publishedTreeRoot
                check(it.target.startsWith(parent)) { "恢复树发布路径越界" }
            }
        }
        ensureDirectory(treeArchiveRoot)
        var publishing = staged
        staged.treePublications.forEach { originalPublication ->
            var publication = publishing.treePublications.first {
                it.snapshotTransactionId == originalPublication.snapshotTransactionId
            }
            val plan = plans.first { it.snapshotId == publication.snapshotTransactionId }
            val completed = if (publication.activeWorkspace || publication.assetBundle) {
                val materialized = snapshotStore.materializeVerifiedArchive(
                    transactionId = publication.snapshotTransactionId,
                    archiveRoot = treeArchiveRoot,
                    expectedSourceManifestSha256 = publication.sourceTreeSha256,
                    targetDirectory = plan.target,
                    expectedTargetIdentity = publication.targetRootIdentity,
                    selectedRelativeFiles = publication.selectedRelativeFiles.takeIf { publication.assetBundle }?.toSet(),
                    onPrepared = { identity ->
                        publication = publication.copy(targetRootIdentity = identity)
                        publishing = publishing.withTreePublication(publication)
                        journal.write(publishing)
                    },
                )
                check(materialized.rootIdentity == publication.targetRootIdentity) {
                    "恢复工作区身份与prepared回执不一致"
                }
                publication.copy(
                    targetTreeSha256 = materialized.manifest.sourceManifestSha256,
                    targetRootIdentity = materialized.rootIdentity,
                    published = true,
                )
            } else {
                val archived = snapshotStore.readVerifiedArchive(
                    publication.snapshotTransactionId,
                    treeArchiveRoot,
                    publication.sourceTreeSha256,
                    publication.sourceRootIdentity,
                )
                val archivedFiles = treeArchiveRoot.resolve(plan.snapshotId).resolve("files")
                val publishId = requireNotNull(plan.target.parent).fileName.toString()
                val publishBase = requireNotNull(plan.target.parent).parent
                val materializer = RecoverySnapshotStore(
                    allowedSourceRoots = setOf(archivedFiles),
                    maxFiles = maxOf(1, archived.files.size + archived.directories.size),
                    maxTotalBytes = archived.totalBytes,
                    trustedAppDataRoot = appContext.dataDir.toPath(),
                )
                val published = materializer.createTreeSnapshot(publishId, archivedFiles, publishBase)
                check(published.sourceManifestSha256 == publication.sourceTreeSha256) {
                    "恢复树二次发布清单与永久archive不一致"
                }
                publication.copy(targetTreeSha256 = published.sourceManifestSha256, published = true)
            }
            publishing = publishing.withTreePublication(completed)
            journal.write(publishing)
        }
        val next = publishing.copy(
            stage = DualDatabaseRecoveryStage.FILES_PUBLISHED,
        )
        journal.write(next)
        return next
    }

    private fun buildCandidate(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
        val txRoot = workingAttemptRoot(record)
        val legacy = txRoot.resolve("legacy-v18.db")
        val current = txRoot.resolve("current-v18.db")
        check(digest(legacy) == record.workingLegacySha256 && digest(current) == record.workingCurrentSha256) {
            "双库working副本与journal不一致"
        }
        // FILES_PUBLISHED阶段可能在候选checkpoint或journal落盘前中断。每次使用新名称，
        // 旧候选及其sidecar原样保留，绝不让下一次尝试回放上一次未认证的WAL。
        val candidateName = "candidate-${UUID.randomUUID()}.db"
        val candidate = txRoot.resolve(candidateName)
        copyVerified(current, candidate)
        val materializedPublications = record.treePublications.filter { it.activeWorkspace || it.assetBundle }
        val activePublications = materializedPublications.filter(RecoveryTreePublication::activeWorkspace)
        check(materializedPublications.all { it.published && it.targetRootIdentity != null }) {
            "恢复候选缺少工作区发布身份"
        }
        fun targetPath(publication: RecoveryTreePublication): Path = publishedWorkspaceParent
            .resolve(publication.targetRelativePath).normalize().also {
                check(it.startsWith(publishedWorkspaceParent)) { "恢复工作区目标路径越界" }
            }
        val pathMap = pathMapForRole(record, "legacy")
        val currentPathMap = pathMapForRole(record, "current")
        rewriteWorkspacePaths(candidate, currentPathMap)
        checkpointAndVerifyV18(candidate)
        val rootIdentities = activePublications.associate { publication ->
            targetPath(publication).toString() to requireNotNull(publication.targetRootIdentity)
        }
        val commitMarker = "dual-database-recovery-v1:${record.transactionId}"
        val merge = DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value ->
            rewriteKnownPath(value, pathMap)
        }, rootIdentities).merge(candidate, legacy, commitMarker)
        checkpointAndVerifyV18(candidate)
        val unresolved = merge.unresolvedReferences.joinToString("\n").toByteArray(Charsets.UTF_8)
        val next = record.copy(
            stage = DualDatabaseRecoveryStage.DB_PUBLISHING,
            candidateRelativePath = candidateName,
            candidateSha256 = digest(candidate),
            candidateRoomIdentity = CURRENT_V18_IDENTITY,
            candidateCommitMarker = commitMarker,
            idMappingSha256 = merge.idMappingSha256,
            unresolvedReferenceReportSha256 = recoverySha256(unresolved),
            unresolvedUniqueData = merge.unresolvedReferences.any { it.startsWith("required:") },
        )
        journal.write(next)
        return next
    }

    private fun publishDatabase(record: DualDatabaseRecoveryRecord): DualDatabaseRecoveryRecord {
        check(!record.unresolvedUniqueData) { "双库候选仍含不可达唯一数据" }
        val candidate = workingAttemptRoot(record).resolve(requireNotNull(record.candidateRelativePath))
        val live = databaseDirectory.resolve(CURRENT_DATABASE_NAME)
        val retained = retainedRoot.resolve(record.transactionId)
        ensureDirectory(retained)
        var receipts = record.databaseMoves.toMutableList()

        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS) && digest(live) == record.candidateSha256) {
            forceDirectory(requireNotNull(candidate.parent))
            forceDirectory(databaseDirectory)
            verifyPublishedCandidate(live, record)
        } else {
            listOf("", "-wal", "-journal", "-shm").forEach { suffix ->
                val source = live.resolveSibling(CURRENT_DATABASE_NAME + suffix)
                val target = retained.resolve(CURRENT_DATABASE_NAME + suffix)
                val existing = receipts.firstOrNull { it.sourceRelativePath == source.fileName.toString() }
                if (existing?.completed == true) {
                    forceDirectory(requireNotNull(source.parent))
                    forceDirectory(requireNotNull(target.parent))
                    check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                        Files.size(target) == existing.size && digest(target) == existing.sha256)
                    check(!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) { "已保留数据库文件在原路径再次出现" }
                } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    check(existing != null && !Files.exists(source, LinkOption.NOFOLLOW_LINKS) &&
                        Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                        "数据库rename窗口存在无法解释的双份文件"
                    }
                    val sha = digest(target)
                    check(sha == existing.sha256 && Files.size(target) == existing.size) {
                        "事务保留数据库文件与prepared receipt不符"
                    }
                    forceDirectory(requireNotNull(source.parent))
                    forceDirectory(requireNotNull(target.parent))
                    record.current.files.firstOrNull { it.relativePath == source.fileName.toString() }?.let {
                        check(it.sha256 == sha) { "事务保留数据库文件与源manifest不符" }
                    }
                    receipts = (receipts.filterNot { it.sourceRelativePath == source.fileName.toString() } +
                        existing.copy(completed = true)).toMutableList()
                    journal.write(record.copy(databaseMoves = receipts))
                } else if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    return@forEach
                } else {
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
                    val sha = digest(source)
                    val node = snapshotFileSystem().openDirectory(source.parent).use {
                        requireNotNull(it.node(source.fileName.toString()))
                    }
                    val prepared = RecoveryMoveReceipt(
                        source.fileName.toString(), target.fileName.toString(), sha, node.size, node.key, false,
                    )
                    receipts = (receipts.filterNot { it.sourceRelativePath == source.fileName.toString() } + prepared).toMutableList()
                    journal.write(record.copy(databaseMoves = receipts))
                    moveAtomic(source, target)
                    receipts = (receipts.filterNot { it.sourceRelativePath == source.fileName.toString() } +
                        prepared.copy(completed = true)).toMutableList()
                    journal.write(record.copy(databaseMoves = receipts))
                }
            }
            check(!Files.exists(live, LinkOption.NOFOLLOW_LINKS)) { "旧主库尚未完整移入事务保留区" }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) moveAtomic(candidate, live)
            else check(Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS) && digest(live) == record.candidateSha256) {
                "候选数据库发布窗口状态无法认证"
            }
            forceDirectory(databaseDirectory)
            verifyPublishedCandidate(live, record)
        }
        val next = record.copy(stage = DualDatabaseRecoveryStage.DB_PUBLISHED, databaseMoves = receipts)
        journal.write(next)
        return next
    }

    private fun migrateToV18(path: Path) {
        val isolatedContext = object : ContextWrapper(appContext) {
            override fun getDatabasePath(name: String): java.io.File = path.toFile()
        }
        val database = Room.databaseBuilder(isolatedContext, NexaraDatabase::class.java, path.fileName.toString())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_18, MIGRATION_17_18)
            .build()
        try {
                database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 18) { "working数据库迁移未达到v18" }
                }
                database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "working数据库WAL无法checkpoint" }
                }
        } finally { database.close() }
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + "-shm"))
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + "-wal"))
    }

    private fun materializeDatabase(snapshotFiles: Path, source: RecoveryDatabaseSource, targetMain: Path) {
        source.files.forEach { entry ->
            val sourceFile = snapshotFiles.resolve(entry.relativePath).normalize()
            check(sourceFile.startsWith(snapshotFiles) && Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS))
            val suffix = entry.relativePath.removePrefix(source.databaseName)
            val target = targetMain.resolveSibling(targetMain.fileName.toString() + suffix)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                check(digest(target) == entry.sha256) { "working数据库既有文件摘要不匹配" }
            } else copyVerified(sourceFile, target, entry.sha256)
        }
    }

    private fun discoverRoots(
        role: String,
        database: Path,
        txId: String,
        preserveFixedRagId: Boolean,
    ): List<RootPlan> =
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            val filesRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
            val roots = linkedSetOf<String>()
            sqlite.rawQuery(
                "SELECT DISTINCT physical_root_path FROM workspace_files WHERE physical_root_path IS NOT NULL AND physical_root_path<>''",
                null,
            ).use { cursor -> while (cursor.moveToNext()) roots += cursor.getString(0) }
            sqlite.rawQuery(
                "SELECT DISTINCT workspace_path FROM sessions WHERE workspace_path IS NOT NULL AND workspace_path<>''",
                null,
            ).use { cursor -> while (cursor.moveToNext()) roots += cursor.getString(0) }
            val sessionsByRoot = linkedMapOf<String, String>()
            sqlite.rawQuery(
                "SELECT s.id,w.physical_root_path FROM sessions s JOIN workspace_files w " +
                    "ON w.uuid=s.workspace_root_uuid AND w.workspace_root_uuid=s.workspace_root_uuid",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) sessionsByRoot.putIfAbsent(
                    Paths.get(cursor.getString(1)).toAbsolutePath().normalize().toString(),
                    cursor.getString(0),
                )
            }
            val normalizedRoots = roots.map { Paths.get(it).toAbsolutePath().normalize() }
            check(filesRoot !in normalizedRoots) { "整个filesDir不能作为活动工作区根" }
            val plans = mutableListOf<RootPlan>()
            val preservationKey = recoverySha256("$role\u0000$filesRoot".toByteArray()).take(24)
            val selectedAssets = discoverKnownAssets(sqlite, filesRoot, normalizedRoots).sorted()
            plans += RootPlan(
                role,
                filesRoot,
                publishedWorkspaceParent.resolve("recovered-assets").resolve(txId).resolve(role),
                "$txId-tree-${UUID.randomUUID()}",
                false,
                true,
                null,
                selectedAssets,
            )
            roots.sorted().forEach { raw ->
                val source = Paths.get(raw).toAbsolutePath().normalize()
                val oldSessionId = sessionsByRoot[source.toString()]
                    ?: throw IllegalStateException("工作区根缺少唯一Session认领")
                val ownerSessionId = if (role == "legacy" && !(preserveFixedRagId && oldSessionId == RAG_WORKSPACE_SESSION_ID)) {
                    RecoveryStableId.map("sessions", "id", oldSessionId)
                } else oldSessionId
                check(ownerSessionId == RAG_WORKSPACE_SESSION_ID || isSinglePathSegment(ownerSessionId)) {
                    "恢复Session标识不能安全映射到受信工作区路径"
                }
                val key = recoverySha256("$role\u0000$source".toByteArray()).take(24)
                plans += RootPlan(
                    role,
                    source,
                    publishedWorkspaceParent.resolve("workspaces")
                        .resolve("recovered-$txId-$role-$key"),
                    "$txId-tree-${UUID.randomUUID()}",
                    true,
                    false,
                    ownerSessionId,
                    emptyList(),
                )
            }
            plans
        }

    private fun databaseHasSession(database: Path, sessionId: String): Boolean =
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT 1 FROM sessions WHERE id=? LIMIT 1", arrayOf(sessionId)).use { it.moveToFirst() }
        }

    /**
     * 只在working副本中收敛Android为同一应用数据根提供的系统别名。
     * 仅认证Context数据根、其real path、系统上报dataDir及严格的/data/data/<package>锚；不解析任何数据库子路径。
     */
    private fun normalizeTrustedAppPathAliases(database: Path) {
        val declaredDataRoot = appContext.dataDir.toPath().toAbsolutePath().normalize()
        val realDataRoot = declaredDataRoot.toRealPath()
        val osReportedDataRoot = Paths.get(appContext.applicationInfo.dataDir).toAbsolutePath().normalize()
        val systemAliasRoot = Paths.get("/data/data", appContext.packageName).toAbsolutePath().normalize()
        val declaredFilesRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
        val relativeFiles = when {
            declaredFilesRoot.startsWith(declaredDataRoot) -> declaredDataRoot.relativize(declaredFilesRoot)
            declaredFilesRoot.startsWith(realDataRoot) -> realDataRoot.relativize(declaredFilesRoot)
            else -> error("应用files目录不在受信数据根内")
        }
        val trustedAppRoots = linkedSetOf(declaredDataRoot, realDataRoot)
        if (Files.exists(osReportedDataRoot) && Files.isSameFile(osReportedDataRoot, realDataRoot)) {
            trustedAppRoots.add(osReportedDataRoot)
        }
        if (Files.exists(systemAliasRoot) && Files.isSameFile(systemAliasRoot, realDataRoot)) {
            trustedAppRoots.add(systemAliasRoot)
        }
        val replacements = trustedAppRoots.map { it.resolve(relativeFiles).normalize() }
            .distinct()
            .onEach { candidate ->
                check(Files.isSameFile(candidate, declaredFilesRoot)) { "应用files别名身份不一致" }
            }
            .filter { it != declaredFilesRoot }
            .associate { it.toString() to declaredFilesRoot.toString() }
        if (replacements.isNotEmpty()) {
            rewriteTrustedAliasPaths(database, replacements)
        }
    }

    private fun ensureRagWorkspaceSession(database: Path, recoveredTitle: String) {
        val ragRoot = publishedWorkspaceParent.resolve("rag_workspace").toAbsolutePath().normalize()
        SQLiteDatabase.openDatabase(database.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            val matches = mutableListOf<Pair<String, String>>()
            sqlite.rawQuery(
                "SELECT uuid,physical_root_path FROM workspace_files WHERE uuid=workspace_root_uuid " +
                    "AND parent_uuid IS NULL AND is_directory=1",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val candidate = runCatching { Paths.get(cursor.getString(1)).toAbsolutePath().normalize() }.getOrNull()
                    if (candidate != null && (candidate == ragRoot || runCatching {
                            Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(candidate, ragRoot)
                        }.getOrDefault(false))) matches += cursor.getString(0) to cursor.getString(1)
                }
            }
            if (matches.isEmpty()) return
            check(matches.size == 1) { "规范RAG工作区存在多个根记录" }
            val (rootUuid, declaredRootPath) = matches.single()
            sqlite.rawQuery("SELECT id FROM sessions WHERE workspace_root_uuid=?", arrayOf(rootUuid)).use { cursor ->
                if (cursor.moveToFirst()) return
            }
            sqlite.rawQuery("SELECT workspace_root_uuid FROM sessions WHERE id=?", arrayOf(RAG_WORKSPACE_SESSION_ID)).use { cursor ->
                check(!cursor.moveToFirst()) { "RAG固定Session已被其它工作区占用" }
            }
            val values = ContentValues()
            val provided = mapOf<String, Any>(
                "id" to RAG_WORKSPACE_SESSION_ID,
                "agent_id" to "__system__",
                "title" to recoveredTitle,
                "workspace_path" to declaredRootPath,
                "workspace_root_uuid" to rootUuid,
                "created_at" to 0L,
                "updated_at" to 0L,
            )
            sqlite.rawQuery("PRAGMA table_info(sessions)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(1)
                    val explicit = provided[column]
                    when (explicit) {
                        is String -> values.put(column, explicit)
                        is Long -> values.put(column, explicit)
                        null -> if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                            when (cursor.getString(2).uppercase()) {
                                "INTEGER" -> values.put(column, 0L)
                                "REAL" -> values.put(column, 0.0)
                                "BLOB" -> values.put(column, byteArrayOf())
                                else -> values.put(column, "")
                            }
                        }
                    }
                }
            }
            check(sqlite.insertOrThrow("sessions", null, values) >= 0) { "无法建立恢复RAG入口" }
        }
    }

    private fun discoverKnownAssets(
        sqlite: SQLiteDatabase,
        filesRoot: Path,
        workspaceRoots: List<Path>,
    ): Set<String> {
        val selected = linkedSetOf<String>()
        var inspectedChars = 0L
        fun consider(raw: String) {
            if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("content:")) return
            val value = raw.removePrefix("file://")
            val path = runCatching { Paths.get(value).toAbsolutePath().normalize() }.getOrNull() ?: return
            if (!path.startsWith(filesRoot) || workspaceRoots.any { path == it || path.startsWith(it) }) return
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                "已知恢复资产不是普通文件"
            }
            val relative = filesRoot.relativize(path).joinToString("/")
            check(relative.isSafeAssetRelative() && selected.size < MAX_RECOVERY_ASSET_FILES) {
                "恢复资产清单超过安全边界"
            }
            selected += relative
        }
        fun inspectJson(raw: String) {
            inspectedChars += raw.length
            check(raw.length <= MAX_RECOVERY_JSON_CHARS && inspectedChars <= MAX_RECOVERY_JSON_TOTAL_CHARS) {
                "恢复结构化引用超过解析预算"
            }
            fun visit(element: JsonElement) {
                when (element) {
                    is JsonArray -> element.forEach(::visit)
                    is JsonObject -> element.values.forEach(::visit)
                    is JsonPrimitive -> element.contentOrNull?.let(::consider)
                }
            }
            runCatching { visit(Json.parseToJsonElement(raw)) }
        }
        fun scan(table: String, columns: List<String>, jsonColumns: Set<String> = emptySet()) {
            sqlite.rawQuery("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table`", null).use { cursor ->
                while (cursor.moveToNext()) columns.indices.forEach { index ->
                    if (!cursor.isNull(index)) {
                        val value = cursor.getString(index)
                        if (columns[index] in jsonColumns) inspectJson(value) else consider(value)
                    }
                }
            }
        }
        scan("agents", listOf("avatar_path"))
        scan("attachments", listOf("uri", "local_uri"))
        scan("artifacts", listOf("workspace_path"))
        scan(
            "messages",
            listOf("images", "files", "user_images", "legacy_attachments"),
            setOf("files", "user_images", "legacy_attachments"),
        )
        return selected
    }

    private fun rewriteKnownPath(value: String, paths: Map<String, String>): String {
        val prefix = if (value.startsWith("file://")) "file://" else ""
        val pathValue = value.removePrefix(prefix)
        val match = paths.keys.sortedByDescending(String::length).firstOrNull {
            pathValue == it || pathValue.startsWith("$it/")
        }
            ?: return value
        return prefix + paths.getValue(match) + pathValue.removePrefix(match)
    }

    private fun isSinglePathSegment(value: String): Boolean = value.isNotBlank() &&
        value != "." && value != ".." && '/' !in value && '\\' !in value

    private fun String.isSafeAssetRelative(): Boolean = isNotBlank() && !startsWith('/') &&
        split('/').all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it }

    private fun targetPathForPublication(publication: RecoveryTreePublication): Path {
        val parent = if (publication.activeWorkspace || publication.assetBundle) publishedWorkspaceParent else publishedTreeRoot
        return parent.resolve(publication.targetRelativePath).normalize().also {
            check(it.startsWith(parent)) { "恢复树目标路径越界" }
        }
    }

    private fun pathMapForRole(record: DualDatabaseRecoveryRecord, role: String): Map<String, String> = buildMap {
        record.treePublications.filter { it.role == role && it.activeWorkspace }.forEach {
            put(it.sourcePath, targetPathForPublication(it).toString())
        }
        record.treePublications.filter { it.role == role && it.assetBundle }.forEach { publication ->
            val sourceRoot = Paths.get(publication.sourcePath).toAbsolutePath().normalize()
            val targetRoot = targetPathForPublication(publication)
            publication.selectedRelativeFiles.forEach { relative ->
                put(sourceRoot.resolve(relative).normalize().toString(), targetRoot.resolve(relative).normalize().toString())
            }
        }
    }

    private fun checkpointAndVerifyV18(path: Path) {
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
            sqlite.rawQuery("PRAGMA quick_check(1)", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            check(sqlite.version == 18 && sqlite.roomIdentity() == CURRENT_V18_IDENTITY) { "候选数据库不是可信v18" }
        }
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + "-shm"))
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + "-wal"))
    }

    private fun verifyPublishedCandidate(path: Path, record: DualDatabaseRecoveryRecord) {
        check(digest(path) == record.candidateSha256) { "已发布候选数据库摘要不匹配" }
        checkpointAndVerifyV18(path)
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery(
                "SELECT mapping_sha256 FROM dual_database_recovery_receipts WHERE transaction_marker=?",
                arrayOf(record.candidateCommitMarker),
            ).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == record.idMappingSha256) {
                    "候选数据库事务回执缺失"
                }
            }
        }
    }

    private fun copyVerified(source: Path, target: Path, expectedSha256: String? = null) {
        ensureDirectory(target.parent)
        val fileSystem = snapshotFileSystem()
        fileSystem.openDirectory(source.parent).use { sourceParent ->
            fileSystem.openDirectory(target.parent).use { targetParent ->
                val sourceName = source.fileName.toString()
                val targetName = target.fileName.toString()
                val before = sourceParent.node(sourceName)?.takeIf { !it.directory }
                    ?: throw IllegalStateException("恢复复制源不是普通文件")
                check(targetParent.node(targetName) == null) { "恢复复制目标已存在" }
                sourceParent.read(sourceName).use { input ->
                    targetParent.writeNew(targetName).use { output ->
                val buffer = ByteBuffer.allocateDirect(64 * 1024)
                        var copied = 0L
                        while (copied < before.size) {
                            buffer.clear()
                            buffer.limit(minOf(buffer.capacity().toLong(), before.size - copied).toInt())
                            val count = input.read(buffer)
                            check(count > 0) { "恢复复制源提前结束或无进展" }
                            copied += count
                            buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                }
                output.force(true)
            }
                    check(input.read(ByteBuffer.allocate(1)) < 0) { "恢复复制源在限额后仍有内容" }
                }
                targetParent.force()
                val after = sourceParent.node(sourceName)
                val published = targetParent.node(targetName)
                check(after == before && published?.directory == false && published.size == before.size) {
                    "恢复复制前后文件身份或大小变化"
                }
            }
        }
        val expected = expectedSha256 ?: digest(source)
        check(digest(target) == expected && digest(source) == expected) { "恢复文件复制前后摘要不一致" }
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        snapshotFileSystem().openDirectory(path.parent).use { parent ->
            val name = path.fileName.toString()
            val before = parent.node(name)?.takeIf { !it.directory }
                ?: throw IllegalStateException("摘要目标不是普通文件")
            parent.read(name).use { channel ->
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
                var read = 0L
                while (read < before.size) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), before.size - read).toInt())
                    val count = channel.read(buffer)
                    check(count > 0) { "摘要读取提前结束或无进展" }
                    read += count
                    buffer.flip(); digest.update(buffer)
                }
                check(channel.read(ByteBuffer.allocate(1)) < 0) { "摘要目标在限额后仍有内容" }
            }
            check(parent.node(name) == before) { "摘要读取期间文件身份变化" }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun moveAtomic(source: Path, target: Path) {
        val fileSystem = snapshotFileSystem()
        fileSystem.openDirectory(source.parent).use { sourceParent ->
            fileSystem.openDirectory(target.parent).use { targetParent ->
                val sourceName = source.fileName.toString()
                val targetName = target.fileName.toString()
                val before = sourceParent.node(sourceName) ?: throw IllegalStateException("恢复rename源不存在")
                check(!before.directory && targetParent.node(targetName) == null) { "恢复rename目标已存在或源类型错误" }
                sourceParent.move(sourceName, targetParent, targetName)
                faultHook?.invoke(DualDatabaseRecoveryFaultPoint.AFTER_RENAME_BEFORE_DIRECTORY_FSYNC)
                sourceParent.force()
                targetParent.force()
                check(sourceParent.node(sourceName) == null && targetParent.node(targetName) == before) {
                    "恢复rename后节点身份无法确认"
                }
            }
        }
    }

    private fun forceDirectory(path: Path) {
        snapshotFileSystem().openDirectory(path).use { it.force() }
    }

    private fun ensureDirectory(path: Path) {
        snapshotFileSystem().openDirectory(path, createParents = true).use { it.force() }
    }

    private fun snapshotFileSystem(): AndroidSnapshotFileSystem =
        AndroidSnapshotFileSystem(appContext.dataDir.toPath())

    private fun workingAttemptRoot(record: DualDatabaseRecoveryRecord): Path {
        check(record.stage >= DualDatabaseRecoveryStage.STAGED) { "working attempt尚未持久化" }
        val attempt = requireNotNull(record.workingAttemptId)
        check(attempt.matches(Regex("attempt-[A-Za-z0-9_-]{1,96}"))) { "working attempt编号无效" }
        return workingRoot.resolve(record.transactionId).resolve(attempt)
    }

    private fun DualDatabaseRecoveryRecord.withTreePublication(
        publication: RecoveryTreePublication,
    ): DualDatabaseRecoveryRecord = copy(
        treePublications = treePublications.map {
            if (it.snapshotTransactionId == publication.snapshotTransactionId) publication else it
        },
    )

    private fun SQLiteDatabase.roomIdentity(): String =
        rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
            check(it.moveToFirst()); it.getString(0)
        }

    private data class RootPlan(
        val role: String,
        val source: Path,
        val target: Path,
        val snapshotId: String,
        val activeWorkspace: Boolean,
        val assetBundle: Boolean,
        val ownerSessionId: String?,
        val selectedRelativeFiles: List<String>,
    )

}

internal enum class DualDatabaseRecoveryFaultPoint {
    AFTER_LEGACY_MIGRATION,
    BEFORE_STAGED_JOURNAL,
    AFTER_TREE_ARCHIVE_BEFORE_JOURNAL,
    AFTER_RENAME_BEFORE_DIRECTORY_FSYNC,
}
