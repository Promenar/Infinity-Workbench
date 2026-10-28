package com.promenar.infinity.navigation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.WorkerThread
import com.promenar.infinity.MainActivity
import org.json.JSONObject

/** 独立的应用私有令牌存储，不进入备份；写入及消费完成后才允许发布或打开通知。 */
class AndroidSessionOpenTokenStore(context: Context) : SessionOpenTokenStore {
    private val applicationContext = context.applicationContext
    private val preferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @WorkerThread
    override fun issue(requestId: String, sessionId: String, issuedAtMillis: Long) {
        synchronized(lock) {
            val editor = preferences.edit()
            preferences.all.forEach { (token, value) ->
                if (decode(value)?.isFresh(issuedAtMillis) != true) editor.remove(token)
            }
            val value = JSONObject()
                .put("sessionId", sessionId)
                .put("issuedAtMillis", issuedAtMillis)
                .toString()
            check(editor.putString(requestId, value).commit()) { "会话通知令牌写入失败" }
        }
    }

    @WorkerThread
    override fun consume(requestId: String, sessionId: String, nowMillis: Long): Boolean =
        synchronized(lock) {
            val value = preferences.all[requestId] ?: return@synchronized false
            val record = decode(value)
            if (record == null || !record.isFresh(nowMillis)) {
                preferences.edit().remove(requestId).commit()
                return@synchronized false
            }
            if (record.sessionId != sessionId) return@synchronized false
            // 磁盘删除失败时拒绝导航，避免进程重建后重放已经接受的点击。
            preferences.edit().remove(requestId).commit()
        }

    private fun decode(value: Any?): SessionOpenTokenRecord? {
        if (value !is String) return null
        return runCatching {
            val json = JSONObject(value)
            SessionOpenTokenRecord(json.getString("sessionId"), json.getLong("issuedAtMillis"))
                .takeIf { it.sessionId.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        internal const val PREFERENCES_NAME = "infinity_session_open_tokens"
        private val lock = Any()
    }
}

/** [router] 必须装配 [AndroidSessionOpenTokenStore]，以支持通知点击时的冷进程恢复。 */
class AndroidSessionOpenIntentFactory(
    context: Context,
    private val router: AppIntentRouter,
) : SessionOpenIntentFactory {
    private val applicationContext = context.applicationContext

    @WorkerThread
    override fun create(sessionId: String, tag: String): PendingIntent {
        val issued = router.issueSession(sessionId)
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = AppIntentRouter.ACTION_OPEN_SESSION
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, issued.sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)
            data = Uri.Builder()
                .scheme("infinity")
                .authority("session")
                .appendPath("open")
                .appendPath(sessionId)
                .appendQueryParameter("tag", tag)
                .build()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
