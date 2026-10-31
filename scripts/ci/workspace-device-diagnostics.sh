#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT="${ROOT}/artifacts/workspace-device-diagnostics"
mkdir -p "${OUTPUT}"
cd "${ROOT}/native-ui"
./gradlew --stacktrace -Pinfinity.deviceE2e=true -Pinfinity.deviceE2eAbi=x86_64 \
    :app:assembleSideloadDeviceTest :app:assembleSideloadDeviceTestAndroidTest
adb install --no-streaming app/build/outputs/apk/sideload/deviceTest/app-sideload-deviceTest.apk
adb install -r --no-streaming app/build/outputs/apk/androidTest/sideload/deviceTest/app-sideload-deviceTest-androidTest.apk
runner="$(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\) (target=com\.promenar\.infinity\.deviceTest)$/\1/p')"
[[ -n "${runner}" && "${runner}" != *$'\n'* ]]
adb shell am instrument -w -r -e class \
    com.promenar.infinity.data.repository.AndroidPortedSecureWorkspaceFileOpsTest \
    "${runner}" | tr -d '\r' | tee "${OUTPUT}/instrumentation.txt"
grep -Eq '^OK \(7 tests\)$' "${OUTPUT}/instrumentation.txt"
