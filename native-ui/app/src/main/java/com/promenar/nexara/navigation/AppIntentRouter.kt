package com.promenar.nexara.navigation

import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OpenGenerationRequest(
    val requestId: String,
    val sessionId: String,
)

class AppIntentRouter(
    private val taskValidator: (taskId: String, sessionId: String) -> Boolean = { _, _ -> true },
) {
    private data class IssuedOpen(val taskId: String, val sessionId: String)

    private val mutablePending = MutableStateFlow<OpenGenerationRequest?>(null)
    val pending: StateFlow<OpenGenerationRequest?> = mutablePending
    private val issuedByToken = mutableMapOf<String, IssuedOpen>()
    private val tokenByTask = mutableMapOf<String, String>()
    private val issuedLock = Any()

    fun issue(taskId: String, sessionId: String): OpenGenerationRequest {
        require(taskId.isNotBlank() && sessionId.isNotBlank())
        val token = UUID.randomUUID().toString()
        synchronized(issuedLock) {
            tokenByTask.put(taskId, token)?.let(issuedByToken::remove)
            issuedByToken[token] = IssuedOpen(taskId, sessionId)
        }
        return OpenGenerationRequest(token, sessionId)
    }

    fun offer(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_GENERATION) return false
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return false
        val token = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return false
        val issued = synchronized(issuedLock) {
            val candidate = issuedByToken[token]
            if (candidate?.sessionId != sessionId) return@synchronized null
            if (!taskValidator(candidate.taskId, candidate.sessionId)) {
                issuedByToken.remove(token)
                tokenByTask.remove(candidate.taskId, token)
                return@synchronized null
            }
            issuedByToken.remove(token)
            tokenByTask.remove(candidate.taskId, token)
            candidate
        } ?: return false
        mutablePending.value = OpenGenerationRequest(token, issued.sessionId)
        return true
    }

    fun consume(requestId: String): Boolean {
        val current = mutablePending.value ?: return false
        if (current.requestId != requestId) return false
        return mutablePending.compareAndSet(current, null)
    }

    companion object {
        const val ACTION_OPEN_GENERATION = "com.promenar.nexara.action.OPEN_GENERATION"
        const val EXTRA_SESSION_ID = "com.promenar.nexara.extra.SESSION_ID"
        const val EXTRA_REQUEST_ID = "com.promenar.nexara.extra.REQUEST_ID"
    }
}
