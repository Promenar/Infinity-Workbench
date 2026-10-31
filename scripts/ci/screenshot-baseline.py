#!/usr/bin/env python3
"""校验 Linux 覆盖参考图与本地基线，再在新目录合成发行测试参考图。"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path, PurePosixPath


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(base: Path, overlay: Path, output: Path) -> None:
    base, overlay, output = base.resolve(), overlay.resolve(), output.resolve()
    if output.exists() or output == base or output == overlay or base in output.parents or overlay in output.parents:
        raise ValueError("输出目录必须是输入目录以外的全新目录")
    for directory in (base, overlay):
        if any(path.is_symlink() for path in directory.rglob("*")):
            raise ValueError("截图参考目录不得包含符号链接")
    manifest = json.loads((overlay / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or not isinstance(manifest.get("entries"), dict):
        raise ValueError("Linux 截图参考清单格式无效")
    entries = manifest["entries"]
    if not entries:
        raise ValueError("Linux 截图参考清单不能为空")
    for name, hashes in entries.items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or relative.suffix != ".png" or str(relative) != name:
            raise ValueError(f"截图参考路径无效：{name}")
        original, candidate = base / name, overlay / name
        if not original.is_file() or not candidate.is_file() or not isinstance(hashes, dict):
            raise ValueError(f"截图参考缺失：{name}")
        if digest(original) != hashes.get("base_sha256") or digest(candidate) != hashes.get("linux_sha256"):
            raise ValueError(f"截图参考已变化，须重新审阅：{name}")
    actual = {str(path.relative_to(overlay)) for path in overlay.rglob("*.png")}
    if actual != set(entries):
        raise ValueError("Linux 截图覆盖文件与清单不一致")
    shutil.copytree(base, output)
    for name in entries:
        shutil.copyfile(overlay / name, output / name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.base, args.overlay, args.output)


if __name__ == "__main__":
    main()
