package com.promenar.nexara.data.backup

import android.content.ContentValues
import android.database.Cursor
import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class RoomBackupDataSource(
    private val database: NexaraDatabase,
    private val preferences: BackupPreferenceStore,
    private val secretStore: SecretStore,
    private val restoreParent: Path,
    private val appVersion: String,
) : BackupDataSource {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = withContext(Dispatchers.IO) {
        val databaseAndFiles = if (BackupContent.DATABASE in content || BackupContent.FILES in content) {
            database.withTransaction {
                val payload = readDatabasePayload()
                val files = if (BackupContent.FILES in content) snapshotFiles(payload) else emptyMap()
                payload to files
            }
        } else {
            null
        }
        val tablePayload = databaseAndFiles?.first
        val rawPreferences = if (
            BackupContent.PREFERENCES in content || BackupContent.SECRETS in content
        ) preferences.snapshot() else null
        val safePreferences = rawPreferences?.let(::sanitizePreferences)

        val files = databaseAndFiles?.second.orEmpty()
        val secrets = if (BackupContent.SECRETS in content) {
            snapshotSecrets(safePreferences?.providerIds.orEmpty())
        } else {
            emptyMap()
        }

        BackupSnapshot(
            database = if (BackupContent.DATABASE in content) {
                json.encodeToString(tablePayload).toByteArray(Charsets.UTF_8)
            } else ByteArray(0),
            preferences = if (BackupContent.PREFERENCES in content) {
                json.encodeToString(safePreferences).toByteArray(Charsets.UTF_8)
            } else ByteArray(0),
            files = files,
            secrets = secrets,
            databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
            appVersion = appVersion,
        )
    }

    override suspend fun restore(validated: ValidatedBackup) = withContext(Dispatchers.IO) {
        val payload = parseDatabase(validated.database)
        val preferenceSnapshot = parsePreferences(validated.preferences)
        validatePayload(payload, validated.files)
        validateSecrets(validated.secrets, preferenceSnapshot.providerIds)

        Files.createDirectories(restoreParent)
        val restoreId = UUID.randomUUID().toString()
        val stagingRoot = restoreParent.resolve(".restore-$restoreId.tmp")
        val finalRoot = restoreParent.resolve("restore-$restoreId")
        val transformed = transformPaths(payload, finalRoot)
        var finalRootCreated = false
        val preferenceRestore = preferences.prepareReplace(preferenceSnapshot)
        val previousSecrets = capturePreviousSecrets(validated.secrets.keys)
        var preferencesCommitted = false
        var secretsCommitted = false

        try {
            stageFiles(stagingRoot, transformed, validated.files)
            moveAtomically(stagingRoot, finalRoot)
            finalRootCreated = true

            database.withTransaction {
                clearIncludedTables()
                insertPayload(transformed)
                preferenceRestore.commit()
                preferencesCommitted = true
                replaceSecrets(validated.secrets)
                secretsCommitted = true
            }
        } catch (error: Throwable) {
            var compensationFailure: Throwable? = null
            if (secretsCommitted || validated.secrets.isNotEmpty()) {
                runCatching { restoreSecrets(previousSecrets) }
                    .onFailure { compensationFailure = it }
            }
            if (preferencesCommitted) {
                runCatching { preferenceRestore.rollback() }
                    .onFailure { failure ->
                        compensationFailure?.addSuppressed(failure) ?: run { compensationFailure = failure }
                    }
            } else {
                // prepare 可能已持有临时资源；rollback 必须幂等。
                runCatching { preferenceRestore.rollback() }
                    .onFailure { failure ->
                        compensationFailure?.addSuppressed(failure) ?: run { compensationFailure = failure }
                    }
            }
            deleteRecursively(stagingRoot)
            if (finalRootCreated) deleteRecursively(finalRoot)
            compensationFailure?.let { error.addSuppressed(it) }
            throw error
        } finally {
            previousSecrets.values.forEach { it.value?.fill(0) }
        }
    }

    private fun readDatabasePayload(): DatabaseBackupPayload {
        val sqlite = database.openHelper.writableDatabase
        val tables = INSERT_ORDER.associateWith { table ->
            sqlite.query("SELECT * FROM `$table`").use(::cursorRows)
        }
        return DatabaseBackupPayload(DATABASE_SCHEMA_VERSION, tables)
    }

    private fun cursorRows(cursor: Cursor): List<JsonObject> = buildList {
        while (cursor.moveToNext()) {
            add(JsonObject(buildMap {
                cursor.columnNames.forEachIndexed { index, name ->
                    put(name, when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> throw BackupValidationException("用户源表不允许未声明的 BLOB 字段: $name")
                        else -> throw BackupValidationException("无法备份数据库字段: $name")
                    })
                }
            }))
        }
    }

    private fun snapshotFiles(payload: DatabaseBackupPayload): Map<String, ByteArray> {
        val rows = payload.rows(FILE_TABLE)
        val result = linkedMapOf<String, ByteArray>()
        rows.forEach { row ->
            val id = row.requiredString("uuid")
            if (row.requiredBoolean("is_directory")) return@forEach
            if (result.containsKey(id)) throw BackupValidationException("文件 UUID 重复")
            val path = resolveSnapshotPath(row)
            val bytes = try {
                Files.readAllBytes(path)
            } catch (error: Exception) {
                throw BackupValidationException("无法读取备份源文件: $id", error)
            }
            validateFileBytes(row, bytes)
            result[id] = bytes
        }
        return result
    }

    private fun resolveSnapshotPath(row: JsonObject): Path {
        val root = Path.of(row.requiredString("physical_root_path")).toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw BackupValidationException("备份根目录无效或为符号链接")
        }
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

    private fun snapshotSecrets(providerIds: Set<String>): Map<SecretId, ByteArray> = buildMap {
        eligibleSecretIds(providerIds).forEach { id ->
            secretStore.get(id)?.let { value -> put(id, value.copyOf()).also { value.fill(0) } }
        }
    }

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
            val allowed = PreferencePolicy.isAllowed(entry.namespace, entry.key)
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
        payload.rows("messages").forEach { requireReference(it, "session_id", sessions, "Session") }
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

        val nonDirectories = payload.rows(FILE_TABLE)
            .filterNot { it.requiredBoolean("is_directory") }
            .associateBy { it.requiredString("uuid") }
        if (files.keys != nonDirectories.keys) throw BackupValidationException("文件清单与数据库不一致")
        nonDirectories.forEach { (id, row) -> validateFileBytes(row, files.getValue(id)) }
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
        if (expectedHash.isNotBlank() && !expectedHash.equals(sha256(bytes), ignoreCase = true)) {
            throw BackupValidationException("文件摘要不一致")
        }
    }

    private fun transformPaths(payload: DatabaseBackupPayload, finalRoot: Path): DatabaseBackupPayload {
        val rootTokens = linkedMapOf<String, String>()
        payload.rows(FILE_TABLE).forEach { row ->
            val logicalRoot = logicalRoot(row)
            rootTokens.getOrPut(logicalRoot) { safeRootToken(logicalRoot) }
        }
        val transformedTables = payload.tables.toMutableMap()
        transformedTables[FILE_TABLE] = payload.rows(FILE_TABLE).map { row ->
            val token = rootTokens.getValue(logicalRoot(row))
            JsonObject(
                row + mapOf(
                    "physical_root_path" to JsonPrimitive(finalRoot.resolve(token).toString()),
                    "vectorized_at" to JsonNull,
                    "kg_extracted_at" to JsonNull,
                )
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
        return payload.copy(tables = transformedTables)
    }

    private fun stageFiles(
        stagingRoot: Path,
        transformed: DatabaseBackupPayload,
        files: Map<String, ByteArray>,
    ) {
        if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("staging 目录已存在")
        }
        Files.createDirectory(stagingRoot)
        val seen = mutableSetOf<Path>()
        transformed.rows(FILE_TABLE).forEach { row ->
            val finalPhysicalRoot = Path.of(row.requiredString("physical_root_path"))
            val rootToken = finalPhysicalRoot.fileName.toString()
            val stagingPhysicalRoot = stagingRoot.resolve(rootToken)
            Files.createDirectories(stagingPhysicalRoot)
            val relative = normalizeMaterializedPath(row.requiredString("materialized_path"))
            val destination = relative.fold(stagingPhysicalRoot) { current, part -> current.resolve(part) }.normalize()
            if (!destination.startsWith(stagingPhysicalRoot) || !seen.add(destination)) {
                throw BackupValidationException("恢复文件路径重复或越界")
            }
            if (row.requiredBoolean("is_directory")) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                writeAndSync(destination, files.getValue(row.requiredString("uuid")))
            }
        }
        syncDirectoryTree(stagingRoot)
    }

    private fun writeAndSync(path: Path, bytes: ByteArray) {
        FileOutputStream(path.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun syncDirectoryTree(root: Path) {
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(::syncDirectoryBestEffort)
        }
    }

    private fun syncDirectoryBestEffort(path: Path) {
        runCatching {
            FileOutputStream(path.toFile()).use { it.fd.sync() }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw BackupValidationException("恢复目录所在文件系统不支持原子移动", error)
        }
        syncDirectoryBestEffort(target.parent)
    }

    private fun clearIncludedTables() {
        val sqlite = database.openHelper.writableDatabase
        DELETE_ORDER.forEach { sqlite.execSQL("DELETE FROM `$it`") }
    }

    private fun insertPayload(payload: DatabaseBackupPayload) {
        val sqlite = database.openHelper.writableDatabase
        INSERT_ORDER.forEach { table ->
            payload.rows(table).forEach { row ->
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

    private fun capturePreviousSecrets(ids: Set<SecretId>): Map<SecretId, PreviousSecret> =
        ids.associateWith { id -> PreviousSecret(secretStore.contains(id), secretStore.get(id)) }

    private fun replaceSecrets(secrets: Map<SecretId, ByteArray>) {
        secrets.forEach { (id, value) -> secretStore.put(id, value.copyOf()) }
    }

    private fun restoreSecrets(previous: Map<SecretId, PreviousSecret>) {
        previous.forEach { (id, state) ->
            if (state.existed) secretStore.put(id, state.value?.copyOf() ?: ByteArray(0))
            else secretStore.remove(id)
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

    private fun logicalRoot(row: JsonObject): String =
        row.optionalString("workspace_root_uuid")?.takeIf(String::isNotBlank)
            ?: "path:${row.requiredString("physical_root_path")}"

    private fun safeRootToken(logicalRoot: String): String {
        if (SAFE_TOKEN.matches(logicalRoot)) return logicalRoot
        return "root-${sha256(logicalRoot.toByteArray()).take(24)}"
    }

    private fun normalizeMaterializedPath(value: String): List<String> {
        if (value.indexOf('\u0000') >= 0) throw BackupValidationException("文件路径包含 NUL")
        val normalizedSeparators = value.replace('\\', '/')
        val parts = normalizedSeparators.split('/').filter(String::isNotEmpty)
        if (parts.any { it == "." || it == ".." }) throw BackupValidationException("文件路径越界")
        return parts
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
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

    private data class PreviousSecret(val existed: Boolean, val value: ByteArray?)

    private object PreferencePolicy {
        private val deniedFragments = listOf(
            "api_key", "apikey", "authorization", "cookie", "token", "password", "passwd",
            "secret", "private_key", "privatekey", "service_account", "serviceaccount", "vertex_json",
            "vertexjson", "credential", "webdav_pass", "webdavpass", "automatic_backup_password",
            "automaticbackuppassword", "tavily_key", "tavilykey", "embedding_key", "embeddingkey",
        )
        private val allowedNamespaces = setOf("provider", "model", "settings", "rag", "search", "ui")
        private val allowedKeyFragments = listOf(
            "provider", "model", "protocol", "base_url", "baseurl", "enabled", "language", "locale",
            "rag", "search", "theme", "ui", "font", "layout", "appearance", "retrieval", "rerank",
            "chunk", "top_k", "threshold", "default_model",
        )

        fun isAllowed(namespace: String, key: String): Boolean {
            val normalizedNamespace = normalize(namespace)
            val normalizedKey = normalize(key)
            if (normalizedNamespace !in allowedNamespaces) return false
            if (deniedFragments.any(normalizedKey::contains)) return false
            return allowedKeyFragments.any(normalizedKey::contains)
        }

        private fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
    }

    private companion object {
        const val DATABASE_SCHEMA_VERSION = 1
        const val FILE_TABLE = "workspace_files"
        val SAFE_TOKEN = Regex("[A-Za-z0-9._-]{1,128}")

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
    }
}
