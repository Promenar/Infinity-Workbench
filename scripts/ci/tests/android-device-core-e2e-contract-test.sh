#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEVICE_SCRIPT="${REPO_ROOT}/scripts/ci/android-device-core-e2e.sh"
APP_BUILD="${REPO_ROOT}/native-ui/app/build.gradle.kts"
MAIN_ACTIVITY_E2E_BUILD="${REPO_ROOT}/native-ui/mainactivity-e2e/build.gradle.kts"
ONBOARDING_E2E_TEST="${REPO_ROOT}/native-ui/app/src/androidTest/java/com/promenar/nexara/onboarding/OnboardingAndroidEndToEndTest.kt"
WELCOME_LAYOUT_TEST="${REPO_ROOT}/native-ui/app/src/androidTest/java/com/promenar/nexara/onboarding/WelcomeScreenLayoutTest.kt"
RESTORE_RELAY_E2E_TEST="${REPO_ROOT}/native-ui/app/src/androidTest/java/com/promenar/nexara/data/backup/AndroidRestoreRelayEndToEndTest.kt"
NOTIFICATION_E2E_TEST="${REPO_ROOT}/native-ui/mainactivity-e2e/src/main/java/com/promenar/nexara/MainActivityNotificationE2eTest.kt"
NEW_SESSION_E2E_TEST="${REPO_ROOT}/native-ui/mainactivity-e2e/src/main/java/com/promenar/nexara/MainActivityNewSessionE2eTest.kt"
TIMEOUT_HELPER="${REPO_ROOT}/scripts/ci/run-with-timeout.py"

fail() {
    echo "设备 CI 契约断言失败：$*" >&2
    exit 1
}

assert_contains() {
    local file="$1"
    local expected="$2"
    grep -Fq -- "${expected}" "${file}" || fail "${file} 缺少 ${expected}"
}

assert_count() {
    local file="$1"
    local expected="$2"
    local count="$3"
    local actual
    actual="$(grep -Fc -- "${expected}" "${file}" || true)"
    [[ "${actual}" == "${count}" ]] || fail "${file} 中 ${expected} 应出现 ${count} 次，实际 ${actual} 次"
}

assert_not_contains() {
    local file="$1"
    local expected="$2"
    if grep -Fq -- "${expected}" "${file}"; then
        fail "${file} 不应包含 ${expected}"
    fi
}

ANDROID_CI_WORKFLOW="${REPO_ROOT}/.github/workflows/android-ci.yml"
RELEASE_WORKFLOW="${REPO_ROOT}/.github/workflows/release.yml"

# 先锁定 white-box 脚本边界，确保旧 minified instrumentation 架构给出直接 RED。
assert_not_contains "${DEVICE_SCRIPT}" 'NEXARA_DEVICE_E2E_BUILD_TYPE'
assert_not_contains "${DEVICE_SCRIPT}" 'MinifiedTest'
assert_not_contains "${DEVICE_SCRIPT}" 'minifiedTest'

for workflow in "${ANDROID_CI_WORKFLOW}" "${RELEASE_WORKFLOW}"; do
    assert_count "${workflow}" "- api-level: 31" 1
    assert_contains "${workflow}" 'NEXARA_DEVICE_E2E_SCOPE: ${{ matrix.e2e-scope }}'
    assert_contains "${workflow}" 'artifacts/android-device-api-${{ matrix.api-level }}-x86_64/'
done
assert_count "${ANDROID_CI_WORKFLOW}" "e2e-scope: minimum" 1
assert_count "${ANDROID_CI_WORKFLOW}" "e2e-scope: full" 2
assert_count "${RELEASE_WORKFLOW}" "e2e-scope: minimum" 1
assert_count "${RELEASE_WORKFLOW}" "e2e-scope: full" 2

assert_count "${ANDROID_CI_WORKFLOW}" "- api-level: 35" 1
assert_count "${ANDROID_CI_WORKFLOW}" "- api-level: 36" 1
assert_count "${RELEASE_WORKFLOW}" "- api-level: 31" 1
assert_count "${RELEASE_WORKFLOW}" "- api-level: 35" 3
assert_count "${RELEASE_WORKFLOW}" "- api-level: 36" 3
assert_contains "${RELEASE_WORKFLOW}" "签名 R8 APK 无密钥冷安装 smoke"

assert_contains "${DEVICE_SCRIPT}" 'NEXARA_DEVICE_E2E_SCOPE:-full'
assert_contains "${DEVICE_SCRIPT}" 'BUILD_TYPE_TASK="DeviceTest"'
artifact_resolution_block="$(sed -n '/^ARTIFACT_DIR_INPUT=/,/^INSTRUMENT_TIMEOUT_SECONDS=/p' "${DEVICE_SCRIPT}")"
[[ -n "${artifact_resolution_block}" ]] || fail "设备 E2E 必须先保存 ARTIFACT_DIR_INPUT 再规范路径"
for artifact_path_contract in \
    'ARTIFACT_DIR_INPUT="${ANDROID_E2E_ARTIFACT_DIR:-artifacts/android-device-api-${API_LEVEL:-unknown}-${DEVICE_E2E_ABI}}"' \
    'if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then' \
    'ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"' \
    'ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"'; do
    grep -Fq -- "${artifact_path_contract}" <<<"${artifact_resolution_block}" ||
        fail "artifact 路径规范块缺少 ${artifact_path_contract}"
done
artifact_input_line="$(grep -n '^ARTIFACT_DIR_INPUT=' "${DEVICE_SCRIPT}" | cut -d: -f1)"
native_cd_line="$(grep -n '^[[:space:]]*cd "${NATIVE_ROOT}"' "${DEVICE_SCRIPT}" | head -n 1 | cut -d: -f1)"
[[ -n "${artifact_input_line}" && -n "${native_cd_line}" && "${artifact_input_line}" -lt "${native_cd_line}" ]] ||
    fail "artifact 目录必须在进入 native-ui 前完成绝对路径规范化"
assert_not_contains "${DEVICE_SCRIPT}" 'NEXARA_DEVICE_E2E_BUILD_TYPE'
assert_not_contains "${DEVICE_SCRIPT}" 'MinifiedTest'
assert_not_contains "${DEVICE_SCRIPT}" 'minifiedTest'
assert_contains "${DEVICE_SCRIPT}" 'minimum|full'
assert_contains "${DEVICE_SCRIPT}" 'target=${TARGET_PACKAGE}'
assert_contains "${DEVICE_SCRIPT}" 'app-deviceTest.apk'
assert_contains "${DEVICE_SCRIPT}" 'app-deviceTest-androidTest.apk'
assert_contains "${DEVICE_SCRIPT}" 'mainactivity-e2e-deviceTest.apk'
assert_contains "${DEVICE_SCRIPT}" ':app:assemble${BUILD_TYPE_TASK}'
assert_contains "${DEVICE_SCRIPT}" 'com.promenar.nexara.ui.AccessibilitySmokeTest'
assert_contains "${DEVICE_SCRIPT}" 'com.promenar.nexara.ui.AdaptiveNavigationTest'
assert_contains "${DEVICE_SCRIPT}" 'com.promenar.nexara.onboarding.WelcomeScreenLayoutTest'
assert_contains "${DEVICE_SCRIPT}" 'run_test document-parser-fixtures "${APP_RUNNER}"'
assert_contains "${DEVICE_SCRIPT}" 'com.promenar.nexara.data.rag.DocumentParserDeviceE2eTest'
assert_contains "${DEVICE_SCRIPT}" 'run_test mainactivity-new-session "${MAIN_ACTIVITY_RUNNER}"'
assert_contains "${DEVICE_SCRIPT}" 'com.promenar.nexara.MainActivityNewSessionE2eTest'
assert_contains "${DEVICE_SCRIPT}" 'restore_device_display_state'
assert_contains "${DEVICE_SCRIPT}" 'if [[ "${DEVICE_E2E_SCOPE}" == "minimum" ]]'
assert_contains "${DEVICE_SCRIPT}" 'run_expected_relay_death stageNoKey'
assert_contains "${DEVICE_SCRIPT}" 'run_expected_relay_death stage'
assert_contains "${DEVICE_SCRIPT}" 'run_test restore-relay-export-no-key'
assert_contains "${DEVICE_SCRIPT}" 'run_test restore-relay-verify-no-key'
assert_contains "${DEVICE_SCRIPT}" 'run_test restore-relay-no-replay-no-key'
assert_contains "${DEVICE_SCRIPT}" 'restore-relay-${phase}-after-pids.txt'
assert_contains "${DEVICE_SCRIPT}" 'restore-relay-${phase}-expected-death.txt'
assert_contains "${DEVICE_SCRIPT}" 'restore-relay-${phase}-relay-logcat.txt'
assert_contains "${DEVICE_SCRIPT}" 'adb logcat -b main -c'
assert_contains "${DEVICE_SCRIPT}" 'adb logcat -b main -d -v threadtime -s NexaraLogger:D'
assert_contains "${DEVICE_SCRIPT}" '\[NexaraRestoreRelay\] relay_pid=[0-9]+ main_pid=[0-9]+ payload=none'
assert_contains "${DEVICE_SCRIPT}" '-e restoreRelayPhase exportNoKey'
assert_contains "${DEVICE_SCRIPT}" '-e restoreRelayPhase verifyNoKey'
assert_contains "${DEVICE_SCRIPT}" '-e restoreRelayPhase noReplayNoKey'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'Process.myPid()).isNotEqualTo(proof(app).getInt(KEY_STAGE_PID, -1))'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'PHASE_EXPORT_NO_KEY'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'PHASE_STAGE_NO_KEY'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'PHASE_VERIFY_NO_KEY'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'PHASE_NO_REPLAY_NO_KEY'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'KEY_NO_KEY_TX_ID'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'validated.manifest.encrypted).isFalse()'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'validated.manifest.containsSecrets).isFalse()'
assert_contains "${APP_BUILD}" 'val allowedE2eBuildTypes = setOf("debug", "deviceTest")'
assert_contains "${APP_BUILD}" '.orElse(if (deviceE2eEnabled) "deviceTest" else "debug")'
assert_contains "${APP_BUILD}" 'require(!deviceE2eEnabled || selectedDeviceE2eBuildType == "deviceTest")'
assert_contains "${APP_BUILD}" '启用 nexara.deviceE2e 时，nexara.deviceE2eTestBuildType 必须为 deviceTest'
assert_contains "${APP_BUILD}" 'testBuildType = selectedDeviceE2eBuildType'
assert_contains "${MAIN_ACTIVITY_E2E_BUILD}" 'create("deviceTest")'
assert_not_contains "${MAIN_ACTIVITY_E2E_BUILD}" 'create("minifiedTest")'
assert_not_contains "${MAIN_ACTIVITY_E2E_BUILD}" 'testProguardFiles'
[[ ! -e "${REPO_ROOT}/native-ui/mainactivity-e2e/proguard-rules.pro" ]] ||
    fail "mainactivity-e2e 不应保留 minifiedTest 专用 ProGuard 文件"
assert_contains "${DEVICE_SCRIPT}" 'run-with-timeout.py'
assert_contains "${DEVICE_SCRIPT}" 'INSTRUMENT_TIMEOUT_SECONDS'
assert_contains "${DEVICE_SCRIPT}" 'python3 "${TIMEOUT_HELPER}"'
assert_contains "${DEVICE_SCRIPT}" 'adb shell cmd package wait-for-handler --timeout 5000'
assert_contains "${DEVICE_SCRIPT}" 'adb shell cmd package wait-for-background-handler --timeout 5000'
assert_contains "${DEVICE_SCRIPT}" 'if (( API_LEVEL >= 33 )); then'
assert_contains "${DEVICE_SCRIPT}" 'sleep 1'
assert_count "${DEVICE_SCRIPT}" 'wait_for_package_manager_idle' 5
assert_not_contains "${DEVICE_SCRIPT}" "\"${TIMEOUT_HELPER}\" adb"
assert_not_contains "${DEVICE_SCRIPT}" "'${TIMEOUT_HELPER}' adb"
assert_not_contains "${DEVICE_SCRIPT}" 'timeout "${INSTRUMENT_TIMEOUT_SECONDS}s"'
assert_contains "${DEVICE_SCRIPT}" 'run_notification_permission_contracts() {'
assert_contains "${DEVICE_SCRIPT}" 'pull_notification_permission_screenshots() {'
assert_contains "${DEVICE_SCRIPT}" 'run_notification_permission_test() {'
assert_contains "${DEVICE_SCRIPT}" 'run_test "${stage}" "${MAIN_ACTIVITY_RUNNER}" "$@" || test_status=$?'
assert_contains "${DEVICE_SCRIPT}" 'pull_notification_permission_screenshots "${stage}" "${expected_name}" || pull_status=$?'
assert_contains "${DEVICE_SCRIPT}" 'if (( pull_status == 0 )); then'
assert_contains "${DEVICE_SCRIPT}" 'if (( test_status != 0 )); then'
assert_contains "${DEVICE_SCRIPT}" 'PENDING_NOTIFICATION_SCREENSHOT_STAGE'
assert_contains "${DEVICE_SCRIPT}" 'pull_notification_permission_screenshots \'
assert_contains "${DEVICE_SCRIPT}" 'if ! remote_files="$(adb shell find "${remote_dir}" -maxdepth 1 -type f -name '\''*.png'\'' -print 2>&1)"; then'
assert_contains "${DEVICE_SCRIPT}" 'if ! adb pull "${remote_file}" "${ARTIFACT_DIR}/${stage}-$(basename "${remote_file}")" >/dev/null; then'
assert_contains "${DEVICE_SCRIPT}" 'pull_failed=true'
assert_contains "${DEVICE_SCRIPT}" 'if [[ "${pull_failed}" == "true" ]]; then'
assert_contains "${DEVICE_SCRIPT}" 'grep -Fxq -- "${remote_dir}/${expected_name}"'
assert_contains "${DEVICE_SCRIPT}" 'if (( pulled == 0 )); then'
assert_contains "${DEVICE_SCRIPT}" 'notification-permission-deny notification-permission-deny-dialog.png'
assert_contains "${DEVICE_SCRIPT}" 'notification-permission-grant notification-permission-grant-dialog.png'
permission_pull_line="$(grep -n 'if ! adb pull "${remote_file}"' "${DEVICE_SCRIPT}" | cut -d: -f1)"
permission_expected_line="$(grep -n 'grep -Fxq -- "${remote_dir}/${expected_name}"' "${DEVICE_SCRIPT}" | cut -d: -f1)"
[[ -n "${permission_pull_line}" && -n "${permission_expected_line}" &&
    "${permission_pull_line}" -lt "${permission_expected_line}" ]] ||
    fail "宿主 ANR 失败证据必须在预期 dialog 完整性检查前回拉"
assert_contains "${DEVICE_SCRIPT}" 'run_notification_open_session_contract() {'
assert_contains "${DEVICE_SCRIPT}" 'run_notification_background_lifecycle_contract() {'
assert_contains "${DEVICE_SCRIPT}" 'prepare_target_for_foreground_notification() {'
assert_contains "${DEVICE_SCRIPT}" $'prepare_target_for_foreground_notification\nrun_test generation-foreground-service'
assert_contains "${DEVICE_SCRIPT}" $'prepare_target_for_foreground_notification\n    run_test notification-background-lifecycle'
assert_contains "${DEVICE_SCRIPT}" $'if (( API_LEVEL >= 33 )); then\n        run_notification_permission_contracts\n    else\n        echo "API ${API_LEVEL} 无运行时通知权限，跳过 Android 13+ 权限弹窗 E2E。"\n    fi\n    run_notification_open_session_contract'
assert_contains "${DEVICE_SCRIPT}" $'run_notification_open_session_contract\n    run_notification_background_lifecycle_contract'

# 设备夹具必须服从异步启动闸门，且系统权限按钮不能只依赖当前活动窗口。
assert_contains "${ONBOARDING_E2E_TEST}" 'waitForStartupReady()'
startup_wait_line="$(grep -n 'waitForStartupReady()' "${ONBOARDING_E2E_TEST}" | head -n 1 | cut -d: -f1)"
provider_snapshot_line="$(grep -n 'originalProviderSummary = ProviderManager.getInstance()' "${ONBOARDING_E2E_TEST}" | head -n 1 | cut -d: -f1)"
[[ -n "${startup_wait_line}" && -n "${provider_snapshot_line}" && "${startup_wait_line}" -lt "${provider_snapshot_line}" ]] ||
    fail "onboarding 设备夹具必须先等待启动 Ready，再读取 ProviderManager"
assert_contains "${NOTIFICATION_E2E_TEST}" 'uiAutomation.windows'
assert_contains "${NOTIFICATION_E2E_TEST}" 'FLAG_RETRIEVE_INTERACTIVE_WINDOWS'
assert_contains "${NOTIFICATION_E2E_TEST}" 'waitForChatReadySemantics()'
assert_contains "${NOTIFICATION_E2E_TEST}" 'runCatching {'
assert_contains "${NOTIFICATION_E2E_TEST}" 'backgroundGenerationSurvivesRealLockWakeRotationAndRepeatedStopDoesNotRevive'
assert_contains "${NOTIFICATION_E2E_TEST}" 'input keyevent KEYCODE_SLEEP'
assert_contains "${NOTIFICATION_E2E_TEST}" 'input keyevent KEYCODE_WAKEUP'
assert_contains "${NOTIFICATION_E2E_TEST}" 'keyguardManager().isKeyguardLocked'
assert_contains "${NOTIFICATION_E2E_TEST}" 'locksettings set-disabled false'
assert_contains "${NOTIFICATION_E2E_TEST}" 'locksettings set-disabled ${state.lockScreenDisabled}'
assert_contains "${NOTIFICATION_E2E_TEST}" 'wm dismiss-keyguard'
assert_contains "${NOTIFICATION_E2E_TEST}" 'UiAutomation.ROTATION_FREEZE_90'
assert_contains "${NOTIFICATION_E2E_TEST}" 'restoreDeviceInteractionState()'
assert_contains "${NOTIFICATION_E2E_TEST}" 'assertSingleTrackedGeneration(snapshot)'
assert_contains "${NOTIFICATION_E2E_TEST}" 'PendingIntent.OnFinished'
assert_contains "${NOTIFICATION_E2E_TEST}" 'requireDeviceConditionRemains'
assert_contains "${NOTIFICATION_E2E_TEST}" 'scenario.state != Lifecycle.State.RESUMED'
assert_contains "${NOTIFICATION_E2E_TEST}" 'current === activityBeforeRotation'
assert_contains "${NOTIFICATION_E2E_TEST}" 'generationNotifications().singleOrNull()?.notification?.actions?.size == 1'
assert_count "${NOTIFICATION_E2E_TEST}" 'requireNotNull(notification.actions) { "前台生成通知缺少停止动作" }' 2
assert_contains "${NOTIFICATION_E2E_TEST}" 'private fun clickPermissionControllerButton'
assert_contains "${NOTIFICATION_E2E_TEST}" 'var hostSystemAnrRecovered = false'
assert_contains "${NOTIFICATION_E2E_TEST}" 'private fun findBlockingHostSystemAnrRoot'
assert_contains "${NOTIFICATION_E2E_TEST}" 'HOST_SYSTEM_ANR_TITLES = setOf('
assert_contains "${NOTIFICATION_E2E_TEST}" '"Quickstep isn'"'"'t responding"'
assert_contains "${NOTIFICATION_E2E_TEST}" '"Pixel Launcher isn'"'"'t responding"'
assert_contains "${NOTIFICATION_E2E_TEST}" '"System UI isn'"'"'t responding"'
assert_contains "${NOTIFICATION_E2E_TEST}" 'text.trim().equals(title, ignoreCase = true)'
host_anr_titles_block="$(sed -n '/HOST_SYSTEM_ANR_TITLES = setOf(/,/^[[:space:]]*)/p' "${NOTIFICATION_E2E_TEST}")"
host_anr_title_count="$(grep -Ec '^[[:space:]]+"[^"]+",?$' <<<"${host_anr_titles_block}" || true)"
[[ "${host_anr_title_count}" == "3" ]] ||
    fail "宿主 ANR 精确允许列表只能包含三个完整标题，实际 ${host_anr_title_count} 个"
if grep -Fq -- '"Nexara isn'"'"'t responding"' <<<"${host_anr_titles_block}"; then
    fail "宿主 ANR 允许列表不得包含 Nexara"
fi
host_anr_match_block="$(sed -n '/private fun isHostSystemAnrTitle/,/^$/p' "${NOTIFICATION_E2E_TEST}")"
if grep -Fq -- '.contains(' <<<"${host_anr_match_block}"; then
    fail "宿主 ANR 标题不得使用模糊 contains 匹配"
fi
assert_contains "${NOTIFICATION_E2E_TEST}" 'check(!hostSystemAnrRecovered)'
assert_contains "${NOTIFICATION_E2E_TEST}" 'captureDeviceScreenshot(hostSystemAnrScreenshotName(screenshotName))'
assert_contains "${NOTIFICATION_E2E_TEST}" 'waitForPermissionControllerButtonEnabled'
assert_contains "${NOTIFICATION_E2E_TEST}" 'check(waitButton?.isEnabled == true)'
assert_contains "${NOTIFICATION_E2E_TEST}" 'check(waitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))'
assert_contains "${NOTIFICATION_E2E_TEST}" 'check(recoveredButton?.isEnabled == true)'
screenshot_line="$(grep -n 'captureDeviceScreenshot(hostSystemAnrScreenshotName(screenshotName))' "${NOTIFICATION_E2E_TEST}" | cut -d: -f1)"
wait_click_line="$(grep -n 'check(waitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))' "${NOTIFICATION_E2E_TEST}" | cut -d: -f1)"
single_recovery_line="$(grep -n 'check(!hostSystemAnrRecovered)' "${NOTIFICATION_E2E_TEST}" | cut -d: -f1)"
[[ -n "${screenshot_line}" && -n "${wait_click_line}" && "${screenshot_line}" -lt "${wait_click_line}" ]] ||
    fail "宿主 ANR 证据截图必须发生在 Wait 点击之前"
[[ -n "${single_recovery_line}" && "${single_recovery_line}" -lt "${wait_click_line}" ]] ||
    fail "第二次宿主 ANR 必须在点击 Wait 前 fail closed"
assert_not_contains "${NOTIFICATION_E2E_TEST}" 'minifiedTest'
[[ -f "${NEW_SESSION_E2E_TEST}" ]] || fail "缺少新会话真实导航 E2E"
assert_contains "${NEW_SESSION_E2E_TEST}" 'LoopStatus.COMPLETED'
assert_contains "${NEW_SESSION_E2E_TEST}" 'app.sessionRepository.getById(sessionId)'

# 欢迎页压力测试必须在 Compose 内覆盖窗口/字体配置，不能旋转真实宿主 Activity。
assert_contains "${WELCOME_LAYOUT_TEST}" 'createComposeRule()'
assert_contains "${WELCOME_LAYOUT_TEST}" 'DeviceConfigurationOverride.WindowSize'
assert_contains "${WELCOME_LAYOUT_TEST}" 'DeviceConfigurationOverride.FontScale(2f)'
assert_not_contains "${WELCOME_LAYOUT_TEST}" 'requestedOrientation'

python3 "${REPO_ROOT}/scripts/ci/run-with-timeout.py" 1 /usr/bin/true

echo "设备 timeout helper 形状与无 Android/Gradle smoke 通过。"

echo "设备 CI 范围、矩阵和布局测试契约通过。"
