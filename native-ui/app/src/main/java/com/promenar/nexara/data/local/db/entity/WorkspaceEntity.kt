package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户登记的自选工作区目录。会话与 Agent 只保存 [id]；目录是否存在、权限是否仍有效在使用时校验，
 * 不可用时拒绝执行而不回退到会话私有工作区。
 *
 * [location] 的含义由 [kind] 决定：[KIND_PROJECT] 为 App 私有项目根下的目录名，不随安装路径变化；
 * [KIND_SHARED] 为共享存储卷上的绝对路径；[KIND_LINUX_HOME] 为 `/root#环境代次标识`，不包含宿主安装路径。
 */
@Entity(tableName = "workspaces", indices = [Index(value = ["kind", "location"], unique = true)])
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val location: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val KIND_PROJECT = "project"
        const val KIND_SHARED = "shared"
        const val KIND_LINUX_HOME = "linux_home"
        private const val MAX_SHARED_PATH_LENGTH = 4096
        private val PROJECT_NAME = Regex("[A-Za-z0-9._-]{1,128}")

        /** 只校验记录格式；真实目录的存在性、符号链接与卷归属由目录后端在打开时检查。 */
        fun isValidLocation(kind: String, location: String): Boolean = when (kind) {
            KIND_PROJECT -> PROJECT_NAME.matches(location) && location != "." && location != ".."
            KIND_LINUX_HOME -> Regex("/root#[0-9a-f]{64}").matches(location)
            KIND_SHARED -> location.length in 2..MAX_SHARED_PATH_LENGTH &&
                location.startsWith("/") && !location.endsWith("/") &&
                '\u0000' !in location && '\\' !in location &&
                location.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }
            else -> false
        }
    }
}
