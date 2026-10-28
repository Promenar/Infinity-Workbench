package com.promenar.infinity.data.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.promenar.infinity.domain.script.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 句柄只服务当前调用，消息内的会话、路径或能力名不能选择其它主进程资源。 */
class AndroidScriptProcess private constructor(private val context: Context) : ScriptProcess {
    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var current: Invocation? = null
    private val ready = CompletableDeferred<Unit>()
    private var isolatedUid: Int? = null
    override val alive get() = bound && remote?.binder?.isBinderAlive == true
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound) return
            remote = Messenger(binder)
            try { ScriptWire.send(remote!!, ScriptWire.HELLO, replyTo = messenger) }
            catch (_: Exception) { disconnected() }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private data class Invocation(
        val id: String,
        val capabilities: ScriptCapabilities,
        val callbacks: ScriptCapabilityDispatcher.Invocation,
        val result: CompletableDeferred<ScriptResult> = CompletableDeferred(),
        val count: AtomicInteger = AtomicInteger(),
        val outputMessages: AtomicInteger = AtomicInteger(),
    )

    override suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult {
        check(alive) { "隔离进程已断开" }
        val callbackScope = capabilityDispatcher.open(capabilities)
        val call = Invocation(UUID.randomUUID().toString(), capabilities, callbackScope)
        current = call
        return try {
            ScriptWire.send(remote!!, ScriptWire.EXECUTE, call.id, payload = Json.encodeToString(request), replyTo = messenger)
            call.result.await()
        } finally {
            current = null
            callbackScope.close()
        }
    }

    private fun handle(message: Message) {
        val payload = message.data.getString("payload").orEmpty()
        try {
            ScriptWire.checkPayload(payload)
            if (message.what == ScriptWire.READY && !ready.isCompleted) {
                isolatedUid = Json.parseToJsonElement(payload).jsonObject["uid"]!!.jsonPrimitive.int
                if (isolatedUid != message.sendingUid || isolatedUid == android.os.Process.myUid()) throw ScriptFailure("PROCESS_DIED", "服务没有运行在隔离 UID")
                ready.complete(Unit)
                return
            }
            val call = current ?: return
            if (message.sendingUid != isolatedUid || message.data.getString("invocation") != call.id) return
            when (message.what) {
                ScriptWire.RESULT -> call.result.complete(Json.decodeFromString<ScriptResult>(payload))
                // 实时输出只用于展示：超出条数预算后静默丢弃，不影响脚本结果。
                ScriptWire.OUTPUT -> if (call.outputMessages.incrementAndGet() <= ScriptLimits.OUTPUT_MESSAGES) {
                    Json.parseToJsonElement(payload).jsonArray.forEach { item ->
                        val frame = item.jsonObject
                        val fd = if (frame["fd"]?.jsonPrimitive?.intOrNull == 2) 2 else 1
                        call.capabilities.output(fd, frame["d"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
                ScriptWire.CAPABILITY -> {
                    if (call.count.incrementAndGet() > ScriptLimits.CAPABILITY_CALLS) throw ScriptFailure("CAPABILITY_LIMIT", "能力调用次数超过限制")
                    val callId = message.data.getInt("call")
                    val args = Json.parseToJsonElement(payload).jsonObject
                    call.callbacks.submit(args["operation"]!!.jsonPrimitive.content, args["arguments"]!!.jsonObject) { result ->
                        val response = result.fold(
                            onSuccess = { value -> buildJsonObject { put("value", value) } },
                            onFailure = { e ->
                            val failure = e as? ScriptFailure
                            if (failure?.code?.endsWith("_LIMIT") == true) call.result.complete(ScriptResult(errorCode = failure.code, error = failure.message))
                            buildJsonObject { put("errorCode", failure?.code ?: "CAPABILITY_DENIED"); put("error", failure?.message ?: "能力请求被拒绝") }
                            },
                        )
                        if (current === call && !call.result.isCompleted) {
                            try { remote?.let { ScriptWire.send(it, ScriptWire.CAPABILITY_RESULT, call.id, callId, response.toString()) } }
                            catch (e: Exception) { call.result.completeExceptionally(e) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            current?.result?.completeExceptionally(e)
            if (!ready.isCompleted) ready.completeExceptionally(e)
        }
    }

    private fun disconnected() {
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
        val error = ScriptFailure("PROCESS_DIED", "隔离进程已退出")
        ready.completeExceptionally(error)
        current?.result?.completeExceptionally(error)
    }

    override fun terminate() {
        remote?.let { runCatching { ScriptWire.send(it, ScriptWire.TERMINATE) } }
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }

    companion object {
        private val capabilityDispatcher = ScriptCapabilityDispatcher.Shared
        suspend fun bind(context: Context, sessionId: String): AndroidScriptProcess {
            val process = AndroidScriptProcess(context.applicationContext)
            val name = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            try {
                process.bound = context.bindIsolatedService(Intent(context, ScriptRuntimeService::class.java), Context.BIND_AUTO_CREATE, "script_$name", context.mainExecutor, process.connection)
                if (!process.bound) throw ScriptFailure("PROCESS_DIED", "无法绑定隔离服务")
                withTimeout(5000) { process.ready.await() }
                return process
            } catch (e: Throwable) { process.terminate(); throw e }
        }
    }
}
