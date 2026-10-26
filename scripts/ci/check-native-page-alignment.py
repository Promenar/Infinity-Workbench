#!/usr/bin/env python3
"""检查 APK 内 64 位原生库是否兼容 16KB 页大小。先构建相应的两个风味。

每个 arm64-v8a / x86_64 共享库的全部 PT_LOAD 段对齐必须不小于 16KB；以未压缩方式存放的库，
其在 ZIP 中的数据偏移也必须按 16KB 对齐，否则系统无法直接映射。借 .so 名随安装解压的非 ELF
数据载荷（如 Python 标准库归档）不经动态链接器加载，跳过对齐检查。
"""

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16384
PT_LOAD = 1
CHECKED_ABIS = ("arm64-v8a", "x86_64")


def load_alignments(data: bytes) -> list[int]:
    """返回 ELF64 小端共享库全部 PT_LOAD 段的 p_align。"""
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    if data[4] != 2 or data[5] != 1:
        raise ValueError("不是 64 位小端 ELF")
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if struct.unpack_from("<I", data, offset)[0] == PT_LOAD:
            alignments.append(struct.unpack_from("<Q", data, offset + 0x30)[0])
    if not alignments:
        raise ValueError("没有 PT_LOAD 段")
    return alignments


def data_offset(apk: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """本地文件头之后的数据起点；本地头的扩展字段长度可能与中央目录不同。"""
    apk.fp.seek(info.header_offset)
    header = apk.fp.read(30)
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def check_apk(path: Path) -> list[str]:
    """返回不合规条目说明；APK 中没有可检查的 64 位原生库也视为不合规。"""
    problems = []
    checked = 0
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[1] not in CHECKED_ABIS or not parts[2].endswith(".so"):
                continue
            data = apk.read(info)
            if data[:4] != b"\x7fELF":
                continue
            checked += 1
            try:
                alignments = load_alignments(data)
            except (ValueError, struct.error) as error:
                problems.append(f"{info.filename}：{error}")
                continue
            low = sorted({align for align in alignments if align < PAGE_SIZE})
            if low:
                problems.append(f"{info.filename}：PT_LOAD 对齐 {', '.join(hex(align) for align in low)} 小于 {hex(PAGE_SIZE)}")
            if info.compress_type == zipfile.ZIP_STORED and data_offset(apk, info) % PAGE_SIZE:
                problems.append(f"{info.filename}：未压缩存放但 ZIP 数据偏移未按 16KB 对齐")
    if checked == 0:
        problems.append("没有找到 arm64-v8a 或 x86_64 原生库")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-type", choices=("debug", "deviceTest", "minifiedTest", "release"), default="debug")
    parser.add_argument("--app-build-dir", type=Path, default=Path(__file__).resolve().parents[2] / "native-ui/app/build")
    parser.add_argument("apks", nargs="*", type=Path, help="直接指定 APK；省略时检查两个风味的构建产物")
    args = parser.parse_args()
    apks = args.apks
    if not apks:
        apk_dir = args.app_build_dir / "outputs/apk"
        apks = []
        for flavor in ("play", "sideload"):
            found = sorted((apk_dir / flavor / args.build_type).glob("*.apk"))
            if not found:
                print(f"16KB 页对齐检查失败：没有找到 {flavor} {args.build_type} APK", file=sys.stderr)
                return 1
            apks.extend(found)
    failed = False
    for apk in apks:
        try:
            problems = check_apk(apk)
        except (OSError, zipfile.BadZipFile) as error:
            problems = [str(error)]
        if problems:
            failed = True
            print(f"16KB 页对齐检查失败：{apk.name}", file=sys.stderr)
            for problem in problems:
                print(f"  {problem}", file=sys.stderr)
    if failed:
        return 1
    print(f"16KB 页对齐检查通过（{len(apks)} 个 APK）：64 位原生库 PT_LOAD 对齐均不小于 16KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
