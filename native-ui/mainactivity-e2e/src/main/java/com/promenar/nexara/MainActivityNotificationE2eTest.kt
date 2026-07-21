package com.promenar.nexara

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private var scenarioIntentWasSanitized = false
    private var deviceInteractionState: DeviceInteractionState? = null

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
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                failures += error
            }
        }

        attempt { restoreDeviceInteractionState() }
        attempt {
            if (::scenario.isInitialized && !scenarioIntentWasSanitized) scenario.close()
        }
        // 通知 Open 会按生产逻辑把 Activity intent 清洗为 ACTION_MAIN。ActivityScenario 以原始
        // intent 匹配生命周期事件，此后 close() 会忽略真实 DESTROYED 并错误等待 45 秒；该路径
        // 由 instrumentation 进程退出和宿主下一阶段 pm clear 回收，避免把框架误判当产品失败。
        attempt { stopGenerationService() }
        attempt {
            app.getSystemService(NotificationManager::class.java)
                .cancel(GenerationNotificationFactory.NOTIFICATION_ID)
        }
        attempt {
            if (productionGenerationStateTouched) {
                mutableProductionGenerationState(targetSessionId)?.value = null
                app.generationCoordinator.release(targetSessionId, discardTerminal = true)
            }
        }
        attempt { app.sessionRepository.delete(currentSessionId) }
        attempt { app.sessionRepository.delete(targetSessionId) }
        attempt { app.chatStore.clear() }
        attempt { ProviderManager.getInstance().deleteModel(modelId) }

        failures.firstOrNull()?.let { primary ->
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
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
        assertNotificationPermission(granted = false)

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
        scenarioIntentWasSanitized = true
        posted.notification.contentIntent.send()

        compose.waitUntil(10_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == targetSessionId
        }
        compose.onNodeWithTag(UiTags.CHAT_STATE_READY).assertIsDisplayed()
        assertThat(app.createdChatViewModel?.uiState?.value?.session?.id)
            .isEqualTo(targetSessionId)
        captureDeviceScreenshot("notification-open-target-session.png")
    }

    @Test
    fun backgroundGenerationSurvivesRealLockWakeRotationAndRepeatedStopDoesNotRevive() {
        assertNotificationPermission(granted = true)
        val snapshot = GenerationTaskSnapshot(
            taskId = "notification-e2e-lifecycle-task",
            sessionId = targetSessionId,
            assistantMessageId = "notification-e2e-lifecycle-assistant",
            phase = GenerationPhase.STREAMING,
            generatedChars = 73,
            startedAt = System.currentTimeMillis(),
        )
        val productionState = requireNotNull(mutableProductionGenerationState(targetSessionId))
        productionGenerationStateTouched = true
        productionState.value = snapshot
        ContextCompat.startForegroundService(
            app,
            GenerationForegroundService.trackIntent(app, snapshot),
        )

        requireDeviceCondition("前台生成服务与通知未在时限内启动") {
            isGenerationServiceRunning() && generationNotifications().size == 1
        }
        assertSingleTrackedGeneration(snapshot)

        captureDeviceInteractionState()
        ensureLockScreenEnabled()
        putDeviceToSleep()
        requireDeviceCondition("锁屏后 MainActivity 仍停留在 RESUMED") {
            scenario.state != Lifecycle.State.RESUMED
        }
        assertSingleTrackedGeneration(snapshot)

        wakeDevice()
        assertSingleTrackedGeneration(snapshot)
        dismissKeyguard()
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.RESUMED }

        var activityBeforeRotation: MainActivity? = null
        scenario.onActivity { activityBeforeRotation = it }

        rotateDeviceToLandscape()
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.RESUMED }
        scenario.onActivity { current ->
            assertThat(current === activityBeforeRotation).isFalse()
        }
        activityBeforeRotation = null
        compose.waitUntil(10_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == currentSessionId
        }
        assertSingleTrackedGeneration(snapshot)

        val notification = generationNotifications().single().notification
        scenarioIntentWasSanitized = true
        notification.contentIntent.send()
        compose.waitUntil(10_000) {
            app.createdChatViewModel?.uiState?.value?.session?.id == targetSessionId
        }
        compose.onNodeWithTag(UiTags.CHAT_STATE_READY).assertIsDisplayed()
        assertThat(app.createdChatViewModel?.uiState?.value?.session?.id).isEqualTo(targetSessionId)

        val stopAction = notification.actions.single().actionIntent
        sendAndAwaitPendingIntent(stopAction)
        requireDeviceCondition("首次停止动作后服务或通知仍存在") {
            !isGenerationServiceRunning() && generationNotifications().isEmpty()
        }

        // 一次性 stop 已被消费；重放必须由系统拒绝，且不能重新创建服务或通知。
        val replayFailure = runCatching { sendAndAwaitPendingIntent(stopAction) }.exceptionOrNull()
        assertThat(replayFailure).isInstanceOf(PendingIntent.CanceledException::class.java)
        requireDeviceConditionRemains("重复停止动作重新创建了服务或通知") {
            !isGenerationServiceRunning() && generationNotifications().isEmpty()
        }
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
            if (handleBlockingQuickstepAnr(uiAutomation)) {
                SystemClock.sleep(500)
                continue
            }
            val button = findPermissionControllerButton(uiAutomation, resourceId)
            if (button != null && button.isEnabled) {
                captureDeviceScreenshot(screenshotName)
                val refreshedButton = findPermissionControllerButton(uiAutomation, resourceId)
                if (refreshedButton?.isEnabled == true &&
                    refreshedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
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

    private fun handleBlockingQuickstepAnr(uiAutomation: UiAutomation): Boolean {
        val blockingRoot = permissionControllerRoots(uiAutomation).firstOrNull { root ->
            root.packageName == SYSTEM_PACKAGE &&
                root.findAccessibilityNodeInfosByViewId(SYSTEM_ALERT_TITLE)?.any { title ->
                    title.text?.contains(QUICKSTEP_APP_NAME, ignoreCase = true) == true
                } == true
        } ?: return false
        val waitButton = blockingRoot.findAccessibilityNodeInfosByViewId(SYSTEM_ANR_WAIT_BUTTON)
            ?.firstOrNull { it.isEnabled }
        waitButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
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

    private fun assertSingleTrackedGeneration(snapshot: GenerationTaskSnapshot) {
        val observed = requireNotNull(
            requireNotNull(mutableProductionGenerationState(snapshot.sessionId)).value,
        )
        assertThat(observed.taskId).isEqualTo(snapshot.taskId)
        assertThat(observed.sessionId).isEqualTo(snapshot.sessionId)
        assertThat(isGenerationServiceRunning()).isTrue()
        val notifications = generationNotifications()
        assertThat(notifications).hasSize(1)
        val notification = notifications.single().notification
        assertThat(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.actions).hasLength(1)
    }

    private fun generationNotifications() = app.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .filter { it.id == GenerationNotificationFactory.NOTIFICATION_ID }

    private fun captureDeviceInteractionState() {
        check(deviceInteractionState == null) { "设备交互状态只能在单个用例中捕获一次" }
        val state = DeviceInteractionState(
            accelerometerRotation = readSystemSetting("accelerometer_rotation"),
            userRotation = readSystemSetting("user_rotation"),
            displayRotation = displayRotation(),
            wasInteractive = powerManager().isInteractive,
            wasKeyguardLocked = keyguardManager().isKeyguardLocked,
            lockScreenDisabled = readLockScreenDisabled(),
        )
        check(state.wasInteractive) { "测试启动后设备并未处于可交互状态；${deviceStateEvidence()}" }
        check(!state.wasKeyguardLocked) { "测试启动后设备仍处于锁屏状态；${deviceStateEvidence()}" }
        deviceInteractionState = state
    }

    private fun ensureLockScreenEnabled() {
        if (deviceInteractionState?.lockScreenDisabled != true) return
        runDeviceShell("locksettings set-disabled false")
        check(!readLockScreenDisabled()) { "无法为测试启用系统锁屏；${deviceStateEvidence()}" }
    }

    private fun putDeviceToSleep() {
        runDeviceShell("input keyevent KEYCODE_SLEEP")
        requireDeviceCondition("设备未在时限内进入锁屏/熄屏状态") {
            !powerManager().isInteractive && keyguardManager().isKeyguardLocked
        }
    }

    private fun wakeDevice() {
        runDeviceShell("input keyevent KEYCODE_WAKEUP")
        requireDeviceCondition("设备未在时限内从锁屏/熄屏状态唤醒") {
            powerManager().isInteractive && keyguardManager().isKeyguardLocked
        }
    }

    private fun dismissKeyguard() {
        runDeviceShell("wm dismiss-keyguard")
        requireDeviceCondition("设备唤醒后无法退出锁屏界面") {
            powerManager().isInteractive && !keyguardManager().isKeyguardLocked
        }
    }

    private fun rotateDeviceToLandscape() {
        check(uiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_90)) {
            "UiAutomation 未接受横屏冻结命令；${deviceStateEvidence()}"
        }
        requireDeviceCondition("设备未在时限内完成真实横屏旋转") {
            displayRotation() == Surface.ROTATION_90
        }
    }

    private fun restoreDeviceInteractionState() {
        val state = deviceInteractionState ?: return
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                failures += error
            }
        }

        attempt {
            check(uiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE)) {
                "UiAutomation 未接受旋转解冻命令；${deviceStateEvidence()}"
            }
        }
        attempt {
            runDeviceShell("wm user-rotation lock ${state.displayRotation}")
            requireDeviceCondition("无法恢复测试前的屏幕方向") {
                displayRotation() == state.displayRotation
            }
        }
        attempt { restoreSystemSetting("user_rotation", state.userRotation) }
        attempt { restoreSystemSetting("accelerometer_rotation", state.accelerometerRotation) }
        attempt {
            if (state.accelerometerRotation == "1") runDeviceShell("wm user-rotation free")
        }
        attempt {
            if (!powerManager().isInteractive) {
                runDeviceShell("input keyevent KEYCODE_WAKEUP")
                requireDeviceCondition("无法恢复测试前的亮屏状态") { powerManager().isInteractive }
            }
        }
        attempt {
            if (!state.wasKeyguardLocked && keyguardManager().isKeyguardLocked) dismissKeyguard()
        }
        attempt {
            runDeviceShell("locksettings set-disabled ${state.lockScreenDisabled}")
            check(readLockScreenDisabled() == state.lockScreenDisabled) {
                "无法恢复测试前的锁屏启用状态；${deviceStateEvidence()}"
            }
        }

        deviceInteractionState = null
        failures.firstOrNull()?.let { primary ->
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    private fun restoreSystemSetting(key: String, value: String) {
        if (value.isBlank() || value == "null") {
            runDeviceShell("settings delete system $key")
        } else {
            runDeviceShell("settings put system $key $value")
        }
    }

    private fun readSystemSetting(key: String): String =
        runDeviceShell("settings get system $key")

    private fun readLockScreenDisabled(): Boolean = when (val value = runDeviceShell("locksettings get-disabled")) {
        "true" -> true
        "false" -> false
        else -> error("无法读取系统锁屏启用状态：$value")
    }

    private fun requireDeviceCondition(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + DEVICE_OPERATION_TIMEOUT_MILLIS
        do {
            if (predicate()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(predicate()) { "$description；${deviceStateEvidence()}" }
    }

    private fun requireDeviceConditionRemains(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + DEVICE_QUIET_WINDOW_MILLIS
        do {
            check(predicate()) { "$description；${deviceStateEvidence()}" }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(predicate()) { "$description；${deviceStateEvidence()}" }
    }

    private fun sendAndAwaitPendingIntent(pendingIntent: PendingIntent) {
        val finished = AtomicBoolean(false)
        pendingIntent.send(
            app,
            0,
            null,
            PendingIntent.OnFinished { _, _, _, _, _ -> finished.set(true) },
            Handler(Looper.getMainLooper()),
        )
        requireDeviceCondition("PendingIntent 未在时限内完成系统投递") { finished.get() }
    }

    private fun deviceStateEvidence(): String =
        "interactive=${powerManager().isInteractive}, rotation=${displayRotation()}, " +
            "keyguardLocked=${keyguardManager().isKeyguardLocked}, " +
            "lockScreenDisabled=${runDeviceShell("locksettings get-disabled")}, " +
            "power=${runDeviceShell("dumpsys power").take(1_000)}, " +
            "wm=${runDeviceShell("wm user-rotation").take(300)}"

    private fun displayRotation(): Int {
        var rotation: Int? = null
        scenario.onActivity { activity -> rotation = activity.display.rotation }
        return requireNotNull(rotation)
    }

    private fun powerManager(): PowerManager = app.getSystemService(PowerManager::class.java)

    private fun keyguardManager(): KeyguardManager = app.getSystemService(KeyguardManager::class.java)

    private fun runDeviceShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation().executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }

    private fun uiAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

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
        check(!isGenerationServiceRunning()) { "前台生成服务未在清理时限内停止" }
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
        private const val DEVICE_OPERATION_TIMEOUT_MILLIS = 10_000L
        private const val DEVICE_QUIET_WINDOW_MILLIS = 1_000L
        private const val PERMISSION_CONTROLLER_PACKAGE = "com.android.permissioncontroller"
        private const val PERMISSION_ALLOW_BUTTON =
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_allow_button"
        private const val PERMISSION_DENY_BUTTON =
            "$PERMISSION_CONTROLLER_PACKAGE:id/permission_deny_button"
        private const val SYSTEM_ALERT_TITLE = "android:id/alertTitle"
        private const val SYSTEM_ANR_WAIT_BUTTON = "android:id/aerr_wait"
        private const val SYSTEM_PACKAGE = "android"
        private const val QUICKSTEP_APP_NAME = "Quickstep"

        internal fun isPermissionControllerPackage(packageName: CharSequence?): Boolean =
            packageName?.endsWith("permissioncontroller") == true
    }

    private data class DeviceInteractionState(
        val accelerometerRotation: String,
        val userRotation: String,
        val displayRotation: Int,
        val wasInteractive: Boolean,
        val wasKeyguardLocked: Boolean,
        val lockScreenDisabled: Boolean,
    )
}
