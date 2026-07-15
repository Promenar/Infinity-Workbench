#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BLACKBOX_SCRIPT="${REPO_ROOT}/scripts/ci/android-minified-blackbox-smoke.sh"
APP_BUILD="${REPO_ROOT}/native-ui/app/build.gradle.kts"
SETTINGS="${REPO_ROOT}/native-ui/settings.gradle.kts"
FIXTURE_ROOT="${REPO_ROOT}/native-ui/blackbox-fixture"
FIXTURE_BUILD="${FIXTURE_ROOT}/build.gradle.kts"
FIXTURE_MANIFEST="${FIXTURE_ROOT}/src/main/AndroidManifest.xml"
FIXTURE_PROVIDER="${FIXTURE_ROOT}/src/main/java/com/promenar/nexara/blackboxfixture/BlackBoxFixtureProvider.java"
UI_TAGS="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt"
SHARE_SHEET="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/nexara/share/ui/ShareImportSheet.kt"
MAIN_ACTIVITY="${REPO_ROOT}/native-ui/app/src/main/java/com/promenar/nexara/MainActivity.kt"
RELEASE_WORKFLOW="${REPO_ROOT}/.github/workflows/release.yml"

fail() {
    echo "minified black-box 契约断言失败：$*" >&2
    exit 1
}

assert_file() {
    [[ -f "$1" ]] || fail "缺少文件 $1"
}

assert_contains() {
    grep -Fq -- "$2" "$1" || fail "$1 缺少 $2"
}

assert_not_contains() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1 不应包含 $2"
    fi
}

assert_count() {
    local actual
    actual="$(grep -Fc -- "$2" "$1" || true)"
    [[ "${actual}" == "$3" ]] || fail "$1 中 $2 应出现 $3 次，实际 ${actual} 次"
}

assert_absent() {
    [[ ! -e "$1" ]] || fail "应删除 $1"
}

assert_file "${BLACKBOX_SCRIPT}"
assert_file "${FIXTURE_BUILD}"
assert_file "${FIXTURE_MANIFEST}"
assert_file "${FIXTURE_PROVIDER}"

assert_contains "${SETTINGS}" 'include(":blackbox-fixture")'
assert_contains "${FIXTURE_BUILD}" 'id("com.android.application")'
assert_not_contains "${FIXTURE_BUILD}" 'project(":app")'
assert_not_contains "${FIXTURE_BUILD}" 'androidTest'
assert_not_contains "${FIXTURE_BUILD}" 'org.jetbrains.kotlin'
assert_contains "${FIXTURE_MANIFEST}" 'com.promenar.nexara.blackboxfixture.documents'
assert_contains "${FIXTURE_MANIFEST}" 'android:exported="true"'
assert_contains "${FIXTURE_MANIFEST}" 'android:grantUriPermissions="true"'
assert_not_contains "${FIXTURE_MANIFEST}" 'android.intent.action.MAIN'
assert_not_contains "${FIXTURE_MANIFEST}" '<activity'

for fixture_contract in \
    'release-parser-canary-empty.pdf' \
    'application/pdf' \
    'release-parser-canary-empty.docx' \
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
    'OpenableColumns.DISPLAY_NAME' \
    'OpenableColumns.SIZE' \
    '[Content_Types].xml' \
    '_rels/.rels' \
    'word/document.xml' \
    'ParcelFileDescriptor.MODE_READ_ONLY'; do
    assert_contains "${FIXTURE_PROVIDER}" "${fixture_contract}"
done
for rejected_operation in 'insert(' 'delete(' 'update('; do
    assert_contains "${FIXTURE_PROVIDER}" "${rejected_operation}"
done

minified_block="$(sed -n '/create("minifiedTest") {/,/^        release {/p' "${APP_BUILD}")"
[[ -n "${minified_block}" ]] || fail "app 缺少 minifiedTest"
for invariant in \
    'applicationIdSuffix = ".minifiedTest"' \
    'isDebuggable = false' \
    'isMinifyEnabled = true' \
    'isShrinkResources = true' \
    'signingConfig = signingConfigs.getByName("debug")' \
    'buildConfigField("boolean", "LOCAL_INFERENCE_AVAILABLE", "false")' \
    'getDefaultProguardFile("proguard-android-optimize.txt")' \
    '"proguard-rules.pro"'; do
    grep -Fq -- "${invariant}" <<<"${minified_block}" || fail "minifiedTest 缺少 ${invariant}"
done
assert_not_contains "${APP_BUILD}" 'getByName("minifiedTest") {'
assert_not_contains "${APP_BUILD}" '"minifiedTestImplementation"'
assert_not_contains "${APP_BUILD}" 'proguard-minified-test-runner-rules.pro'
assert_not_contains "${APP_BUILD}" 'proguard-android-test-rules.pro'
assert_absent "${REPO_ROOT}/native-ui/app/proguard-minified-test-runner-rules.pro"
assert_absent "${REPO_ROOT}/native-ui/app/proguard-android-test-rules.pro"
assert_absent "${REPO_ROOT}/scripts/ci/tests/app-minified-runner-tracing-contract-test.sh"
assert_absent "${REPO_ROOT}/scripts/ci/tests/app-minified-androidtest-r8-contract-test.sh"

for tag in \
    SHARE_IMPORT_SHEET SHARE_IMPORT_ITEM SHARE_IMPORT_STATUS \
    SHARE_IMPORT_KNOWLEDGE_BASE_TARGET SHARE_IMPORT_LATER SHARE_IMPORT_CANCEL SHARE_IMPORT_ACTION; do
    assert_contains "${UI_TAGS}" "const val ${tag}"
done
assert_contains "${SHARE_SHEET}" '.testTag(UiTags.SHARE_IMPORT_SHEET)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_ITEM)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_STATUS)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_KNOWLEDGE_BASE_TARGET)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_LATER)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_CANCEL)'
assert_contains "${SHARE_SHEET}" 'Modifier.testTag(UiTags.SHARE_IMPORT_ACTION)'
assert_contains "${MAIN_ACTIVITY}" 'testTagsAsResourceId = true'
assert_contains "${SHARE_SHEET}" 'import androidx.compose.ui.semantics.semantics'
assert_contains "${SHARE_SHEET}" 'import androidx.compose.ui.semantics.testTagsAsResourceId'
modal_sheet_block="$(sed -n '/^[[:space:]]*ModalBottomSheet(/,/^[[:space:]]*) {/p' "${SHARE_SHEET}")"
grep -Eq '^[[:space:]]*modifier = Modifier$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet modifier 必须建立独立语义根"
grep -Eq '^[[:space:]]*\.testTag\(UiTags\.SHARE_IMPORT_SHEET\)$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet 独立语义根缺少 SHARE_IMPORT_SHEET tag"
grep -Eq '^[[:space:]]*\.semantics \{ testTagsAsResourceId = true \},$' <<<"${modal_sheet_block}" ||
    fail "ModalBottomSheet 独立语义根未启用 testTagsAsResourceId"
sheet_tag_line="$(grep -nF '.testTag(UiTags.SHARE_IMPORT_SHEET)' <<<"${modal_sheet_block}" | cut -d: -f1)"
sheet_resource_id_line="$(grep -nF '.semantics { testTagsAsResourceId = true }' <<<"${modal_sheet_block}" | cut -d: -f1)"
[[ -n "${sheet_tag_line}" && -n "${sheet_resource_id_line}" && "${sheet_tag_line}" -lt "${sheet_resource_id_line}" ]] ||
    fail "ModalBottomSheet 必须在同一 modifier 链上先标记 sheet 再启用 resource-id"

for smoke_contract in \
    ':app:assembleMinifiedTest' \
    ':blackbox-fixture:assembleDebug' \
    'NEXARA_MINIFIED_TARGET_APK' \
    'NEXARA_MINIFIED_TARGET_PACKAGE' \
    'NEXARA_MINIFIED_SKIP_BUILD' \
    'NEXARA_MINIFIED_E2E_ABI' \
    'ANDROID_MINIFIED_BLACKBOX_ARTIFACT_DIR' \
    'run-with-timeout.py' \
    'pm list instrumentation' \
    'run-as' \
    'usesCleartextTraffic' \
    'StartupGateTestActivity' \
    'RestoreRelayDebugReceiver' \
    'SecretFieldTestActivity' \
    'BackupTestActivity' \
    'mapping.txt' \
    'android.intent.action.SEND' \
    'release-parser-canary-empty.pdf' \
    'release-parser-canary-empty.docx' \
    'uiautomator dump' \
    'screencap -p' \
    'Imported · Indexed' \
    '已导入 · 已完成索引' \
    'am force-stop' \
    'exit-code.txt'; do
    assert_contains "${BLACKBOX_SCRIPT}" "${smoke_contract}"
done
assert_not_contains "${BLACKBOX_SCRIPT}" 'assembleMinifiedTestAndroidTest'
assert_not_contains "${BLACKBOX_SCRIPT}" 'mainactivity-e2e'
assert_not_contains "${BLACKBOX_SCRIPT}" 'am instrument'
assert_not_contains "${BLACKBOX_SCRIPT}" 'ui-test-manifest'
assert_contains "${BLACKBOX_SCRIPT}" '--eu android.intent.extra.STREAM "${uri}"'
assert_contains "${BLACKBOX_SCRIPT}" '-d "${uri}"'
assert_contains "${BLACKBOX_SCRIPT}" '--grant-read-uri-permission'
assert_not_contains "${BLACKBOX_SCRIPT}" '-f 0x10000001'
assert_count "${BLACKBOX_SCRIPT}" '-a android.intent.action.MAIN' 3
assert_count "${BLACKBOX_SCRIPT}" '-c android.intent.category.LAUNCHER' 3
assert_contains "${BLACKBOX_SCRIPT}" 'com.promenar.nexara.native.test'
assert_not_contains "${BLACKBOX_SCRIPT}" '--projection _display_name:size'
assert_contains "${BLACKBOX_SCRIPT}" '--projection _display_name:_size'
assert_not_contains "${BLACKBOX_SCRIPT}" 'cp "${LAST_WINDOW_XML}" "${durable_xml}"'
assert_contains "${BLACKBOX_SCRIPT}" 'require_file "${durable_xml}"'
assert_contains "${BLACKBOX_SCRIPT}" 'WAIT_SECONDS="${ANDROID_MINIFIED_BLACKBOX_TIMEOUT_SECONDS:-120}"'
assert_contains "${BLACKBOX_SCRIPT}" 'WAIT_SECONDS < 10 || WAIT_SECONDS > 900'
assert_contains "${BLACKBOX_SCRIPT}" 'date +%s'
assert_contains "${BLACKBOX_SCRIPT}" 'UI_DUMP_MAX_SECONDS=10'
assert_contains "${BLACKBOX_SCRIPT}" 'remaining_seconds() {'
assert_not_contains "${BLACKBOX_SCRIPT}" 'seq 1 '

for wait_function in \
    wait_for_launcher_stable \
    wait_for_share_surface \
    wait_for_indexed_status \
    wait_for_initial_launcher_ui; do
    wait_block="$(sed -n "/^${wait_function}() {/,/^}/p" "${BLACKBOX_SCRIPT}")"
    [[ -n "${wait_block}" ]] || fail "缺少 ${wait_function} deadline 等待函数"
    grep -Fq 'local deadline=$(( $(date +%s) + WAIT_SECONDS ))' <<<"${wait_block}" ||
        fail "${wait_function} 未按 WAIT_SECONDS 建立阶段 deadline"
    grep -Fq 'while (( $(date +%s) < deadline )); do' <<<"${wait_block}" ||
        fail "${wait_function} 未消费阶段 deadline"
done

dump_ui_block="$(sed -n '/^dump_ui() {/,/^}/p' "${BLACKBOX_SCRIPT}")"
for dump_timeout_contract in \
    'local deadline="$2"' \
    'remaining="$(remaining_seconds "${deadline}")"' \
    'dump_timeout="${UI_DUMP_MAX_SECONDS}"' \
    'if (( dump_timeout > remaining )); then'; do
    grep -Fq -- "${dump_timeout_contract}" <<<"${dump_ui_block}" ||
        fail "dump_ui 未按剩余 deadline 约束单次 timeout：${dump_timeout_contract}"
done

health_block="$(sed -n '/^assert_target_healthy() {/,/^}/p' "${BLACKBOX_SCRIPT}")"
for health_contract in \
    'adb logcat -d -v threadtime' \
    'logcat-health-${phase}.txt' \
    'adb shell dumpsys activity exit-info "${TARGET_PACKAGE}"' \
    'exit-info-${phase}.txt' \
    'ANR in ${TARGET_PACKAGE//./\\.}' \
    'reason=(4 \(CRASH\)|5 \(CRASH_NATIVE\)|6 \(ANR\))'; do
    grep -Fq -- "${health_contract}" <<<"${health_block}" ||
        fail "assert_target_healthy 缺少全 logcat/exit-info 契约：${health_contract}"
done

artifact_resolution_block="$(sed -n '/^ARTIFACT_DIR_INPUT=/,/^WAIT_SECONDS=/p' "${BLACKBOX_SCRIPT}")"
[[ -n "${artifact_resolution_block}" ]] || fail "smoke 必须先保存 ARTIFACT_DIR_INPUT 再规范路径"
for artifact_path_contract in \
    'ARTIFACT_DIR_INPUT="${ANDROID_MINIFIED_BLACKBOX_ARTIFACT_DIR:-artifacts/android-minified-blackbox-api-${API_LEVEL:-unknown}-${TARGET_ABI}}"' \
    'if [[ "${ARTIFACT_DIR_INPUT}" == /* ]]; then' \
    'ARTIFACT_DIR="${ARTIFACT_DIR_INPUT}"' \
    'ARTIFACT_DIR="${REPO_ROOT}/${ARTIFACT_DIR_INPUT}"'; do
    grep -Fq -- "${artifact_path_contract}" <<<"${artifact_resolution_block}" ||
        fail "artifact 路径规范块缺少 ${artifact_path_contract}"
done
artifact_input_line="$(grep -n '^ARTIFACT_DIR_INPUT=' "${BLACKBOX_SCRIPT}" | cut -d: -f1)"
native_cd_line="$(grep -n '^[[:space:]]*cd "${NATIVE_ROOT}"' "${BLACKBOX_SCRIPT}" | head -n 1 | cut -d: -f1)"
[[ -n "${artifact_input_line}" && -n "${native_cd_line}" && "${artifact_input_line}" -lt "${native_cd_line}" ]] ||
    fail "artifact 目录必须在进入 native-ui 前完成绝对路径规范化"

assert_contains "${RELEASE_WORKFLOW}" 'minified-blackbox:'
assert_contains "${RELEASE_WORKFLOW}" 'Release 等价 minifiedTest 黑盒回归'
assert_contains "${RELEASE_WORKFLOW}" 'scripts/ci/android-minified-blackbox-smoke.sh'
assert_contains "${RELEASE_WORKFLOW}" 'release-minified-blackbox-api-${{ matrix.api-level }}-${{ github.run_attempt }}'
assert_contains "${RELEASE_WORKFLOW}" 'artifacts/android-minified-blackbox-api-${{ matrix.api-level }}-x86_64/'
assert_contains "${RELEASE_WORKFLOW}" 'needs: [device-e2e, minified-blackbox]'
assert_not_contains "${RELEASE_WORKFLOW}" 'NEXARA_DEVICE_E2E_BUILD_TYPE: minifiedTest'

echo "minified release-equivalent black-box 架构契约通过。"
