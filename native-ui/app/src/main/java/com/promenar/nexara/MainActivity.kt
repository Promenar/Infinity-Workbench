package com.promenar.nexara

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.navigation.NavDestinations
import com.promenar.nexara.navigation.NexaraNavGraph
import com.promenar.nexara.navigation.AppIntentRouter
import com.promenar.nexara.navigation.initialOnboardingDestination
import com.promenar.nexara.onboarding.OnboardingState
import com.promenar.nexara.onboarding.OnboardingStateStore
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.chat.ChatRoute
import com.promenar.nexara.ui.chat.ChatRouteDependencies
import com.promenar.nexara.share.core.AndroidShareIndexScheduler
import com.promenar.nexara.share.core.DurableShareInbox
import com.promenar.nexara.share.core.ShareImportTargetProvider
import com.promenar.nexara.share.core.SharedFileImporter
import com.promenar.nexara.share.ui.ShareImportSheet
import com.promenar.nexara.share.ui.ShareImportViewModel
import com.promenar.nexara.share.ui.SharePendingBanner
import com.promenar.nexara.ui.startup.StartupGate
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.util.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.promenar.nexara.share.ui.ShareImportUiState

internal fun allowOnboardingEmptyModelsOverride(
    isDebugBuild: Boolean,
    requestedByIntent: Boolean,
): Boolean = isDebugBuild && requestedByIntent

internal fun debugChatSessionId(
    isDebugBuild: Boolean,
    requestedSessionId: String?,
): String? = requestedSessionId
    ?.trim()
    ?.takeIf { isDebugBuild && it.isNotEmpty() }

open class MainActivity : ComponentActivity() {
    /** 供真实 Activity E2E 以子类覆写依赖，生产默认不暴露全局可变测试注册表。 */
    protected open fun provideChatRouteDependencies(
        application: NexaraApplication,
    ): ChatRouteDependencies = application.createChatRouteDependencies()

    private val appIntentRouter: AppIntentRouter
        get() = (application as NexaraApplication).appIntentRouter
    private val onboardingStore by lazy { OnboardingStateStore(applicationContext) }
    private val durableShareInbox by lazy { DurableShareInbox.get(noBackupFilesDir) }
    private val shareIntentViewModel by viewModels<ShareIntentViewModel> {
        ShareIntentViewModel.factory(durableShareInbox, applicationContext.contentResolver)
    }
    private val shareIntentQueue: ShareIntentQueue get() = shareIntentViewModel.queue
    @Volatile private var currentSessionId: String? = null
    @Volatile private var currentShareSubmissionId: Long? = null
    private val shareTargetProvider by lazy {
        val app = application as NexaraApplication
        ShareImportTargetProvider(app.database.sessionDao(), app.workspaceRepository)
    }
    private val sharedFileImporter by lazy {
        val app = application as NexaraApplication
        SharedFileImporter(
            source = durableShareInbox.contentSource(),
            workspace = app.workspaceRepository,
            indexScheduler = AndroidShareIndexScheduler(app),
        )
    }
    private val shareImportViewModel by viewModels<ShareImportViewModel> {
        ShareImportViewModel.factory(
            queue = shareIntentQueue,
            importer = sharedFileImporter,
            targetProvider = {
                val session = currentSessionId?.let { appSessionId ->
                    (application as NexaraApplication).chatStore.current.sessions
                        .firstOrNull { it.id == appSessionId }
                }
                shareTargetProvider.load(
                    currentSessionId = currentSessionId,
                    currentSessionTitle = session?.title,
                    knowledgeBaseLabel = getString(R.string.share_import_knowledge_base),
                )
            },
            indexQueueState = (application as NexaraApplication).vectorizationQueue.state,
            retryIndex = { root, fileUuid ->
                (application as NexaraApplication).vectorizationQueue.retryDocumentReference(root, fileUuid)
            },
        )
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shareIntentViewModel.stagingCoordinator.outcomes.collect { outcome ->
                    handleShareStageOutcome(outcome)
                }
            }
        }
        stageShareIntent(intent)
        routeOpenGenerationIntent(intent)
        val app = application as NexaraApplication
        setContent {
            NexaraTheme {
                val chatRouteDependencies = remember(app) {
                    provideChatRouteDependencies(app)
                }
                val startupState by app.startupState.collectAsStateWithLifecycle()
                StartupGate(
                    state = startupState,
                    onRetry = app::retryStartupRecovery,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true }
                    ) {
                        val navController = rememberNavController()
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        val importState by shareImportViewModel.state.collectAsStateWithLifecycle()
                        val openGenerationRequest by appIntentRouter.pending.collectAsStateWithLifecycle()
                        val debugChatSessionId = debugChatSessionId(
                            isDebugBuild = BuildConfig.DEBUG,
                            requestedSessionId = intent.getStringExtra(
                                EXTRA_CHAT_SESSION_ID_FOR_TESTING,
                            ),
                        )
                        val startDestination = remember(onboardingStore) {
                            initialOnboardingDestination(onboardingStore.state.value.step)
                        }

                        NexaraNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            onboardingStateStore = onboardingStore,
                            onboardingModelsOverride = if (allowOnboardingEmptyModelsOverride(
                                isDebugBuild = BuildConfig.DEBUG,
                                requestedByIntent = intent.getBooleanExtra(
                                    EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING,
                                    false,
                                ),
                            )) {
                                emptyList<ModelInfo>()
                            } else {
                                null
                            },
                            forceLocalProbeFailureForTesting = allowOnboardingEmptyModelsOverride(
                                isDebugBuild = BuildConfig.DEBUG,
                                requestedByIntent = intent.getBooleanExtra(
                                    EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING,
                                    false,
                                ),
                            ),
                            chatDestination = { sessionId, onNavigateBack, onNavigateToSession ->
                                ChatRoute(
                                    sessionId = sessionId,
                                    onNavigateBack = onNavigateBack,
                                    onNavigateToSession = onNavigateToSession,
                                    dependencies = chatRouteDependencies,
                                )
                            },
                            openGenerationRequest = openGenerationRequest,
                            onOpenGenerationConsumed = appIntentRouter::consume,
                        )
                        LaunchedEffect(debugChatSessionId) {
                            debugChatSessionId?.let { sessionId ->
                                navController.navigate(NavDestinations.chatHero(sessionId)) {
                                    popUpTo(startDestination) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                        LaunchedEffect(startupState, backStackEntry) {
                            currentSessionId = backStackEntry?.arguments?.getString("sessionId")
                            if (startupState == BackupStartupState.Ready) shareImportViewModel.presentNext()
                        }
                        ShareImportSheet(
                            state = importState,
                            onSelectTarget = shareImportViewModel::selectTarget,
                            onImport = shareImportViewModel::importAll,
                            onRetry = shareImportViewModel::retryRejected,
                            onClose = { shareImportViewModel.postpone() },
                            onCancel = shareImportViewModel::cancelConfirmed,
                        )
                        SharePendingBanner(
                            pendingCount = importState.pendingCount,
                            visible = !importState.visible && importState.pendingCount > 0,
                            onOpen = shareImportViewModel::presentNext,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ShareIntentQueue.isShareIntent(intent)) {
            setIntent(intent)
            stageShareIntent(intent)
        } else if (!routeOpenGenerationIntent(intent) && currentShareSubmissionId == null) {
            setIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            shareIntentQueue.refreshDurableCount()
            consumeShareIntentsIfReady()
        }
    }

    @VisibleForTesting
    internal fun onboardingStateForTesting(): OnboardingState = onboardingStore.state.value

    @VisibleForTesting
    internal fun onboardingStateStoreForTesting(): OnboardingStateStore = onboardingStore

    private fun stageShareIntent(candidate: Intent?) {
        if (!ShareIntentQueue.isShareIntent(candidate)) return
        currentShareSubmissionId = shareIntentViewModel.stagingCoordinator.submit(candidate!!)
    }

    private fun handleShareStageOutcome(outcome: ShareStageOutcome) {
        val isCurrent = currentShareSubmissionId == outcome.submissionId
        when (outcome.result) {
            ShareEnqueueResult.Accepted, ShareEnqueueResult.Duplicate -> {
                try {
                    if (isCurrent) {
                        setIntent(cleanMainIntent())
                        currentShareSubmissionId = null
                    }
                    consumeShareIntentsIfReady()
                } finally {
                    shareIntentViewModel.stagingCoordinator.acknowledge(outcome.submissionId)
                }
            }
            ShareEnqueueResult.RejectedInvalid -> {
                try {
                    revokeShareReadGrants(outcome.candidate)
                    if (isCurrent) {
                        setIntent(cleanMainIntent())
                        currentShareSubmissionId = null
                    }
                    showShareFeedback(R.string.share_intent_invalid)
                } finally {
                    shareIntentViewModel.stagingCoordinator.acknowledge(outcome.submissionId)
                }
            }
            ShareEnqueueResult.RejectedCapacity -> showShareFeedback(R.string.share_intent_queue_full)
        }
    }

    private fun cleanMainIntent() = Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
    }

    private fun routeOpenGenerationIntent(candidate: Intent?): Boolean {
        if (!appIntentRouter.offer(candidate)) return false
        setIntent(cleanMainIntent())
        return true
    }

    private fun revokeShareReadGrants(candidate: Intent) {
        val uris = linkedSetOf<android.net.Uri>()
        candidate.data?.let(uris::add)
        try {
            @Suppress("DEPRECATION")
            if (candidate.action == Intent.ACTION_SEND) {
                candidate.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            } else {
                uris += candidate.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
        } catch (_: IllegalArgumentException) {
            // 无法解析 EXTRA_STREAM 时仍继续回收 data/ClipData 授权并清空 Activity intent。
        } catch (_: ClassCastException) {
            // 同上。
        }
        candidate.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(uris::add) }
        }
        uris.forEach { uri ->
            try {
                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // grant 可能已由发送方或系统回收。
            }
        }
    }

    private fun showShareFeedback(message: Int) {
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
    }

    private fun consumeShareIntentsIfReady() {
        val app = application as NexaraApplication
        if (app.startupState.value != BackupStartupState.Ready) return
        shareImportViewModel.presentNext()
    }

    @VisibleForTesting
    internal fun shareImportStateForTesting(): ShareImportUiState = shareImportViewModel.state.value

    companion object {
        @VisibleForTesting
        internal const val EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING =
            "com.promenar.nexara.extra.ONBOARDING_EMPTY_MODELS_FOR_TESTING"
        @VisibleForTesting
        internal const val EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING =
            "com.promenar.nexara.extra.ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING"
        @VisibleForTesting
        internal const val EXTRA_CHAT_SESSION_ID_FOR_TESTING =
            "com.promenar.nexara.extra.CHAT_SESSION_ID_FOR_TESTING"
    }
}
