package com.promenar.nexara.data.local.db.dao

import androidx.room.*
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import kotlinx.coroutines.flow.Flow

data class McpDiscoveryRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "server_type") val serverType: String,
    @ColumnInfo(name = "remote_tool_name") val remoteToolName: String,
    val description: String,
    @ColumnInfo(name = "input_schema_json") val inputSchemaJson: String,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)

@Dao
interface SkillDao {
    // Custom Skills
    @Query("SELECT * FROM custom_skills ORDER BY createdAt DESC")
    fun getAllCustomSkills(): Flow<List<CustomSkillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomSkill(skill: CustomSkillEntity)

    @Delete
    suspend fun deleteCustomSkill(skill: CustomSkillEntity)

    @Query("UPDATE custom_skills SET enabled = :enabled WHERE id = :id")
    suspend fun updateCustomSkillEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM custom_skills WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getAllEnabledCustomSkills(): List<CustomSkillEntity>

    @Query("SELECT * FROM custom_skills WHERE name = :name AND enabled = 1 LIMIT 1")
    suspend fun getEnabledCustomSkillByName(name: String): CustomSkillEntity?

    // MCP Servers
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt DESC")
    fun getAllMcpServers(): Flow<List<McpServerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcpServer(server: McpServerEntity)

    @Delete
    suspend fun deleteMcpServer(server: McpServerEntity)

    @Query("UPDATE mcp_servers SET enabled = :enabled WHERE id = :id")
    suspend fun updateMcpServerEnabled(id: String, enabled: Boolean)

    @Query("UPDATE mcp_servers SET isDefault = :isDefault WHERE id = :id")
    suspend fun updateMcpServerDefault(id: String, isDefault: Boolean)

    @Query("SELECT * FROM mcp_servers WHERE id = :id LIMIT 1")
    suspend fun getMcpServer(id: String): McpServerEntity?

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getAllEnabledMcpServers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_tool_snapshots ORDER BY server_id, remote_tool_name")
    suspend fun getAllMcpToolSnapshots(): List<McpToolSnapshotEntity>

    @Query(
        """SELECT servers.id AS server_id,
                  servers.url AS server_url,
                  servers.type AS server_type,
                  snapshots.remote_tool_name AS remote_tool_name,
                  snapshots.description AS description,
                  snapshots.input_schema_json AS input_schema_json,
                  snapshots.synced_at AS synced_at
           FROM mcp_servers AS servers
           INNER JOIN mcp_tool_snapshots AS snapshots ON snapshots.server_id = servers.id
           WHERE servers.enabled = 1
           ORDER BY servers.id, snapshots.remote_tool_name""",
    )
    fun observeMcpDiscoveryRows(): Flow<List<McpDiscoveryRow>>

    @Query("SELECT * FROM mcp_tool_snapshots WHERE server_id = :serverId ORDER BY remote_tool_name")
    suspend fun getMcpToolSnapshots(serverId: String): List<McpToolSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMcpToolSnapshots(snapshots: List<McpToolSnapshotEntity>)

    @Query("DELETE FROM mcp_tool_snapshots WHERE server_id = :serverId")
    suspend fun clearMcpToolSnapshots(serverId: String)

    @Transaction
    suspend fun replaceMcpToolSnapshotsIfServerUnchanged(
        serverId: String,
        expectedUrl: String,
        expectedType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ): Boolean {
        val current = getMcpServer(serverId) ?: return false
        if (!current.enabled || current.url != expectedUrl || current.type != expectedType ||
            !current.type.equals("http", ignoreCase = true)
        ) return false
        if (snapshots.any { it.serverId != serverId } ||
            snapshots.map { it.remoteToolName }.distinct().size != snapshots.size
        ) return false
        clearMcpToolSnapshots(serverId)
        if (snapshots.isNotEmpty()) insertMcpToolSnapshots(snapshots)
        return true
    }

    @Transaction
    suspend fun updateMcpServerEnabledAndInvalidate(id: String, enabled: Boolean) {
        updateMcpServerEnabled(id, enabled)
        if (!enabled) clearMcpToolSnapshots(id)
    }
}
