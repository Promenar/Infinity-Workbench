#!/usr/bin/env python3
"""全站文本换行静态门禁。

规则：
1. 主题目录外不得直接新建 `TextStyle(...)`，除非紧跟 `.forInput()` 或 `.forReading()`；
   界面样式必须来自 typography 角色以继承换行策略。
2. 界面代码（`ui/` 目录）不得用 " · " 或 " • " 拼接元数据；通知、导出等非排版文本不受此限；多段元数据使用 `InfinityMetaLine`。
   确属单行省略号、无障碍描述或完整句子时，在同一行注释 `wrap-check: allow <原因>`。
3. `OutlinedTextField` / `TextField` 必须传入带 `.forInput()` 的 `textStyle`；
   `BasicTextField` 传入的 `textStyle` 必须带 `.forInput()`。
4. 字符串资源中带占位符且含 "·" 的格式串只允许出现在白名单中。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ALLOW_MARKER = "wrap-check: allow"

# 带占位符的“·”格式串：标题前缀、完整句子或无障碍状态，排版上作为单个短语处理。
FORMAT_STRING_ALLOWLIST = {
    "settings_provider_status_summary",
    "chat_branch_title",
    "chat_task_title",
    "provider_models_test_success",
    "token_in_out",
    "doc_editor_statistics",
    "rag_debug_total_vectors",
    "share_import_pending_banner",
    "generation_notification_current_session",
}

FIELD_CALL = re.compile(r"(?<![A-Za-z_])(OutlinedTextField|TextField|BasicTextField)\(")
RAW_STYLE = re.compile(r"(?<![A-Za-z_.])TextStyle\(")
DOT_JOIN = re.compile(r'" [·•] "|[·•] \$|\$\{?[A-Za-z_][\w.]*\}? [·•] ')
STRING_ENTRY = re.compile(r'<string name="([^"]+)"[^>]*>([^<]*)</string>')


def matching_paren(text: str, open_index: int) -> int:
    depth = 0
    in_string = False
    index = open_index
    while index < len(text):
        char = text[index]
        if char == '"' and text[index - 1] != "\\":
            in_string = not in_string
        elif not in_string:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return index
        index += 1
    return len(text) - 1


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def line_text(text: str, offset: int) -> str:
    start = text.rfind("\n", 0, offset) + 1
    end = text.find("\n", offset)
    return text[start : end if end >= 0 else len(text)]


def top_level_argument(arguments: str, name: str) -> str | None:
    """返回调用参数列表中顶层（括号深度 0）具名参数的表达式。"""
    depth = 0
    in_string = False
    index = 0
    value_start = None
    while index < len(arguments):
        char = arguments[index]
        if char == '"' and (index == 0 or arguments[index - 1] != "\\"):
            in_string = not in_string
        elif not in_string:
            if value_start is not None:
                if char in "([{":
                    depth += 1
                elif char in ")]}":
                    if depth == 0:
                        return arguments[value_start:index].strip()
                    depth -= 1
                elif char == "," and depth == 0:
                    return arguments[value_start:index].strip()
            elif char in "([{":
                depth += 1
            elif char in ")]}":
                depth -= 1
            elif depth == 0 and (index == 0 or arguments[index - 1] in " \t\n,("):
                match = re.match(rf"{name}\s*=(?!=)\s*", arguments[index:])
                if match:
                    value_start = index + match.end()
                    index = value_start
                    continue
        index += 1
    return arguments[value_start:].strip() if value_start is not None else None


def check_kotlin(path: Path, relative: str) -> list[str]:
    text = path.read_text(encoding="utf-8")
    problems: list[str] = []
    in_theme = "/ui/theme/" in f"/{relative}"

    if not in_theme:
        for match in RAW_STYLE.finditer(text):
            if "import " in line_text(text, match.start()):
                continue
            close = matching_paren(text, match.end() - 1)
            tail = text[close + 1 : close + 20]
            if not re.match(r"\s*\.(forInput|forReading)\(\)", tail):
                problems.append(f"{relative}:{line_of(text, match.start())}: 主题外直接新建 TextStyle，改用 typography 角色")

    is_ui = "/ui/" in f"/{relative}"
    for match in DOT_JOIN.finditer(text) if is_ui else ():
        line = line_text(text, match.start())
        if ALLOW_MARKER in line or line.lstrip().startswith(("//", "*", "/*")):
            continue
        problems.append(f"{relative}:{line_of(text, match.start())}: 以“·”拼接元数据，改用 InfinityMetaLine")

    for match in FIELD_CALL.finditer(text):
        prefix = line_text(text, match.start())
        if "fun " in prefix or "import " in prefix:
            continue
        close = matching_paren(text, match.end() - 1)
        style = top_level_argument(text[match.end() : close], "textStyle")
        kind = match.group(1)
        if style is None:
            if kind != "BasicTextField":
                problems.append(f"{relative}:{line_of(text, match.start())}: {kind} 缺少 textStyle = ….forInput()")
        elif ".forInput()" not in style:
            problems.append(f"{relative}:{line_of(text, match.start())}: {kind} 的 textStyle 未使用 .forInput()")
    return problems


def check_strings(path: Path, relative: str) -> list[str]:
    problems = []
    text = path.read_text(encoding="utf-8")
    for match in STRING_ENTRY.finditer(text):
        name, value = match.groups()
        if "·" in value and "%" in value and name not in FORMAT_STRING_ALLOWLIST:
            problems.append(f"{relative}:{line_of(text, match.start())}: 带占位符的“·”格式串 {name}，拆成分段资源并用 InfinityMetaLine")
    return problems


def run(root: Path) -> list[str]:
    source_root = root / "native-ui/app/src/main"
    problems: list[str] = []
    for path in sorted((source_root / "java").rglob("*.kt")):
        if ".mimosa" in path.parts:
            continue
        problems += check_kotlin(path, path.relative_to(root).as_posix())
    for path in sorted((source_root / "res").glob("values*/strings.xml")):
        problems += check_strings(path, path.relative_to(root).as_posix())
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=Path(__file__).resolve().parents[2], type=Path)
    args = parser.parse_args()
    problems = run(args.root)
    for problem in problems:
        print(problem)
    if problems:
        print(f"文本换行门禁失败：{len(problems)} 项", file=sys.stderr)
        return 1
    print("文本换行门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
