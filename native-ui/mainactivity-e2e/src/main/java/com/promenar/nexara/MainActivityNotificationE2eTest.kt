package com.promenar.nexara

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_ASKED
import com.promenar.nexara.background.generation.GENERATION_NOTIFICATION_PERMISSION_PREFS
import com.promenar.nexara.background.generation.GenerationForegroundService
import com.promenar.nexara.background.generation.GenerationNotificationFactory
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.stableModelId
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.testing.UiTags
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNotificationE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: MainActivityE2eApplication = ApplicationProvider.getApplicationContext()
    private val currentSessionId = "notification-e2e-current"
    private val targetSessionId = "notification-e2e-target"
    private val modelId = stableModelId("notification-e2e-provider", "model")
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var productionGenerationStateTouched = false

    @Before
    fun setUp(): Unit = runBlocking {
        waitForStartupReady()
        app.resetE2eObservations()
        stopGenerationService()
        prepareModel()
        seedSessions()
        app.getSharedPreferences("nexara_onboarding", Context.MODE_PRIVATE)
            .edit().putString("step", OnboardingStep.COMPLETED.name).commit()
        app.getSharedPreferences(GENERATION_NOTIFICATION_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit().remove(GENERATION_NOTIFICATION_PERMISSION_ASKED).commit()
        assertThat(permissionWasAsked()).isFalse()

        val intent = Intent(app, MainActivity::class.java).apply {
            putExtra("com.promenar.nexara.extra.CHAT_SESSION_ID_FOR_TESTING", currentSessionId)
        }
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(15_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == currentSessionId
        }
        waitForChatReadySemantics()
        Unit
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (::scenario.isInitialized && !productionGenerationStateTouched) {
            scenario.close()
        }
        // 通知 Open 会按生产逻辑把 Activity intent 清洗为 ACTION_MAIN。ActivityScenario 以原始
        // intent 匹配生命周期事件，此后 close() 会忽略真实 DESTROYED 并错误等待 45 秒；该路径
        // 由 instrumentation 进程退出和宿主下一阶段 pm clear 回收，避免把框架误判当产品失败。
        stopGenerationService()
        app.getSystemService(NotificationManager::class.java)
            .cancel(GenerationNotificationFactory.NOTIFICATION_ID)
        if (productionGenerationStateTouched) {
            mutableProductionGenerationState(targetSessionId)?.value = null
            app.generationCoordinator.release(targetSessionId, discardTerminal = true)
        }
        app.sessionRepository.delete(currentSessionId)
        app.sessionRepository.delete(targetSessionId)
        app.chatStore.clear()
        ProviderManager.getInstance().deleteModel(modelId)
        Unit
    }

    @Test
    fun denyingSystemNotificationPermissionContinuesForegroundOnlyWithoutFgs() {
        assertNotificationPermission(granted = false)

        sendFirstMessage()
        waitForNotificationPermissionDialog()
        assertThat(app.recordingCoordinator.requests).isEmpty()

        compose.onNodeWithTag(UiTags.NOTIFICATION_PERMISSION_CONTINUE).performClick()
        assertThat(
            clickPermissionControllerButton(
                resourceId = PERMISSION_DENY_BUTTON,
                screenshotName = "notification-permission-deny-dialog.png",
            ),
        ).isTrue()

        compose.waitUntil(10_000) { app.recordingCoordinator.requests.size == 1 }
        assertThat(app.recordingCoordinator.requests.single().runtimePolicy)
            .isEqualTo(GenerationRuntimePolicy.FOREGROUND_ONLY)
        assertThat(isGenerationServiceRunning()).isFalse()
        assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        assertThat(permissionWasAsked()).isTrue()
        waitForPermissionExplanationToClose()
        captureDeviceScreenshot("notification-permission-denied-foreground.png")
    }

    @Test
    fun grantingSystemNotificationPermissionContinuesBackgroundAllowed() {
        assertNotificationPermission(granted = false)

        sendFirstMessage()
        waitForNotificationPermissionDialog()
        assertThat(app.recordingCoordinator.requests).isEmpty()

        compose.onNodeWithTag(UiTags.NOTIFICATION_PERMISSION_CONTINUE).performClick()
        assertThat(
            clickPermissionControllerButton(
                resourceId = PERMISSION_ALLOW_BUTTON,
                screenshotName = "notification-permission-grant-dialog.png",
            ),
        ).isTrue()

        compose.waitUntil(10_000) { app.recordingCoordinator.requests.size == 1 }
        assertThat(app.recordingCoordinator.requests.single().runtimePolicy)
            .isEqualTo(GenerationRuntimePolicy.BACKGROUND_ALLOWED)
        assertNotificationPermission(granted = true)
        assertThat(permissionWasAsked()).isTrue()
        waitForPermissionExplanationToClose()
        captureDeviceScreenshot("notification-permission-granted.png")
    }

    @Test
    fun postedNotificationOpenPendingIntentReturnsToExactSession() {
        assertNotificationPermission(granted = true)
        val snapshot = GenerationTaskSnapshot(
            taskId = "notification-e2e-task",
            sessionId = targetSessionId,
            assistantMessageId = "notification-e2e-target-assistant",
            phase = GenerationPhase.STREAMING,
            generatedChars = 17,
            startedAt = System.currentTimeMillis(),
        )
        val productionState = requireNotNull(mutableProductionGenerationState(targetSessionId))
        productionGenerationStateTouched = true
        productionState.value = snapshot
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.notify(
            GenerationNotificationFactory.NOTIFICATION_ID,
            GenerationNotificationFactory(app, app.appIntentRouter).create(snapshot),
        )

        compose.waitUntil(10_000) {
            notificationManager.activeNotifications.any {
                it.id == GenerationNotificationFactory.NOTIFICATION_ID
            }
        }
        val posted = notificationManager.activeNotifications.single {
            it.id == GenerationNotificationFactory.NOTIFICATION_ID
        }
        posted.notification.contentIntent.send()

        compose.waitUntil(10_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == targetSessionId
        }
        compose.onNodeWithTag(UiTags.CHAT_STATE_READY).assertIsDisplayed()
        assertThat(app.createdChatViewModel?.uiState?.value?.session?.id)
            .isEqualTo(targetSessionId)
        captureDeviceScreenshot("notification-open-target-session.png")
    }

    private fun sendFirstMessage() {
        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("notification permission route")
        compose.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION).performClick()
        compose.waitForIdle()
    }

    private fun waitForChatReadySemantics() {
        compose.waitUntil(15_000) {
            runCatching {
                compose.onAllNodesWithTag(UiTags.CHAT_STATE_READY)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(UiTags.CHAT_STATE_READY).assertIsDisplayed()
    }

    private fun clickPermissionControllerButton(
        resourceId: String,
        screenshotName: String,
    ): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        enableInteractiveWindowRetrieval(uiAutomation)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val button = findPermissionControllerButton(uiAutomation, resourceId)
            if (button != null && button.isEnabled) {
                SystemClock.sleep(500)
                captureDeviceScreenshot(screenshotName)
                return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        captureDeviceScreenshot(notFoundScreenshotName(screenshotName))
        return false
    }

    private fun findPermissionControllerButton(
        uiAutomation: UiAutomation,
        resourceId: String,
    ): AccessibilityNodeInfo? =
        permissionControllerRoots(uiAutomation).firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByViewId(resourceId)?.firstOrNull { it.isEnabled }
        }

    private fun permissionControllerRoots(uiAutomation: UiAutomation): List<AccessibilityNodeInfo> {
        val roots = uiAutomation.windows.orEmpty().mapNotNull { it.root }.toMutableList()
        uiAutomation.rootInActiveWindow?.let(roots::add)
        return roots
            .distinctBy { it.windowId }
            .sortedByDescending { isPermissionControllerPackage(it.packageName) }
    }

    private fun enableInteractiveWindowRetrieval(uiAutomation: UiAutomation) {
        val serviceInfo = uiAutomation.serviceInfo
        if (serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            serviceInfo.flags =
                serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = serviceInfo
        }
    }

    private fun notFoundScreenshotName(screenshotName: String): String =
        screenshotName.substringBeforeLast('.') + "-not-found.png"

    private fun waitForPermissionExplanationToClose() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.NOTIFICATION_PERMISSION_DIALOG)
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        SystemClock.sleep(200)
    }

    private fun waitForNotificationPermissionDialog() {
        compose.waitUntil(10_000) {
            app.createdChatViewModel?.notificationPermissionRequests?.value != null
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(UiTags.NOTIFICATION_PERMISSION_DIALOG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.NOTIFICATION_PERMISSION_DIALOG).assertIsDisplayed()
    }

    private fun captureDeviceScreenshot(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertThat(screenshot).isNotNull()
        val output = requireNotNull(app.getExternalFilesDir(null)).resolve(name)
        FileOutputStream(output).use { stream ->
            assertThat(requireNotNull(screenshot).compress(Bitmap.CompressFormat.PNG, 100, stream)).isTrue()
        }
    }

    private fun assertNotificationPermission(granted: Boolean) {
        val actual = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        assertThat(actual).isEqualTo(granted)
    }

    private fun permissionWasAsked(): Boolean = app.getSharedPreferences(
        GENERATION_NOTIFICATION_PERMISSION_PREFS,
        Context.MODE_PRIVATE,
    ).getBoolean(GENERATION_NOTIFICATION_PERMISSION_ASKED, false)

    @Suppress("UNCHECKED_CAST")
    private fun mutableProductionGenerationState(
        sessionId: String,
    ): MutableStateFlow<GenerationTaskSnapshot?>? =
        app.generationCoordinator.observe(sessionId) as? MutableStateFlow<GenerationTaskSnapshot?>

    @Suppress("DEPRECATION")
    private fun isGenerationServiceRunning(): Boolean =
        app.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GenerationForegroundService::class.java.name }

    private fun stopGenerationService() {
        app.stopService(Intent(app, GenerationForegroundService::class.java))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (isGenerationServiceRunning() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
    }

    private suspend fun waitForStartupReady() {
        repeat(200) {
            if (app.startupState.value == com.promenar.nexara.data.backup.BackupStartupState.Ready) {
                return
            }
            delay(50)
        }
        error("NexaraApplication startup did not become Ready: ${app.startupState.value}")
    }

    private fun prepareModel() {
        val manager = ProviderManager.getInstance()
        manager.providerModels.value.filter { it.id == modelId }.forEach { manager.deleteModel(it.id) }
        manager.addModel(
            ModelInfo(
                name = "Notification E2E Model",
                id = modelId,
                remoteModelId = "model",
                description = "Notification device E2E fixture",
                enabled = true,
                type = "chat",
                capabilities = listOf("chat"),
                providerName = "Notification E2E Provider",
                providerId = "notification-e2e-provider",
            ),
        )
    }

    private suspend fun seedSessions() {
        app.sessionRepository.delete(currentSessionId)
        app.sessionRepository.delete(targetSessionId)
        val current = sessionFixture(currentSessionId, "Current notification session")
        val target = sessionFixture(targetSessionId, "Target notification session")
        listOf(current, target).forEach { session ->
            app.sessionRepository.create(session.copy(messages = emptyList()))
            session.messages.forEach { message -> app.messageRepository.insert(message, session.id) }
        }
        app.chatStore.update { ChatState(sessions = listOf(current, target)) }
    }

    private fun sessionFixture(id: String, title: String): Session {
        val assistant = Message(
            id = "$id-assistant",
            role = MessageRole.ASSISTANT,
            content = "fixture response",
            modelId = modelId,
        )
        return Session(
            id = id,
            agentId = "notification-e2e-agent",
            title = title,
            modelId = modelId,
            messages = listOf(assistant),
        )
    }

    companion object {
        private const val PERMISSION_CONTROLLER_PACKAGE = "com.android.permissioncontroller"
        private const val PERMISSION_ALLOW_BUTTON =
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_allow_button"
        private const val PERMISSION_DENY_BUTTON =
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_deny_button"

        internal fun isPermissionControllerPackage(packageName: CharSequence?): Boolean =
            packageName?.endsWith("permissioncontroller") == true
    }
}
