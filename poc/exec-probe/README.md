# 执行后端（B+D）探针

独立的 Android 应用（包名 `com.promenar.nexara.execprobe`，targetSdk 36，minSdk 31），用来验证“APK 内打包固定工具集（B）”在目标设备上的可行性。它不依赖 Nexara 主工程，也不接触 Nexara 的数据。

## 构建与运行

```bash
./build-native.sh          # 用 NDK clang 预编译 libnxprobe.so（可执行文件）与 libnxdl.so（共享库）
./gradlew assembleDebug
adb -s <serial> install app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.promenar.nexara.execprobe/.ProbeActivity --es t basic
adb -s <serial> logcat -d -s NXPROBE:I
```

测试名：`basic`、`iso`、`bg`（`--ei seconds N --ez busy true|false`）、`fgs`（同上）、`fgs_stop`、`status`、`py`（`--es code` 或 `--esa margs`，解压标准库方案）、`pyz`（打包库方案）、`recon`（`--ei n 文件数 --ei size 字节`）。

Python 相关测试需先下载官方 CPython Android 包（`python-3.14.8-aarch64-linux-android.tar.gz`），解压后执行 `./prepare-python.sh <解压目录>/prefix`。`jniLibs/` 与 `assets/python/` 均为脚本产物，不入库。

AGP 只打包 CMake 产出的共享库，不打包可执行目标，所以探针二进制用 `build-native.sh` 预编译后放进 `jniLibs`，并以 `lib*.so` 命名。`packaging.jniLibs.useLegacyPackaging = true` 让安装器把它解压到 `nativeLibraryDir`。

## 结果

设备：API 31 模拟器、API 35 模拟器、OPPO PKH110（Android 16，ColorOS V16.1.0，SELinux enforcing）。三台设备在下表各项上结果一致。

| 项目 | 结果 | 依据 |
| --- | --- | --- |
| 执行 `nativeLibraryDir` 中的打包二进制 | 可以 | `exit=0`，域为 `untrusted_app` |
| 直接 execve 应用数据目录中的文件 | 拒绝 | `error=13`；`avc: denied { execute_no_trans } ... app_data_file` |
| 经 `/system/bin/linker64` 运行数据目录中的文件 | 当前可以 | `exit=0`，但伴随 `avc: granted { execute }` 审计日志 |
| `System.load` 数据目录中的 `.so` | 当前可以 | 同样记录 `avc: granted { execute }` |
| `System.loadLibrary` 打包库 | 可以 | — |
| 系统 `sh` / toybox | 可以 | 手机为 toybox 0.8.12；`/system/bin` 不可列目录 |
| 主进程网络 | 可以 | 手机直连 1.1.1.1:443 成功 |
| isolatedProcess 执行打包二进制 | 可以 | 域为 `isolated_app`，uid 为 `u0_iNNNN` |
| isolatedProcess 读取主进程私有文件 | 不可见 | `ENOENT`（数据目录不在其挂载视图中） |
| isolatedProcess 网络 | 拒绝 | `socket: errno=13` |
| isolatedProcess 可写目录 | 无 | `getCacheDir()` 不存在 |

`avc: granted` 是 SELinux 的 auditallow 记录：策略当前放行，但被标记为需要审计的行为。依赖 linker64 或数据目录 dlopen 的方案（例如 pip 安装的原生扩展）存在被后续系统版本收紧的风险。

## 后台存活（OPPO PKH110，ColorOS V16.1.0，USB 充电中）

| 场景 | 结果 |
| --- | --- |
| 无前台服务，熄屏 | 应用转为缓存进程（`procState=19`），随后被 ColorOS Hans 冻结；进程仍在，CPU 时间不再增长，心跳停写，回到前台后恢复 |
| 前台服务（`specialUse`），熄屏 | 服务确在前台（`isForeground=true`、`procState=4`、oom adj 200），仍在启动约 10 秒后被 Hans 冻结，60 秒内只写 2 行心跳 |
| 透明 Activity 保持“前台” | 不被视为前台，同样冻结 |

冻结来自厂商的 `OplusHansManager`/`OFreezer`（日志 `FROZEN_TRANS`），AOSP `dumpsys activity` 的 `isFrozen` 始终为 false。冻结的是整个应用 uid，子进程一并停下。未测：设为“完全允许后台行为”后的表现，以及不充电时的表现。

结论：B 不能靠前台服务绕过 ColorOS 后台管理，长任务仍需要用户把 Nexara 设为“完全允许后台行为”；与 D（Termux）相比，需要放行的应用从两个减为一个。

## Python

官方 CPython 3.14.8 Android 包（aarch64，22,915,536 字节，SHA-256 `11324313…cf9a33`，与 sigstore 包内摘要一致，签名者身份 hugo@python.org；本机未安装 cosign，未做密码学验签）只支持嵌入，不含 `python` 可执行文件。探针用 `pylauncher.c`（`Py_BytesMain`，`-Wl,-rpath,'$ORIGIN'`）编译为 `libnxpython.so` 放进 jniLibs。

| 方案 | 做法 | 结果 |
| --- | --- | --- |
| 解压标准库 | stdlib 放 assets，首次运行解压到 `filesDir/python`；扩展模块从数据目录 dlopen | 可用。解压 1.2 秒，冷启动 0.54 秒，热启动约 0.15 秒。扩展模块从数据目录加载，依赖当前的 `avc: granted` 放行，隔离进程不可用 |
| 打包库 | stdlib 存为不压缩 zip `libpystdlib.so`（zlib 本身是扩展模块，只能 STORED）；扩展改名 `libpyext_<模块>.so`，由 `sitecustomize.py` 的 meta_path finder 加载 | 可用，且主进程与隔离进程都能运行。冷启动主进程 0.43 秒、隔离进程 0.63 秒；ssl、sqlite3、hashlib、zlib、ctypes、decimal、socket 均可导入 |

两个打包细节：AAPT 默认 `ignoreAssetsPattern` 会丢弃以 `_` 开头的目录（如 `zipfile/_path`），需覆盖；isolatedProcess 内无网络、无可写目录，也看不到主进程数据，需要由主进程代理文件访问或传递文件描述符。

| 生态 | 结果 |
| --- | --- |
| pip | `ensurepip` 自带 pip 26.2.1，纯 Python 包可装（`six` 2.45 秒）。PyPI 上 numpy、pandas、pillow、cryptography、pydantic-core、lxml、pyyaml、regex、orjson、matplotlib 均无 android-arm64 wheel |
| TLS | Python ssl 不认 Android 的 CA 目录：`/apex/com.android.conscrypt/cacerts` 是旧式哈希文件名，`SSL_CERT_DIR` 无效。需把 149 张证书拼成 bundle 并设 `SSL_CERT_FILE` |
| git | 无官方 Android git。纯 Python 的 dulwich 1.2.17（+urllib3）可 HTTPS 克隆（octocat/Hello-World 6.2 秒）；init/add/commit/status 正确（第二次提交父指针正确、树含两文件、提交后状态干净，16 毫秒） |

## 包体

均为 clean build 的 debug APK（增量构建会留下空洞，不能用来比较）。

| 内容 | APK 大小 | 增量 |
| --- | --- | --- |
| 仅探针 | 2,500,797 | — |
| 探针 + Python 打包库方案（无 assets） | 15,021,647 | +12.5 MB |
| 探针 + Python 解压方案（stdlib 在 assets） | 15,246,414 | +12.7 MB |

`useLegacyPackaging` 会把 jniLibs 解压安装，所以安装后占用高于 APK：解压后的库约 32.5 MB。裁剪掉的 stdlib 目录：`test/`（37 MB）、`idlelib/`、`tkinter/`、`turtledemo/`、`pydoc_data/`。

## 命令后工作区索引对账

`recon` 在 `filesDir` 生成文件，测元数据遍历、全量 SHA-256，以及子进程（`sh`）改 20 个、新建 5 个、删除 5 个文件后按 size+mtime 找差异并只重算差异文件哈希的耗时。不含 Room 写入，文件刚写入、页缓存是热的。

| 规模 | 遍历元数据 | 全量哈希 | 差异检测 + 差异重算 |
| --- | --- | --- | --- |
| 5,000 × 4 KB | 88 ms | 223 ms | 69 ms |
| 20,000 × 4 KB | 171 ms | 483 ms | 266 ms |
| 200 × 1 MB | 3 ms | 150 ms（200 MB） | 22 ms |

差异检测准确识别 20/5/5。结论：命令结束后按 size+mtime 对账、只对变动文件重算哈希，在两万文件规模内是亚秒级，可以同步进行；全量哈希只在首次建索引或 mtime 不可信时使用。

## Termux SAF 挂载

在 Nexara 设置 → 工具设置 → Termux 接入与诊断 → “挂载 Termux 主目录”中以读写方式挂载（系统选择器侧栏选 Termux，授权对话框允许），得到 `/mnt/termux`（可读写、所有会话）。Termux 中新建的文件立即出现在 Nexara 资源管理器的挂载浏览中，内容一致。Nexara 界面的挂载浏览只读，写入需经文件工具。
