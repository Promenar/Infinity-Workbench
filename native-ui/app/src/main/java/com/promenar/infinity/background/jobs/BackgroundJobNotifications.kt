package com.promenar.infinity.background.jobs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.promenar.infinity.MainActivity
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.jobs.BackgroundJobCompletion
import com.promenar.infinity.data.jobs.BackgroundJobRecord
import com.promenar.infinity.navigation.SessionOpenIntentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackgroundJobNotifications(
    private val app: InfinityApplication,
    private val openSession: () -> SessionOpenIntentFactory? = { null },
) {
    suspend fun notify(record: BackgroundJobRecord) = withContext(Dispatchers.Main.immediate) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && app.visibleSessionId == record.sessionId) return@withContext
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return@withContext
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,app.getString(R.string.jobs_channel),NotificationManager.IMPORTANCE_DEFAULT))
        // 会话令牌同步写入私有存储，需在 IO 上签发。
        val open=withContext(Dispatchers.IO) { openSession()?.create(record.sessionId,"background-job:${record.id}") } ?: PendingIntent.getActivity(app,record.id.hashCode(),Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text=app.getString(R.string.jobs_finished_text,record.summary,record.exitCode?.toString() ?: record.state,BackgroundJobCompletion.elapsedSeconds(record))
        val notification=NotificationCompat.Builder(app,CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.jobs_finished_title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {NotificationManagerCompat.from(app).notify(CHANNEL_ID,record.id.hashCode(),notification)} catch(_:SecurityException) { /* 撤销通知权限不影响持久化任务结果。 */ }
    }
    companion object {const val CHANNEL_ID="background_jobs"}
}
