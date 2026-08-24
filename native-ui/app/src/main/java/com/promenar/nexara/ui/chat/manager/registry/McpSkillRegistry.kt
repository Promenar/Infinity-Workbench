package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.remote.mcp.McpInputSchemaPolicy
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.ui.chat.manager.skills.McpSkill
import io.ktor.client.HttpClient
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

sealed interface McpSyncResult {
    data class Success(val toolCount: Int) : McpSyncResult
    data class Failure(val code: String) : McpSyncResult
}

class McpSkillRegistry(
    private val repository: SkillRepository,
    private val httpClient: HttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) : SkillRegistry {
    override fun getSkill(name: String): SkillDefinition? = currentSkills()
        .singleOrNull { it.name == name }

    override fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? = currentSkills()
        .singleOrNull { it.runtimeToolId == runtimeToolId }

    override fun getAllSkills(): List<SkillDefinition> = currentSkills()

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        val skills = currentSkills()
        val filtered = if (allowedIds == null) skills else skills.filter { skill ->
            skill.mcpServerId in allowedIds || skill.runtimeToolId in allowedIds
        }
        return filtered.map(SkillDefinition::toProtocolTool)
    }

    suspend fun syncServer(serverId: String): McpSyncResult {
        val server = repository.getMcpServer(serverId)
            ?: return McpSyncResult.Failure("MCP_SERVER_NOT_FOUND")
        if (!isSupported(server.type, server.url)) {
            return McpSyncResult.Failure("MCP_LEGACY_TRANSPORT_UNSUPPORTED")
        }
        if (!server.enabled) return McpSyncResult.Failure("MCP_SERVER_DISABLED")
        return try {
            val tools = McpClient(httpClient, server.url).listTools()
            val timestamp = now()
            val snapshots = tools.map { tool ->
                McpToolSnapshotEntity(
                    serverId = server.id,
                    remoteToolName = tool.name,
                    description = tool.description,
                    inputSchemaJson = tool.inputSchema,
                    syncedAt = timestamp,
                )
            }
            if (!repository.replaceMcpToolSnapshotsIfServerUnchanged(
                    serverId = server.id,
                    expectedUrl = server.url,
                    expectedType = server.type,
                    snapshots = snapshots,
                )
            ) {
                McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC")
            } else {
                McpSyncResult.Success(snapshots.size)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            McpSyncResult.Failure(error.message?.takeIf { it.startsWith("MCP_") } ?: "MCP_SYNC_FAILED")
        }
    }

    private fun currentSkills(): List<SkillDefinition> = try {
        runBlocking(Dispatchers.IO) {
            val servers = repository.getAllEnabledMcpServers()
                .filter { isSupported(it.type, it.url) }
                .associateBy { it.id }
            repository.getAllMcpToolSnapshots()
                .filter { it.serverId in servers }
                .groupBy { it.serverId }
                .toSortedMap()
                .flatMap { (serverId, snapshots) ->
                    val server = servers.getValue(serverId)
                    val parsed = runCatching {
                        snapshots.map { snapshot ->
                            val schema = McpInputSchemaPolicy.parse(snapshot.inputSchemaJson)
                            McpSkill(
                                id = runtimeToolId(serverId, snapshot.remoteToolName),
                                name = providerAlias(serverId, snapshot.remoteToolName),
                                remoteToolName = snapshot.remoteToolName,
                                description = snapshot.description,
                                parametersSchema = schema.schema.toString(),
                                headerParameters = schema.headerParameters,
                                mcpClient = McpClient(httpClient, server.url),
                                mcpServerId = serverId,
                            )
                        }
                    }
                    // 快照由同一批原子写入；任一行异常时整个 server 均不广告。
                    parsed.getOrElse { emptyList() }
                }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun isSupported(type: String, url: String): Boolean {
        if (!type.equals("http", ignoreCase = true)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    companion object {
        fun runtimeToolId(serverId: String, remoteToolName: String): String =
            "mcp:$serverId:$remoteToolName"

        fun providerAlias(serverId: String, remoteToolName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$remoteToolName".toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
            val safeName = remoteToolName.map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
            }.joinToString("").take(80).ifBlank { "tool" }
            return "mcp_${digest}_$safeName"
        }
    }
}
