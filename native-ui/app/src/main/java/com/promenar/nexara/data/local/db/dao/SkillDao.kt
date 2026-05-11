package com.promenar.nexara.data.local.db.dao

import androidx.room.*
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import kotlinx.coroutines.flow.Flow

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
}
