package com.promenar.infinity.data.secret

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.AskUserLedgerRepository
import com.promenar.infinity.domain.secret.*
import com.promenar.infinity.ui.chat.manager.AskUserTool
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.skills.RequestSecretSkill
import com.promenar.infinity.ui.chat.manager.skills.SecretRequestProtocol
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class SecretInputLedgerRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var ledger: SecretInputLedgerRepository
    private var now = 100L
    private val writes = mutableListOf<ByteArray>()
    private val destinations = object : SecretDestinationWriter {
        override suspend fun describe(sessionId: String, destination: SecretDestination) = when (destination) {
            is SecretDestination.SshHost -> throw SecretIoFailure("SECRET_SSH_HOST_NOT_FOUND")
            is SecretDestination.ProviderApiKey -> throw SecretIoFailure("SECRET_PROVIDER_NOT_FOUND")
            is SecretDestination.JobInput -> throw SecretIoFailure("SECRET_JOB_NOT_FOUND")
            else -> SecretDestinationInfo("本会话环境变量", false, false)
        }
        override suspend fun write(sessionId: String, destination: SecretDestination, value: ByteArray, username: String?) {
            writes += value.copyOf()
        }
    }
    private val call = ToolCall("secret", "request_secret", """{"purpose":"登录","kind":"token","destination":{"type":"session_env","name":"TEST_TOKEN"}}""")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = SecretInputLedgerRepository(db, destinations, clock = { now })
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "a", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(Message(id = "u", role = MessageRole.USER, content = "处理任务", createdAt = 1).toEntity("s"))
        db.messageDao().insert(Message(id = "m", role = MessageRole.ASSISTANT, content = "", parentMessageId = "u", createdAt = 2).toEntity("s"))
    }
    @After fun cleanup() { db.close(); writes.forEach { it.fill(0) } }
    private suspend fun create(calls: List<ToolCall> = listOf(call)) = ledger.createBatch("s", "m", calls, RequestSecretSkill().toProtocolTool())

    @Test fun `提交只持久化状态并复用提问续跑入口且清零输入`() = runBlocking {
        val pending = create().pending!!
        val value = "synthetic-credential-for-test".toCharArray()
        assertThat(ledger.submit(pending, value, null)).isInstanceOf(SecretInputOutcome.Saved::class.java)
        assertThat(value.all { it == '\u0000' }).isTrue()
        assertThat(writes.single().decodeToString()).isEqualTo("synthetic-credential-for-test")
        val resume = AskUserLedgerRepository(db).resumption("s")!!
        assertThat(resume.result.name).isEqualTo("request_secret")
        assertThat(resume.result.content).contains("saved")
        assertThat(AskUserLedgerRepository(db).consumeResumption("s", resume.assistant.id)).isTrue()
        db.openHelper.readableDatabase.query("SELECT content, tool_calls FROM messages").use { cursor ->
            while (cursor.moveToNext()) for (i in 0 until cursor.columnCount) {
                assertThat(cursor.getString(i).orEmpty()).doesNotContain("synthetic-credential-for-test")
            }
        }
        db.openHelper.readableDatabase.query("SELECT * FROM tool_execution_ledger").use { cursor ->
            while (cursor.moveToNext()) for (i in 0 until cursor.columnCount) {
                assertThat(cursor.getString(i).orEmpty()).doesNotContain("synthetic-credential-for-test")
            }
        }
    }

    @Test fun `过期引用不写入并清零输入`() = runBlocking {
        val pending = create().pending!!
        assertThat(ledger.cancel(pending)).isEqualTo(SecretInputOutcome.Cancelled)
        val value = "synthetic".toCharArray()
        assertThat(ledger.submit(pending, value, null)).isNull()
        assertThat(value.all { it == '\u0000' }).isTrue()
        assertThat(writes).isEmpty()
    }

    @Test fun `重建账本保留待输入并在期限后创建过期结果`() = runBlocking {
        val pending = create().pending!!
        assertThat(SecretInputLedgerRepository(db, destinations, clock = { now }).pending("s")).isEqualTo(pending)
        now = pending.expiresAtMillis
        ledger.expirePending()
        assertThat(ledger.pending("s")).isNull()
        assertThat(AskUserLedgerRepository(db).resumption("s")!!.result.content).contains("expired")
        assertThat(writes).isEmpty()
    }

    @Test fun `不存在的去向立即失败且无等待状态`() = runBlocking {
        listOf(
            """{"type":"ssh_host","hostId":"missing","field":"password"}""" to "SECRET_SSH_HOST_NOT_FOUND",
            """{"type":"provider_api_key","providerId":"missing"}""" to "SECRET_PROVIDER_NOT_FOUND",
            """{"type":"job_input","jobId":"missing"}""" to "SECRET_JOB_NOT_FOUND",
        ).forEachIndexed { index, (destination, error) ->
            val invalid = call.copy(id = "missing-$index", arguments = """{"purpose":"登录","destination":$destination}""")
            val result = create(listOf(invalid))
            assertThat(result.pending).isNull()
            assertThat(result.messages.single().content).contains(error)
        }
        assertThat(db.sessionDao().getById("s")!!.loopStatus).isNotEqualTo("waiting_for_input")
    }

    @Test fun `空值与超限失败保留等待且可重试`() = runBlocking {
        val pending = create().pending!!
        assertThat(ledger.submit(pending, CharArray(0), null)).isInstanceOf(SecretInputOutcome.Failed::class.java)
        val large = CharArray(SecretIoLimits.MAX_SECRET_BYTES + 1) { 'x' }
        assertThat(ledger.submit(pending, large, null)).isInstanceOf(SecretInputOutcome.Failed::class.java)
        assertThat(large.all { it == '\u0000' }).isTrue()
        assertThat(ledger.pending("s")).isEqualTo(pending)
        assertThat(writes).isEmpty()
    }

    @Test fun `同轮多个保密请求只接受第一个其它调用失败`() = runBlocking {
        val result = create(listOf(call, call.copy(id = "second"), ToolCall("other", "shell_exec", "{}")))
        assertThat(result.pending!!.toolCallId).isEqualTo("secret")
        assertThat(result.messages).hasSize(2)
        assertThat(writes).isEmpty()
    }

    @Test fun `ask_user批次优先且拒绝保密请求`() = runBlocking {
        val ask = ToolCall("ask", "ask_user", """{"questions":[{"header":"方案","question":"选择","options":[{"label":"甲"},{"label":"乙"}]}]}""")
        val result = AskUserLedgerRepository(db).createBatch("s", "m", listOf(call, ask), AskUserTool().toProtocolTool())
        assertThat(result.pending!!.toolCallId).isEqualTo("ask")
        assertThat(result.messages.single().content).contains("ASK_USER_BATCH_NOT_EXECUTED")
        assertThat(ledger.pending("s")).isNull()
    }

    @Test fun `参数规范严格拒绝未知字段空目的及非法槽位`() {
        assertThat(SecretRequestProtocol.decode(call.arguments)).isNotNull()
        listOf("{}", call.arguments.replace("登录", ""), call.arguments.replace("TEST_TOKEN", "bad-name"),
            call.arguments.replace("\"purpose\"", "\"unexpected\"")).forEach {
            assertThat(runCatching { SecretRequestProtocol.decode(it) }.isFailure).isTrue()
        }
    }

    @Test fun `删除会话后待输入消失且过期引用不会写入`() = runBlocking {
        val pending = create().pending!!
        AskUserLedgerRepository(db).cancel("s")
        assertThat(db.messageDao().getBySession("s").last { it.role == "tool" }.content).contains("cancelled")
        db.sessionDao().deleteById("s")
        assertThat(ledger.pending("s")).isNull()
        val value = "synthetic-deleted".toCharArray()
        assertThat(ledger.submit(pending, value, null)).isNull()
        assertThat(value.all { it == '\u0000' }).isTrue()
        assertThat(writes).isEmpty()
    }
}
