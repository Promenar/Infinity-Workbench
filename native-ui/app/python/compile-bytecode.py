"""构建期把目录中的 .py 编译为同目录的 .pyc，供打包 Python 的 zipimport 直接加载。

zipimport 只在 .py 旁查找同名 .pyc（不读 __pycache__），并按 pyc 头部 magic 判断能否使用。
生成不检查源码的哈希 pyc：打包 zip 随 APK 只读安装，不存在源码与字节码不同步。
co_filename 取相对根目录的路径（即 zip 内路径），不写入构建机的绝对路径。
上游故意含语法错误的样例等无法编译的文件跳过并列出，只保留源码。

用法：python3.<主次版本> -I compile-bytecode.py --magic <PYC_MAGIC_NUMBER> <目录>
宿主解释器的 pyc magic 与打包 CPython 不一致时退出码为 3，不写任何 pyc。
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import py_compile
import sys

MAGIC_MISMATCH = 3


def host_magic() -> int:
    return int.from_bytes(importlib.util.MAGIC_NUMBER[:2], "little")


def compile_tree(root: str) -> tuple[int, list[str]]:
    compiled = 0
    skipped: list[str] = []
    for directory, subdirectories, names in os.walk(root):
        subdirectories.sort()
        for name in sorted(names):
            if not name.endswith(".py"):
                continue
            path = os.path.join(directory, name)
            relative = os.path.relpath(path, root).replace(os.sep, "/")
            try:
                py_compile.compile(
                    path,
                    cfile=path + "c",
                    dfile=relative,
                    doraise=True,
                    invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH,
                )
            except py_compile.PyCompileError as error:
                skipped.append(f"{relative}（{error.exc_type_name}）")
            else:
                compiled += 1
    return compiled, skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="把目录中的 .py 编译为同目录哈希 pyc。")
    parser.add_argument("--magic", type=int, required=True, help="打包 CPython 的 PYC_MAGIC_NUMBER")
    parser.add_argument("root")
    args = parser.parse_args(argv)

    magic = host_magic()
    if magic != args.magic:
        print(
            f"宿主 Python {sys.version.split()[0]} 的 pyc magic {magic} 与打包 CPython 的 {args.magic} 不一致",
            file=sys.stderr,
        )
        return MAGIC_MISMATCH
    compiled, skipped = compile_tree(args.root)
    for entry in skipped:
        print(f"跳过 {entry}", file=sys.stderr)
    print(f"已编译 {compiled} 个模块，跳过 {len(skipped)} 个")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
