#!/usr/bin/env python3
"""验证 Nexara 侧载 APK 的完整性、版本、签名与常见秘密泄漏。"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


MAX_APK_BYTES = 50 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
SCAN_OVERLAP_BYTES = 512

SENSITIVE_PATTERNS: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    ("macOS 用户绝对路径", re.compile(rb"/Users/[A-Za-z0-9._-]+/")),
    ("Linux 用户绝对路径", re.compile(rb"/home/[A-Za-z0-9._-]+/")),
    ("Windows 本机绝对路径", re.compile(rb"(?i)[A-Z]:[\\/](?:Users|Nexara)[\\/]")),
    ("本机 secure_env 路径", re.compile(rb"(?i)(?:secure_env|promenar\.keystore|secure\.properties)")),
    ("Tailscale 内网测试地址", re.compile(rb"100\.72\.176\.103")),
    ("疑似 OpenAI 兼容密钥", re.compile(rb"sk-[A-Za-z0-9_-]{20,}")),
)


class VerificationError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-version-code", required=True)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--checksum-output", type=Path)
    parser.add_argument("--max-size-bytes", type=int, default=MAX_APK_BYTES)
    return parser.parse_args()


def normalize_digest(value: str) -> str:
    return re.sub(r"[^0-9a-fA-F]", "", value).lower()


def android_build_tools_version(path: Path) -> tuple[int, ...]:
    numbers = re.findall(r"\d+", path.parent.name)
    return tuple(int(number) for number in numbers) if numbers else (0,)


def find_android_tool(name: str) -> Path:
    direct = shutil.which(name)
    if direct:
        return Path(direct)

    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    candidates: list[Path] = []
    for raw_root in roots:
        if not raw_root:
            continue
        build_tools = Path(raw_root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(build_tools.glob(f"*/{name}"))
            candidates.extend(build_tools.glob(f"*/{name}.bat"))
            candidates.extend(build_tools.glob(f"*/{name}.exe"))
    if not candidates:
        raise VerificationError(f"找不到 Android SDK 工具：{name}")
    return max(candidates, key=android_build_tools_version)


def run_tool(command: list[str]) -> str:
    effective_command = command
    if os.name == "nt" and Path(command[0]).suffix.lower() in {".bat", ".cmd"}:
        effective_command = ["cmd.exe", "/d", "/s", "/c", subprocess.list2cmdline(command)]
    completed = subprocess.run(
        effective_command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if completed.returncode != 0:
        raise VerificationError(f"工具执行失败：{Path(command[0]).name}（退出码 {completed.returncode}）")
    return completed.stdout


def verify_zip(apk: Path) -> None:
    try:
        with zipfile.ZipFile(apk) as archive:
            corrupt = archive.testzip()
    except (OSError, zipfile.BadZipFile) as error:
        raise VerificationError("APK 不是有效 ZIP 文件") from error
    if corrupt is not None:
        raise VerificationError(f"APK ZIP 条目损坏：{corrupt}")


def verify_badging(apk: Path, expected_package: str, version_code: str, version_name: str) -> None:
    output = run_tool([str(find_android_tool("aapt")), "dump", "badging", str(apk)])
    package_line = next((line for line in output.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(name|versionCode|versionName)='([^']*)'", package_line))
    expected = {
        "name": expected_package,
        "versionCode": version_code,
        "versionName": version_name,
    }
    if fields != expected:
        raise VerificationError(f"APK 包版本不匹配：实际 {fields!r}，预期 {expected!r}")


def verify_signature(apk: Path, expected_digest: str) -> None:
    output = run_tool(
        [str(find_android_tool("apksigner")), "verify", "--verbose", "--print-certs", str(apk)]
    )
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", output)
    if match is None:
        raise VerificationError("apksigner 未返回签名证书 SHA-256")
    actual = normalize_digest(match.group(1))
    expected = normalize_digest(expected_digest)
    if len(expected) != 64:
        raise VerificationError("预期签名证书 SHA-256 格式无效")
    if actual != expected:
        raise VerificationError("APK 签名证书与登记的发行证书不一致")


def scan_stream(stream, entry_name: str) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []
    tail = b""
    while True:
        chunk = stream.read(READ_CHUNK_BYTES)
        if not chunk:
            break
        candidate = tail + chunk
        for label, pattern in SENSITIVE_PATTERNS:
            if pattern.search(candidate):
                findings.append((entry_name, label))
        tail = candidate[-SCAN_OVERLAP_BYTES:]
    return findings


def scan_sensitive_content(apk: Path) -> None:
    findings: set[tuple[str, str]] = set()
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            normalized_name = info.filename.replace("\\", "/")
            encoded_name = normalized_name.encode("utf-8", errors="ignore")
            for label, pattern in SENSITIVE_PATTERNS:
                if pattern.search(encoded_name):
                    findings.add((normalized_name, label))
            if info.is_dir():
                continue
            with archive.open(info) as stream:
                findings.update(scan_stream(stream, normalized_name))
    if findings:
        summary = ", ".join(f"{entry} [{label}]" for entry, label in sorted(findings))
        raise VerificationError(f"APK 敏感信息扫描失败：{summary}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(READ_CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        raise VerificationError(f"APK 不存在：{apk}")
    size = apk.stat().st_size
    if size <= 0 or size > args.max_size_bytes:
        raise VerificationError(f"APK 大小不合规：{size} bytes，上限 {args.max_size_bytes} bytes")

    verify_zip(apk)
    verify_badging(apk, args.expected_package, args.expected_version_code, args.expected_version_name)
    verify_signature(apk, args.expected_cert_sha256)
    scan_sensitive_content(apk)

    digest = sha256_file(apk)
    checksum_output = args.checksum_output or apk.with_suffix(apk.suffix + ".sha256")
    checksum_output.parent.mkdir(parents=True, exist_ok=True)
    checksum_output.write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    print(f"APK 验证通过：{apk.name}，{size} bytes，SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        print(f"APK 验证失败：{error}", file=sys.stderr)
        raise SystemExit(1)
