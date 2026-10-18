package com.promenar.nexara.data.ssh

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.generation.DefaultSessionToolResolver
import com.promenar.nexara.data.generation.resolveSessionToolTarget
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.security.SshCredential
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import com.promenar.nexara.ui.chat.manager.skills.sshTools
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 配合仅回环的宿主 MINA 夹具，通过 adb reverse 连接；不读取任何真实主机配置。 */
class SshDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as NexaraApplication
    private val runtime get() = app.sshRuntime
    private val store get() = app.sshHostStore
    private val owner = "agent:device-${UUID.randomUUID()}"
    private lateinit var host: SshHost
    private lateinit var password: String
    private lateinit var fingerprint: String
    private var echoPort = 2223

    @Before fun configureHost() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("缺少 sshPort、sshFingerprint 或 sshPassword；需启动宿主夹具并由设备脚本传入，当前 SSH 用例跳过。",
            listOf("sshPort", "sshFingerprint", "sshPassword").all { !args.getString(it).isNullOrBlank() })
        val port = args.getString("sshPort")!!.toInt()
        password = args.getString("sshPassword")!!
        fingerprint = args.getString("sshFingerprint")!!
        check(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(fingerprint)) { "SSH 测试指纹格式无效" }
        echoPort = args.getString("sshEchoPort", "2223").toInt()
        host = SshHost(alias = "device-${UUID.randomUUID()}", hostname = "127.0.0.1", port = port,
            username = args.getString("sshUsername", "nexara-test"), authType = SshAuthType.PASSWORD)
        SshCredential(password = password.toByteArray()).use { store.save(host, it) }
    }

    @After fun removeHost() = runBlocking {
        if (::host.isInitialized) {
            try {
                runtime.stopForward(host.alias)
                runtime.closeOwner(owner)
                runtime.closeOwner("ui")
            } finally { store.delete(host.alias) }
        }
    }

    @Test fun firstTrustMatchesSessionFingerprintAndWrongPinIsRejected() = runBlocking {
        assertThat(failure { runtime.exec(owner, host.alias, "device-exec") }).isEqualTo("HOST_KEY_UNTRUSTED")
        val observed = runtime.probe(host.alias)
        assertThat(observed.fingerprint).isEqualTo(fingerprint)
        assertThat(store.trust(host.alias, null, observed, host)).isTrue()
        assertThat(runtime.exec(owner, host.alias, "device-exec").exitCode).isEqualTo(7)
        val trusted = store.find(host.alias)!!
        val wrongKey = SshHostKey(observed.type, "SHA256:" + "A".repeat(43))
        assertThat(store.trust(host.alias, trusted.trustedFingerprint, wrongKey, trusted)).isTrue()
        assertThat(store.find(host.alias)?.trustedFingerprint).isEqualTo(wrongKey.fingerprint)
        assertThat(failure { runtime.exec(owner, host.alias, "device-exec") }).isEqualTo("HOST_KEY_CHANGED")
        trust()
        assertThat(runtime.exec(owner, host.alias, "device-exec").exitCode).isEqualTo(7)
    }

    @Test fun execReturnsSeparateStreamsAndExitCode() = runBlocking {
        trust()
        val output = runtime.exec(owner, host.alias, "device-exec")
        assertThat(output.stdout).isEqualTo("stdout-ok\n")
        assertThat(output.stderr).isEqualTo("stderr-ok\n")
        assertThat(output.exitCode).isEqualTo(7)
        assertThat(output.timedOut).isFalse()
        assertThat(output.truncated).isFalse()
    }

    @Test fun execTimeoutInterruptsRemoteCommandAndConnectionRemainsUsable() = runBlocking {
        trust()
        val started = SystemClock.elapsedRealtime()
        val output = runtime.exec(owner, host.alias, "device-sleep", timeoutSeconds = 1)
        assertThat(output.timedOut).isTrue()
        assertThat(output.exitCode).isEqualTo(-1)
        assertThat(SystemClock.elapsedRealtime() - started).isLessThan(10_000L)
        await { runtime.exec(owner, host.alias, "device-active").stdout.trim() == "0" }
        assertThat(runtime.exec(owner, host.alias, "device-exec").stdout).isEqualTo("stdout-ok\n")
    }

    @Test fun sftpToolsListReadConditionalWriteAndRejectVersionConflict() = runBlocking {
        trust()
        val path = "/device-${UUID.randomUUID()}.txt"
        val context = object : SkillExecutionContext {
            override val sessionId = owner.removePrefix("agent:")
            override val agentId = "device-agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "device-workspace"
        }
        val tools = sshTools(runtime).associateBy { it.name }
        suspend fun call(name: String, args: JsonObject) = tools.getValue(name).execute(args, context)
        fun args(content: String, hash: String? = null) = buildJsonObject {
            put("host", host.alias); put("path", path); put("content", content)
            hash?.let { put("expected_sha256", it) }
        }
        val initial = call("sftp_write", args("设备初始😀"))
        assertWithMessage(initial.content).that(initial.status).isEqualTo("success")
        val hash = Json.parseToJsonElement(initial.content).jsonObject.getValue("sha256").jsonPrimitive.content
        val written = call("sftp_write", args("条件写入成功", hash))
        assertWithMessage(written.content).that(written.status).isEqualTo("success")
        val conflict = call("sftp_write", args("不得覆盖", hash))
        assertThat(conflict.status).isEqualTo("error")
        assertThat(conflict.content).contains("SHA256_CONFLICT")
        val read = call("sftp_read", buildJsonObject { put("host", host.alias); put("path", path) })
        assertWithMessage(read.content).that(read.status).isEqualTo("success")
        assertThat(Json.parseToJsonElement(read.content).jsonObject.getValue("content").jsonPrimitive.content).isEqualTo("条件写入成功")
        val listed = call("sftp_list", buildJsonObject { put("host", host.alias); put("path", "/") })
        assertWithMessage(listed.content).that(listed.status).isEqualTo("success")
        val entries = Json.parseToJsonElement(listed.content).jsonObject.getValue("entries").jsonArray
        assertThat(entries.map { it.jsonObject.getValue("name").jsonPrimitive.content }).contains(path.removePrefix("/"))
        assertThat(entries.any { ".nexara-" in it.jsonObject.getValue("name").jsonPrimitive.content }).isFalse()
    }

    @Test fun ptyOpenSendReadResizeAndClose() = runBlocking {
        trust()
        val session = runtime.openSession(owner, host.alias)
        val text = StringBuilder()
        await { text.append(runtime.readSession(owner, session.sessionId, 100).output); "pty-ready:xterm-256color" in text }
        runtime.resizeSession(owner, session.sessionId, 100, 30)
        runtime.sendSession(owner, session.sessionId, "设备 PTY 往返\n")
        await { text.append(runtime.readSession(owner, session.sessionId, 100).output); "echo:设备 PTY 往返" in text }
        runtime.closeSession(owner, session.sessionId)
        assertThat(runtime.sessionHost(owner, session.sessionId)).isNull()
        assertThat(failure { runtime.readSession(owner, session.sessionId) }).isEqualTo("SESSION_NOT_FOUND")
    }

    @Test fun localForwardBindsOnlyLoopbackAndReachesHostEchoPort() = runBlocking {
        trust()
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        store.save(store.find(host.alias)!!.copy(localForwardPort = localPort, forwardPort = echoPort))
        val bound = runtime.startForward(host.alias)
        try {
            assertThat(bound).isEqualTo(localPort)
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", bound), 5000)
                socket.soTimeout = 5000
                val bytes = "设备转发回显".toByteArray()
                socket.getOutputStream().apply { write(bytes); flush() }
                val response = ByteArray(bytes.size)
                java.io.DataInputStream(socket.getInputStream()).readFully(response)
                assertThat(response.toString(Charsets.UTF_8)).isEqualTo("设备转发回显")
            }
            val otherAddresses = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            assertThat(otherAddresses).isNotEmpty()
            otherAddresses.forEach { address ->
                // 若转发监听通配地址，这个 bind 会以 EADDRINUSE 失败。
                ServerSocket().use { it.bind(InetSocketAddress(address, bound)) }
                val reachable = runCatching { Socket().use { it.connect(InetSocketAddress(address, bound), 500) } }.isSuccess
                assertThat(reachable).isFalse()
            }
        } finally { runtime.stopForward(host.alias) }
        assertThat(runtime.forwardStates.value).doesNotContainKey(host.alias)
    }

    @Test fun passwordIsMaskedInBothRemoteOutputStreams() = runBlocking {
        trust()
        val output = runtime.exec(owner, host.alias, "device-secret")
        // 断言只输出布尔值，失败报告也不能把原始凭据写出来。
        assertThat(output.stdout.contains(password) || output.stderr.contains(password)).isFalse()
        assertThat(output.stdout).contains("secret:")
        assertThat(output.stderr).contains("secret:")
        assertThat(output.stdout).contains("█")
        assertThat(output.stderr).contains("█")
    }

    @Test fun foregroundNotificationExistsUntilEveryPersistentSessionCloses() = runBlocking {
        trust()
        val manager = app.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled()) { "请通过设备脚本授予测试包通知权限后运行 SSH 通知验收" }
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        store.save(store.find(host.alias)!!.copy(localForwardPort = localPort, forwardPort = echoPort))
        val terminal = runtime.openSession("ui", host.alias)
        runtime.startForward(host.alias)
        fun notification() = manager.activeNotifications.singleOrNull { it.notification.channelId == "ssh_connections" }
        await { notification()?.notification?.flags?.and(Notification.FLAG_FOREGROUND_SERVICE) != 0 && notification() != null }
        runtime.closeSession("ui", terminal.sessionId)
        assertThat(runtime.hasPersistentResources).isTrue()
        assertThat(notification()).isNotNull()
        runtime.stopForward(host.alias)
        await { notification() == null }
        assertThat(runtime.hasPersistentResources).isFalse()
    }

    @Test fun agentToolEntryRequiresExactApprovalBeforeSshExec() = runBlocking {
        trust()
        val database = Room.inMemoryDatabaseBuilder(app, NexaraDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessionId = owner.removePrefix("agent:")
        try {
            database.agentDao().insert(AgentEntity("device-agent", "设备测试", createdAt = 1))
            val sessions = SessionRepository(database.sessionDao(), database.messageDao())
            val messages = MessageRepository(database.messageDao())
            val chat = ChatStore()
            val manager = MessageManager(chat, messages, sessions, scope)
            SessionManager(chat, sessions).addSession(Session(id = sessionId, agentId = "device-agent",
                options = SessionOptions(toolsEnabled = true), workspaceRootUuid = "device-workspace"))
            val tools = sshTools(runtime)
            val registry = object : SkillRegistry {
                override fun getSkill(name: String) = tools.singleOrNull { it.name == name }
                override fun getAllSkills() = tools
                override fun getAllTools(allowedIds: List<String>?) = tools.filter { allowedIds == null || it.id in allowedIds }.map { it.toProtocolTool() }
            }
            val preferences = app.getSharedPreferences("device-ssh-${UUID.randomUUID()}", 0)
            val resolver = DefaultSessionToolResolver(preferences, registry)
            val ledger = ToolExecutionLedgerRepository(database)
            val executor = ToolExecutor(chat, manager, registry, resolver, ledger = ledger)
            val call = ToolCall("device-exec-call", "ssh_exec", buildJsonObject { put("host", host.alias); put("command", "device-exec") }.toString())
            val assistant = Message(id = "device-assistant", role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call))
            manager.addMessage(sessionId, assistant)
            val prepared = resolver.resolve(chat.getSession(sessionId)!!)
            executor.executeTools(sessionId, assistant.id, listOf(call), allowedToolCallIds = emptySet(), preparedTools = prepared)
            val key = ToolExecutionKey(sessionId, assistant.id, call.id)
            assertWithMessage("工具消息：%s", messages.getBySession(sessionId).filter { it.role == MessageRole.TOOL }.map { it.content })
                .that(ledger.state(key)).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
            assertThat(messages.getBySession(sessionId).none { it.role == MessageRole.TOOL }).isTrue()
            val identity = ledger.invocationIdentity(key)!!
            val tool = prepared.single { it.function.name == call.name }
            val target = resolveSessionToolTarget(chat.getSession(sessionId)!!, tool, call.arguments, null,
                sshHostStore = store, sshSessionHost = runtime::sessionHost)
            assertThat(target).isNotNull()
            val tuple = Json.parseToJsonElement(target!!).jsonArray
            assertThat(tuple.take(3).map { it.jsonPrimitive.content }).containsExactly("process", host.alias, "device-exec").inOrder()
            assertThat(tuple.last().jsonObject.getValue("sshHostId").jsonPrimitive.content).isEqualTo(host.id)
            assertThat(tuple.last().jsonObject.getValue("sshIdentity").jsonPrimitive.content).isEqualTo(store.find(host.alias)!!.executionIdentity().digest)
            val request = ToolApprovalRequestFactory.create(assistant.id,
                listOf(ApprovalCallCandidate(call, tool.risk, identity, executionTarget = target)))
            assertThat(ledger.createToolApproval(sessionId, assistant.id, listOf(call), setOf(call.id), request)).isEqualTo(ToolApprovalCreation.CREATED)
            assertThat(ledger.decideExactToolApproval(sessionId, request, true)).isInstanceOf(ExactToolApprovalDecision.Decided::class.java)
            chat.upsertSession(sessions.getById(sessionId)!!)
            executor.executeTools(sessionId, assistant.id, listOf(call), allowedToolCallIds = setOf(call.id), preparedTools = prepared)
            // 退出码非 0 按统一命令结果格式记为失败，模型收到带根因字段的失败摘要。
            assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
            val result = messages.getBySession(sessionId).single { it.role == MessageRole.TOOL && it.toolCallId == call.id }
            assertThat(result.content).startsWith("工具执行失败：")
            val value = Json.parseToJsonElement(result.content.removePrefix("工具执行失败：")).jsonObject
            assertThat(value.getValue("stdout").jsonPrimitive.content).isEqualTo("stdout-ok\n")
            assertThat(value.getValue("stderr").jsonPrimitive.content).isEqualTo("stderr-ok\n")
            assertThat(value.getValue("exit_code").jsonPrimitive.int).isEqualTo(7)
            assertThat(ledger.completeExactToolApproval(sessionId, request)).isInstanceOf(ExactToolApprovalCompletion.Completed::class.java)
            assertThat(sessions.getById(sessionId)!!.approvalRequest).isNull()
        } finally { scope.coroutineContext.job.cancelAndJoin(); database.close() }
    }

    private suspend fun trust() {
        val current = store.find(host.alias)!!
        val key = runtime.probe(host.alias)
        assertThat(key.fingerprint).isEqualTo(fingerprint)
        assertThat(store.trust(host.alias, current.trustedFingerprint, key, current)).isTrue()
    }
    private suspend fun failure(block: suspend () -> Any?): String? = try { block(); null } catch (error: SshFailure) { error.code }
    private suspend fun await(predicate: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; delay(50) }
        check(predicate()) { "SSH 设备状态未在时限内到达预期值" }
    }
}
