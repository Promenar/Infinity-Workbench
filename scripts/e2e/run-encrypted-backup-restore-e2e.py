#!/usr/bin/env python3
"""驱动加密 includeKeys 备份恢复的四阶段 Android 设备门禁。"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


TEST_CLASS = (
    "com.promenar.nexara.data.backup."
    "AndroidRestoreRelayEndToEndTest#relayPhase"
)
DEFAULT_APP_PACKAGE = "com.promenar.nexara.native.debug"
DEFAULT_TEST_COMPONENT = (
    "com.promenar.nexara.native.debug.test/"
    "com.promenar.nexara.MainActivityE2eRunner"
)
FAILURE_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed.")
STAGE_TERMINATION = re.compile(
    r"INSTRUMENTATION_ABORTED|instrumentation.*(?:terminated|crash)|system has crashed",
    re.IGNORECASE,
)
RELAY_PROOF = re.compile(
    r"NexaraRestoreRelay.*relay_pid=\d+\s+main_pid=\d+\s+payload=none"
)


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    output: str


def require_regular_phase_success(phase: str, returncode: int, output: str) -> None:
    failure = next((marker for marker in FAILURE_MARKERS if marker in output), None)
    if failure is not None:
        raise RuntimeError(f"{phase} 阶段出现 {failure}，不能只按 adb 退出码放行")
    if returncode != 0 or "OK (1 test)" not in output:
        raise RuntimeError(
            f"{phase} 阶段缺少 JUnit 语义成功标记（adb={returncode}）"
        )


def require_stage_termination(returncode: int, output: str, relay_log: str) -> None:
    if "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output:
        raise RuntimeError("stage 阶段出现 FAILURES，不能误判为预期杀进程")
    if "OK (1 test)" in output:
        raise RuntimeError("stage 阶段正常结束，relay 未按预期杀死主进程")
    if returncode == 0 and STAGE_TERMINATION.search(output) is None:
        raise RuntimeError("stage 阶段既无非零退出也无 instrumentation 终止证据")
    if RELAY_PROOF.search(relay_log) is None:
        raise RuntimeError("stage 阶段缺少无 payload 的 relay 日志证据")


class Adb:
    def __init__(self, serial: str | None):
        self.prefix = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args: str, timeout: int = 60, check: bool = False) -> CommandResult:
        completed = subprocess.run(
            [*self.prefix, *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
        result = CommandResult(completed.returncode, completed.stdout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb 命令失败: {' '.join(args)}\n{result.output}")
        return result


def instrument(adb: Adb, component: str, phase: str) -> CommandResult:
    return adb.run(
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        TEST_CLASS,
        "-e",
        "restoreRelayPhase",
        phase,
        component,
        timeout=75,
    )


def install_if_requested(adb: Adb, app_apk: Path | None, test_apk: Path | None) -> None:
    if (app_apk is None) != (test_apk is None):
        raise RuntimeError("--app-apk 与 --test-apk 必须同时提供")
    for apk in (app_apk, test_apk):
        if apk is None:
            continue
        if not apk.is_file():
            raise RuntimeError(f"APK 不存在: {apk}")
        adb.run("install", "-r", "-t", str(apk), timeout=120, check=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--app-package", default=DEFAULT_APP_PACKAGE)
    parser.add_argument("--test-component", default=DEFAULT_TEST_COMPONENT)
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument(
        "--keep-data",
        action="store_true",
        help="不清理 app/test 数据；仅用于诊断，正式门禁不得使用",
    )
    args = parser.parse_args(argv)
    adb = Adb(args.serial)

    adb.run("get-state", check=True)
    install_if_requested(adb, args.app_apk, args.test_apk)
    if not args.keep_data:
        adb.run("shell", "pm", "clear", args.app_package, check=True)
        test_package = args.test_component.split("/", 1)[0]
        adb.run("shell", "pm", "clear", test_package, check=True)

    export = instrument(adb, args.test_component, "export")
    print(export.output, end="")
    require_regular_phase_success("export", export.returncode, export.output)

    adb.run("logcat", "-c", check=True)
    stage = instrument(adb, args.test_component, "stage")
    print(stage.output, end="")
    relay_log = adb.run(
        "logcat", "-d", "-s", "NexaraRestoreRelay:I", "*:S", timeout=15
    ).output
    require_stage_termination(stage.returncode, stage.output, relay_log)

    verify = instrument(adb, args.test_component, "verify")
    print(verify.output, end="")
    require_regular_phase_success("verify", verify.returncode, verify.output)

    # noReplay 必须是显式冷启；不能依赖不同设备/runner 对 am instrument 的隐式行为。
    adb.run("shell", "am", "force-stop", args.app_package, check=True)
    no_replay = instrument(adb, args.test_component, "noReplay")
    print(no_replay.output, end="")
    require_regular_phase_success("noReplay", no_replay.returncode, no_replay.output)

    print("加密 includeKeys 备份恢复门禁通过：export/stage/verify/noReplay")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"门禁失败: {error}", file=sys.stderr)
        raise SystemExit(1)
