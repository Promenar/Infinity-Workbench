#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${REPO_ROOT}/scripts/ci/lib/android-instrumentation.sh"
NATIVE_ROOT="${REPO_ROOT}/native-ui"
DEVICE_E2E_ABI="${NEXARA_DEVICE_E2E_ABI:-x86_64}"
DEVICE_E2E_SCOPE="${NEXARA_DEVICE_E2E_SCOPE:-full}"
BUILD_TYPE_TASK="DeviceTest"
API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
case "${DEVICE_E2E_ABI}" in
    x86_64|arm64-v8a) ;;
    *)
        echo "NEXARA_DEVICE_E2E_ABI 仅支持 x86_64 或 arm64-v8a：${DEVICE_E2E_ABI}" >&2
        exit 1
        ;;
esac
TARGET_PACKAGE="com.promenar.nexara.native.deviceTest"
TARGET_PACKAGE_REGEX="${TARGET_PACKAGE//./\\.}"
APP_APK="${NATIVE_ROOT}/app/build/outputs/apk/deviceTest/app-deviceTest.apk"
APP_TEST_APK="${NATIVE_ROOT}/app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk"
MAIN_ACTIVITY_TEST_APK="${NATIVE_ROOT}/mainactivity-e2e/build/outputs/apk/deviceTest/mainactivity-e2e-deviceTest.apk"
case "${DEVICE_E2E_SCOPE}" in
    minimum|full) ;;
    *)
        echo "NEXARA_DEVICE_E2E_SCOPE 仅支持 minimum 或 full：${DEVICE_E2E_SCOPE}" >&2
        exit 1
        ;;
esac
ARTIFACT_DIR_INPUT="${ANDROID_E2E_ARTIFACT_DIR:-artifacts/android-device-api-${API_LEVEL:-unknown}-${DEVICE_E2E_ABI}}"
if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then
    ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"
else
    ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"
fi
INSTRUMENT_TIMEOUT_SECONDS="${ANDROID_E2E_TIMEOUT_SECONDS:-120}"
APP_RUNNER=""
MAIN_ACTIVITY_RUNNER=""
TIMEOUT_HELPER="${REPO_ROOT}/scripts/ci/run-with-timeout.py"
DISPLAY_STATE_CAPTURED=false
ORIGINAL_WM_SIZE_OVERRIDE=""
ORIGINAL_WM_DENSITY_OVERRIDE=""
ORIGINAL_FONT_SCALE=""
ORIGINAL_ACCELEROMETER_ROTATION=""
ORIGINAL_USER_ROTATION=""
PENDING_NOTIFICATION_SCREENSHOT_STAGE=""
PENDING_NOTIFICATION_SCREENSHOT_EXPECTED=""

mkdir -p "${ARTIFACT_DIR}"
printf 'api_level=%s\nabi=%s\nscope=%s\nbuild_type=deviceTest\n' \
    "${API_LEVEL}" "${DEVICE_E2E_ABI}" "${DEVICE_E2E_SCOPE}" \
    > "${ARTIFACT_DIR}/matrix.txt"

capture_artifacts() {
    local exit_code="$1"
    set +e
    adb logcat -d -v threadtime > "${ARTIFACT_DIR}/logcat.txt" 2>&1
    adb logcat -b crash -d -v threadtime > "${ARTIFACT_DIR}/crash-log.txt" 2>&1
    adb shell dumpsys activity processes > "${ARTIFACT_DIR}/activity-processes.txt" 2>&1
    adb shell dumpsys package "${TARGET_PACKAGE}" > "${ARTIFACT_DIR}/target-package.txt" 2>&1
    adb shell uiautomator dump /sdcard/nexara-window.xml >/dev/null 2>&1
    adb pull /sdcard/nexara-window.xml "${ARTIFACT_DIR}/window.xml" >/dev/null 2>&1
    adb exec-out screencap -p > "${ARTIFACT_DIR}/screen.png" 2>/dev/null
    printf '%s\n' "${exit_code}" > "${ARTIFACT_DIR}/exit-code.txt"
    set -e
}

capture_device_display_state() {
    local wm_size_output
    local wm_density_output
    wm_size_output="$(adb shell wm size 2>/dev/null | tr -d '\r')"
    wm_density_output="$(adb shell wm density 2>/dev/null | tr -d '\r')"
    ORIGINAL_WM_SIZE_OVERRIDE="$(printf '%s\n' "${wm_size_output}" | sed -n 's/^Override size: //p')"
    ORIGINAL_WM_DENSITY_OVERRIDE="$(printf '%s\n' "${wm_density_output}" | sed -n 's/^Override density: //p')"
    ORIGINAL_FONT_SCALE="$(adb shell settings get system font_scale 2>/dev/null | tr -d '\r')"
    ORIGINAL_ACCELEROMETER_ROTATION="$(adb shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')"
    ORIGINAL_USER_ROTATION="$(adb shell settings get system user_rotation 2>/dev/null | tr -d '\r')"
    DISPLAY_STATE_CAPTURED=true
}

restore_setting() {
    local namespace="$1"
    local key="$2"
    local value="$3"
    if [[ -z "${value}" || "${value}" == "null" ]]; then
        adb shell settings delete "${namespace}" "${key}" >/dev/null 2>&1 || true
    else
        adb shell settings put "${namespace}" "${key}" "${value}" >/dev/null 2>&1 || true
    fi
}

restore_device_display_state() {
    if [[ "${DISPLAY_STATE_CAPTURED}" != "true" ]]; then
        return 0
    fi

    if [[ -n "${ORIGINAL_WM_SIZE_OVERRIDE}" ]]; then
        adb shell wm size "${ORIGINAL_WM_SIZE_OVERRIDE}" >/dev/null 2>&1 || true
    else
        adb shell wm size reset >/dev/null 2>&1 || true
    fi
    if [[ -n "${ORIGINAL_WM_DENSITY_OVERRIDE}" ]]; then
        adb shell wm density "${ORIGINAL_WM_DENSITY_OVERRIDE}" >/dev/null 2>&1 || true
    else
        adb shell wm density reset >/dev/null 2>&1 || true
    fi
    restore_setting system font_scale "${ORIGINAL_FONT_SCALE}"
    restore_setting system accelerometer_rotation "${ORIGINAL_ACCELEROMETER_ROTATION}"
    restore_setting system user_rotation "${ORIGINAL_USER_ROTATION}"
    DISPLAY_STATE_CAPTURED=false
}

finish_run() {
    local exit_code="$1"
    if [[ -n "${PENDING_NOTIFICATION_SCREENSHOT_STAGE}" ]]; then
        pull_notification_permission_screenshots \
            "${PENDING_NOTIFICATION_SCREENSHOT_STAGE}" \
            "${PENDING_NOTIFICATION_SCREENSHOT_EXPECTED}" || true
    fi
    capture_artifacts "${exit_code}"
    restore_device_display_state
}

trap 'finish_run "$?"' EXIT

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "缺少构建产物：$1" >&2
        exit 1
    fi
}

discover_runner() {
    local runner_class="$1"
    local matches
    matches="$(adb shell pm list instrumentation | tr -d '\r' | sed -n \
        "s#^instrumentation:\([^ ]*/${runner_class}\) (target=${TARGET_PACKAGE})#\1#p")"
    if [[ -z "${matches}" ]] || [[ "$(printf '%s\n' "${matches}" | wc -l | tr -d ' ')" != "1" ]]; then
        echo "无法唯一定位 ${runner_class}，当前 instrumentation：" >&2
        adb shell pm list instrumentation >&2
        exit 1
    fi
    printf '%s\n' "${matches}"
}

run_test() {
    local name="$1"
    local runner="$2"
    shift 2
    local output_file="${ARTIFACT_DIR}/${name}.txt"
    local status
    echo "运行设备测试：${name}"
    set +e
    python3 "${TIMEOUT_HELPER}" "${INSTRUMENT_TIMEOUT_SECONDS}" adb shell am instrument -w -r "$@" "${runner}" \
        2>&1 | tee "${output_file}"
    status="${PIPESTATUS[0]}"
    set -e
    nexara_assert_normal_instrumentation_output "${output_file}" "${status}"
}

run_layout_and_accessibility_contracts() {
    local status=0
    capture_device_display_state

    # 这些测试在 Compose 测试宿主内自行覆盖 2.0x 字体、横屏、紧凑/展开宽度，
    # 不用 shell 强改显示参数；无论测试成功与否，阶段结束都恢复设备显示状态。
    run_test accessibility-smoke "${APP_RUNNER}" \
        -e class com.promenar.nexara.ui.AccessibilitySmokeTest || status=$?
    run_test adaptive-navigation "${APP_RUNNER}" \
        -e class com.promenar.nexara.ui.AdaptiveNavigationTest || status=$?
    run_test welcome-layout "${APP_RUNNER}" \
        -e class com.promenar.nexara.onboarding.WelcomeScreenLayoutTest || status=$?

    restore_device_display_state
    return "${status}"
}

force_stop_target() {
    adb shell am force-stop "${TARGET_PACKAGE}"
    local attempt
    for attempt in $(seq 1 50); do
        if [[ -z "$(adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r')" ]]; then
            return 0
        fi
        sleep 0.1
    done
    echo "目标进程未在 force-stop 后退出" >&2
    return 1
}

wait_for_package_manager_idle() {
    if (( API_LEVEL >= 33 )); then
        adb shell cmd package wait-for-handler --timeout 5000 >/dev/null
        adb shell cmd package wait-for-background-handler --timeout 5000 >/dev/null
    else
        # Android 12 尚未提供 wait-for-handler 子命令，给权限状态落盘留出稳定窗口。
        sleep 1
    fi
}

reset_target_with_notification_permission_revoked() {
    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb shell pm revoke "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb shell pm clear-permission-flags \
        "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS user-set user-fixed \
        >/dev/null 2>&1 || true
    wait_for_package_manager_idle
}

reset_target_with_notification_permission_granted() {
    adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
    adb shell pm grant "${TARGET_PACKAGE}" android.permission.POST_NOTIFICATIONS
    wait_for_package_manager_idle
}

pull_notification_permission_screenshots() {
    local stage="$1"
    local expected_name="$2"
    local remote_dir="/sdcard/Android/data/${TARGET_PACKAGE}/files"
    local remote_files
    local remote_file
    local pulled=0
    local pull_failed=false

    if ! remote_files="$(adb shell find "${remote_dir}" -maxdepth 1 -type f -name '*.png' -print 2>&1)"; then
        echo "无法枚举 ${stage} 权限截图：${remote_files}" >&2
        return 1
    fi
    remote_files="${remote_files//$'\r'/}"

    while IFS= read -r remote_file; do
        [[ -n "${remote_file}" ]] || continue
        if ! adb pull "${remote_file}" "${ARTIFACT_DIR}/${stage}-$(basename "${remote_file}")" >/dev/null; then
            echo "${stage} 无法回拉权限截图：${remote_file}" >&2
            pull_failed=true
            continue
        fi
        pulled=$((pulled + 1))
    done <<<"${remote_files}"
    if (( pulled == 0 )); then
        echo "${stage} 未回拉任何权限截图" >&2
        return 1
    fi
    if [[ "${pull_failed}" == "true" ]]; then
        return 1
    fi
    if ! grep -Fxq -- "${remote_dir}/${expected_name}" <<<"${remote_files}"; then
        echo "${stage} 缺少预期权限截图：${expected_name}" >&2
        return 1
    fi
}

run_notification_permission_test() {
    local stage="$1"
    local expected_name="$2"
    shift 2
    local test_status=0
    local pull_status=0

    PENDING_NOTIFICATION_SCREENSHOT_STAGE="${stage}"
    PENDING_NOTIFICATION_SCREENSHOT_EXPECTED="${expected_name}"
    run_test "${stage}" "${MAIN_ACTIVITY_RUNNER}" "$@" || test_status=$?
    pull_notification_permission_screenshots "${stage}" "${expected_name}" || pull_status=$?
    if (( pull_status == 0 )); then
        PENDING_NOTIFICATION_SCREENSHOT_STAGE=""
        PENDING_NOTIFICATION_SCREENSHOT_EXPECTED=""
    fi

    if (( test_status != 0 )); then
        return "${test_status}"
    fi
    return "${pull_status}"
}

run_notification_permission_contracts() {
    reset_target_with_notification_permission_revoked
    run_notification_permission_test \
        notification-permission-deny notification-permission-deny-dialog.png \
        -e class com.promenar.nexara.MainActivityNotificationE2eTest#denyingSystemNotificationPermissionContinuesForegroundOnlyWithoutFgs

    reset_target_with_notification_permission_revoked
    run_notification_permission_test \
        notification-permission-grant notification-permission-grant-dialog.png \
        -e class com.promenar.nexara.MainActivityNotificationE2eTest#grantingSystemNotificationPermissionContinuesBackgroundAllowed
}

run_notification_open_session_contract() {
    if (( API_LEVEL >= 33 )); then
        reset_target_with_notification_permission_granted
    else
        adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
        wait_for_package_manager_idle
    fi
    run_test notification-open-session "${MAIN_ACTIVITY_RUNNER}" \
        -e class com.promenar.nexara.MainActivityNotificationE2eTest#postedNotificationOpenPendingIntentReturnsToExactSession
}

run_notification_background_lifecycle_contract() {
    prepare_target_for_foreground_notification
    run_test notification-background-lifecycle "${MAIN_ACTIVITY_RUNNER}" \
        -e class com.promenar.nexara.MainActivityNotificationE2eTest#backgroundGenerationSurvivesRealLockWakeRotationAndRepeatedStopDoesNotRevive
}

prepare_target_for_foreground_notification() {
    if (( API_LEVEL >= 33 )); then
        reset_target_with_notification_permission_granted
    else
        adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
        wait_for_package_manager_idle
    fi
}

run_expected_relay_death() {
    local phase="${1:-stage}"
    local output_file="${ARTIFACT_DIR}/restore-relay-${phase}-expected-death.txt"
    local relay_log_file="${ARTIFACT_DIR}/restore-relay-${phase}-relay-logcat.txt"
    local after_pids_file="${ARTIFACT_DIR}/restore-relay-${phase}-after-pids.txt"
    local status
    echo "运行预期杀进程阶段：restore-relay-${phase}"
    # 只清理 relay 证据所在的 main buffer，保留此前测试的 crash buffer 供最终门禁审计。
    adb logcat -b main -c
    set +e
    python3 "${TIMEOUT_HELPER}" "${INSTRUMENT_TIMEOUT_SECONDS}" adb shell am instrument -w -r \
        -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
        -e restoreRelayPhase "${phase}" \
        "${APP_RUNNER}" 2>&1 | tee "${output_file}"
    status="${PIPESTATUS[0]}"
    set -e

    nexara_assert_expected_process_death_output "${output_file}" "${status}"
    adb logcat -b main -d -v threadtime -s NexaraLogger:D '*:S' > "${relay_log_file}" 2>&1
    if ! grep -Eq '\[NexaraRestoreRelay\] relay_pid=[0-9]+ main_pid=[0-9]+ payload=none' "${relay_log_file}"; then
        echo "预期进程死亡阶段缺少 RestoreRelayActivity 专属执行证据：${relay_log_file}" >&2
        return 1
    fi
    if [[ "$(adb get-state 2>/dev/null)" != "device" ]]; then
        echo "relay 阶段后 ADB 设备不可用，不能判定为预期进程死亡" >&2
        return 1
    fi
    # relay 可能已拉起新进程；下一 verify 阶段会用持久化的 KEY_STAGE_PID
    # 断言新 PID 与被杀死的 stage PID 不同，这里只保留阶段结束时的进程证据。
    adb shell pidof "${TARGET_PACKAGE}" 2>/dev/null | tr -d '\r' > "${after_pids_file}" || true
}

cd "${NATIVE_ROOT}"
chmod +x gradlew
./gradlew --no-daemon --stacktrace -Pnexara.deviceE2e=true \
    -Pnexara.deviceE2eAbi="${DEVICE_E2E_ABI}" \
    ":app:assemble${BUILD_TYPE_TASK}" \
    ":app:assemble${BUILD_TYPE_TASK}AndroidTest" \
    ":mainactivity-e2e:assemble${BUILD_TYPE_TASK}"

require_file "${APP_APK}"
require_file "${APP_TEST_APK}"
require_file "${MAIN_ACTIVITY_TEST_APK}"

adb wait-for-device
adb shell pm uninstall "${TARGET_PACKAGE}" >/dev/null 2>&1 || true
adb install --no-streaming "${APP_APK}"
adb install -r --no-streaming "${APP_TEST_APK}"
adb install -r --no-streaming "${MAIN_ACTIVITY_TEST_APK}"

APP_RUNNER="$(discover_runner androidx.test.runner.AndroidJUnitRunner)"
MAIN_ACTIVITY_RUNNER="$(discover_runner com.promenar.nexara.MainActivityE2eRunner)"
printf '%s\n' "${APP_RUNNER}" > "${ARTIFACT_DIR}/app-runner.txt"
printf '%s\n' "${MAIN_ACTIVITY_RUNNER}" > "${ARTIFACT_DIR}/mainactivity-runner.txt"

adb logcat -c

if [[ "${DEVICE_E2E_SCOPE}" == "full" ]]; then
    if (( API_LEVEL >= 33 )); then
        run_notification_permission_contracts
    else
        echo "API ${API_LEVEL} 无运行时通知权限，跳过 Android 13+ 权限弹窗 E2E。"
    fi
    run_notification_open_session_contract
    run_notification_background_lifecycle_contract
fi

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test mainactivity-chat-flow "${MAIN_ACTIVITY_RUNNER}" \
    -e class com.promenar.nexara.MainActivityChatFlowE2eTest

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test mainactivity-new-session "${MAIN_ACTIVITY_RUNNER}" \
    -e class com.promenar.nexara.MainActivityNewSessionE2eTest

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test onboarding-full "${APP_RUNNER}" \
    -e class com.promenar.nexara.onboarding.OnboardingAndroidEndToEndTest
run_layout_and_accessibility_contracts

if [[ "${DEVICE_E2E_SCOPE}" == "minimum" ]]; then
    adb logcat -b crash -d -v threadtime > "${ARTIFACT_DIR}/crash-after-tests.txt" 2>&1
    if grep -Eq "Process: ${TARGET_PACKAGE_REGEX}([,[:space:]]|$)|ANR in ${TARGET_PACKAGE_REGEX}" "${ARTIFACT_DIR}/crash-after-tests.txt"; then
        echo "最低 SDK 核心回归后 crash buffer 包含 Nexara 崩溃" >&2
        exit 1
    fi
    echo "API ${API_LEVEL} 最低 SDK 核心可达性、无障碍与自适应布局测试全部通过。"
    exit 0
fi

run_test onboarding-seed-first-chat "${APP_RUNNER}" \
    -e class com.promenar.nexara.onboarding.OnboardingAndroidEndToEndTest#forceStopPhaseCheckpoint \
    -e onboardingPhase seed_first_chat
force_stop_target
run_test onboarding-verify-first-chat "${APP_RUNNER}" \
    -e class com.promenar.nexara.onboarding.OnboardingAndroidEndToEndTest#forceStopPhaseCheckpoint \
    -e onboardingPhase verify_first_chat

run_test backup-compose-saf-roundtrip "${APP_RUNNER}" \
    -e class com.promenar.nexara.ui.settings.BackupComposeSafRoundTripTest

run_test backup-process-prepare "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidBackupProcessRecreationTest \
    -e backupPhase prepare
force_stop_target
run_test backup-process-commit "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidBackupProcessRecreationTest \
    -e backupPhase commit

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test restore-relay-export-no-key "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase exportNoKey
force_stop_target
run_expected_relay_death stageNoKey
run_test restore-relay-verify-no-key "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase verifyNoKey
force_stop_target
run_test restore-relay-no-replay-no-key "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase noReplayNoKey

adb shell pm clear "${TARGET_PACKAGE}" >/dev/null
run_test restore-relay-export "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase export
force_stop_target
run_expected_relay_death stage
run_test restore-relay-verify "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase verify
force_stop_target
run_test restore-relay-no-replay "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest \
    -e restoreRelayPhase noReplay

run_test generation-notification-factory "${APP_RUNNER}" \
    -e class com.promenar.nexara.background.generation.GenerationNotificationFactoryTest
prepare_target_for_foreground_notification
run_test generation-foreground-service "${APP_RUNNER}" \
    -e class com.promenar.nexara.background.generation.GenerationForegroundServiceDeviceTest

run_test document-parser-fixtures "${APP_RUNNER}" \
    -e class com.promenar.nexara.data.rag.DocumentParserDeviceE2eTest

force_stop_target
run_test generation-cold-track "${APP_RUNNER}" \
    -e class com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest#coldTrackDuringStartupRecoveryStopsGracefullyWithoutProcessCrash
force_stop_target
run_test generation-cold-stop "${APP_RUNNER}" \
    -e class com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest#coldStopDuringStartupRecoveryStopsGracefullyWithoutCoordinator

adb logcat -b crash -d -v threadtime > "${ARTIFACT_DIR}/crash-after-tests.txt" 2>&1
if grep -Eq "Process: ${TARGET_PACKAGE_REGEX}([,[:space:]]|$)|ANR in ${TARGET_PACKAGE_REGEX}" "${ARTIFACT_DIR}/crash-after-tests.txt"; then
    echo "设备回归后 crash buffer 包含 Nexara 崩溃" >&2
    exit 1
fi

echo "API ${API_LEVEL} 完整设备 E2E 全部通过。"
