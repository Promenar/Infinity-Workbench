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
assert_contains "${DEVICE_SCRIPT}" 'restore-relay-stage-after-pids.txt'
assert_contains "${RESTORE_RELAY_E2E_TEST}" 'Process.myPid()).isNotEqualTo(proof(app).getInt(KEY_STAGE_PID, -1))'
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
assert_count "${DEVICE_SCRIPT}" 'wait_for_package_manager_idle' 3
assert_not_contains "${DEVICE_SCRIPT}" "\"${TIMEOUT_HELPER}\" adb"
assert_not_contains "${DEVICE_SCRIPT}" "'${TIMEOUT_HELPER}' adb"
assert_not_contains "${DEVICE_SCRIPT}" 'timeout "${INSTRUMENT_TIMEOUT_SECONDS}s"'

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
