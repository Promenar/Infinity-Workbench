# termux.c 来源

`termux.c` 原样取自 [termux/termux-app](https://github.com/termux/termux-app) 标签 `v0.118.0`（提交 `6e2689f55295fa444be8ac8592c527c2c5ef3253`）的 `terminal-emulator/src/main/jni/termux.c`，SHA-256 `729112f2e66cdddb7e7311ddf8a89ad54037a54bddbf4d5291f2e1fff5b97373`，许可证 Apache-2.0（见 `docs/legal/ssh/termux-LICENSE.md`）。

`terminal-emulator:0.118.0` AAR 内预编译的 `libtermux.so` 按 4KB 页对齐，会在 16KB 页设备上触发兼容模式。本目录源码由 `../CMakeLists.txt` 以 16KB 最大页大小重新编译，构建时替换 AAR 自带副本；JNI 入口（`com.termux.terminal.JNI`）与上游一致。升级 terminal-emulator 依赖时须同步替换本文件并更新上述标签、提交与哈希。
