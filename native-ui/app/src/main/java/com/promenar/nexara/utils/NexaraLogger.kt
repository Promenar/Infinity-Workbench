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

    fun init(context: Context) {
        val logFile = getLogFile(context)
        if (!logFile.exists()) {
            logFile.createNewFile()
        }

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError("FATAL EXCEPTION", throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        Log.d(TAG, message)
        writeToDisk("DEBUG: $message")
    }

    fun logError(tag: String, throwable: Throwable) {
        val stackTrace = Log.getStackTraceString(throwable)
        Log.e(TAG, "$tag: $stackTrace")
        writeToDisk("ERROR [$tag]: $stackTrace")
    }

    private fun writeToDisk(content: String) {
        // Run on a background thread if possible, but for fatal crashes we need to be careful
        try {
            val context = com.promenar.nexara.NexaraApplication.instance
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
        val file = getLogFile(context)
        return if (file.exists()) file.readText() else "No logs found."
    }

    fun clearLogs(context: Context) {
        val file = getLogFile(context)
        if (file.exists()) file.writeText("")
    }
}
