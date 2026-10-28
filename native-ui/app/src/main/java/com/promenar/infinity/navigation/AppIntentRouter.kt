package com.promenar.infinity.navigation

import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OpenGenerationRequest(
    val requestId: String,
    val sessionId: String,
)

/** 会话通知令牌的持久化边界；校验与删除必须在同一原子操作内完成。 */
interface SessionOpenTokenStore {
    fun issue(requestId: String, sessionId: String, issuedAtMillis: Long)
    fun consume(requestId: String, sessionId: String, nowMillis: Long): Boolean
}

internal data class SessionOpenTokenRecord(val sessionId: String, val issuedAtMillis: Long) {
    fun isFresh(nowMillis: Long): Boolean = nowMillis >= issuedAtMillis &&
        nowMillis - issuedAtMillis in 0 until AppIntentRouter.SESSION_OPEN_TOKEN_TTL_MILLIS
}

private class InMemorySessionOpenTokenStore : SessionOpenTokenStore {
    private val records = mutableMapOf<String, SessionOpenTokenRecord>()

    @Synchronized
    override fun issue(requestId: String, sessionId: String, issuedAtMillis: Long) {
        records.entries.removeAll { !it.value.isFresh(issuedAtMillis) }
        records[requestId] = SessionOpenTokenRecord(sessionId, issuedAtMillis)
    }

    @Synchronized
    override fun consume(requestId: String, sessionId: String, nowMillis: Long): Boolean {
        val record = records[requestId] ?: return false
        if (!record.isFresh(nowMillis)) {
            records.remove(requestId)
            return false
        }
        if (record.sessionId != sessionId) return false
        records.remove(requestId)
        return true
    }
}

class AppIntentRouter(
    private val sessionOpenTokens: SessionOpenTokenStore = InMemorySessionOpenTokenStore(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
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

    fun issueSession(sessionId: String): OpenGenerationRequest {
        require(sessionId.isNotBlank())
        val token = UUID.randomUUID().toString()
        sessionOpenTokens.issue(token, sessionId, nowMillis())
        return OpenGenerationRequest(token, sessionId)
    }

    fun offer(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action != ACTION_OPEN_GENERATION && action != ACTION_OPEN_SESSION) return false
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return false
        val token = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return false
        if (action == ACTION_OPEN_SESSION) {
            if (!sessionOpenTokens.consume(token, sessionId, nowMillis())) return false
            mutablePending.value = OpenGenerationRequest(token, sessionId)
            return true
        }
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
        const val ACTION_OPEN_GENERATION = "com.promenar.infinity.action.OPEN_GENERATION"
        const val ACTION_OPEN_SESSION = "com.promenar.infinity.action.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "com.promenar.infinity.extra.SESSION_ID"
        const val EXTRA_REQUEST_ID = "com.promenar.infinity.extra.REQUEST_ID"
        const val SESSION_OPEN_TOKEN_TTL_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
