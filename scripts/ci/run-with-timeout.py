#!/usr/bin/env python3
"""跨平台子进程超时执行工具。"""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import sys
import time


def _is_executable(path: str) -> bool:
    return os.path.isfile(path) and os.access(path, os.X_OK)


def _command_exists(cmd: str) -> bool:
    if os.sep in cmd:
        return _is_executable(cmd)
    return shutil.which(cmd) is not None


def _is_process_group_alive(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _wait_for_process_group_exit(pgid: int, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _is_process_group_alive(pgid):
            return True
        time.sleep(0.05)
    return not _is_process_group_alive(pgid)


def _terminate_process_group(pgid: int, sig: int) -> None:
    try:
        os.killpg(pgid, sig)
    except ProcessLookupError:
        return
    except PermissionError:
        return


def _cleanup_process_group(pgid: int, proc: subprocess.Popen[str], grace_seconds: float) -> None:
    graceful_end = time.monotonic() + grace_seconds
    while time.monotonic() < graceful_end:
        _terminate_process_group(pgid, signal.SIGTERM)
        if not _is_process_group_alive(pgid):
            break
        try:
            proc.wait(timeout=0.05)
            break
        except subprocess.TimeoutExpired:
            time.sleep(0.05)

    if _is_process_group_alive(pgid):
        _terminate_process_group(pgid, signal.SIGKILL)
        _wait_for_process_group_exit(pgid, 1.0)

    try:
        proc.wait(timeout=1)
    except subprocess.TimeoutExpired:
        pass


def run_with_timeout(seconds: int, args: list[str]) -> int:
    proc = subprocess.Popen(args, start_new_session=True, stdin=None, stdout=None, stderr=None)
    pgid = os.getpgid(proc.pid)
    end_time = time.monotonic() + seconds
    timed_out = False
    signal_state: dict[str, int | None] = {"received": None}

    def _sig_handler(signo: int, _frame: object) -> None:
        signal_state["received"] = signo

    previous_handlers = {
        signal.SIGINT: signal.signal(signal.SIGINT, _sig_handler),
        signal.SIGTERM: signal.signal(signal.SIGTERM, _sig_handler),
    }

    try:
        while True:
            remaining = end_time - time.monotonic()
            if signal_state["received"] is not None:
                break
            if remaining <= 0:
                timed_out = True
                break
            try:
                proc.wait(timeout=min(remaining, 0.2))
                break
            except subprocess.TimeoutExpired:
                continue
    finally:
        for signo, handler in previous_handlers.items():
            signal.signal(signo, handler)

    if signal_state["received"] is not None:
        _cleanup_process_group(pgid, proc, 3.0)
        return 128 + signal_state["received"]

    if timed_out:
        _cleanup_process_group(pgid, proc, 3.0)
        return 124

    return proc.returncode if proc.returncode is not None else 124


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: python3 run-with-timeout.py <正整数秒> <command> [args...]", file=sys.stderr)
        return 1

    try:
        seconds = int(sys.argv[1])
    except ValueError:
        print("秒数必须是正整数。", file=sys.stderr)
        return 2
    if seconds <= 0:
        print("秒数必须是正整数。", file=sys.stderr)
        return 2

    command = sys.argv[2]
    command_args = sys.argv[2:]
    if not _command_exists(command):
        print(f"命令不存在：{command}", file=sys.stderr)
        return 127

    return run_with_timeout(seconds, command_args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("脚本被中断。", file=sys.stderr)
        raise SystemExit(130)
