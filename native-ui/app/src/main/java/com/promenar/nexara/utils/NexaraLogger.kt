package com.promenar.nexara.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NexaraLogger {
    private const val TAG = "NexaraLogger"
    private const val LOG_FILE_NAME = "nexara_logs.txt"
    private const val DIAGNOSTIC_FILE_NAME = "nexara_diagnostics.jsonl"
    private const val MAX_DIAGNOSTIC_BYTES = 512 * 1024L
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val diagnosticExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexara-diagnostics").apply { isDaemon = true }
    }

    private val isAndroid = System.getProperty("java.vendor") == "The Android Project"

    fun init(context: Context) {
        if (!isAndroid) return
        try {
            diagnosticExecutor.execute {
                runCatching { getDiagnosticFile(context).let { if (!it.exists()) it.createNewFile() } }
            }
            if (com.promenar.nexara.BuildConfig.DEBUG) getLogFile(context).let {
                if (!it.exists()) it.createNewFile()
            }

            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                diagnostic("app.uncaught", mapOf(
                    "thread" to thread.name.take(40),
                    "exception" to throwable::class.java.simpleName.take(60),
                ))
                logError("FATAL EXCEPTION", throwable)
                originalHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    /**
     * 发行版可用的有界诊断事件。调用方只能传标识符、计数和状态，不得传消息正文、
     * Prompt、凭据或完整 URL；这里仍会执行统一脱敏并限制单字段长度。
     */
    fun diagnostic(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!isAndroid) return
        val context = com.promenar.nexara.NexaraApplication.instance ?: return
        diagnosticExecutor.execute { runCatching {
            val safeEvent = event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
            val payload = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("event", safeEvent)
                sanitizeDiagnosticFields(fields).forEach { (key, value) ->
                    put(key, value)
                }
            }.toString()
            val file = getDiagnosticFile(context)
            file.appendText(payload + "\n")
            if (file.length() > MAX_DIAGNOSTIC_BYTES) {
                val retained = file.readLines().takeLast(800)
                file.writeText(retained.joinToString("\n", postfix = "\n"))
            }
        } }
    }

    fun getDiagnosticFile(context: Context): File = File(context.filesDir, DIAGNOSTIC_FILE_NAME)

    suspend fun prepareDiagnosticExport(context: Context): File = withContext(Dispatchers.IO) {
        getDiagnosticFile(context).also { file ->
            if (!file.exists()) file.createNewFile()
        }
    }

    fun clearDiagnostics(context: Context) {
        getDiagnosticFile(context).writeText("")
    }

    fun log(message: String) {
        if (!com.promenar.nexara.BuildConfig.DEBUG) return
        val safeMessage = SensitiveDataRedactor.redactMessage(message)
        if (!isAndroid) {
            println("[$TAG] $safeMessage")
            return
        }
        Log.d(TAG, safeMessage)
        try {
            val trimMsg = safeMessage.trim()
            if (trimMsg.startsWith("[")) {
                val closeBracket = trimMsg.indexOf("]")
                if (closeBracket > 0) {
                    val tag = trimMsg.substring(1, closeBracket).uppercase(Locale.getDefault())
                    val content = trimMsg.substring(closeBracket + 1).trim()
                    val json = org.json.JSONObject().apply {
                        put("message", content)
                    }
                    Log.d("NEXARA_METRO", "EVENT_START|${tag}|${json}|EVENT_END")
                } else {
                    logDefaultMetro(safeMessage)
                }
            } else {
                logDefaultMetro(safeMessage)
            }
        } catch (_: Exception) {
            logDefaultMetro(safeMessage)
        }
        writeToDisk("DEBUG: $safeMessage")
    }

    private fun logDefaultMetro(message: String) {
        if (!isAndroid) return
        try {
            val json = org.json.JSONObject().apply {
                put("message", message)
            }
            Log.d("NEXARA_METRO", "EVENT_START|LOG|${json}|EVENT_END")
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun logError(tag: String, throwable: Throwable) {
        if (!com.promenar.nexara.BuildConfig.DEBUG) return
        val safeTag = SensitiveDataRedactor.redactMessage(tag).take(80)
        val safeError = SensitiveDataRedactor.safeThrowable(
            throwable,
            debug = true
        )
        if (!isAndroid) {
            System.err.println("[$TAG] ERROR [$safeTag]: $safeError")
            return
        }
        Log.e(TAG, "$safeTag: $safeError")

        writeToDisk("ERROR [$safeTag]: $safeError")
        try {
            val json = org.json.JSONObject().apply {
                put("tag", safeTag)
                put("error", safeError)
            }
            metro("ERROR", json.toString())
        } catch (_: Exception) {}
    }

    fun metro(event: String, payload: String) {
        if (!com.promenar.nexara.BuildConfig.DEBUG) return
        val safeEvent = event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        val safePayload = SensitiveDataRedactor.redactMessage(payload)
        runCatching {
            Log.d("NEXARA_METRO", "EVENT_START|$safeEvent|$safePayload|EVENT_END")
        }
    }

    private fun writeToDisk(content: String) {
        if (!isAndroid || !com.promenar.nexara.BuildConfig.DEBUG) return
        try {
            val context = com.promenar.nexara.NexaraApplication.instance ?: return
            val logFile = getLogFile(context)
            val timestamp = dateFormat.format(Date())
            logFile.appendText("[$timestamp] $content\n")
            
            // Limit log file size to 1MB
            if (logFile.length() > 1024 * 1024) {
                val lines = logFile.readLines()
                if (lines.size > 500) {
                    logFile.writeText(lines.takeLast(500).joinToString("\n"))
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "log_io_failure")
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun getLogs(context: Context): String {
        if (!isAndroid || !com.promenar.nexara.BuildConfig.DEBUG) return "Logs unavailable."
        val file = getLogFile(context)
        return if (file.exists()) file.readText() else "No logs found."
    }

    fun clearLogs(context: Context) {
        if (!isAndroid) return
        val file = getLogFile(context)
        if (file.exists()) file.writeText("")
    }
}

private val ForbiddenDiagnosticFieldFragments = setOf(
    "authorization", "apikey", "api_key", "token", "secret", "prompt",
    "content", "message", "request", "response", "body", "url",
)

internal fun sanitizeDiagnosticFields(fields: Map<String, Any?>): Map<String, Any?> =
    fields.entries.take(16).associate { (key, value) ->
        val safeKey = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
        val forbidden = ForbiddenDiagnosticFieldFragments.any {
            safeKey.lowercase(Locale.ROOT).contains(it)
        }
        safeKey to if (forbidden) {
            "[omitted]"
        } else when (value) {
            null, is Number, is Boolean -> value
            else -> SensitiveDataRedactor.redactMessage(value.toString()).take(160)
        }
    }
