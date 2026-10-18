#!/usr/bin/env bash
# 用法：./prepare-python.sh <官方 Android 包解压后的 prefix 目录>
# 生成 jniLibs 中的 libpython、依赖库与启动器，以及 assets 中裁剪后的标准库（不入库）。
set -euo pipefail
cd "$(dirname "$0")"
PREFIX="$(cd "$1" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android31-clang"
LIBS=app/src/main/jniLibs/arm64-v8a
ASSETS=app/src/main/assets/python/lib/python3.14
mkdir -p "$LIBS"
cp "$PREFIX"/lib/libpython3.14.so "$PREFIX"/lib/lib*_python.so "$LIBS"/
"$CC" -O2 -fPIE -pie -I"$PREFIX/include/python3.14" -L"$PREFIX/lib" -lpython3.14 \
    -Wl,-rpath,'$ORIGIN' -o "$LIBS/libnxpython.so" app/src/main/cpp/pylauncher.c
rm -rf app/src/main/assets/python
mkdir -p "$ASSETS"
rsync -a --exclude '__pycache__' --exclude 'test/' --exclude 'idlelib/' --exclude 'tkinter/' \
    --exclude 'turtledemo/' --exclude 'pydoc_data/' "$PREFIX/lib/python3.14/" "$ASSETS/"
du -sh "$LIBS" app/src/main/assets/python

# 打包库方案：标准库纯 Python 部分存为不压缩的 zip（libpystdlib.so；zlib 本身是扩展模块，启动时尚不可用），原生扩展改名为 libpyext_<模块>.so。
rm -f "$LIBS"/libpyext_*.so "$LIBS/libpystdlib.so"
for f in "$PREFIX"/lib/python3.14/lib-dynload/*.so; do
    mod="$(basename "$f" | cut -d. -f1)"
    cp "$f" "$LIBS/libpyext_${mod}.so"
done
STAGE="$(mktemp -d)"
rsync -a --exclude '__pycache__' --exclude 'test/' --exclude 'idlelib/' --exclude 'tkinter/' --exclude 'turtledemo/' \
    --exclude 'pydoc_data/' --exclude 'lib-dynload/' --exclude 'site-packages/' "$PREFIX/lib/python3.14/" "$STAGE/"
cp app/src/main/python/sitecustomize.py "$STAGE/"
(cd "$STAGE" && zip -qr -0 "$OLDPWD/$LIBS/libpystdlib.so" .)
rm -rf "$STAGE"
ls -l "$LIBS/libpystdlib.so"; ls "$LIBS"/libpyext_*.so | wc -l
