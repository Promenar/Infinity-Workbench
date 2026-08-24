package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.nexara.data.local.db.dao.McpDiscoveryRow
import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.remote.mcp.McpInputSchemaPolicy
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.ui.chat.manager.skills.McpSkill
import io.ktor.client.HttpClient
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

sealed interface McpSyncResult {
    data class Success(val toolCount: Int) : McpSyncResult
    data class Failure(val code: String) : McpSyncResult
}

class McpSkillRegistry(
    private val repository: SkillRepository,
    private val httpClient: HttpClient,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : SkillRegistry {
    private data class PublishedSkill(
        val skill: McpSkill,
        val serverUrl: String,
        val serverType: String,
    )

    private val published = AtomicReference<List<PublishedSkill>>(emptyList())
    private val syncGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val syncCommitLocks = ConcurrentHashMap<String, Mutex>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeMcpDiscoveryRows()
                .catch { emit(emptyList()) }
                .collectLatest { rows ->
                    published.set(buildPublishedSkills(rows))
                }
        }
    }

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
        val syncGeneration = syncGenerations.computeIfAbsent(serverId) { AtomicLong() }
            .incrementAndGet()
        val configRevision = repository.currentMcpConfigRevision(serverId)
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
            syncCommitLocks.computeIfAbsent(serverId) { Mutex() }.withLock {
                if (syncGenerations[serverId]?.get() != syncGeneration) {
                    return@withLock McpSyncResult.Failure("MCP_SYNC_SUPERSEDED")
                }
                if (!repository.replaceMcpToolSnapshotsAtRevision(
                        serverId = server.id,
                        expectedUrl = server.url,
                        expectedType = server.type,
                        snapshots = snapshots,
                        expectedConfigRevision = configRevision,
                    )
                ) {
                    McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC")
                } else {
                    replacePublishedServer(server.id, server.url, server.type, snapshots)
                    McpSyncResult.Success(snapshots.size)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            McpSyncResult.Failure(error.message?.takeIf { it.startsWith("MCP_") } ?: "MCP_SYNC_FAILED")
        }
    }

    private fun currentSkills(): List<SkillDefinition> = published.get()
        .filter { record ->
            repository.isMcpSnapshotCurrent(
                record.skill.mcpServerId ?: return@filter false,
                record.serverUrl,
                record.serverType,
            )
        }
        .map { it.skill }

    private suspend fun buildPublishedSkills(rows: List<McpDiscoveryRow>): List<PublishedSkill> =
        rows.groupBy { it.serverId }
            .toSortedMap()
            .flatMap { (_, serverRows) ->
                coroutineContext.ensureActive()
                runCatching { serverRows.map(::publishedSkill) }.getOrElse { emptyList() }
            }

    private fun publishedSkill(row: McpDiscoveryRow): PublishedSkill {
        if (!isSupported(row.serverType, row.serverUrl)) {
            throw IllegalArgumentException("MCP_LEGACY_TRANSPORT_UNSUPPORTED")
        }
        val schema = McpInputSchemaPolicy.parse(row.inputSchemaJson)
        return PublishedSkill(
            skill = McpSkill(
                id = runtimeToolId(row.serverId, row.remoteToolName),
                name = providerAlias(row.serverId, row.remoteToolName),
                remoteToolName = row.remoteToolName,
                description = row.description,
                parametersSchema = schema.schema.toString(),
                headerParameters = schema.headerParameters,
                mcpClient = McpClient(httpClient, row.serverUrl),
                mcpServerId = row.serverId,
            ),
            serverUrl = row.serverUrl,
            serverType = row.serverType,
        )
    }

    private fun replacePublishedServer(
        serverId: String,
        serverUrl: String,
        serverType: String,
        snapshots: List<McpToolSnapshotEntity>,
    ) {
        val replacement = runCatching {
            snapshots.map { snapshot ->
                publishedSkill(
                    McpDiscoveryRow(
                        serverId = serverId,
                        serverUrl = serverUrl,
                        serverType = serverType,
                        remoteToolName = snapshot.remoteToolName,
                        description = snapshot.description,
                        inputSchemaJson = snapshot.inputSchemaJson,
                        syncedAt = snapshot.syncedAt,
                    ),
                )
            }
        }.getOrElse { emptyList() }
        published.updateAndGet { existing ->
            (existing.filterNot { it.skill.mcpServerId == serverId } + replacement)
                .sortedWith(compareBy({ it.skill.mcpServerId }, { it.skill.runtimeToolId }))
        }
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
