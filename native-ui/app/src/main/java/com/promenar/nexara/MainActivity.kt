package com.promenar.nexara

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.navigation.NexaraNavGraph
import com.promenar.nexara.ui.theme.NexaraTheme
import android.content.Intent
import android.net.Uri
import com.promenar.nexara.util.LocaleHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NexaraTheme {
                // Ensure status bar icons are white (light appearance = false)
                val view = androidx.compose.ui.platform.LocalView.current
                if (!view.isInEditMode) {
                    androidx.compose.runtime.SideEffect {
                        val window = (view.context as android.app.Activity).window
                        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                        insetsController.isAppearanceLightStatusBars = false
                    }
                }
                
                val navController = rememberNavController()
                val context = LocalContext.current
                val prefs = context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
                val hasShownWelcome = prefs.getBoolean("has_shown_welcome", false)
                val startDestination = if (hasShownWelcome) {
                    NavDestinations.MAIN_TAB_SCAFFOLD
                } else {
                    NavDestinations.WELCOME
                }
                
                NexaraNavGraph(
                    navController = navController,
                    startDestination = startDestination
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                importSharedFiles(listOf(uri))
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                importSharedFiles(uris)
            }
        }
    }

    private fun importSharedFiles(uris: List<Uri>) {
        val app = application as NexaraApplication
        lifecycleScope.launch {
            app.documentImporter.importFromUris(uris)
            // Optional: Show a toast or navigate to RAG screen
            android.widget.Toast.makeText(this@MainActivity, "正在导入 ${uris.size} 个文件", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
