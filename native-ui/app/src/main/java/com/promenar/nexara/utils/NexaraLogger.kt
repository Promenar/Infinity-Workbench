package com.promenar.nexara.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object NexaraLogger {
    private const val TAG = "NexaraLogger"
    private const val LOG_FILE_NAME = "nexara_logs.txt"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val isAndroid = System.getProperty("java.vendor") == "The Android Project"

    fun init(context: Context) {
        if (!isAndroid) return
        try {
            if (com.promenar.nexara.BuildConfig.DEBUG) {
                val logFile = getLogFile(context)
                if (!logFile.exists()) {
                    logFile.createNewFile()
                }
            }

            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logError("FATAL EXCEPTION", throwable)
                originalHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            // Ignored
        }
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
        val safeTag = SensitiveDataRedactor.redactMessage(tag).take(80)
        val safeError = SensitiveDataRedactor.safeThrowable(
            throwable,
            debug = com.promenar.nexara.BuildConfig.DEBUG
        )
        if (!isAndroid) {
            System.err.println("[$TAG] ERROR [$safeTag]: $safeError")
            return
        }
        Log.e(TAG, "$safeTag: $safeError")

        if (com.promenar.nexara.BuildConfig.DEBUG) {
            writeToDisk("ERROR [$safeTag]: $safeError")
            try {
                val json = org.json.JSONObject().apply {
                    put("tag", safeTag)
                    put("error", safeError)
                }
                Log.d("NEXARA_METRO", "EVENT_START|ERROR|${json}|EVENT_END")
            } catch (_: Exception) {}
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
