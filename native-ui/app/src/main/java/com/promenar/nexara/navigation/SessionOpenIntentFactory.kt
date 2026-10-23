package com.promenar.nexara.navigation

import android.app.PendingIntent

/**
 * 通知点击后打开指定会话的意图。实现经 [AppIntentRouter] 签发一次性令牌，防止外部应用伪造意图跳转会话；
 * [tag] 区分同一会话的不同通知，避免 PendingIntent 互相覆盖。
 */
fun interface SessionOpenIntentFactory {
    fun create(sessionId: String, tag: String): PendingIntent
}
