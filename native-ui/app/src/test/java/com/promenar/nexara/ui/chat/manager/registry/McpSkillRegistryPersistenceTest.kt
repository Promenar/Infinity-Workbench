package com.promenar.nexara.ui.chat.manager.registry

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.nexara.data.repository.SkillRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteReadPacket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class McpSkillRegistryPersistenceTest {
    private lateinit var database: NexaraDatabase
    private lateinit var repository: SkillRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SkillRepository(database.skillDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `restart从Room恢复且同名跨server使用稳定不同身份`() = runTest {
        seedServer("a")
        seedServer("b")
        seedSnapshot("a", "search")
        seedSnapshot("b", "search")

        val first = registry(MockEngine { error("不应联网") }).getAllTools(null)
        val restarted = registry(MockEngine { error("不应联网") }).getAllTools(null)

        assertThat(first.map { it.runtimeToolId })
            .containsExactly("mcp:a:search", "mcp:b:search")
        assertThat(first.map { it.function.name }.distinct()).hasSize(2)
        assertThat(restarted.map { it.runtimeToolId }).containsExactlyElementsIn(first.map { it.runtimeToolId })
        assertThat(McpSkillRegistry.providerAlias("a", "same/path"))
            .isNotEqualTo(McpSkillRegistry.providerAlias("a", "same.path"))
        repository.updateMcpServerEnabled("a", false)
        assertThat(registry(MockEngine { error("不应联网") }).getAllTools(null).map { it.runtimeToolId })
            .containsExactly("mcp:b:search")
        assertThat(registry(MockEngine { error("不应联网") }).getSkillByRuntimeToolId("mcp:a:search"))
            .isNull()
    }

    @Test
    fun `empty成功原子清除且失败保留旧批`() = runTest {
        seedServer("a")
        seedSnapshot("a", "old")
        val emptyRegistry = registry(rpcEngine("""{"tools":[]}"""))

        assertThat(emptyRegistry.syncServer("a")).isEqualTo(McpSyncResult.Success(0))
        assertThat(repository.getMcpToolSnapshots("a")).isEmpty()

        seedSnapshot("a", "kept")
        val failed = registry(MockEngine { respond("bad", HttpStatusCode.BadGateway) }).syncServer("a")
        assertThat(failed).isInstanceOf(McpSyncResult.Failure::class.java)
        assertThat(repository.getMcpToolSnapshots("a").map { it.remoteToolName }).containsExactly("kept")
    }

    @Test
    fun `sync提交前server变化不会让late结果复活`() = runTest {
        seedServer("a")
        seedSnapshot("a", "old")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = registry(MockEngine { request ->
            entered.complete(Unit)
            release.await()
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"late","inputSchema":{"type":"object"}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        val sync = async { registry.syncServer("a") }
        entered.await()
        repository.insertMcpServer(
            McpServerEntity("a", "A", "https://changed.example.test", "http", createdAt = 1),
        )
        release.complete(Unit)

        assertThat(sync.await()).isEqualTo(McpSyncResult.Failure("MCP_SERVER_CHANGED_DURING_SYNC"))
        assertThat(repository.getMcpToolSnapshots("a")).isEmpty()
    }

    @Test
    fun `restart后的原始snapshot仍恢复x mcp header执行语义`() = runTest {
        seedServer("a")
        assertThat(repository.replaceMcpToolSnapshotsIfServerUnchanged(
            "a",
            "https://a.example.test",
            "http",
            listOf(
                McpToolSnapshotEntity(
                    "a",
                    "search",
                    "Search",
                    """{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":true}}}""",
                    1,
                ),
            ),
        )).isTrue()
        var header: String? = null
        val registry = registry(MockEngine { request ->
            header = request.headers["Mcp-Param-tenant"]
            val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
            respond(
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val skill = registry.getSkillByRuntimeToolId("mcp:a:search")!!

        val result = skill.execute(
            Json.parseToJsonElement("""{"tenant":"acme"}""").jsonObject,
            object : SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "root"
            },
        )

        assertThat(result.status).isEqualTo("success")
        assertThat(header).isEqualTo("acme")
    }

    @Test
    fun `历史cleartext与stdio配置可保留但永不sync广告执行`() = runTest {
        repository.insertMcpServer(
            McpServerEntity("http", "HTTP", "http://legacy.example.test", "http", createdAt = 1),
        )
        repository.insertMcpServer(
            McpServerEntity("stdio", "STDIO", "/usr/bin/server", "stdio", createdAt = 1),
        )
        database.skillDao().insertMcpToolSnapshots(
            listOf(
                McpToolSnapshotEntity("http", "legacy", "", """{"type":"object"}""", 1),
                McpToolSnapshotEntity("stdio", "legacy", "", """{"type":"object"}""", 1),
            ),
        )
        val registry = registry(MockEngine { error("旧配置不应联网") })

        assertThat(registry.getAllTools(null)).isEmpty()
        assertThat(registry.getSkillByRuntimeToolId("mcp:http:legacy")).isNull()
        assertThat(registry.syncServer("http"))
            .isEqualTo(McpSyncResult.Failure("MCP_LEGACY_TRANSPORT_UNSUPPORTED"))
        assertThat(registry.syncServer("stdio"))
            .isEqualTo(McpSyncResult.Failure("MCP_LEGACY_TRANSPORT_UNSUPPORTED"))
    }

    private suspend fun seedServer(id: String) = repository.insertMcpServer(
        McpServerEntity(id, id.uppercase(), "https://$id.example.test", "http", createdAt = 1),
    )

    private suspend fun seedSnapshot(serverId: String, name: String) {
        assertThat(repository.replaceMcpToolSnapshotsIfServerUnchanged(
            serverId,
            "https://$serverId.example.test",
            "http",
            listOf(McpToolSnapshotEntity(serverId, name, name, """{"type":"object"}""", 1)),
        )).isTrue()
    }

    private fun registry(engine: MockEngine) = McpSkillRegistry(repository, HttpClient(engine), now = { 2 })

    private fun rpcEngine(result: String) = MockEngine { request ->
        val id = Json.parseToJsonElement(request.body.toByteReadPacket().readText()).jsonObject["id"]
        respond(
            """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
}
