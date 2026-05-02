package com.promenar.nexara.native

import android.os.Build
import android.os.Bundle
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.ReactDelegate
import com.facebook.react.bridge.ReactContext
import com.promenar.nexara.MainApplication
import com.promenar.nexara.native.ui.theme.NexaraTheme
import com.promenar.nexara.native.navigation.NexaraNavGraph

class NativeChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 静默拉起 React Native 引擎 (用于 Bridge 同步)
        // 确保 MainApplication 中的 ReactHost 被初始化并加载 Bundle
        try {
            val reactInstanceManager = (application as MainApplication).reactNativeHost.reactInstanceManager
            if (!reactInstanceManager.hasStartedCreatingInitialContext()) {
                reactInstanceManager.createReactContextInBackground()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 广色域渲染
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }

        setContent {
            NexaraTheme {
                val navController = rememberNavController()
                NexaraNavGraph(navController = navController)
            }
        }
    }
}
