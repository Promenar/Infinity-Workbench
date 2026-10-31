#!/usr/bin/env python3
"""按截图源文件确定性分片；未有参考图的新预览也会被纳入。"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def select_shard(root: Path, index: int, count: int) -> list[str]:
    if count < 1 or index < 0 or index >= count:
        raise ValueError("分片编号必须在范围内")
    app = root / "native-ui/app/src"
    source = app / "screenshotTest"
    reference = app / "screenshotTestDebug/reference"
    classes: dict[str, int] = {}
    for path in sorted(source.rglob("*.kt")):
        if "@PreviewTest" not in path.read_text(encoding="utf-8"):
            continue
        name = path.stem + "Kt"
        if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name) or name in classes:
            raise ValueError(f"截图源文件名无效或重复：{name}")
        matches = list(reference.rglob(name)) if reference.exists() else []
        if len(matches) > 1:
            raise ValueError(f"截图参考目录存在重名类：{name}")
        classes[name] = max(1, sum(1 for _ in matches[0].rglob("*.png"))) if matches else 1
    if not classes:
        raise ValueError("未发现截图预览源文件")
    shards: list[list[str]] = [[] for _ in range(count)]
    totals = [0] * count
    for name, weight in sorted(classes.items(), key=lambda item: (-item[1], item[0])):
        target = min(range(count), key=lambda position: (totals[position], position))
        shards[target].append(name)
        totals[target] += weight
    return sorted(shards[index])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--shard", type=int, required=True)
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--lines", action="store_true")
    args = parser.parse_args()
    result = select_shard(args.root, args.shard, args.count)
    if not result:
        parser.error("分片为空")
    print("\n".join(result) if args.lines else json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
