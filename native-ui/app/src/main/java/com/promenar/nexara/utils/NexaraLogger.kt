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
            val logFile = getLogFile(context)
            if (!logFile.exists()) {
                logFile.createNewFile()
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
        if (!isAndroid) {
            println("[$TAG] $message")
            return
        }
        if (com.promenar.nexara.BuildConfig.DEBUG) {
            Log.d(TAG, message)
            try {
                val trimMsg = message.trim()
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
                        logDefaultMetro(message)
                    }
                } else {
                    logDefaultMetro(message)
                }
            } catch (e: Exception) {
                logDefaultMetro(message)
            }
        }
        writeToDisk("DEBUG: $message")
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
        if (!isAndroid) {
            System.err.println("[$TAG] ERROR [$tag]: ${throwable.message}")
            throwable.printStackTrace()
            return
        }
        val stackTrace = Log.getStackTraceString(throwable)
        Log.e(TAG, "$tag: $stackTrace")
        writeToDisk("ERROR [$tag]: $stackTrace")

        if (com.promenar.nexara.BuildConfig.DEBUG) {
            try {
                val json = org.json.JSONObject().apply {
                    put("tag", tag)
                    put("message", throwable.message ?: throwable.toString())
                    // Limit stacktrace to top 15 lines to stay within log length restrictions
                    val limitedStackTrace = stackTrace.split("\n").take(15).joinToString("\n")
                    put("stacktrace", limitedStackTrace)
                }
                Log.d("NEXARA_METRO", "EVENT_START|ERROR|${json}|EVENT_END")
            } catch (e: Exception) {
                // Safeguard against any logging anomalies
            }
        }
    }

    private fun writeToDisk(content: String) {
        if (!isAndroid) return
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to disk", e)
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun getLogs(context: Context): String {
        if (!isAndroid) return "No logs in non-android environment."
        val file = getLogFile(context)
        return if (file.exists()) file.readText() else "No logs found."
    }

    fun clearLogs(context: Context) {
        if (!isAndroid) return
        val file = getLogFile(context)
        if (file.exists()) file.writeText("")
    }
}
