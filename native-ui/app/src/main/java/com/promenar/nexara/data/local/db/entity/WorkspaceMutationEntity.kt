package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class WorkspaceMutationStage {
    PREPARED,
    DB_COMMITTED,
}

enum class WorkspaceMutationType {
    CREATE,
    CREATE_STREAMING,
    MKDIR,
    RENAME,
    MOVE,
    RECYCLE,
    RESTORE,
    DELETE,
}

class WorkspaceMutationConverters {
    @TypeConverter
    fun stageToString(value: WorkspaceMutationStage): String = value.name

    @TypeConverter
    fun stringToStage(value: String): WorkspaceMutationStage = enumValueOrFail(value, "journal stage")

    @TypeConverter
    fun typeToString(value: WorkspaceMutationType): String = value.name

    @TypeConverter
    fun stringToType(value: String): WorkspaceMutationType = enumValueOrFail(value, "journal operation")

    private inline fun <reified T : Enum<T>> enumValueOrFail(value: String, label: String): T = try {
        enumValueOf(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("未知 $label，拒绝恢复工作区变更", error)
    }
}

@Entity(
    tableName = "workspace_mutations",
    indices = [Index("workspace_root_uuid"), Index("state")],
)
@TypeConverters(WorkspaceMutationConverters::class)
data class WorkspaceMutationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "workspace_root_uuid")
    val workspaceRootUuid: String,
    @ColumnInfo(name = "operation_type")
    val operationType: WorkspaceMutationType,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int = WorkspaceMutationPayloadCodec.CURRENT_VERSION,
    val payload: String,
    @ColumnInfo(name = "payload_digest")
    val payloadDigest: String,
    val state: WorkspaceMutationStage = WorkspaceMutationStage.PREPARED,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Serializable
data class WorkspaceMutationPayload(
    val sourceRelativePath: String,
    val targetRelativePath: String? = null,
    val databaseTargetUuid: String? = null,
    val expectedSha256: String? = null,
)

enum class WorkspaceMutationPayloadErrorCode {
    MALFORMED_PAYLOAD,
    UNSUPPORTED_VERSION,
}

data class WorkspaceMutationPayloadError(
    val code: WorkspaceMutationPayloadErrorCode,
    val message: String,
)

sealed interface WorkspaceMutationPayloadResult {
    data class Valid(val payload: WorkspaceMutationPayload) : WorkspaceMutationPayloadResult
    data class Invalid(val error: WorkspaceMutationPayloadError) : WorkspaceMutationPayloadResult
}

object WorkspaceMutationPayloadCodec {
    const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    fun encode(payload: WorkspaceMutationPayload): String = json.encodeToString(payload)

    fun decode(version: Int, raw: String): WorkspaceMutationPayloadResult {
        if (version != CURRENT_VERSION) {
            return WorkspaceMutationPayloadResult.Invalid(
                WorkspaceMutationPayloadError(
                    WorkspaceMutationPayloadErrorCode.UNSUPPORTED_VERSION,
                    "不支持的工作区 journal payload 版本",
                ),
            )
        }
        return try {
            WorkspaceMutationPayloadResult.Valid(json.decodeFromString(raw))
        } catch (_: SerializationException) {
            malformed()
        } catch (_: IllegalArgumentException) {
            malformed()
        }
    }

    private fun malformed() = WorkspaceMutationPayloadResult.Invalid(
        WorkspaceMutationPayloadError(
            WorkspaceMutationPayloadErrorCode.MALFORMED_PAYLOAD,
            "工作区 journal payload 无效",
        ),
    )
}
