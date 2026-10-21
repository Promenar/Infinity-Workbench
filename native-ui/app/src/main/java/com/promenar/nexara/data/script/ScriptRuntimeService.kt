package com.promenar.nexara.data.script

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.promenar.nexara.domain.script.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** 没有仓库、Context 能力绑定或网络实现，所有脚本能力都经过当前 Binder 会话。 */
class ScriptRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val nextCall = AtomicInteger()
    private var invocation = ""
    private var job: Job? = null
    @Volatile private var engine: ScriptEngine? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        val payload = message.data.getString("payload").orEmpty()
        if (payload.toByteArray(Charsets.UTF_8).size > ScriptLimits.IPC_BYTES) { terminate(); return }
        when (message.what) {
            ScriptWire.HELLO -> message.replyTo?.let {
                ScriptWire.send(it, ScriptWire.READY, payload = buildJsonObject {
                    put("pid", Process.myPid()); put("uid", Process.myUid())
                }.toString())
            }
            ScriptWire.TERMINATE -> terminate()
            ScriptWire.EXECUTE -> {
                if (job?.isActive == true) { terminate(); return }
                val host = message.replyTo ?: return
                val id = message.data.getString("invocation")?.takeIf(String::isNotBlank) ?: return
                invocation = id
                nextCall.set(0)
                job = scope.launch {
                    try {
                        val request = Json.decodeFromString<ScriptRequest>(payload)
                        if (request.timeoutSeconds !in 1..ScriptLimits.MAX_TIMEOUT_SECONDS || request.code.length > ScriptLimits.MAX_CODE_CHARS ||
                            request.language !in ScriptLanguages.all || request.stdin.length > ScriptLimits.MAX_STDIN_CHARS
                        ) {
                            throw ScriptFailure("INVALID_ARGUMENT", "脚本请求超出范围")
                        }
                        val currentEngine = if (request.language == ScriptLanguages.PYTHON) {
                            PythonSandboxEngine.packaged(applicationInfo.nativeLibraryDir)
                        } else {
                            QuickJsScriptEngine()
                        }
                        engine = currentEngine
                        val forwarder = ScriptOutputForwarder { payload -> runCatching { ScriptWire.send(host, ScriptWire.OUTPUT, id, payload = payload) } }
                        val flusher = launch { while (isActive) { delay(ScriptOutputForwarder.INTERVAL_MILLIS); forwarder.flush() } }
                        val result = try {
                            currentEngine.execute(request, object : ScriptCapabilities {
                                override suspend fun invoke(operation: String, arguments: JsonObject): JsonElement {
                                    val call = nextCall.incrementAndGet()
                                    if (call > ScriptLimits.CAPABILITY_CALLS) throw ScriptFailure("CAPABILITY_LIMIT", "能力调用次数超过限制")
                                    val response = CompletableDeferred<JsonElement>()
                                    calls[call] = response
                                    return try {
                                        ScriptWire.send(host, ScriptWire.CAPABILITY, id, call, buildJsonObject {
                                            put("operation", operation); put("arguments", arguments)
                                        }.toString())
                                        response.await()
                                    } finally { calls.remove(call) }
                                }

                                override fun output(fd: Int, text: String) = forwarder.add(fd, text)
                            })
                        } finally {
                            // 先等定时刷新退出，再补发剩余输出，保证顺序且不并发发送。
                            withContext(NonCancellable) { flusher.cancelAndJoin() }
                            forwarder.flush()
                        }
                        ScriptWire.send(host, ScriptWire.RESULT, id, payload = Json.encodeToString(result))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Throwable) {
                        runCatching { ScriptWire.send(host, ScriptWire.RESULT, id, payload = Json.encodeToString(
                            ScriptResult(errorCode = (failure as? ScriptFailure)?.code ?: "JS_ERROR", error = "隔离引擎执行失败"),
                        )) }
                    } finally { engine = null; calls.values.forEach { it.cancel() }; calls.clear() }
                }
            }
            ScriptWire.CAPABILITY_RESULT -> {
                if (message.data.getString("invocation") != invocation) return
                val pending = calls[message.data.getInt("call")] ?: return
                try {
                    val response = Json.parseToJsonElement(payload).jsonObject
                    val error = response["errorCode"]?.jsonPrimitive?.contentOrNull
                    if (error != null) pending.completeExceptionally(ScriptFailure(error, response["error"]?.jsonPrimitive?.content ?: "能力请求失败"))
                    else pending.complete(response["value"] ?: JsonNull)
                } catch (failure: Exception) { pending.completeExceptionally(failure) }
            }
        }
    }

    private fun terminate() {
        // 先结束沙箱 Python 子进程（Process.destroyForcibly 即 SIGKILL），再由 Binder 控制线程直接退出，
        // 不依赖引擎中断回调或锁的可用性。
        runCatching { engine?.cancel() }
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        runCatching { engine?.cancel() }
        scope.cancel()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
