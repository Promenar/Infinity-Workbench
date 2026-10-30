package com.promenar.infinity.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CancellationException

/** 只打包应用私有头像目录；包内引用不携带源设备路径或文件名。 */
internal object AvatarBackupFiles {
    const val PREFIX = "avatars/"
    const val REFERENCE_PREFIX = "avatar:"
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")

    data class Snapshot(val payload: DatabaseBackupPayload, val preferences: BackupPreferenceSnapshot, val files: Map<String, ByteArray>)
    data class RestorePlan(val payload: DatabaseBackupPayload, val preferences: BackupPreferenceSnapshot, val files: Map<String, ByteArray>)

    fun requireId(id: String) {
        if (!safeId.matches(id)) throw BackupValidationException("头像文件标识无效")
    }

    fun validateSizes(sizes: Map<String, Long>) {
        var total = 0L
        sizes.forEach { (id, size) ->
            requireId(id)
            if (size < 0 || size > MAX_FILE_BYTES || size > MAX_TOTAL_BYTES - total) {
                throw BackupValidationException("头像文件大小超过限制")
            }
            total += size
        }
    }

    fun snapshot(
        payload: DatabaseBackupPayload,
        preferences: BackupPreferenceSnapshot,
        root: Path?,
        memoryBudget: Long,
        entryBudget: Int,
        readStable: (Path, Long, String) -> Pair<ByteArray, String>,
    ): Snapshot {
        val files = linkedMapOf<String, ByteArray>()
        val references = mutableMapOf<Path, String>()
        var remaining = minOf(memoryBudget.coerceAtLeast(0), MAX_TOTAL_BYTES)
        fun capture(raw: String?): String? {
            if (raw.isNullOrBlank() || root == null) return null
            return try {
                val trustedRoot = FileRestoreJournal.requireTrustedDirectory(root)
                val rootIdentity = Files.readAttributes(trustedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                val path = Paths.get(raw).toAbsolutePath().normalize()
                if (!path.startsWith(trustedRoot) || path == trustedRoot) return null
                FileRestoreJournal.rejectSymlinkAncestors(path)
                references[path]?.let { return it }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_FILE_BYTES ||
                    attributes.size() > remaining || files.size >= entryBudget
                ) return null
                val id = UUID.randomUUID().toString()
                val bytes = readStable(path, attributes.size(), id).first
                try {
                    FileRestoreJournal.requireTrustedDirectory(root)
                    FileRestoreJournal.rejectSymlinkAncestors(path)
                    if (bytes.size.toLong() != attributes.size() ||
                        rootIdentity != Files.readAttributes(trustedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                    ) throw BackupValidationException("头像目录或文件在读取期间发生变化")
                } catch (error: Throwable) {
                    bytes.fill(0)
                    throw error
                }
                files[id] = bytes
                remaining -= bytes.size
                (REFERENCE_PREFIX + id).also { references[path] = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 缺失、越界、链接或不稳定头像只清除对应引用，不阻断整个备份。
                null
            }
        }
        try {
            val rewritten = mapPayload(payload, ::capture)
            val rewrittenPreferences = mapPreferences(preferences, ::capture)
            return Snapshot(rewritten, rewrittenPreferences, files)
        } catch (error: Throwable) {
            files.values.forEach { it.fill(0) }
            throw error
        }
    }

    fun planRestore(
        payload: DatabaseBackupPayload,
        preferences: BackupPreferenceSnapshot,
        files: Map<String, ByteArray>,
        root: Path?,
        allowReferences: Boolean,
    ): RestorePlan {
        validateSizes(files.mapValues { it.value.size.toLong() })
        if (!allowReferences && files.isNotEmpty()) throw BackupValidationException("旧格式不支持头像分区")
        if (files.isNotEmpty() && root == null) throw BackupValidationException("头像恢复根目录未配置")
        val names = files.keys.associateWith { UUID.randomUUID().toString() + ".img" }
        val referenced = mutableSetOf<String>()
        fun resolve(raw: String?): String? {
            if (!allowReferences || raw == null || !raw.startsWith(REFERENCE_PREFIX)) return null
            val id = raw.removePrefix(REFERENCE_PREFIX)
            requireId(id)
            val name = names[id] ?: throw BackupValidationException("头像引用缺少文件")
            referenced += id
            return root!!.toAbsolutePath().normalize().resolve(name).toString()
        }
        val rewritten = mapPayload(payload, ::resolve)
        val rewrittenPreferences = mapPreferences(preferences, ::resolve)
        if (referenced != files.keys) throw BackupValidationException("头像分区包含无引用文件")
        return RestorePlan(rewritten, rewrittenPreferences, files.mapKeys { names.getValue(it.key) })
    }

    private fun mapPayload(payload: DatabaseBackupPayload, mapPath: (String?) -> String?): DatabaseBackupPayload {
        val agents = payload.tables["agents"] ?: return payload
        return payload.copy(tables = payload.tables + ("agents" to agents.map { row ->
            val raw = row["avatar_path"]?.let { if (it == JsonNull) null else it.jsonPrimitive.contentOrNull }
            JsonObject(row + ("avatar_path" to (mapPath(raw)?.let(::JsonPrimitive) ?: JsonNull)))
        }))
    }

    private fun mapPreferences(snapshot: BackupPreferenceSnapshot, mapPath: (String?) -> String?): BackupPreferenceSnapshot =
        snapshot.copy(entries = snapshot.entries.mapNotNull { entry ->
            if (BackupPreferencePolicy.normalizeNamespace(entry.namespace) != "settings" || entry.key != "user_avatar") entry
            else if (entry.type != PreferenceValueType.STRING) throw BackupValidationException("用户头像偏好类型无效")
            else mapPath(entry.value)?.let { entry.copy(value = it) }
        })
}
