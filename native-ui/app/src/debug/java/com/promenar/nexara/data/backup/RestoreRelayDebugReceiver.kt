package com.promenar.nexara.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 仅 debug APK 的设备测试入口；release variant 不包含此组件。 */
class RestoreRelayDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION) RestoreRelayActivity.requestRestart(context)
    }

    companion object {
        const val ACTION = "com.promenar.nexara.debug.TEST_RESTORE_RELAY"
    }
}
