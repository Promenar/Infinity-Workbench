#!/usr/bin/env python3
"""检查真实合并清单的发行渠道权限与包身份。先构建相应的两个风味。"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PERMISSION = "android.permission.MANAGE_EXTERNAL_STORAGE"
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def check_manifests(play: Path, sideload: Path) -> None:
    manifests = [ET.parse(path).getroot() for path in (play, sideload)]
    packages = [manifest.get("package") for manifest in manifests]
    if not packages[0] or packages[0] != packages[1]:
        raise ValueError("两个风味的 applicationId 必须相同且非空")
    for flavor, manifest, expected in zip(("play", "sideload"), manifests, (False, True)):
        present = any(
            item.tag.startswith("uses-permission") and item.get(ANDROID_NAME) == PERMISSION
            for item in manifest
        )
        if present != expected:
            raise ValueError(f"{flavor} 合并清单的 MANAGE_EXTERNAL_STORAGE 不符合渠道契约")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", choices=("debug", "deviceTest", "minifiedTest", "release"), default="debug")
    parser.add_argument("--app-build-dir", type=Path, default=Path(__file__).resolve().parents[2] / "native-ui/app/build")
    args = parser.parse_args()
    paths = []
    for flavor in ("play", "sideload"):
        variant = flavor + args.build_type[0].upper() + args.build_type[1:]
        task = "process" + variant[0].upper() + variant[1:] + "Manifest"
        paths.append(args.app_build_dir / "intermediates/merged_manifests" / variant / task / "AndroidManifest.xml")
    try:
        check_manifests(*paths)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"发行清单检查失败：{error}", file=sys.stderr)
        return 1
    print(f"发行清单检查通过（{args.build_type}）：play 无所有文件访问权限，sideload 包含；包身份一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
