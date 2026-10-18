#!/usr/bin/env bash
# 用 NDK clang 预编译探针，输出到 jniLibs（AGP 不打包 CMake 可执行目标）。
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android31-clang"
OUT=app/src/main/jniLibs/arm64-v8a
mkdir -p "$OUT"
"$CC" -O2 -fPIE -pie -o "$OUT/libnxprobe.so" app/src/main/cpp/probe.c
"$CC" -O2 -fPIC -shared -o "$OUT/libnxdl.so" app/src/main/cpp/dl.c
ls -l "$OUT"
