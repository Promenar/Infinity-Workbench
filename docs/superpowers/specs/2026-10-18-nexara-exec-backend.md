# Nexara 命令执行后端规格

- 状态：待评审
- 范围：`native-ui`（Android，targetSdk 36，minSdk 31）
- 方向：内置固定工具集（B）作为默认后端，Termux 与 SSH（D）作为完整 Linux 后端并存
- 依据：执行后端探针 `poc/exec-probe/`（分支 `poc/bd-exec`），在 API 31、API 35 模拟器与 OPPO PKH110（Android 16，ColorOS V16.1.0）上实测；数据见探针 README

## 1. 目标与非目标

目标：

1. 不安装 Termux 也能在会话工作区里运行 shell 命令和 Python 脚本，覆盖数据处理、文件批量处理、git 读写等常见代理任务。
2. 内置后端、Termux、SSH 共用一套工具结果格式、虚拟路径约定和失败语义，模型与界面不需要按后端区分处理。
3. 命令改动工作区后，Room 索引、版本哈希和界面在命令结束时与磁盘一致。
4. 工具失败时，模型和界面都能看到错误码、指引、退出码和 stderr，不因截断丢失根因。

非目标：

- 不提供完整 Linux 发行版、包管理器或编译工具链；需要这些的任务交给 Termux 或 SSH。
- 不从网络下载可执行二进制或原生扩展（W^X 与 Play 政策都不允许）。
- 不解决厂商后台冻结；只做检测与引导。
- 不改变现有工作区文件工具（读写、补丁、回收区、按路径审批、SAF 挂载）的接口与语义，它们仍是文件读写主接口。

## 2. 已验证的平台事实

| 事实 | 结论 |
| --- | --- |
| target ≥ 29 时 execve 应用数据目录中的文件 | 被拒（`execute_no_trans` on `app_data_file`） |
| 执行 `nativeLibraryDir` 中的打包二进制 | 可以；需 `packaging.jniLibs.useLegacyPackaging = true`，否则库不解压、无文件路径可执行 |
| 经 `linker64` 运行或 `System.load` 数据目录文件 | 当前可以，但触发 `avc: granted { execute }` 审计记录，属于可能被收紧的行为，不作为设计依赖 |
| isolatedProcess | 可执行打包二进制；看不到应用数据目录（ENOENT）；无网络（socket EACCES）；无可写目录 |
| 系统 `/system/bin/sh` 与 toybox | 可用；toybox 版本随设备不同（实测 0.8.12） |
| 官方 CPython 3.14.8 Android 包 | 只支持嵌入。用 `Py_BytesMain` 启动器 `libnxpython.so`（`-Wl,-rpath,'$ORIGIN'`）可在主进程与隔离进程运行 |
| Python 打包库方案 | stdlib 存为不压缩 zip `libpystdlib.so`，扩展模块改名 `libpyext_<模块>.so` 由 meta_path finder 加载；不需要解压、不从数据目录 dlopen |
| 启动耗时 | 打包库方案冷启动：主进程 0.43 秒，隔离进程 0.63 秒 |
| APK 增量 | +12.5 MB（arm64-v8a，已裁剪 `test/`、`idlelib/`、`tkinter/`、`turtledemo/`、`pydoc_data/`）；解压安装后库占用约 32.5 MB |
| pip | 纯 Python 包可装；numpy、pandas、pillow、cryptography、pydantic-core、lxml、pyyaml 等常用原生包在 PyPI 上无 android-arm64 wheel |
| TLS | Python ssl 读不了 Android CA 目录（旧式哈希文件名）；需把 `/apex/com.android.conscrypt/cacerts` 拼成 bundle 并设 `SSL_CERT_FILE` |
| git | 无官方 Android git；dulwich（纯 Python）可 HTTPS 克隆、add、commit、status |
| 后台（默认电池策略） | ColorOS Hans 在熄屏后冻结整个应用 uid，前台服务（`isForeground=true`、procState 4）同样被冻结；AOSP `isFrozen` 不反映该冻结 |
| 后台（“完全允许后台行为”，未充电锁屏 15 分钟） | 不再被冻结，前台服务内的子进程运行到结束；但 CPU 会深度休眠，每 5 秒一次的心跳只写了 49/约 175 行，最长间隔 66 秒。持续计算需要 `PARTIAL_WAKE_LOCK` |
| 对账耗时 | 2 万个 4 KB 文件：元数据遍历 171 ms，size+mtime 差异检测并重算差异文件哈希 266 ms；全量 SHA-256 483 ms |
| Termux SAF 挂载 | 读写挂载 Termux 主目录后双向可用：Termux 新建的文件立即在 Nexara `/mnt/<别名>` 可见；Nexara 经 `create_file` 写入的文件在 Termux 主目录可见，属主为 Termux uid |
| 共享存储 | Termux（`termux-setup-storage` 后）、adb 与 Nexara（“挂载存储路径”，需所有文件访问）在同一 `Download` 子目录中互相读写可见，内容一致 |

## 3. 总体结构

```
ToolExecutor
  └─ 命令类工具（shell_exec、python_exec、termux_exec、ssh_exec）
        └─ ExecBackend 契约：run(request) -> ExecResult
              ├─ BuiltinExecBackend   :exec 进程，打包工具集，工作区物理目录
              ├─ SandboxExecBackend   isolatedProcess，仅打包 Python，无文件系统、无网络
              ├─ TermuxExecBackend    现有 TermuxRuntime（RUN_COMMAND）
              └─ SshExecBackend       现有 SshRuntime
```

### 3.1 统一结果格式

所有命令类后端返回同一 JSON 对象，键顺序固定，根因字段在前：

| 字段 | 说明 |
| --- | --- |
| `error_code` | 后端或协议层错误码，命令本身运行成功（无论退出码）时为 null |
| `guidance` | 面向模型的下一步指引，可为 null |
| `exit_code` | 进程退出码；未启动或超时为 null |
| `timed_out` | 是否超时 |
| `stderr` / `stdout` | 输出，超限时保留尾部 |
| `stdout_original_length` / `stderr_original_length` | 原始长度，`length_unit` 为 `UTF-16` |
| `truncated` / `truncation` | 是否截断及方式（`head_removed`、`none`） |
| `workspace_changes` | 仅内置后端：命令结束对账得到的 `added` / `modified` / `removed` 虚拟路径列表（超过 200 项时给计数并截断） |

`TermuxCommandResult.toJson()` 已符合前八项；`ssh_exec` 需补 `error_code`、`guidance` 与原始长度字段。`status` 规则统一为：`error_code == null && !timed_out && exit_code == 0` 为 `success`，否则 `error`。

### 3.2 统一路径与环境

- 命令的工作目录是会话工作区物理根目录，模型侧路径仍以 `/` 表示工作区根。结果与指引中出现的物理路径在返回前替换为虚拟路径。
- `/mnt/<别名>` 是 SAF 挂载，只对文件工具可见，命令不可见。内置后端在 `guidance` 中说明需要先用文件工具复制到工作区。
- 环境变量统一：`HOME`（后端各自的家目录）、`TMPDIR`、`NEXARA_WORKSPACE`（工作区物理根）、`NEXARA_BACKEND`（`builtin` / `sandbox` / `termux` / `ssh`）、`LANG=C.UTF-8`。内置后端额外设置 `PYTHONHOME`、`PYTHONPATH`、`NEXARA_PYEXT_DIR`、`LD_LIBRARY_PATH`、`SSL_CERT_FILE`、`PYTHONNOUSERSITE=1`、`PYTHONDONTWRITEBYTECODE=1`。

## 4. 内置后端（B）

### 4.1 打包内容

| 组件 | 形式 | 说明 |
| --- | --- | --- |
| shell | 系统 `/system/bin/sh` | 不打包 busybox/toybox；命令文案声明“POSIX sh + 系统 toybox，具体命令随设备不同” |
| Python 3.14 | `libnxpython.so`、`libpython3.14.so`、`lib*_python.so`、`libpystdlib.so`、`libpyext_*.so` | 打包库方案；stdlib 裁剪同第 2 节 |
| `python3` / `python` / `pip` 入口 | `sh` 函数或 `$NEXARA_BIN` 下的包装脚本 | 包装脚本是数据目录里的文本文件，由 `sh` 解释执行，不 execve 数据目录文件 |
| git | dulwich（纯 Python，随 stdlib zip 打包）+ `git` 包装 | 支持 clone、status、add、commit、log、diff、push、pull 的常用子集；不支持的子命令返回 `GIT_UNSUPPORTED` 与指引 |
| CA bundle | 首次运行由 conscrypt 证书拼出，存于 `noBackupFilesDir` | 系统证书更新后按 apex 版本重建 |

ABI：发行包随 App Bundle 按 ABI 拆分，包含 arm64-v8a 与 x86_64（模拟器与测试），不打 armeabi-v7a。

清单变更：Nexara 当前 `extractNativeLibs=false`，需改为 `useLegacyPackaging = true`。代价是安装占用增加约 32.5 MB 且 Play 下载大小略增，需在 CHANGELOG 与发布说明中披露。

### 4.2 进程与安全

- 命令在独立的 `:exec` 进程中启动（非 isolated），以 Nexara uid 运行。命令可读写应用私有目录，包括 Room 数据库与配置；密钥由 Android Keystore 保护，命令无法直接取得明文，但不能视为强隔离。
- 因此 `shell_exec` 与 `python_exec` 的风险等级为 `ToolRisk.PROCESS`，沿用现有逐次审批；审批卡片展示完整命令、工作目录和“可访问 Nexara 私有数据与网络”的提示。审批绑定命令文本、工作目录与后端标识。
- 环境变量白名单启动，不继承主进程环境。
- 网络：默认允许（pip、git 需要）。是否增加“仅局域网白名单”开关与脚本运行时保持一致，不单独设计。
- 资源：单条命令默认 60 秒、最长 600 秒；stdout、stderr 各保留尾部 32,000 UTF-16 字符；超时先 SIGTERM，2 秒后 SIGKILL 整个进程组。

### 4.3 沙箱后端

`python_exec` 增加 `sandbox: true` 模式，在 isolatedProcess 中运行打包 Python：无网络、看不到应用数据。输入只能经 stdin 与参数中声明的工作区文件（主进程读出内容后通过 Binder 传入，进入 Python 的 `nexara.inputs` 映射）；输出为 stdout、stderr 与通过 `nexara.write(path, data)` 提交的文件，由主进程经现有工作区写入路径落盘（版本哈希、写入审批照常）。沙箱模式风险等级与 `run_script` 相同。

### 4.4 工作区一致性

1. 命令启动前对会话工作区加写锁，文件工具在锁内的写入排队等待；读取不受影响。
2. 启动前记录工作区的 `size + mtime` 快照（来自 Room 索引，缺失时现场遍历）。
3. 命令结束（含超时、取消）后遍历工作区，按 size+mtime 找出新增、修改、删除，只对新增与修改的文件重算 SHA-256，更新 Room 索引并写一条变更日志，然后释放写锁。2 万文件规模内耗时低于 0.3 秒，同步执行。
4. 命令直接删除或覆盖的文件不进入回收区，审批卡片须写明这一点。命令写入 `.nexara*`、`.recycle_bin` 等保留路径时，对账不把这些路径写入索引，并在结果中给出 `WORKSPACE_RESERVED_PATH` 警告。
5. 共享存储是 FUSE，不支持符号链接与可执行位，不适合放 git 仓库或虚拟环境；内置后端只把会话工作区作为工作目录。

### 4.5 后台与生命周期

- 命令运行期间以前台服务持有 `:exec` 进程，通知显示命令摘要与停止按钮。服务类型：侧载渠道用 `specialUse`；Play 渠道的类型与政策声明需单独评审。
- 命令运行期间持有 `PARTIAL_WAKE_LOCK`，超时上限即持有上限，命令结束、超时或取消时立即释放；不持有时锁屏后 CPU 休眠，命令会大幅变慢。
- 运行中若检测到心跳停滞（子进程 CPU 时间不增长且应用不在前台），结果追加 `guidance`：系统可能冻结了后台进程，请将 Nexara 设为“完全允许后台行为”。Termux 设置页已有同类文案，内置后端复用。
- 进程被系统结束时返回 `error_code = EXEC_PROCESS_KILLED`，对账照常执行。

### 4.6 分发渠道

| 能力 | 侧载 | Play |
| --- | --- | --- |
| shell_exec、python_exec、沙箱模式 | 有 | 有（全部代码随 APK 分发） |
| pip 安装 | 有，仅纯 Python 包，安装到 `filesDir/python-user` | 默认关闭，待政策评审 |
| git clone/pull | 有 | 有 |
| 前台服务类型 | `specialUse` | 待评审 |

## 5. Termux 与 SSH 后端（D）

- `termux_exec`、`ssh_exec` 保持现有接口，结果按第 3.1 节对齐。
- Termux 主目录与 Nexara 工作区互不可见。双向交换文件的方式：读写挂载 Termux 主目录到 `/mnt/<别名>`，文件工具在两边复制；Termux 侧命令看不到 Nexara 工作区。
- 模型选择后端的规则写进工具描述：需要完整 Linux、apt 包、原生 Python 扩展或编译时用 Termux/SSH；其余优先内置后端。

## 6. 失败文案结构化

状态：已实施（`ui/chat/manager/ToolFailureSummary.kt`、`WorkspaceToolSupport.missingEntryGuidance`）。

`ToolExecutor` 失败分支当前为 `"工具执行失败：${result.content.take(200)}"`，同一字符串既写入执行步骤（界面）也写入工具消息（模型）。Termux 结果 JSON 的 `stdout` 排在 `stderr`、`exit_code` 之前，stdout 稍长就会把退出码和 stderr 截掉；纯文本失败也只剩 200 字符。

改为 `ToolFailureSummary.format(content)`：

1. `content` 能解析为 JSON 对象且含任一根因或输出字段时，按顺序取 `error_code`、`guidance`、`exit_code`、`timed_out`、`err`、`errmsg`，再取其余标量字段（如 `truncated`、`length_unit`），最后取 `stderr` 与 `stdout`，组装为紧凑 JSON；值为 null 或空输出的字段省略。
2. 总长上限 4,000 UTF-16 字符（按 JSON 编码后长度计）；超出时依次压缩 `stdout`（保留尾部）、`stderr`（保留尾部）、`errmsg`（保留开头），`error_code`、`guidance`、`exit_code` 始终完整保留。截断处标注 `…(已截断，原始 N 字符)`，结果自带 `<字段>_original_length` 时取其与当前长度的较大值。
3. 非 JSON 内容，以及不含上述字段的 JSON，保留前 1,000 字符并标注原始长度。
4. 前缀“工具执行失败：”保留，供界面识别。
5. 执行步骤与工具消息使用同一摘要。

文件工具的“未找到条目”同样缺少下一步指引：`write_file` 只覆盖已有文件，模型对新路径调用时只得到“未找到条目：<路径>”，真机上多用了两轮才改用 `create_file`。`WorkspaceToolSupport` 的该错误追加指引：“新建文件请使用 create_file；先用 list_files 确认路径”。

测试（`ToolFailureSummaryTest`）：stdout 2 万字符的 Termux 失败结果保留 `exit_code` 与 stderr 尾部；只有 `error_code`/`guidance` 的结果原样保留；超长 guidance 不被截断；非 JSON 文本按 1,000 字符截断并带原始长度；中文与代理对不被截断在半个字符上。

## 7. 实施顺序

1. 失败文案结构化与文件工具“未找到条目”指引（第 6 节），独立于执行后端，可先合入。
2. `ExecBackend` 契约与统一结果格式；`ssh_exec` 结果对齐。
3. 打包 Python（构建脚本纳入 Gradle 任务，产物不入库，按版本与 SHA-256 校验官方包）与 `useLegacyPackaging`。
4. `:exec` 进程、`shell_exec`、`python_exec`、审批卡片、工作区写锁与对账。
5. 沙箱模式。
6. dulwich 与 `git` 包装、pip（侧载）。
7. 前台服务、唤醒锁与冻结检测文案。

每一步的验收：单元测试覆盖结果格式、对账差异、失败摘要；设备测试在 API 31、API 35 模拟器与真机上覆盖命令执行、超时、取消、对账与审批。

## 8. 待验证与风险

| 项目 | 状态 |
| --- | --- |
| 持有 `PARTIAL_WAKE_LOCK` 后锁屏未充电时心跳是否连续，以及耗电 | 未测 |
| Play Feature Delivery 按需模块中的原生库是否进入可执行的 `nativeLibraryDir`，可否把 Python 改为按需下载 | 未测 |
| Play 政策对解释器加载 pip 包的适用性 | 未评审 |
| dulwich 的 push、pull 与认证（已实测 clone、add、commit、status） | 未测 |
| 沙箱模式下 Binder 传输大文件（超过 1 MB 需分块） | 未实现 |
| `linker64` / 数据目录 dlopen 的 auditallow 在后续系统版本被收紧 | 设计不依赖该行为 |
| 命令绕过回收区，删除与覆盖不可恢复 | 审批时明示；是否加硬链接快照另行评估 |
