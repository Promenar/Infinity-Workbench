#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "用法：$0 <signed-release.apk>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK_PATH="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
PACKAGE_NAME="${NEXARA_EXPECTED_PACKAGE:-com.promenar.nexara.native}"
EXPECTED_VERSION_CODE="${NEXARA_EXPECTED_VERSION_CODE:-3}"
EXPECTED_VERSION_NAME="${NEXARA_EXPECTED_VERSION_NAME:-0.2.1-beta}"
EXPECTED_CERT_SHA256="${NEXARA_SIGNING_CERT_SHA256:-}"
API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
ARTIFACT_DIR="${ANDROID_RELEASE_SMOKE_ARTIFACT_DIR:-${REPO_ROOT}/artifacts/release-smoke-api-${API_LEVEL:-unknown}}"

mkdir -p "${ARTIFACT_DIR}"

capture_artifacts() {
    local exit_code="$1"
    set +e
    adb logcat -d -v threadtime > "${ARTIFACT_DIR}/logcat.txt" 2>&1
    adb logcat -b crash -d -v threadtime > "${ARTIFACT_DIR}/crash-log.txt" 2>&1
    adb shell dumpsys activity activities > "${ARTIFACT_DIR}/activities.txt" 2>&1
    adb shell dumpsys package "${PACKAGE_NAME}" > "${ARTIFACT_DIR}/package.txt" 2>&1
    adb exec-out screencap -p > "${ARTIFACT_DIR}/screen.png" 2>/dev/null
    printf '%s\n' "${exit_code}" > "${ARTIFACT_DIR}/exit-code.txt"
    set -e
}

trap 'capture_artifacts "$?"' EXIT

if [[ ! -f "${APK_PATH}" ]]; then
    echo "签名 APK 不存在：${APK_PATH}" >&2
    exit 1
fi
if [[ -z "${EXPECTED_CERT_SHA256}" ]]; then
    echo "缺少公开证书指纹 NEXARA_SIGNING_CERT_SHA256" >&2
    exit 1
fi

BUILD_TOOLS_DIR="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
if [[ ! -x "${ZIPALIGN}" ]]; then
    echo "未找到 zipalign" >&2
    exit 1
fi
if ! "${ZIPALIGN}" -c -P 16 4 "${APK_PATH}"; then
    echo "APK 未通过 16 KiB 页面对齐校验" >&2
    exit 1
fi

python3 "${REPO_ROOT}/scripts/verify-release-apk.py" "${APK_PATH}" \
    --expected-package "${PACKAGE_NAME}" \
    --expected-version-code "${EXPECTED_VERSION_CODE}" \
    --expected-version-name "${EXPECTED_VERSION_NAME}" \
    --expected-cert-sha256 "${EXPECTED_CERT_SHA256}"

adb wait-for-device
adb shell pm uninstall "${PACKAGE_NAME}" >/dev/null 2>&1 || true
if [[ -n "$(adb shell pm path "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')" ]]; then
    echo "冷安装前目标包仍存在" >&2
    exit 1
fi
adb install --no-streaming "${APK_PATH}"

PACKAGE_DUMP="${ARTIFACT_DIR}/package-after-install.txt"
adb shell dumpsys package "${PACKAGE_NAME}" | tr -d '\r' | tee "${PACKAGE_DUMP}" >/dev/null
grep -Eq "versionCode=${EXPECTED_VERSION_CODE}([[:space:]]|$)" "${PACKAGE_DUMP}" || {
    echo "安装包 versionCode 不匹配" >&2
    exit 1
}
grep -Fq "versionName=${EXPECTED_VERSION_NAME}" "${PACKAGE_DUMP}" || {
    echo "安装包 versionName 不匹配" >&2
    exit 1
}

INSTALLED_PATH="$(adb shell pm path "${PACKAGE_NAME}" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
if [[ -z "${INSTALLED_PATH}" ]]; then
    echo "无法定位设备上的 base.apk" >&2
    exit 1
fi
adb pull "${INSTALLED_PATH}" "${ARTIFACT_DIR}/installed-base.apk" >/dev/null
cmp --silent "${APK_PATH}" "${ARTIFACT_DIR}/installed-base.apk" || {
    echo "设备安装的 APK 与发行产物字节不一致" >&2
    exit 1
}

COMPONENT="$(adb shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "${PACKAGE_NAME}" \
    | tr -d '\r' | tail -n 1)"
if [[ "${COMPONENT}" != "${PACKAGE_NAME}/"* ]]; then
    echo "无法解析发行包启动组件：${COMPONENT}" >&2
    exit 1
fi

adb logcat -c
adb shell am force-stop "${PACKAGE_NAME}"
adb shell am start -S -W -n "${COMPONENT}" | tee "${ARTIFACT_DIR}/am-start.txt"

PID=""
for _ in $(seq 1 100); do
    PID="$(adb shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r')"
    [[ -n "${PID}" ]] && break
    sleep 0.1
done
if [[ -z "${PID}" ]]; then
    echo "发行包冷启动后没有存活进程" >&2
    exit 1
fi

adb shell dumpsys activity activities | tr -d '\r' > "${ARTIFACT_DIR}/activities-after-start.txt"
if ! grep -Eq "(topResumedActivity|mResumedActivity)=.*${PACKAGE_NAME//./\.}" "${ARTIFACT_DIR}/activities-after-start.txt"; then
    echo "发行包未成为前台 resumed Activity" >&2
    exit 1
fi

sleep 5
adb logcat -b crash -d -v threadtime | tr -d '\r' > "${ARTIFACT_DIR}/crash-after-start.txt"
if grep -Eq "Process: ${PACKAGE_NAME}([,[:space:]]|$)|ANR in ${PACKAGE_NAME}" "${ARTIFACT_DIR}/crash-after-start.txt"; then
    echo "发行包冷启动出现崩溃或 ANR" >&2
    exit 1
fi
if [[ -z "$(adb shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r')" ]]; then
    echo "发行包在冷启动观察窗口内退出" >&2
    exit 1
fi

echo "API ${API_LEVEL} 签名 APK 冷安装、身份与启动验证通过。"
