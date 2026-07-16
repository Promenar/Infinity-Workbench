package com.promenar.nexara.data.backup

import android.content.ContentValues
import android.database.Cursor
import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal const val ROOM_SCHEMA_V1_IDENTITY_HASH = "1cec46d28d19744e8cb885fe6abdfcf1"
internal const val ROOM_SCHEMA_V2_IDENTITY_HASH = "7777303c63145d5bbb9b161b38f94495"

class RoomBackupDataSource(
    private val database: NexaraDatabase,
    private val preferences: TransactionalBackupPreferenceStore,
    private val secrets: TransactionalBackupSecretStore,
    trustedSourceBases: Set<Path>,
    trustedRestoreBase: Path,
    private val appVersion: String,
    private val crashHook: RestoreCrashHook = RestoreCrashHook {},
    private val snapshotReadHook: (Path) -> Unit = {},
    journalAuthenticator: RestoreJournalAuthenticator,
    private val restoreFileOperations: RestoreFileOperations = SecureBackupFileOps,
) : BackupDataSource {
    private val trustedSourceBases = trustedSourceBases.map(FileRestoreJournal::requireTrustedDirectory)
    private val restoreParent = FileRestoreJournal.requireTrustedDirectory(trustedRestoreBase)
    private val journal: RestoreJournal = FileRestoreJournal(restoreParent, journalAuthenticator)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val restoreMutex = Mutex()

    override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = withContext(Dispatchers.IO) {
        requireCanonicalSnapshotContent(content)
        val safePreferences = sanitizePreferences(preferences.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES))
        val preferenceBytes = json.encodeToString(safePreferences).toByteArray(Charsets.UTF_8)
        if (preferenceBytes.size.toLong() > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("偏好数据超过内存安全限制")
        }
        var files: Map<String, ByteArray> = emptyMap()
        var secretSnapshot: Map<SecretId, ByteArray> = emptyMap()
        try {
            secretSnapshot = if (BackupContent.SECRETS in content) {
                snapshotSecrets(
                    safePreferences.providerIds,
                    BackupPackageLimits.MAX_IN_MEMORY_BYTES - preferenceBytes.size,
                )
            } else emptyMap()
            val secretBytes = secretSnapshot.values.sumOf { it.size.toLong() }
            val databaseAndFiles =
            database.withTransaction {
                val payload = readDatabasePayload()
                val preliminaryDatabase = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                val nonDirectoryCount = payload.rows(FILE_TABLE).count { !it.requiredBoolean("is_directory") }
                val remaining = BackupPackageLimits.MAX_IN_MEMORY_BYTES - preferenceBytes.size - secretBytes -
                    preliminaryDatabase.size - nonDirectoryCount * 64L
                preliminaryDatabase.fill(0)
                if (remaining < 0) throw BackupValidationException("数据库与偏好已超过内存安全限制")
                val result = snapshotFiles(payload, remaining)
                result.payload to result.files
            }
            files = databaseAndFiles.second
            val databaseBytes = json.encodeToString(databaseAndFiles.first).toByteArray(Charsets.UTF_8)
            validateSnapshotLimits(databaseBytes, preferenceBytes, files, secretSnapshot)
            BackupSnapshot(
                database = databaseBytes,
                preferences = preferenceBytes,
                files = files,
                secrets = secretSnapshot,
                databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
                appVersion = appVersion,
            )
        } catch (error: Throwable) {
            files.values.forEach { it.fill(0) }
            secretSnapshot.values.forEach { it.fill(0) }
            throw error
        }
    }

    override suspend fun restore(validated: ValidatedBackup) =
        restore(validated, UUID.randomUUID().toString())

    override suspend fun restore(validated: ValidatedBackup, operationId: String) {
        requireOperationId(operationId)
        validated.beginConsumption()
        var lockAcquired = false
        try {
            restoreMutex.lock()
            lockAcquired = true
            withContext(Dispatchers.IO) {
        if (hasCompletedRestoreLocked(operationId)) return@withContext
        requireCanonicalRestoreContent(validated)
        if (journal.read() != null) throw BackupValidationException("存在未恢复的 restore journal，请先执行 recoverInterruptedRestore")
        val payload = parseDatabase(validated.database)
        val preferenceSnapshot = parsePreferences(validated.preferences)
        validatePayload(payload, validated.files)
        validateSecrets(validated.secrets, preferenceSnapshot.providerIds)

        val restoreId = operationId
        val stagingRoot = restoreParent.resolve(".restore-$restoreId.tmp")
        val finalRoot = restoreParent.resolve("restore-$restoreId")
        var transformed = transformPaths(payload, finalRoot)
        validatePayload(transformed, validated.files)
        var expectedFingerprint = fingerprint(transformed)
        val beforePreferences = preferences.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        preferences.preflightRestore(restoreId, beforePreferences, preferenceSnapshot)
        val eligibleIds = eligibleSecretIds(preferenceSnapshot.providerIds + beforePreferences.providerIds)
        val beforeSecrets = secrets.snapshot(eligibleIds, BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val afterSecrets = validated.secrets.mapValues { it.value.copyOf() }
        var record = RestoreJournalRecord(
            txId = restoreId,
            expectedDatabaseFingerprint = expectedFingerprint,
            oldRootIdentity = currentOldRootIdentity(),
            newRootIdentity = finalRoot.fileName.toString(),
            newRootFileKey = null,
            phase = RestoreJournalPhase.PREPARED,
        )

        try {
            journal.write(record)
            preferences.prepare(restoreId, beforePreferences, preferenceSnapshot)
            secrets.prepare(restoreId, beforeSecrets, afterSecrets)
            crashHook.hit(RestoreCrashPoint.JOURNAL_PREPARED)
            val staged = stageFiles(stagingRoot, transformed, validated.files)
            transformed = staged.payload
            validatePayload(transformed, validated.files)
            expectedFingerprint = fingerprint(transformed)
            record = record.copy(expectedDatabaseFingerprint = expectedFingerprint)
            journal.write(record)
            val expectedTree = staged.expectedTree
            moveAtomically(stagingRoot, finalRoot)
            restoreFileOperations.verifyAndSync(finalRoot, expectedTree)
            record = record.copy(newRootFileKey = fileKey(finalRoot))
            journal.write(record)
            crashHook.hit(RestoreCrashPoint.FILES_MOVED)

            // Task 8 仍须在应用层冻结其它 DB/文件 writer；本地 mutex 只串行化 restore/recovery。
            restoreFileOperations.verifyAndSync(finalRoot, expectedTree)
            database.withTransaction {
                clearAllRestorableTables()
                insertPayload(transformed)
                preferences.commitPrepared(restoreId)
                secrets.commitPrepared(restoreId)
                crashHook.hit(RestoreCrashPoint.EXTERNAL_COMMITTED)
                writeDatabaseCommitMarker(record)
            }
            crashHook.hit(RestoreCrashPoint.ROOM_COMMITTED)
            record = record.copy(phase = RestoreJournalPhase.COMMITTED)
            journal.write(record)
            crashHook.hit(RestoreCrashPoint.JOURNAL_COMMITTED)
            finishCommitted(record)
        } catch (error: Throwable) {
            if (error is SimulatedRestoreProcessDeath) throw error
            val recoveryFailure = runCatching {
                withContext(NonCancellable) { recoverInterruptedRestoreLocked() }
            }.exceptionOrNull()
            recoveryFailure?.let { error.addSuppressed(it) }
            throw error
        } finally {
            beforeSecrets.values.forEach { it.fill(0) }
            afterSecrets.values.forEach { it.fill(0) }
        }
            }
        } finally {
            if (lockAcquired) restoreMutex.unlock()
            validated.close()
        }
    }

    override suspend fun recoverInterruptedRestore() = restoreMutex.withLock { recoverInterruptedRestoreLocked() }

    override suspend fun hasCompletedRestore(operationId: String): Boolean = withContext(Dispatchers.IO) {
        requireOperationId(operationId)
        hasCompletedRestoreLocked(operationId)
    }

    private suspend fun recoverInterruptedRestoreLocked() = withContext(Dispatchers.IO + NonCancellable) {
        val record = journal.read() ?: return@withContext
        val failures = mutableListOf<Throwable>()
        val databaseCommitted = when (record.phase) {
            RestoreJournalPhase.COMMITTED -> true
            RestoreJournalPhase.PREPARED -> {
                if (record.newRootFileKey == null) false else hasDatabaseCommitMarker(record)
            }
        }

        if (databaseCommitted) {
            runCatching { preferences.commitPrepared(record.txId) }.onFailure(failures::add)
            runCatching { secrets.commitPrepared(record.txId) }.onFailure(failures::add)
            if (failures.isEmpty()) {
                runCatching { finishCommitted(record.copy(phase = RestoreJournalPhase.COMMITTED)) }
                    .onFailure(failures::add)
            }
        } else {
            runCatching { preferences.rollbackPrepared(record.txId) }.onFailure(failures::add)
            runCatching { secrets.rollbackPrepared(record.txId) }.onFailure(failures::add)
            runCatching { deleteOwnedRestoreRoots(record) }.onFailure(failures::add)
            if (failures.isEmpty()) {
                runCatching { preferences.finalizePrepared(record.txId) }.onFailure(failures::add)
                runCatching { secrets.finalizePrepared(record.txId) }.onFailure(failures::add)
            }
            if (failures.isEmpty()) runCatching { journal.delete() }.onFailure(failures::add)
        }
        if (failures.isNotEmpty()) {
            val composite = BackupValidationException("中断恢复补偿失败，journal 已保留")
            failures.forEach(composite::addSuppressed)
            throw composite
        }
    }

    private fun readDatabasePayload(): DatabaseBackupPayload {
        val sqlite = database.openHelper.writableDatabase
        val budget = MaterializationBudget(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val tables = INSERT_ORDER.associateWith { table ->
            sqlite.query("SELECT * FROM `$table`").use { cursor -> cursorRows(cursor, budget) }
        }
        return DatabaseBackupPayload(DATABASE_SCHEMA_VERSION, tables)
    }

    private fun cursorRows(cursor: Cursor, budget: MaterializationBudget): List<JsonObject> = buildList {
        while (cursor.moveToNext()) {
            add(JsonObject(buildMap {
                cursor.columnNames.forEachIndexed { index, name ->
                    val value = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> throw BackupValidationException("用户源表不允许未声明的 BLOB 字段: $name")
                        else -> throw BackupValidationException("无法备份数据库字段: $name")
                    }
                    budget.consume(name.toByteArray().size.toLong() + value.toString().toByteArray().size + 32L)
                    put(name, value)
                }
            }))
        }
    }

    private fun snapshotFiles(payload: DatabaseBackupPayload, memoryBudget: Long): SnapshotFilesResult {
        val rows = payload.rows(FILE_TABLE)
        val fileRows = rows.filterNot { it.requiredBoolean("is_directory") }
        if (fileRows.size > BackupPackageLimits.MAX_ENTRIES - 2) throw BackupValidationException("备份文件项过多")
        var declaredTotal = 0L
        fileRows.forEach { row ->
            val size = row.requiredLong("size_bytes")
            if (size < 0 || size > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("文件声明大小超过备份限制")
            }
            declaredTotal = Math.addExact(declaredTotal, size)
            if (declaredTotal > BackupPackageLimits.MAX_TOTAL_BYTES || declaredTotal > memoryBudget) {
                throw BackupValidationException("文件声明总大小超过备份或内存安全限制")
            }
        }
        val result = linkedMapOf<String, ByteArray>()
        val hashes = linkedMapOf<String, String>()
        try {
            rows.forEach { row ->
                val id = row.requiredString("uuid")
                if (row.requiredBoolean("is_directory")) return@forEach
                if (result.containsKey(id)) throw BackupValidationException("文件 UUID 重复")
                val path = resolveSnapshotPath(row)
                val (bytes, actualHash) = readStableFile(path, row.requiredLong("size_bytes"), id)
                val declaredHash = row.requiredString("hash")
                if (declaredHash.isNotBlank() && !declaredHash.equals(actualHash, ignoreCase = true)) {
                    bytes.fill(0)
                    throw BackupValidationException("文件摘要不一致")
                }
                result[id] = bytes
                hashes[id] = actualHash
            }
            val updatedRows = rows.map { row ->
                val hash = hashes[row.requiredString("uuid")]
                if (hash == null) row else JsonObject(row + ("hash" to JsonPrimitive(hash)))
            }
            return SnapshotFilesResult(
                payload.copy(tables = payload.tables + (FILE_TABLE to updatedRows)),
                result,
            )
        } catch (error: Throwable) {
            result.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun readStableFile(path: Path, declaredSize: Long, id: String): Pair<ByteArray, String> {
        val before = Files.readAttributes(
            path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != declaredSize) {
            throw BackupValidationException("文件读取前属性与声明不一致: $id")
        }
        val realBefore = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val bytes = ByteArray(declaredSize.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            openNoFollow(path).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = channel.read(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(bytes, offset, count)
                    offset += count
                }
                if (offset != bytes.size || channel.read(java.nio.ByteBuffer.allocate(1)) != -1) {
                    throw BackupValidationException("文件读取大小发生变化: $id")
                }
            }
            snapshotReadHook(path)
            val after = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            val realAfter = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val afterHash = hashFileWithoutAccumulating(path, declaredSize)
            val finalAttributes = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            val finalReal = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || realBefore != realAfter || hash != afterHash ||
                after.fileKey() != finalAttributes.fileKey() || after.size() != finalAttributes.size() ||
                after.lastModifiedTime() != finalAttributes.lastModifiedTime() || realAfter != finalReal
            ) throw BackupValidationException("文件在 snapshot 期间发生变化: $id")
            return bytes to hash
        } catch (error: BackupValidationException) {
            bytes.fill(0)
            throw error
        } catch (error: Exception) {
            bytes.fill(0)
            throw BackupValidationException("无法稳定读取备份源文件: $id", error)
        }
    }

    private fun hashFileWithoutAccumulating(path: Path, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        openNoFollow(path).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expectedSize) throw BackupValidationException("文件复核读取超过声明大小")
                digest.update(buffer.array(), 0, count)
            }
        }
        if (total != expectedSize) throw BackupValidationException("文件复核读取大小变化")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun openNoFollow(path: Path): java.nio.channels.SeekableByteChannel {
        val stream = Files.newDirectoryStream(path.parent)
        if (stream is java.nio.file.SecureDirectoryStream<Path>) {
            return try {
                val channel = stream.newByteChannel(
                    path.fileName,
                    setOf(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
                ClosingSeekableByteChannel(channel, stream)
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        }
        stream.close()
        return java.nio.channels.FileChannel.open(
            path,
            java.nio.file.StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    private fun resolveSnapshotPath(row: JsonObject): Path {
        val root = requireTrustedSourceRoot(row.requiredString("physical_root_path"))
        val rootReal = root.toRealPath()
        val relative = normalizeMaterializedPath(row.requiredString("materialized_path"))
        var current = root
        relative.forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw BackupValidationException("备份文件路径包含符号链接")
        }
        val candidate = current.normalize()
        val real = try {
            candidate.toRealPath()
        } catch (error: Exception) {
            throw BackupValidationException("备份源文件不存在", error)
        }
        if (!real.startsWith(rootReal) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("备份文件越出根目录或不是普通文件")
        }
        return real
    }

    private suspend fun snapshotSecrets(providerIds: Set<String>, maxTotalBytes: Long): Map<SecretId, ByteArray> =
        secrets.snapshot(eligibleSecretIds(providerIds), maxTotalBytes)

    private fun parseDatabase(bytes: ByteArray): DatabaseBackupPayload {
        if (bytes.isEmpty()) throw BackupValidationException("恢复包缺少 database.json")
        return try {
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw BackupValidationException("数据库 payload 无效", error)
        } catch (error: IllegalArgumentException) {
            throw BackupValidationException("数据库 payload 无效", error)
        }
    }

    private fun parsePreferences(bytes: ByteArray): BackupPreferenceSnapshot {
        if (bytes.isEmpty()) return BackupPreferenceSnapshot(emptyList(), emptySet())
        val parsed = try {
            json.decodeFromString<BackupPreferenceSnapshot>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupValidationException("偏好 payload 无效", error)
        }
        return sanitizePreferences(parsed, rejectUnsafe = true)
    }

    private fun sanitizePreferences(
        snapshot: BackupPreferenceSnapshot,
        rejectUnsafe: Boolean = false,
    ): BackupPreferenceSnapshot {
        if (snapshot.providerIds.any { it.isBlank() || it.length > 200 }) {
            throw BackupValidationException("Provider ID 无效")
        }
        val seen = mutableSetOf<Pair<String, String>>()
        val safe = snapshot.entries.mapNotNull { entry ->
            val identity = entry.namespace to entry.key
            if (!seen.add(identity)) throw BackupValidationException("偏好键重复")
            val allowed = BackupPreferencePolicy.isAllowed(entry.namespace, entry.key)
            if (!allowed && rejectUnsafe) throw BackupValidationException("偏好 payload 包含非白名单字段")
            entry.takeIf { allowed }
        }
        return BackupPreferenceSnapshot(safe, snapshot.providerIds.toSortedSet())
    }

    private fun validatePayload(payload: DatabaseBackupPayload, files: Map<String, ByteArray>) {
        if (payload.schemaVersion != DATABASE_SCHEMA_VERSION) {
            throw BackupValidationException("数据库 schema 版本不兼容")
        }
        if (payload.tables.keys != INSERT_ORDER.toSet()) {
            throw BackupValidationException("数据库 payload 表清单不完整或包含派生表")
        }
        validateRowsAgainstRoomSchema(payload)
        validateUnique(payload, "agents", "id")
        validateUnique(payload, "sessions", "id")
        validateUnique(payload, "messages", "id")
        validateUnique(payload, "attachments", "id")
        validateUnique(payload, "artifacts", "id")
        validateUnique(payload, "context_summaries", "id")
        validateUnique(payload, "tags", "id")
        validateCompositeUnique(payload, "document_tags", listOf("doc_id", "tag_id"))
        validateUnique(payload, "task_nodes", "id")
        validateUnique(payload, "custom_skills", "id")
        validateUnique(payload, "mcp_servers", "id")
        validateUnique(payload, FILE_TABLE, "uuid")
        validateUnique(payload, "workspace_seq", "date_key")

        val agents = payload.ids("agents", "id")
        val sessions = payload.ids("sessions", "id")
        val messages = payload.ids("messages", "id")
        val tags = payload.ids("tags", "id")
        val fileIds = payload.ids(FILE_TABLE, "uuid")
        val taskIds = payload.ids("task_nodes", "id")
        payload.rows("sessions").forEach { requireReference(it, "agent_id", agents, "Agent") }
        val messageRows = payload.rows("messages")
        val messageById = messageRows.associateBy { it.requiredString("id") }
        messageRows.forEach { row ->
            requireReference(row, "session_id", sessions, "Session")
            row.optionalString("parent_message_id")?.let { parentId ->
                val parent = messageById[parentId]
                    ?: throw BackupValidationException("Message parent 不存在")
                if (parent.requiredString("session_id") != row.requiredString("session_id")) {
                    throw BackupValidationException("Message parent 跨 session")
                }
            }
        }
        sortMessagesParentFirst(messageRows)
        payload.rows("attachments").forEach { requireReference(it, "message_id", messages, "Message") }
        payload.rows("artifacts").forEach {
            requireReference(it, "session_id", sessions, "Session")
            requireReference(it, "message_id", messages, "Message")
        }
        payload.rows("context_summaries").forEach {
            requireReference(it, "session_id", sessions, "Session")
            requireReference(it, "start_message_id", messages, "Message")
            requireReference(it, "end_message_id", messages, "Message")
        }
        payload.rows("document_tags").forEach {
            requireReference(it, "tag_id", tags, "Tag")
            requireReference(it, "doc_id", fileIds, "FileEntry")
        }
        payload.rows("task_nodes").forEach {
            requireReference(it, "session_id", sessions, "Session")
            it.optionalString("parent_id")?.let { parent ->
                if (parent !in taskIds) throw BackupValidationException("Task parent 外键无效")
            }
        }
        payload.rows(FILE_TABLE).forEach { row ->
            row.optionalString("parent_uuid")?.let { parent ->
                if (parent !in fileIds) throw BackupValidationException("FileEntry parent 外键无效")
            }
            normalizeMaterializedPath(row.requiredString("materialized_path"))
            row.optionalString("original_materialized_path")?.let(::normalizeMaterializedPath)
        }
        validateWorkspaceIdentity(payload)

        val nonDirectories = payload.rows(FILE_TABLE)
            .filterNot { it.requiredBoolean("is_directory") }
            .associateBy { it.requiredString("uuid") }
        if (files.keys != nonDirectories.keys) throw BackupValidationException("文件清单与数据库不一致")
        if (files.size > BackupPackageLimits.MAX_ENTRIES - 2) throw BackupValidationException("恢复文件项过多")
        var total = 0L
        files.values.forEach { bytes ->
            if (bytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("恢复文件超过单项大小限制")
            }
            total = Math.addExact(total, bytes.size.toLong())
            if (total > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("恢复文件超过总大小限制")
        }
        nonDirectories.forEach { (id, row) -> validateFileBytes(row, files.getValue(id)) }
    }

    private fun validateWorkspaceIdentity(payload: DatabaseBackupPayload) {
        val files = payload.rows(FILE_TABLE)
        val byId = files.associateBy { it.requiredString("uuid") }
        val roots = files.filter { row ->
            row.requiredString("uuid") == row.requiredString("workspace_root_uuid")
        }.associateBy { it.requiredString("uuid") }
        files.forEach { row ->
            val rootId = row.requiredString("workspace_root_uuid")
            val root = roots[rootId] ?: throw BackupValidationException("FileEntry workspace root 不存在")
            if (!root.requiredBoolean("is_directory") ||
                root.requiredString("physical_root_path") != row.requiredString("physical_root_path")
            ) throw BackupValidationException("FileEntry workspace 所属不一致")
            row.optionalString("parent_uuid")?.let { parentId ->
                val parent = byId.getValue(parentId)
                if (parent.requiredString("workspace_root_uuid") != rootId) {
                    throw BackupValidationException("FileEntry parent 跨 workspace")
                }
            }
        }
        payload.rows("sessions").forEach { session ->
            session.optionalString("workspace_root_uuid")?.let { rootId ->
                val root = roots[rootId] ?: throw BackupValidationException("Session workspace root 不存在")
                session.optionalString("workspace_path")?.let { path ->
                    if (Paths.get(path).toAbsolutePath().normalize() !=
                        Paths.get(root.requiredString("physical_root_path")).toAbsolutePath().normalize()
                    ) throw BackupValidationException("Session workspace path 与 root 不一致")
                }
            }
        }
    }

    private fun validateRowsAgainstRoomSchema(payload: DatabaseBackupPayload) {
        val sqlite = database.openHelper.writableDatabase
        val identityHash = sqlite.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (identityHash != ROOM_SCHEMA_V2_IDENTITY_HASH) {
            throw BackupValidationException("当前 Room schema 不是受支持的精确 schema v2")
        }
        INSERT_ORDER.forEach { table ->
            val columns = sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                    val pkIndex = cursor.getColumnIndexOrThrow("pk")
                    while (cursor.moveToNext()) {
                        add(
                            RoomColumn(
                                name = cursor.getString(nameIndex),
                                affinity = cursor.getString(typeIndex).uppercase(),
                                notNull = cursor.getInt(notNullIndex) != 0,
                                primaryKeyOrder = cursor.getInt(pkIndex),
                            )
                        )
                    }
                }
            }
            if (columns.isEmpty()) throw BackupValidationException("当前 Room schema 缺少表: $table")
            val byName = columns.associateBy(RoomColumn::name)
            val rows = if (table == "messages") {
                sortMessagesParentFirst(payload.rows(table))
            } else {
                payload.rows(table)
            }
            rows.forEach { row ->
                if (row.keys != byName.keys) throw BackupValidationException("$table 行列集合与当前 Room schema 不一致")
                row.forEach { (name, value) -> validateSqlitePrimitive(table, byName.getValue(name), value) }
            }
            val pkColumns = columns.filter { it.primaryKeyOrder > 0 }.sortedBy { it.primaryKeyOrder }
            if (pkColumns.isNotEmpty()) {
                val keys = payload.rows(table).map { row -> pkColumns.map { row.getValue(it.name).toString() } }
                if (keys.size != keys.toSet().size) throw BackupValidationException("$table Room 主键重复")
            }
        }
    }

    private fun sortMessagesParentFirst(rows: List<JsonObject>): List<JsonObject> {
        val byId = rows.associateBy { it.requiredString("id") }
        if (byId.size != rows.size) throw BackupValidationException("Message id 重复")
        val childrenByParent = mutableMapOf<String, MutableList<JsonObject>>()
        val indegree = rows.associate { it.requiredString("id") to 0 }.toMutableMap()
        rows.forEach { row ->
            val id = row.requiredString("id")
            row.optionalString("parent_message_id")?.let { parentId ->
                if (parentId == id) throw BackupValidationException("Message 不得自引用")
                val parent = byId[parentId] ?: throw BackupValidationException("Message parent 不存在")
                if (parent.requiredString("session_id") != row.requiredString("session_id")) {
                    throw BackupValidationException("Message parent 跨 session")
                }
                indegree[id] = 1
                childrenByParent.getOrPut(parentId) { mutableListOf() } += row
            }
        }
        val stableOrder = compareBy<JsonObject> { it.requiredLong("created_at") }
            .thenBy { it.requiredString("id") }
        var layer = rows.filter { indegree.getValue(it.requiredString("id")) == 0 }.sortedWith(stableOrder)
        val ordered = ArrayList<JsonObject>(rows.size)
        while (layer.isNotEmpty()) {
            ordered += layer
            val nextLayer = mutableListOf<JsonObject>()
            layer.forEach { parent ->
                childrenByParent[parent.requiredString("id")].orEmpty().forEach { child ->
                    val childId = child.requiredString("id")
                    indegree[childId] = indegree.getValue(childId) - 1
                    if (indegree.getValue(childId) == 0) nextLayer += child
                }
            }
            layer = nextLayer.sortedWith(stableOrder)
        }
        if (ordered.size != rows.size) throw BackupValidationException("Message parent 存在环")
        return ordered
    }

    private fun validateSqlitePrimitive(table: String, column: RoomColumn, value: kotlinx.serialization.json.JsonElement) {
        if (value is JsonNull) {
            if (column.notNull || column.primaryKeyOrder > 0) {
                throw BackupValidationException("$table.${column.name} 不允许 NULL")
            }
            return
        }
        val primitive = value as? JsonPrimitive
            ?: throw BackupValidationException("$table.${column.name} 不是 SQLite primitive")
        val valid = when {
            column.affinity.contains("INT") -> !primitive.isString && primitive.longOrNull != null
            column.affinity.contains("CHAR") || column.affinity.contains("CLOB") || column.affinity.contains("TEXT") ->
                primitive.isString
            column.affinity.contains("REAL") || column.affinity.contains("FLOA") || column.affinity.contains("DOUB") ->
                !primitive.isString && primitive.doubleOrNull != null
            column.affinity.contains("BLOB") || column.affinity.isBlank() -> false
            else -> !primitive.isString && primitive.doubleOrNull != null
        }
        if (!valid) throw BackupValidationException("$table.${column.name} SQLite 类型不匹配")
    }

    private fun validateSecrets(secrets: Map<SecretId, ByteArray>, providerIds: Set<String>) {
        val eligible = eligibleSecretIds(providerIds)
        if (secrets.keys.any { it !in eligible || it == SecretCatalog.automaticBackupPassword }) {
            throw BackupValidationException("恢复包包含不可备份的密钥")
        }
    }

    private fun eligibleSecretIds(providerIds: Collection<String>): Set<SecretId> =
        SecretCatalog.backupEligible(providerIds) - SecretCatalog.automaticBackupPassword

    private fun validateFileBytes(row: JsonObject, bytes: ByteArray) {
        val expectedSize = row.requiredLong("size_bytes")
        if (expectedSize != bytes.size.toLong()) throw BackupValidationException("文件大小不一致")
        val expectedHash = row.requiredString("hash")
        if (!Regex("[0-9a-fA-F]{64}").matches(expectedHash) ||
            !expectedHash.equals(sha256(bytes), ignoreCase = true)
        ) {
            throw BackupValidationException("文件摘要不一致")
        }
    }

    private fun transformPaths(payload: DatabaseBackupPayload, finalRoot: Path): DatabaseBackupPayload {
        val rootTokens = linkedMapOf<String, String>()
        val rootIdentities = linkedMapOf<String, String>()
        payload.rows(FILE_TABLE).forEach { row ->
            val logicalRoot = logicalRoot(row)
            rootTokens.getOrPut(logicalRoot) { safeRootToken(logicalRoot) }
            rootIdentities.getOrPut(logicalRoot) { UUID.randomUUID().toString() }
        }
        if (rootTokens.values.size != rootTokens.values.toSet().size) {
            throw BackupValidationException("workspace root token 冲突")
        }
        val transformedTables = payload.tables.toMutableMap()
        transformedTables[FILE_TABLE] = payload.rows(FILE_TABLE).map { row ->
            val token = rootTokens.getValue(logicalRoot(row))
            val rewritten = row + mapOf(
                    "physical_root_path" to JsonPrimitive(finalRoot.resolve(token).toString()),
                    "vectorized_at" to JsonNull,
                    "kg_extracted_at" to JsonNull,
                )
            JsonObject(
                if (row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                    row.optionalString("parent_uuid") == null
                ) rewritten + ("hash" to JsonPrimitive(rootIdentities.getValue(logicalRoot(row))))
                else rewritten
            )
        }
        transformedTables["messages"] = payload.rows("messages").map { row ->
            JsonObject(row + ("vectorization_status" to JsonNull))
        }
        transformedTables["sessions"] = payload.rows("sessions").map { row ->
            val workspaceRootUuid = row.optionalString("workspace_root_uuid")
            val token = workspaceRootUuid?.let(rootTokens::get)
            if (token == null) row else JsonObject(
                row + ("workspace_path" to JsonPrimitive(finalRoot.resolve(token).toString()))
            )
        }
        val sessionPathMapping = payload.rows("sessions").associate { row ->
            val sessionId = row.requiredString("id")
            val rootId = row.optionalString("workspace_root_uuid")
            val token = rootId?.let(rootTokens::get)
            val oldRoot = row.optionalString("workspace_path")?.let(Paths::get)?.toAbsolutePath()?.normalize()
            val newRoot = token?.let(finalRoot::resolve)
            sessionId to (oldRoot to newRoot)
        }
        transformedTables["artifacts"] = payload.rows("artifacts").map { row ->
            val artifactPath = row.optionalString("workspace_path") ?: return@map row
            val (oldRoot, newRoot) = sessionPathMapping.getValue(row.requiredString("session_id"))
            if (oldRoot == null || newRoot == null) throw BackupValidationException("Artifact 缺少可重写的 workspace root")
            val oldArtifact = Paths.get(artifactPath).toAbsolutePath().normalize()
            if (!oldArtifact.startsWith(oldRoot)) throw BackupValidationException("Artifact workspace path 越界")
            JsonObject(row + ("workspace_path" to JsonPrimitive(newRoot.resolve(oldRoot.relativize(oldArtifact)).toString())))
        }
        return payload.copy(tables = transformedTables)
    }

    private data class StagedWorkspaceFiles(
        val expectedTree: Set<RestoreTreeEntry>,
        val payload: DatabaseBackupPayload,
    )

    private fun stageFiles(
        stagingRoot: Path,
        transformed: DatabaseBackupPayload,
        files: Map<String, ByteArray>,
    ): StagedWorkspaceFiles {
        if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("staging 目录已存在")
        }
        restoreFileOperations.createTransactionRoot(restoreParent, stagingRoot.fileName.toString())
        val txId = stagingRoot.fileName.toString().removePrefix(".restore-").removeSuffix(".tmp")
        restoreFileOperations.writeNew(stagingRoot, listOf(OWNER_MARKER), txId.toByteArray())
        val seen = mutableSetOf<String>()
        val directories = linkedSetOf<List<String>>()
        val pendingFiles = mutableListOf<Pair<List<String>, ByteArray>>()
        transformed.rows(FILE_TABLE).forEach { row ->
            val finalPhysicalRoot = Paths.get(row.requiredString("physical_root_path"))
            val rootToken = finalPhysicalRoot.fileName.toString()
            val relative = normalizeMaterializedPath(row.requiredString("materialized_path"))
            val treePath = listOf(rootToken) + relative
            val identity = treePath.joinToString("/")
            if (!seen.add(identity)) {
                throw BackupValidationException("恢复文件路径重复或越界")
            }
            (1..treePath.size).forEach { depth ->
                if (depth < treePath.size || row.requiredBoolean("is_directory")) {
                    directories += treePath.take(depth)
                }
            }
            if (row.requiredBoolean("is_directory")) {
                // 目录统一在 descriptor-relative mkdir 阶段创建。
            } else {
                pendingFiles += treePath to files.getValue(row.requiredString("uuid"))
            }
        }
        directories.sortedBy { it.size }.forEach { restoreFileOperations.createDirectory(stagingRoot, it) }
        val restoredIdentities = linkedMapOf<String, String>()
        transformed.rows(FILE_TABLE)
            .filter { row ->
                row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                    row.optionalString("parent_uuid") == null
            }
            .forEach { rootRow ->
                val rootToken = Paths.get(rootRow.requiredString("physical_root_path")).fileName.toString()
                restoredIdentities[rootRow.requiredString("uuid")] = restoreFileOperations.initializeWorkspaceRootIdentity(
                    stagingRoot,
                    listOf(rootToken),
                    rootRow.requiredString("hash"),
                )
            }
        pendingFiles.forEach { (relative, bytes) -> restoreFileOperations.writeNew(stagingRoot, relative, bytes) }
        val expected = buildSet {
            add(RestoreTreeEntry(OWNER_MARKER, false))
            directories.forEach { add(RestoreTreeEntry(it.joinToString("/"), true)) }
            transformed.rows(FILE_TABLE)
                .filter { it.requiredString("uuid") == it.requiredString("workspace_root_uuid") && it.optionalString("parent_uuid") == null }
                .forEach { row ->
                    val token = Paths.get(row.requiredString("physical_root_path")).fileName.toString()
                    add(RestoreTreeEntry("$token/.nexara_root_identity", false))
                }
            pendingFiles.forEach { (path, _) -> add(RestoreTreeEntry(path.joinToString("/"), false)) }
        }
        restoreFileOperations.verifyAndSync(stagingRoot, expected)
        val rewrittenTables = transformed.tables.toMutableMap()
        rewrittenTables[FILE_TABLE] = transformed.rows(FILE_TABLE).map { row ->
            restoredIdentities[row.requiredString("uuid")]?.let { identity ->
                JsonObject(row + ("hash" to JsonPrimitive(identity)))
            } ?: row
        }
        return StagedWorkspaceFiles(expected, transformed.copy(tables = rewrittenTables))
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            restoreFileOperations.moveTree(
                target.parent,
                source.fileName.toString(),
                target.fileName.toString(),
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw BackupValidationException("恢复目录所在文件系统不支持原子移动", error)
        }
        FileRestoreJournal.syncDirectory(target.parent)
    }

    private fun clearAllRestorableTables() {
        val sqlite = database.openHelper.writableDatabase
        (DERIVED_DELETE_ORDER + DELETE_ORDER).forEach { sqlite.execSQL("DELETE FROM `$it`") }
    }

    private fun insertPayload(payload: DatabaseBackupPayload) {
        val sqlite = database.openHelper.writableDatabase
        INSERT_ORDER.forEach { table ->
            val rows = if (table == "messages") {
                sortMessagesParentFirst(payload.rows(table))
            } else {
                payload.rows(table)
            }
            rows.forEach { row ->
                val values = ContentValues(row.size)
                row.forEach { (column, value) ->
                    when (value) {
                        JsonNull -> values.putNull(column)
                        is JsonPrimitive -> when {
                            value.isString -> values.put(column, value.content)
                            value.booleanOrNull != null -> values.put(column, if (value.booleanOrNull == true) 1L else 0L)
                            value.content.contains('.') || value.content.contains('e', true) ->
                                values.put(column, value.doubleOrNull ?: throw BackupValidationException("数值字段无效"))
                            else -> values.put(column, value.longOrNull ?: throw BackupValidationException("整数字段无效"))
                        }
                        else -> throw BackupValidationException("数据库字段类型无效")
                    }
                }
                if (sqlite.insert(table, 0, values) == -1L) {
                    throw BackupValidationException("恢复数据库表失败: $table")
                }
            }
        }
    }

    private fun validateUnique(payload: DatabaseBackupPayload, table: String, column: String) {
        val values = payload.rows(table).map { it.requiredString(column) }
        if (values.any(String::isBlank) || values.size != values.toSet().size) {
            throw BackupValidationException("$table 主键为空或重复")
        }
    }

    private fun validateCompositeUnique(payload: DatabaseBackupPayload, table: String, columns: List<String>) {
        val values = payload.rows(table).map { row -> columns.map { column -> row.requiredString(column) } }
        if (values.size != values.toSet().size) throw BackupValidationException("$table 复合主键重复")
    }

    private fun requireReference(row: JsonObject, column: String, ids: Set<String>, label: String) {
        if (row.requiredString(column) !in ids) throw BackupValidationException("$label 外键无效")
    }

    private fun requireCanonicalSnapshotContent(content: Set<BackupContent>) {
        val required = setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
        if (!content.containsAll(required) || content.any { it !in required && it != BackupContent.SECRETS }) {
            throw BackupValidationException("初版备份只支持 DATABASE + PREFERENCES + FILES，SECRETS 可选")
        }
    }

    private fun validateSnapshotLimits(
        databaseBytes: ByteArray,
        preferenceBytes: ByteArray,
        files: Map<String, ByteArray>,
        secretValues: Map<SecretId, ByteArray>,
    ) {
        if (databaseBytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES ||
            preferenceBytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES ||
            files.size + 2 + (if (secretValues.isEmpty()) 0 else 1) > BackupPackageLimits.MAX_ENTRIES
        ) throw BackupValidationException("snapshot 超过备份 entry 限制")
        var total = databaseBytes.size.toLong() + preferenceBytes.size.toLong()
        (files.values + secretValues.values).forEach { bytes ->
            if (bytes.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("snapshot 内容项超过大小限制")
            }
            total = Math.addExact(total, bytes.size.toLong())
            if (total > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("snapshot 超过总大小限制")
        }
        if (total > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("snapshot 超过 Android 内存安全限制")
        }
    }

    private fun requireCanonicalRestoreContent(validated: ValidatedBackup) {
        val paths = validated.manifest.entries.map { it.path }.toSet()
        if ("database.json" !in paths || "preferences.json" !in paths ||
            validated.database.isEmpty() || validated.preferences.isEmpty() ||
            validated.files.keys.any { "files/$it" !in paths } ||
            paths.filter { it.startsWith("files/") }.map { it.removePrefix("files/") }.toSet() != validated.files.keys ||
            (("secrets.json" in paths) != validated.manifest.containsSecrets) ||
            (validated.secrets.isNotEmpty() && "secrets.json" !in paths)
        ) throw BackupValidationException("恢复包不是规范全量包")
        val materializedTotal = validated.database.size.toLong() + validated.preferences.size +
            validated.files.values.sumOf { it.size.toLong() } + validated.secrets.values.sumOf { it.size.toLong() }
        if (materializedTotal > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("恢复包超过 Android 内存安全限制")
        }
    }

    private fun requireTrustedSourceRoot(value: String): Path {
        val root = Paths.get(value).toAbsolutePath().normalize()
        FileRestoreJournal.rejectSymlinkAncestors(root)
        val real = root.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val directSource = trustedSourceBases.count { real.parent == it } == 1
        val restoredContainer = real.parent?.takeIf { container ->
            container.parent == restoreParent && RESTORE_ROOT.matches(container.fileName.toString()) &&
                isOwnedRestoreContainer(container)
        }
        if ((!directSource && restoredContainer == null) || !SAFE_TOKEN.matches(real.fileName.toString())) {
            throw BackupValidationException("文件根目录不属于受信 app-private base 的直接子目录")
        }
        return real
    }

    private fun fingerprint(payload: DatabaseBackupPayload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        INSERT_ORDER.sorted().forEach { table ->
            digest.update(table.toByteArray())
            payload.rows(table).map(::canonicalRow).sorted().forEach { digest.update(it.toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canonicalRow(row: JsonObject): String = row.entries.sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }

    private fun fileKey(path: Path): String = Files.readAttributes(
        path,
        java.nio.file.attribute.BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    ).fileKey()?.toString() ?: throw BackupValidationException("文件系统未提供稳定 fileKey")

    private fun currentOldRootIdentity(): String {
        val rows = readDatabasePayload().rows(FILE_TABLE)
        val groups = rows.groupBy { row ->
            managedContainer(requireTrustedSourceRoot(row.requiredString("physical_root_path")))
        }
        return groups.entries.joinToString(",") { (managed, managedRows) ->
            val expected = expectedManagedInventory(managed, managedRows)
            val actual = restoreFileOperations.inventory(managed.path.parent, managed.path.fileName.toString())
            if (actual != expected) throw BackupValidationException(
                "旧 managed root 包含未登记、缺失或异常内容；missing=${expected - actual}；unexpected=${actual - expected}",
            )
            val encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fileKey(managed.path).toByteArray())
            "${managed.baseIndex}:$encodedKey:${inventoryToken(expected)}"
        }
    }

    private suspend fun finishCommitted(record: RestoreJournalRecord) {
        requireOwnedRestoreRoot(restoreParent.resolve(record.newRootIdentity), record)
        preferences.finalizePrepared(record.txId)
        secrets.finalizePrepared(record.txId)
        deleteOldRoots(record)
        writeCompletedReceipt(record)
        crashHook.hit(RestoreCrashPoint.RECEIPT_PERSISTED)
        journal.delete()
    }

    private fun writeDatabaseCommitMarker(record: RestoreJournalRecord) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO audit_logs(id,action,resource_type,status,metadata,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(
                databaseMarkerId(record.txId),
                DATABASE_MARKER_ACTION,
                "restore_transaction",
                "committed",
                record.expectedDatabaseFingerprint,
                System.currentTimeMillis(),
            ),
        )
    }

    private fun hasDatabaseCommitMarker(record: RestoreJournalRecord): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT metadata FROM audit_logs WHERE id = ? AND action = ? AND status = ?",
            arrayOf(databaseMarkerId(record.txId), DATABASE_MARKER_ACTION, "committed"),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            if (cursor.getString(0) != record.expectedDatabaseFingerprint) {
                throw BackupValidationException("Room restore marker 与 journal 不一致")
            }
            true
        }

    private fun writeCompletedReceipt(record: RestoreJournalRecord) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE audit_logs SET status = ?, metadata = ?, created_at = ? WHERE id = ? AND action = ?",
            arrayOf<Any?>(
                "completed",
                record.expectedDatabaseFingerprint,
                System.currentTimeMillis(),
                databaseMarkerId(record.txId),
                DATABASE_MARKER_ACTION,
            ),
        )
        if (!hasCompletedRestoreLocked(record.txId)) {
            throw BackupValidationException("恢复完成回执持久化失败")
        }
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM audit_logs WHERE action = ? AND status = ? AND id NOT IN " +
                "(SELECT id FROM audit_logs WHERE action = ? AND status = ? AND id != ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?) AND id != ?",
            arrayOf<Any?>(
                DATABASE_MARKER_ACTION,
                "completed",
                DATABASE_MARKER_ACTION,
                "completed",
                databaseMarkerId(record.txId),
                MAX_COMPLETED_RECEIPTS - 1,
                databaseMarkerId(record.txId),
            ),
        )
    }

    private fun hasCompletedRestoreLocked(operationId: String): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT 1 FROM audit_logs WHERE id = ? AND action = ? AND status = ? LIMIT 1",
            arrayOf(databaseMarkerId(operationId), DATABASE_MARKER_ACTION, "completed"),
        ).use { it.moveToFirst() }

    private fun requireOperationId(operationId: String) {
        if (!OPERATION_ID.matches(operationId)) throw BackupValidationException("恢复 operationId 无效")
    }

    private fun databaseMarkerId(txId: String): String = "__nexara_restore_$txId"

    private fun deleteOldRoots(record: RestoreJournalRecord) {
        val identity = record.oldRootIdentity
        if (identity.isBlank()) return
        identity.split(',').forEach { encoded ->
            val parts = encoded.split(':', limit = 3)
            if (parts.size != 3) throw BackupValidationException("旧 root identity 无效")
            val baseIndex = parts[0].toIntOrNull() ?: throw BackupValidationException("旧 root base identity 无效")
            val base = if (baseIndex == RESTORE_BASE_INDEX) restoreParent else {
                trustedSourceBases.getOrNull(baseIndex) ?: throw BackupValidationException("旧 root base identity 无效")
            }
            val expectedKey = try {
                String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            } catch (error: IllegalArgumentException) {
                throw BackupValidationException("旧 root identity 编码无效", error)
            }
            val matches = Files.newDirectoryStream(base).use { stream ->
                stream.filter { child ->
                    !Files.isSymbolicLink(child) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                        runCatching { fileKey(child) == expectedKey }.getOrDefault(false)
                }.toList()
            }
            if (matches.size > 1) throw BackupValidationException("旧 root fileKey 匹配不唯一")
            val root = matches.singleOrNull() ?: return@forEach
            val actualInventory = restoreFileOperations.inventory(base, root.fileName.toString())
            val expectedHashes = if (parts[2] == "-") emptySet() else parts[2].split('.').toSet()
            val cleanupMarker = root.resolve(OLD_CLEANUP_MARKER)
            val markerPresent = Files.exists(cleanupMarker, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { readSmallNoFollow(cleanupMarker, 128).toString(Charsets.UTF_8) == record.txId }
                    .getOrDefault(false)
            val actualHashes = inventoryEntryHashes(actualInventory.filterNot { it.path == OLD_CLEANUP_MARKER }.toSet())
            val tombstoneName = ".restore-delete-${record.txId}-${sha256(expectedKey.toByteArray()).take(12)}"
            val terminalPartial = root.fileName.toString() == tombstoneName && actualInventory.isEmpty()
            if (!terminalPartial && ((!markerPresent && actualHashes != expectedHashes) ||
                (markerPresent && !expectedHashes.containsAll(actualHashes))
            )) {
                throw BackupValidationException("旧 managed root inventory 已变化，拒绝整根删除")
            }
            if (!terminalPartial && !markerPresent) {
                restoreFileOperations.writeNew(root, listOf(OLD_CLEANUP_MARKER), record.txId.toByteArray())
            }
            val expectedDeleteInventory = when {
                terminalPartial -> emptySet()
                markerPresent -> actualInventory
                else -> actualInventory + RestoreTreeEntry(OLD_CLEANUP_MARKER, false)
            }
            val deleteRoot = if (root.fileName.toString() == tombstoneName) root else {
                restoreFileOperations.moveTree(base, root.fileName.toString(), tombstoneName)
                FileRestoreJournal.syncDirectory(base)
                base.resolve(tombstoneName)
            }
            val deleteMarker = if (expectedDeleteInventory.any { it.path == OLD_CLEANUP_MARKER }) {
                OLD_CLEANUP_MARKER to record.txId
            } else null
            restoreFileOperations.deleteTree(
                base, deleteRoot.fileName.toString(), expectedKey, deleteMarker, expectedDeleteInventory
            )
            FileRestoreJournal.syncDirectory(base)
        }
    }

    private fun managedContainer(root: Path): ManagedContainer {
        val directIndex = trustedSourceBases.indexOf(root.parent)
        if (directIndex >= 0) return ManagedContainer(root, directIndex)
        val container = root.parent
        if (container?.parent == restoreParent && RESTORE_ROOT.matches(container.fileName.toString()) &&
            isOwnedRestoreContainer(container)
        ) return ManagedContainer(container, RESTORE_BASE_INDEX)
        throw BackupValidationException("无法确定 workspace root 的受信容器")
    }

    private fun expectedManagedInventory(
        managed: ManagedContainer,
        rows: List<JsonObject>,
    ): Set<RestoreTreeEntry> = buildSet {
        if (managed.baseIndex == RESTORE_BASE_INDEX) add(RestoreTreeEntry(OWNER_MARKER, false))
        rows.forEach { row ->
            val physicalRoot = requireTrustedSourceRoot(row.requiredString("physical_root_path"))
            val prefix = if (physicalRoot == managed.path) emptyList() else listOf(physicalRoot.fileName.toString())
            val relative = prefix + normalizeMaterializedPath(row.requiredString("materialized_path"))
            val isDirectory = row.requiredBoolean("is_directory")
            if (row.requiredString("uuid") == row.requiredString("workspace_root_uuid") &&
                row.optionalString("parent_uuid") == null &&
                Regex("^[^|]+\\|[0-9a-f]{64}$").matches(row.requiredString("hash"))
            ) {
                add(RestoreTreeEntry((prefix + ".nexara_root_identity").joinToString("/"), false))
            }
            val ancestorLimit = if (isDirectory) relative.size else relative.size - 1
            (1..ancestorLimit.coerceAtLeast(0)).forEach { depth ->
                add(RestoreTreeEntry(relative.take(depth).joinToString("/"), true))
            }
            if (!isDirectory) add(RestoreTreeEntry(relative.joinToString("/"), false))
        }
    }

    private fun inventoryEntryHashes(entries: Set<RestoreTreeEntry>): Set<String> = entries.mapTo(linkedSetOf()) {
        sha256("${if (it.directory) 'd' else 'f'}:${it.path}".toByteArray())
    }

    private fun inventoryToken(entries: Set<RestoreTreeEntry>): String =
        inventoryEntryHashes(entries).sorted().joinToString(".").ifEmpty { "-" }

    private fun isOwnedRestoreContainer(container: Path): Boolean {
        val txId = container.fileName.toString().removePrefix("restore-")
        return runCatching { readSmallNoFollow(container.resolve(OWNER_MARKER), 128).toString(Charsets.UTF_8) == txId }
            .getOrDefault(false)
    }

    private fun deleteOwnedRestoreRoots(record: RestoreJournalRecord) {
        listOf("staging" to ".restore-${record.txId}.tmp", "new" to record.newRootIdentity)
            .forEach { (kind, originalName) ->
                val tombstoneName = ".restore-delete-${record.txId}-$kind"
                var root = listOf(restoreParent.resolve(originalName), restoreParent.resolve(tombstoneName))
                    .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: return@forEach
                val terminalPartial = root.fileName.toString() == tombstoneName &&
                    restoreFileOperations.inventory(restoreParent, tombstoneName).isEmpty()
                if (!terminalPartial) {
                    requireOwnedRestoreRoot(root, record)
                }
                val expectedInventory = if (terminalPartial) emptySet() else {
                    restoreFileOperations.inventory(restoreParent, root.fileName.toString())
                }
                val expectedKey = if (root.fileName.toString() == record.newRootIdentity) {
                    record.newRootFileKey ?: fileKey(root)
                } else fileKey(root)
                if (root.fileName.toString() != tombstoneName) {
                    restoreFileOperations.moveTree(restoreParent, root.fileName.toString(), tombstoneName)
                    FileRestoreJournal.syncDirectory(restoreParent)
                    root = restoreParent.resolve(tombstoneName)
                }
                val marker = if (expectedInventory.any { it.path == OWNER_MARKER }) OWNER_MARKER to record.txId else null
                restoreFileOperations.deleteTree(
                    restoreParent,
                    tombstoneName,
                    expectedKey,
                    marker,
                    expectedInventory,
                )
            }
        FileRestoreJournal.syncDirectory(restoreParent)
    }

    private fun requireOwnedRestoreRoot(root: Path, record: RestoreJournalRecord) {
        if (root.parent != restoreParent || Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) throw BackupValidationException("恢复 root 身份无效")
        record.newRootFileKey?.let { expected ->
            if (root.fileName.toString() == record.newRootIdentity && fileKey(root) != expected) {
                throw BackupValidationException("恢复 root fileKey 已变化")
            }
        }
        val markerValue = readSmallNoFollow(root.resolve(OWNER_MARKER), 128).toString(Charsets.UTF_8)
        if (markerValue != record.txId) throw BackupValidationException("恢复 root owner marker 无效")
    }

    private fun readSmallNoFollow(path: Path, limit: Long): ByteArray {
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val attributes = Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            )
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > limit) {
                throw BackupValidationException("受信元数据文件无效")
            }
            val buffer = java.nio.ByteBuffer.allocate((limit + 1).toInt())
            var total = 0
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw BackupValidationException("受信元数据文件超过限制")
            }
            return buffer.array().copyOf(total)
        }
    }

    private fun logicalRoot(row: JsonObject): String =
        row.optionalString("workspace_root_uuid")?.takeIf(String::isNotBlank)
            ?: "path:${row.requiredString("physical_root_path")}"

    private fun safeRootToken(logicalRoot: String): String {
        return "root-${sha256(logicalRoot.toByteArray())}"
    }

    private fun normalizeMaterializedPath(value: String): List<String> {
        if (value.indexOf('\u0000') >= 0) throw BackupValidationException("文件路径包含 NUL")
        val normalizedSeparators = value.replace('\\', '/')
        val parts = normalizedSeparators.split('/').filter(String::isNotEmpty)
        if (parts.any { it == "." || it == ".." }) throw BackupValidationException("文件路径越界")
        if (parts.any { it.startsWith(".nexara", ignoreCase = true) }) {
            throw BackupValidationException("文件路径占用系统保留名称")
        }
        return parts
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun DatabaseBackupPayload.rows(table: String): List<JsonObject> =
        tables[table] ?: throw BackupValidationException("数据库 payload 缺少表: $table")

    private fun DatabaseBackupPayload.ids(table: String, column: String): Set<String> =
        rows(table).map { it.requiredString(column) }.toSet()

    private fun JsonObject.requiredString(column: String): String =
        (get(column) as? JsonPrimitive)?.contentOrNull
            ?: throw BackupValidationException("数据库字段缺失或类型无效: $column")

    private fun JsonObject.optionalString(column: String): String? {
        val value = get(column) ?: throw BackupValidationException("数据库字段缺失: $column")
        return if (value is JsonNull) null else (value as? JsonPrimitive)?.contentOrNull
            ?: throw BackupValidationException("数据库字段类型无效: $column")
    }

    private fun JsonObject.requiredLong(column: String): Long =
        (get(column) as? JsonPrimitive)?.longOrNull
            ?: throw BackupValidationException("数据库整数字段无效: $column")

    private fun JsonObject.requiredBoolean(column: String): Boolean {
        val primitive = get(column) as? JsonPrimitive
            ?: throw BackupValidationException("数据库布尔字段无效: $column")
        return primitive.booleanOrNull ?: when (primitive.longOrNull) {
            0L -> false
            1L -> true
            else -> throw BackupValidationException("数据库布尔字段无效: $column")
        }
    }

    private data class SnapshotFilesResult(
        val payload: DatabaseBackupPayload,
        val files: Map<String, ByteArray>,
    )

    private data class RoomColumn(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val primaryKeyOrder: Int,
    )

    private data class ManagedContainer(val path: Path, val baseIndex: Int)

    private class MaterializationBudget(private val limit: Long) {
        private var used = 0L
        fun consume(bytes: Long) {
            used = Math.addExact(used, bytes)
            if (used > limit) throw BackupValidationException("数据库快照超过 Android 内存安全限制")
        }
    }

    private class ClosingSeekableByteChannel(
        private val delegate: java.nio.channels.SeekableByteChannel,
        private val directory: java.nio.file.DirectoryStream<Path>,
    ) : java.nio.channels.SeekableByteChannel by delegate {
        override fun close() {
            var failure: Throwable? = null
            runCatching { delegate.close() }.onFailure { failure = it }
            runCatching { directory.close() }.onFailure { error -> failure?.addSuppressed(error) ?: run { failure = error } }
            failure?.let { throw it }
        }
    }

    private companion object {
        const val DATABASE_SCHEMA_VERSION = 1
        const val FILE_TABLE = "workspace_files"
        const val OWNER_MARKER = ".restore-owner"
        const val OLD_CLEANUP_MARKER = ".restore-cleanup-owner"
        const val DATABASE_MARKER_ACTION = "__nexara_restore_commit"
        val SAFE_TOKEN = Regex("[A-Za-z0-9._-]{1,128}")
        val RESTORE_ROOT = Regex("restore-[A-Za-z0-9-]{1,72}")
        const val RESTORE_BASE_INDEX = -1
        const val MAX_COMPLETED_RECEIPTS = 64
        val OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")

        // 用户源数据。明确排除 vectors/FTS/KG/JIT/vectorization_tasks/audit_logs/
        // tool_execution_ledger/file_versions，它们均为可重建派生数据、运行态账本或版本缓存。
        val INSERT_ORDER = listOf(
            "agents",
            "sessions",
            "messages",
            "attachments",
            "artifacts",
            "context_summaries",
            "tags",
            "document_tags",
            "task_nodes",
            "custom_skills",
            "mcp_servers",
            FILE_TABLE,
            "workspace_seq",
        )
        val DELETE_ORDER = INSERT_ORDER.reversed()
        val DERIVED_DELETE_ORDER = listOf(
            "tool_execution_ledger", "file_versions", "vectorization_tasks",
            "kg_edges", "kg_nodes", "kg_jit_cache", "vectors_fts", "vectors", "audit_logs",
        )
    }
}
