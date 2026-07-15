#!/usr/bin/env python3
"""校验 v0.2-beta 发行状态一致性。"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ALLOWED_RELEASE_MARKERS = {"GO", "GO / PASS"}
RELEASE_STATUS_PREFIX = re.compile(r"^>\s*发布状态：\s*(.*?)\s*$")
TOP_STATUS_PREFIX = re.compile(r"^>\s*状态：\s*(.*?)\s*$")
FINAL_CONCLUSION_PREFIX = re.compile(r"^\s*-\s*最终结论：\s*(.*?)\s*$")


def parse_plain_value(raw: str) -> str:
    cleaned = raw.strip()
    for wrapper in ("**", "*", "`"):
        if cleaned.startswith(wrapper) and cleaned.endswith(wrapper) and len(cleaned) >= len(wrapper) * 2:
            cleaned = cleaned[len(wrapper):-len(wrapper)].strip()
    return cleaned


def find_marker_lines(lines: list[str], pattern: re.Pattern[str]) -> list[str]:
    values: list[str] = []
    for line in lines:
        match = pattern.match(line)
        if match:
            values.append(parse_plain_value(match.group(1)))
    return values


def fail(message: str) -> None:
    print(f"release-validator: {message}", file=sys.stderr)
    sys.exit(1)


def validate_file(path: Path) -> str:
    if not path.is_file():
        fail(f"文件不存在或不可读：{path}")
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        fail(f"文件读取失败：{path}（{error.__class__.__name__}）")


def require_exact_go_marker(
    document_name: str,
    lines: list[str],
    pattern: re.Pattern[str],
) -> None:
    matches = find_marker_lines(lines, pattern)
    if len(matches) != 1:
        fail(f"{document_name}必须出现 1 次，当前为 {len(matches)} 次")
    value = matches[0]
    if not value:
        fail(f"{document_name}值为空")
    if value not in ALLOWED_RELEASE_MARKERS:
        fail(f"{document_name}值非法：{value}，仅允许 GO 或 GO / PASS")


def validate_ledgers(validation_content: str, release_content: str) -> None:
    ledger_lines = validation_content.splitlines()
    require_exact_go_marker("验证账本顶部状态行", ledger_lines, TOP_STATUS_PREFIX)
    require_exact_go_marker("验证账本最终结论行", ledger_lines, FINAL_CONCLUSION_PREFIX)
    require_exact_go_marker("发布说明状态行", release_content.splitlines(), RELEASE_STATUS_PREFIX)


def main() -> int:
    parser = argparse.ArgumentParser(description="校验发行文档状态")
    parser.add_argument("validation_ledger")
    parser.add_argument("release_notes")
    args = parser.parse_args()

    validation_path = Path(args.validation_ledger)
    release_path = Path(args.release_notes)

    validation_content = validate_file(validation_path)
    release_content = validate_file(release_path)
    validate_ledgers(validation_content, release_content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
