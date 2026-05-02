package com.promenar.nexara.native.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.promenar.nexara.native.ui.welcome.WelcomeScreen

/**
 * Nexara UI Core Navigation
 * Enforces MD3 Material Motion transitions instead of abrupt jumps.
 */
object NavDestinations {
    const val WELCOME = "welcome"
    const val MAIN_TAB_SCAFFOLD = "main_tab_scaffold"
    const val SESSION_LIST = "session_list" // Secondary Screen for listing agent's sessions
    const val CHAT_HERO = "chat_hero" // The actual conversation UI
    const val RAG_ADVANCED = "rag_advanced" 
    const val RAG_GLOBAL_CONFIG = "rag_global_config"
    const val SESSION_SETTINGS = "session_settings"
}

@Composable
fun NexaraNavGraph(
    navController: NavHostController,
    startDestination: String = NavDestinations.WELCOME
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Global transition defaults (Material Motion Axis Z / Fade Through)
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                    initialOffset = { it / 10 } // Subtle slide
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                    targetOffset = { -it / 10 }
                )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                    initialOffset = { -it / 10 }
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                    targetOffset = { it / 10 }
                )
        }
    ) {
        composable(NavDestinations.WELCOME) {
            WelcomeScreen(
                onNavigateToChat = {
                    navController.navigate(NavDestinations.MAIN_TAB_SCAFFOLD) {
                        popUpTo(NavDestinations.WELCOME) { inclusive = true }
                    }
                }
            )
        }
        
        // Placeholder for the main app UI
        // The main hub which holds the BottomNavigationBar
        composable(NavDestinations.MAIN_TAB_SCAFFOLD) {
            com.promenar.nexara.native.ui.MainTabScaffold(
                onNavigateToSecondary = { route -> 
                    navController.navigate(route)
                }
            )
        }
        
        // Agent Sessions List (Secondary level, hides bottom bar)
        composable(NavDestinations.SESSION_LIST) {
            com.promenar.nexara.native.ui.hub.AgentSessionsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChat = { navController.navigate(NavDestinations.CHAT_HERO) }
            )
        }

        // The True Conversation Hero Screen (Tertiary level, full immersive)
        composable(NavDestinations.CHAT_HERO) {
            com.promenar.nexara.native.ui.chat.ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(NavDestinations.SESSION_SETTINGS) }
            )
        }

        composable(NavDestinations.RAG_ADVANCED) {
            com.promenar.nexara.native.ui.rag.AdvancedRetrievalScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(NavDestinations.RAG_GLOBAL_CONFIG) {
            com.promenar.nexara.native.ui.rag.GlobalRagConfigScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(NavDestinations.SESSION_SETTINGS) {
            com.promenar.nexara.native.ui.chat.SessionSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
