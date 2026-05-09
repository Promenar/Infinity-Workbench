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
import com.promenar.nexara.util.LocaleHelper

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexaraTheme {
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
}
