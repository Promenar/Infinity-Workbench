#!/usr/bin/env python3
"""SSH 设备验收的会话校验、参数注入和输出脱敏；不打印认证材料。"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
from typing import TextIO


def load_session(path: Path) -> dict:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > 16_384 or info.st_mode & 0o077:
        raise ValueError("SSH 会话必须为至多 16 KiB 的 0600 普通文件")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("SSH 会话格式无效")
    for name in ("port", "echoPort"):
        if type(data.get(name)) is not int or not 1024 <= data[name] <= 65535:
            raise ValueError("SSH 会话端口无效")
    if data["port"] == data["echoPort"] or data.get("transport") != "adb-reverse":
        raise ValueError("SSH 会话必须使用不同端口与 adb-reverse")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,48}", str(data.get("username", ""))):
        raise ValueError("SSH 会话用户名无效")
    if not re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", str(data.get("fingerprint", ""))):
        raise ValueError("SSH 会话指纹无效")
    if not isinstance(data.get("password"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", data["password"]):
        raise ValueError("SSH 会话一次性密码格式无效")
    return data


def redact(source: TextIO, destination: TextIO, session: dict | None) -> None:
    # 按完整行处理，跨系统 read 分片的密码也不会漏到 tee 或控制台。
    for line in source:
        if session:
            line = line.replace(session["password"], "[已隐藏]")
        destination.write(line)
        destination.flush()


def successful_suite(output: str) -> bool:
    return bool(re.search(r"^OK \([1-9][0-9]* tests?\)$", output, re.M)
                and re.search(r"^INSTRUMENTATION_CODE: -1$", output, re.M)
                and not re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:3|4)\b|FAILURES!!!|INSTRUMENTATION_FAILED|AssertionError|Process crashed", output))


def run_device(session: dict, runner: str, timeout: int) -> int:
    if not re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.]+", runner) or timeout <= 0:
        raise ValueError("SSH instrumentation runner 或超时无效")
    arguments = ["am", "instrument", "-w", "-r", "-e", "class", "com.promenar.nexara.data.ssh.SshDeviceTest"]
    for name, value in (("sshPort", session["port"]), ("sshEchoPort", session["echoPort"]),
                        ("sshFingerprint", session["fingerprint"]), ("sshPassword", session["password"]),
                        ("sshUsername", session["username"])):
        arguments += ["-e", name, str(value)]
    arguments.append(runner)
    timeout_helper = Path(__file__).with_name("run-with-timeout.py")
    # 认证信息经 adb shell 的 stdin 注入，宿主 ps/超时工具 argv 不含密码。
    process = subprocess.Popen([sys.executable, str(timeout_helper), str(timeout), "adb", "shell"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace")
    safe_lines = []
    try:
        process.stdin.write(shlex.join(arguments) + "\n")
        process.stdin.close()
        for line in process.stdout:
            safe = line.replace(session["password"], "[已隐藏]").replace("\r", "")
            safe_lines.append(safe)
            sys.stdout.write(safe)
            sys.stdout.flush()
        result = process.wait()
        if result:
            return result
        if not successful_suite("".join(safe_lines)):
            print("SSH 设备用例存在跳过、失败或缺少有效测试汇总，不能判定通过。", file=sys.stderr)
            return 1
        return 0
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("validate", "port", "redact", "run", "stop"))
    parser.add_argument("--session", type=Path)
    parser.add_argument("--runner")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    path = args.session or (Path(os.environ["NEXARA_SSH_TEST_SESSION"]) if os.environ.get("NEXARA_SSH_TEST_SESSION") else None)
    try:
        session = load_session(path) if path else None
        if args.operation == "redact":
            redact(sys.stdin, sys.stdout, session)
        elif session is None:
            raise ValueError("未指定 NEXARA_SSH_TEST_SESSION")
        elif args.operation == "port":
            print(session["port"])
        elif args.operation == "stop":
            path.unlink()
        elif args.operation == "run":
            return run_device(session, args.runner or "", args.timeout)
        return 0
    except (OSError, ValueError, TypeError, KeyError):
        # JSON/进程错误可能带入源文本，不输出异常内容。
        print("SSH 设备会话无效或不可用，请检查 0600 会话文件、端口、指纹和宿主服务。", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
