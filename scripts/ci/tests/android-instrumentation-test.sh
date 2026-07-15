#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/android-instrumentation.sh"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

expect_success() {
    if ! "$@"; then
        echo "预期成功但实际失败：$*" >&2
        exit 1
    fi
}

expect_failure() {
    if "$@" 2>/dev/null; then
        echo "预期失败但实际成功：$*" >&2
        exit 1
    fi
}

printf '%s\n' \
    'INSTRUMENTATION_STATUS: class=com.example.PassingTest' \
    'OK (2 tests)' \
    'INSTRUMENTATION_CODE: -1' > "${TMP_DIR}/normal-ok.txt"
expect_success nexara_assert_normal_instrumentation_output "${TMP_DIR}/normal-ok.txt" 0

printf '%s\n' \
    'FAILURES!!!' \
    'INSTRUMENTATION_CODE: -1' > "${TMP_DIR}/normal-failure.txt"
expect_failure nexara_assert_normal_instrumentation_output "${TMP_DIR}/normal-failure.txt" 0
expect_failure nexara_assert_normal_instrumentation_output "${TMP_DIR}/normal-ok.txt" 124

printf '%s\n' \
    'INSTRUMENTATION_STATUS: class=com.promenar.nexara.data.backup.AndroidRestoreRelayEndToEndTest' \
    'INSTRUMENTATION_RESULT: shortMsg=Process crashed.' \
    'INSTRUMENTATION_CODE: 0' > "${TMP_DIR}/expected-death.txt"
expect_success nexara_assert_expected_process_death_output "${TMP_DIR}/expected-death.txt" 0
expect_failure nexara_assert_expected_process_death_output "${TMP_DIR}/expected-death.txt" 124
expect_failure nexara_assert_expected_process_death_output "${TMP_DIR}/normal-ok.txt" 0

printf '%s\n' 'adb: device offline' > "${TMP_DIR}/empty-failure.txt"
expect_failure nexara_assert_expected_process_death_output "${TMP_DIR}/empty-failure.txt" 1

echo "android instrumentation 输出解析器测试通过。"
