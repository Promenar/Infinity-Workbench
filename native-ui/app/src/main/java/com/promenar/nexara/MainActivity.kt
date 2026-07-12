package com.promenar.nexara

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.navigation.NexaraNavGraph
import com.promenar.nexara.ui.startup.StartupGate
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.util.LocaleHelper

class MainActivity : ComponentActivity() {
    private var pendingIntent: Intent? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntent = intent
        val app = application as NexaraApplication
        setContent {
            NexaraTheme {
                val startupState by app.startupState.collectAsStateWithLifecycle()
                LaunchedEffect(startupState) {
                    if (startupState == BackupStartupState.Ready) consumePendingIntentIfReady()
                }
                StartupGate(
                    state = startupState,
                    onRetry = app::retryStartupRecovery,
                ) {
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
                        startDestination = startDestination,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
        consumePendingIntentIfReady()
    }

    private fun consumePendingIntentIfReady() {
        val app = application as NexaraApplication
        if (app.startupState.value != BackupStartupState.Ready) return
        val pending = pendingIntent ?: return
        pendingIntent = null
        handleIntent(pending)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                importSharedFiles(listOf(uri))
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                importSharedFiles(uris)
            }
        }
    }

    private fun importSharedFiles(uris: List<Uri>) {
        // TODO: Implement workspace-based file import.
        Toast.makeText(
            this,
            getString(R.string.startup_importing_files, uris.size),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
