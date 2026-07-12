package com.promenar.nexara

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
    private val shareIntentViewModel by viewModels<ShareIntentViewModel>()
    private val shareIntentQueue: ShareIntentQueue get() = shareIntentViewModel.queue

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shareIntentQueue.restoreConsumedState(savedInstanceState)
        enqueueShareIntent(intent)
        val app = application as NexaraApplication
        setContent {
            NexaraTheme {
                val startupState by app.startupState.collectAsStateWithLifecycle()
                LaunchedEffect(startupState) {
                    if (startupState == BackupStartupState.Ready) consumeShareIntentsIfReady()
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
        enqueueShareIntent(intent)
        consumeShareIntentsIfReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        shareIntentQueue.saveConsumedState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun enqueueShareIntent(candidate: Intent?) {
        if (!ShareIntentQueue.isShareIntent(candidate)) return
        val result = shareIntentQueue.enqueue(candidate)
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
        when (result) {
            ShareEnqueueResult.Accepted, ShareEnqueueResult.Duplicate -> Unit
            ShareEnqueueResult.RejectedInvalid -> showShareFeedback(R.string.share_intent_invalid)
            ShareEnqueueResult.RejectedCapacity -> showShareFeedback(R.string.share_intent_queue_full)
        }
    }

    private fun showShareFeedback(message: Int) {
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
    }

    private fun consumeShareIntentsIfReady() {
        val app = application as NexaraApplication
        if (app.startupState.value != BackupStartupState.Ready) return
        shareIntentQueue.consumeAll { request -> importSharedFiles(request.uris) }
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
