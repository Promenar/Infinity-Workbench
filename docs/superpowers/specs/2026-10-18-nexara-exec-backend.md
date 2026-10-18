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
| 亮屏无前台界面 | 内置执行设备测试（instrumentation，无 Activity）在亮屏时启动约 3 秒后被 Hans 冻结，连同刚 fork 尚未 exec 的子进程；冻结期间测试与子进程都不推进，收到 SIGQUIT 后解冻并继续，SIGCONT 不解冻；内置后端改以轮询间隔检测暂停并把暂停时间排除在超时之外（第 4.5 节） |
| 亮屏后台（命令前台服务） | 正式包在真机上运行 `sleep 90` 时回到桌面，`specialUse` 前台服务在前台、通知计时持续，约 50 秒内 Hans 无冻结记录；通知“停止”后模型收到 `EXEC_STOPPED` 并按指引不重试 |
| 后台（“完全允许后台行为”，未充电锁屏 15 分钟） | 不再被冻结，前台服务内的子进程运行到结束；但 CPU 会深度休眠，每 5 秒一次的心跳只写了 49/约 175 行，最长间隔 66 秒。持续计算需要 `PARTIAL_WAKE_LOCK` |
| 对账耗时 | 2 万个 4 KB 文件：元数据遍历 171 ms，size+mtime 差异检测并重算差异文件哈希 266 ms；全量 SHA-256 483 ms |
| Termux SAF 挂载 | 读写挂载 Termux 主目录后双向可用：Termux 新建的文件立即在 Nexara `/mnt/<别名>` 可见；Nexara 经 `create_file` 写入的文件在 Termux 主目录可见，属主为 Termux uid |
| 共享存储 | Termux（`termux-setup-storage` 后）、adb 与 Nexara（“挂载存储路径”，需所有文件访问）在同一 `Download` 子目录中互相读写可见，内容一致 |

## 3. 总体结构

```
ToolExecutor
  └─ 命令类工具（shell_exec、python_exec、termux_exec、ssh_exec）
        └─ 共用结果契约 CommandToolResult（第 3.1 节）
              ├─ BuiltinExecService   主进程子进程（独立进程组），打包 Python，工作区物理目录
              ├─ SandboxExecBackend   isolatedProcess，仅打包 Python，无文件系统、无网络（第 5 步）
              ├─ TermuxExecSkill      现有 TermuxRuntime（RUN_COMMAND）
              └─ SshSkills            现有 SshRuntime
```

状态：内置后端已实施（`data/exec/BuiltinExecService.kt`、`BuiltinCommandRunner.kt`、`BuiltinExecEnvironment.kt`，工具 `ui/chat/manager/skills/BuiltinExecSkills.kt`）。三个后端的请求参数与生命周期差异较大（Termux 经 Intent 回调、SSH 经会话池、内置经子进程与工作区锁），共享的是结果格式而非调用接口，因此不另设 `ExecBackend` 接口；沙箱模式落地时再评估是否与内置后端合并请求模型。

### 3.1 统一结果格式

状态：已实施（`domain/tool/CommandToolResult.kt`；`termux_exec`、`ssh_exec` 已接入）。

所有命令类后端返回同一 JSON 对象，键顺序固定，根因字段在前，后端专有字段排在共有字段之后：

| 字段 | 说明 |
| --- | --- |
| `error_code` | 后端或协议层错误码，命令本身运行成功（无论退出码）时为 null |
| `guidance` | 面向模型的下一步指引，可为 null |
| `exit_code` | 进程退出码；未启动或超时为 null |
| `timed_out` | 是否超时 |
| `stderr` / `stdout` | 输出；超限时的保留方向见 `truncation` |
| `stdout_original_length` / `stderr_original_length` | 原始长度，`length_unit` 为 `UTF-16`；后端无法得知时为 null |
| `stdout_truncated` / `stderr_truncated` | 各输出流是否截断 |
| `truncated` / `truncation` | 是否截断及去掉的部分（`head_removed`、`tail_removed`、`none`） |
| `workspace_changes` | 仅内置后端：命令结束对账得到的 `added`、`modified`、`removed`（整目录删除只列最上层）与 `moved`（`{from,to}`，整目录移动只列最上层）虚拟路径，`counts` 给各类总数，四类合计超过 200 项时按此顺序截断并置 `truncated`；对账失败时为 null |
| `workspace_warnings` | 仅内置后端，可省略：`[{code, count, paths}]`，`code` 为 `WORKSPACE_RESERVED_PATH`（改动 `.nexara*`、`.recycle_bin` 或新建顶层 `mnt`）或 `WORKSPACE_UNSUPPORTED_ENTRY`（新建或改动的符号链接、特殊文件、文件工具不接受的名称），`paths` 最多 20 项 |
| `paused_ms` | 仅内置后端，可省略：等待期间 Nexara 被系统暂停（后台冻结或深度休眠）的累计毫秒数，达到 3 秒才给出；暂停时间不计入超时 |

`status` 规则统一为：`error_code == null && !timed_out && exit_code == 0` 为 `success`，否则 `error`。因此 `ssh_exec` 退出码非 0（如 `grep` 无匹配、`diff` 有差异）也记为失败步骤，模型收到第 6 节的失败摘要（不超过 4,000 字符，保留根因字段与输出尾部），与 `termux_exec` 一致。

各后端的差异：

- 内置后端（`shell_exec`、`python_exec`）：stdout、stderr 各保留尾部 32,000 UTF-16 字符（`head_removed`），原始长度按完整解码计数。命令本身运行（无论退出码）时 `error_code` 为 null；后端错误码为 `EXEC_INVALID_ARGUMENTS`、`EXEC_PYTHON_UNAVAILABLE`、`EXEC_WORKSPACE_UNAVAILABLE`、`EXEC_START_FAILED`、`EXEC_TIMEOUT`（`exit_code` 为 null）、`EXEC_STOPPED`（用户在通知栏停止，`exit_code` 为 null，指引要求不自动重试）、`EXEC_PROCESS_KILLED`（退出码 137 且不是本端发出的 SIGKILL）与 `WORKSPACE_RECONCILE_FAILED`（命令已执行但索引未更新，`exit_code` 照常给出）。只有警告时 `guidance` 说明警告含义，状态不受影响；出现 `paused_ms` 时 `guidance` 追加冻结原因与“完全允许后台行为”设置指引。
- `termux_exec`：输出由 Termux 截断并保留尾部（`head_removed`），专有字段 `err`、`errmsg`、`errmsg_original_length`、`errmsg_truncation_possible` 排在共有字段之后。
- `ssh_exec`：凭据与私钥块遮蔽需要完整前文，输出在遮蔽之后截断并保留开头（`tail_removed`）。stdout 与 stderr 合计超过 `max_output_chars` 时按 `splitOutputBudget` 分配：一方不足一半时余量让给另一方，双方都超过一半时各取一半。字节采集上限（`max_output_chars × 4` 字节）被触发时原始长度未知，对应字段为 null。超时返回 `error_code` = `SSH_TIMEOUT`、`exit_code` = null。
- SSH 其余失败（参数、主机、凭据、指纹、连接、会话上限、SFTP 冲突等）返回 `{"error_code":"SSH_<码>","guidance":"..."}`，指引按错误码给出，不含主机地址、用户名或凭据。

### 3.2 统一路径与环境

- 命令的工作目录是会话工作区物理根目录，文件工具与 `workspace_changes` 仍以 `/` 表示工作区根。内置后端把 stdout、stderr 中的工作区物理路径替换为 `$NEXARA_WORKSPACE` 而不是 `/`：替换后既不暴露应用目录，又能原样用于下一条命令；以 `/` 开头的虚拟路径在命令里会指向设备根目录。
- `/mnt/<别名>` 是 SAF 挂载，只对文件工具可见，命令不可见。内置后端在 `guidance` 中说明需要先用文件工具复制到工作区。
- 环境变量统一：`HOME`（后端各自的家目录）、`TMPDIR`、`NEXARA_WORKSPACE`（工作区物理根）、`NEXARA_BACKEND`（`builtin` / `sandbox` / `termux` / `ssh`）、`LANG=C.UTF-8`。内置后端额外设置 `PYTHONHOME`、`PYTHONPATH`、`NEXARA_PYEXT_DIR`、`NEXARA_PYTHON`、`SSL_CERT_FILE`、`PYTHONNOUSERSITE=1`、`PYTHONDONTWRITEBYTECODE=1`、`PYTHONUTF8=1`，并从主进程只继承 `PATH` 与 `ANDROID_ROOT` 等系统路径变量。`LD_LIBRARY_PATH` 只在启动解释器时设置（`python_exec` 与 shell 函数 `python3`），不影响 shell 中的系统命令。

## 4. 内置后端（B）

### 4.1 打包内容

状态：Python 打包、shell 入口与 CA bundle 已实施（构建见 `docs/distribution-builds.md#打包-python`，运行时见 `data/exec/PackagedPython.kt`、`BuiltinExecEnvironment.kt`）；git 与 pip 随第 6 步实施。

| 组件 | 形式 | 说明 |
| --- | --- | --- |
| shell | 系统 `/system/bin/sh` | 不打包 busybox/toybox；命令文案声明“POSIX sh + 系统 toybox，具体命令随设备不同” |
| Python 3.14 | `libnxpython.so`、`libpython3.14.so`、`lib*_python.so`、`libpystdlib.so`、`libpyext_*.so` | 打包库方案；stdlib 裁剪同第 2 节 |
| `python3` / `python` 入口 | `shell_exec` 预置的 `sh` 函数 | 数据目录里的包装脚本无法经 PATH 执行（execve 被拒），因此以函数提供；函数只在该次 `sh -c` 内有效，`xargs`、`find -exec` 等外部程序调用 `python3` 时找不到。`pip` 随第 6 步提供 |
| git | dulwich（纯 Python，随 stdlib zip 打包）+ `git` 包装 | 支持 clone、status、add、commit、log、diff、push、pull 的常用子集；不支持的子命令返回 `GIT_UNSUPPORTED` 与指引 |
| CA bundle | 首次运行由 `/apex/com.android.conscrypt/cacerts`（不存在时为 `/system/etc/security/cacerts`）中的 PEM 块拼出，存于 `noBackupFilesDir/exec-ca` | 证书目录的文件名、大小或修改时间变化时重建 |

ABI：官方包只有 arm64-v8a 与 x86_64。设了 `abiFilters` 的变体按过滤打包；发行产物是不按 ABI 拆分的通用 APK，只带 arm64-v8a Python（x86_64 约 11.5 MB 只服务 x86_64 设备）。armeabi-v7a、x86 与 x86_64 设备上的 Release 中 `PackagedPython.isAvailable` 为 false，内置后端须给出不可用指引。

清单变更：`packaging.jniLibs.useLegacyPackaging = true`，全部原生库改为压缩存放并在安装时解压到 `nativeLibraryDir`。实测：单 ABI 打包 Python 后 deviceTest APK 增加 11.3 MB，安装后 `nativeLibraryDir` 为 33 MB；Release 通用 APK 由约 20.8 MB 增至约 29.8 MB。需在发布说明中披露。

### 4.2 进程与安全

- 命令是主进程的子进程（非 isolated），以 Nexara uid 运行。没有单独的 `:exec` 进程：工具结果、审批账本与工作区对账都在主进程，主进程死亡时本次工具调用随之失效，`:exec` 进程不能让命令结果送达；ColorOS 冻结的是整个 uid，单独进程也不能避开。子进程位于应用的进程 cgroup 内，应用进程被结束时随之结束。
- 启动方式：`/system/bin/setsid /system/bin/sh -c '<写出 $$ 到临时文件>; exec "$@"' …`，命令成为新会话首进程，进程组号即其 pid（API 31 toybox 0.8.4、API 35 toybox 0.8.11 实测）。信号经 `Os.kill(-pgid, …)` 发给整个进程组；命令自行 `setsid` 脱离的进程不在其中。
- 命令可读写应用私有目录，包括 Room 数据库与配置；密钥由 Android Keystore 保护，命令无法直接取得明文，但不能视为强隔离。
- 因此 `shell_exec` 与 `python_exec` 的风险等级为 `ToolRisk.PROCESS`，沿用现有审批（半自动与手动模式逐次审批，全自动模式不审批，与 `termux_exec` 相同）；审批卡片展示完整命令或代码（前 12 行）及“可访问 Nexara 私有数据与网络、删除或覆盖的文件不进回收站”的提示。审批目标为 `["process","builtin",<命令>]` 与 `["process","python",<代码>]`；工作目录固定为本会话工作区根，不作为参数，因此不进入审批目标。非内置来源的同名工具不使用这两个目标。
- 环境变量白名单启动，不继承主进程环境。
- 参数上限：`command` 32,000 字符；`code` 40,000 字符且 UTF-8 不超过 120,000 字节（经 `python -c` 传入，单个 execve 参数上限 128 KiB），更长的代码先写入工作区文件再运行。
- 网络：默认允许（pip、git 需要）。是否增加“仅局域网白名单”开关与脚本运行时保持一致，不单独设计。
- 资源：单条命令默认 60 秒、最长 600 秒；stdout、stderr 各保留尾部 32,000 UTF-16 字符；超时或取消先 SIGTERM，2 秒后 SIGKILL 整个进程组。命令正常结束后，同组仍在运行的后台进程立即被 SIGKILL，避免在释放工作区写锁后继续改动文件；需要常驻的服务改用 Termux。

### 4.3 沙箱后端

`python_exec` 增加 `sandbox: true` 模式，在 isolatedProcess 中运行打包 Python：无网络、看不到应用数据。输入只能经 stdin 与参数中声明的工作区文件（主进程读出内容后通过 Binder 传入，进入 Python 的 `nexara.inputs` 映射）；输出为 stdout、stderr 与通过 `nexara.write(path, data)` 提交的文件，由主进程经现有工作区写入路径落盘（版本哈希、写入审批照常）。沙箱模式风险等级与 `run_script` 相同。

### 4.4 工作区一致性

状态：已实施（`data/repository/WorkspaceCommandReconciliation.kt`，`WorkspaceRepository.runCommandInWorkspace`）。

1. 命令运行期间持有该工作区的写锁（`WorkspaceMutationCoordinator`，与文件工具同一把锁）。文件工具的写入与内容读取（`read_file`、`diff_file` 等也在锁内）都排队等待，最长约为命令超时加对账耗时；列表、搜索等只查 Room 的读取不受影响。
2. 启动前经 `SecureDirectoryStream` 逐级遍历物理树（不跟随符号链接），记录每个条目的类型、size、mtime（纳秒）与 inode（`fileKey`）。Room 不保存 mtime，因此前快照只能来自现场遍历。
3. 命令结束（含失败、超时、取消）后在不可取消上下文中再遍历一次，以 Room 索引为准计算差异：size、mtime、inode 均未变且 size 与索引一致的文件不重算哈希；其余文件重算 SHA-256，内容不同则更新哈希、大小与修改时间并清空向量化状态。前快照中某条已删除条目的 inode 出现在新路径、类型相同且（文件）size 与 mtime 未变时视为移动，保留条目 uuid、版本历史与派生数据；同 inode 但内容已变按删除加新增处理。新增文件的 MIME 按扩展名判定，未知扩展名且不超过 256 KiB 的文件按 UTF-8 文本嗅探。删除经与永久删除相同的派生数据事务与向量化屏障提交，并清理对应 `.nexara_versions` 快照；新增与更新在一个 Room 事务中提交。随后为索引管线支持的 MIME 发布 `FileIndexEvent.Changed`，再释放写锁。
4. 命令直接删除或覆盖的文件不进入回收区，也不生成版本快照（覆盖前的内容已不可得），审批卡片写明这一点。命令改动 `.nexara*`、`.recycle_bin`（任意层级的保留名称）或新建顶层 `mnt`（挂载命名空间，同名目录会被挂载遮蔽）时，对账不写入索引并给出 `WORKSPACE_RESERVED_PATH` 警告；回收区或版本历史可能因此损坏，界面不自动修复。
5. 根身份标记 `.nexara_root_identity` 被命令删除或改写会让后续全部工作区操作失败：命令前读取原始字节，结束后在根目录 fileKey 未变时写回，并列入保留路径警告；标记被替换为目录时对账失败。
6. 命令运行期间工作区根存在 `.nexara_command_pending` 标记，对账成功后删除。进程在命令中途被杀时标记残留，下一次经工作区仓储的修改（新建、移动、回收、删除或再次运行命令）前先以无前快照方式对账（全部文件重算哈希、不识别移动）；恢复对账失败只记日志，不阻断后续修改。`write_file`、`patch_file` 不经工作区仓储，在恢复对账之前对已被改动的文件会因哈希与索引不一致而拒绝写入。
7. 共享存储是 FUSE，不支持符号链接与可执行位，不适合放 git 仓库或虚拟环境；内置后端只把会话工作区作为工作目录。

### 4.5 后台与生命周期

状态：已实施（`background/exec/AndroidExecKeepAlive.kt`、`background/exec/ExecForegroundService.kt`、`data/exec/ExecKeepAlive.kt`）。

- 每条命令从等待工作区写锁到对账结束都持有 `PARTIAL_WAKE_LOCK`（标签 `Nexara:builtin-exec`，超时上限加 5 分钟兜底，结束、超时、停止或取消时在 finally 中释放）。
- 侧载渠道同时启动 `ExecForegroundService`（`specialUse`，子类型说明写在清单 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）：通知显示“Shell/Python · 命令首行”与计时，锁屏只显示“Nexara 正在运行命令”；按钮“停止”（多条命令时“全部停止”）只结束通知渲染时列出的命令。登记表清空即退出前台。服务不持有命令，主进程被结束时命令随之结束。
- 生成任务自身的前台服务在等待审批时退出；半自动与手动模式下批准后的命令在界面协程中执行，正是这里的命令前台服务提供保活与停止入口。全自动模式下两者可能同时显示，各自的停止分别作用于生成与命令。
- Play 渠道不声明命令前台服务与 `FOREGROUND_SERVICE_SPECIAL_USE`（政策待评审），只持有唤醒锁；`scripts/ci/check-distribution-manifests.py` 校验该渠道差异。
- 系统拒绝启动前台服务（应用在后台且无豁免）时命令照常运行，只有唤醒锁。
- 停止：通知按钮经 `BuiltinCommandControl` 请求停止，等待循环（250 ms 步长）发现后 SIGTERM→2 秒→SIGKILL 整个进程组，返回 `EXEC_STOPPED` 并照常对账；模型据此继续本轮，而不是整轮生成被取消。
- 冻结检测：等待循环的时钟为 `SystemClock.elapsedRealtimeNanos`（含深度休眠）。相邻两次轮询间隔超出步长 2 秒以上即视为本进程被暂停，超出部分计入 `paused_ms` 而不消耗超时；累计达到 3 秒时 `guidance` 说明暂停时长、前台服务状态，并提示在应用耗电管理中设为“完全允许后台行为”。
- 进程被系统结束时返回 `error_code = EXEC_PROCESS_KILLED`，对账照常执行。

### 4.6 分发渠道

| 能力 | 侧载 | Play |
| --- | --- | --- |
| shell_exec、python_exec、沙箱模式 | 有 | 有（全部代码随 APK 分发） |
| pip 安装 | 有，仅纯 Python 包，安装到 `filesDir/python-user` | 默认关闭，待政策评审 |
| git clone/pull | 有 | 有 |
| 前台服务类型 | `specialUse`（已声明） | 不声明，只持有唤醒锁；待评审 |

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

1. 失败文案结构化与文件工具“未找到条目”指引（第 6 节），独立于执行后端，可先合入。（已完成）
2. 统一结果格式（第 3.1 节）；`termux_exec`、`ssh_exec` 结果对齐。（已完成）`ExecBackend` 接口随第 4 步内置后端一起引入，避免只有现有两个实现时的空抽象。
3. 打包 Python（构建脚本纳入 Gradle 任务，产物不入库，按版本与 SHA-256 校验官方包）与 `useLegacyPackaging`。（已完成）标准库未预编译 `.pyc`（需要同版本的宿主 Python），冷启动沿用第 2 节实测值。
4. `shell_exec`、`python_exec`、审批卡片、工作区写锁与对账。（已完成）不设 `:exec` 进程，原因见第 4.2 节；前台服务与唤醒锁属于第 7 步。
5. 沙箱模式。
6. dulwich 与 `git` 包装、pip（侧载）。
7. 前台服务、唤醒锁与冻结检测文案。（已完成）停止按钮返回结构化结果 `EXEC_STOPPED` 而非取消整轮生成；冻结检测以轮询间隔判断并把暂停时间排除在超时之外，没有采用子进程 CPU 时间心跳。

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
| 命令运行期间同一工作区的文件读写（含界面打开文档）最长等待约 600 秒（被系统暂停的时间另计） | 已知限制；侧载渠道可从通知停止，Play 渠道只能在界面停止生成 |
| 命令自行 `setsid`/守护化的进程不在进程组内，结束后可能继续改动工作区 | 未处理；对账只反映结束时刻的磁盘状态 |
| 对账重算大文件哈希的耗时（数 GB 文件需数秒）与 inode 复用导致的误判移动（要求文件 size 与 mtime 同时未变） | 已知限制 |
| 命令在对账之后才写入的文件、或进程被杀后到下一次修改之间的磁盘变化 | 由 `.nexara_command_pending` 恢复对账收敛；根身份标记若在进程被杀前已被删除则无法自动恢复 |
| ColorOS 默认电池策略下前台服务在熄屏后仍被冻结（探针实测） | 不能由应用解决；结果的 `paused_ms` 与指引提示用户设为“完全允许后台行为” |
| `ssh_exec` 超限时保留开头，长输出的结尾（通常是错误）会丢失 | 改为保留尾部需先实现流式遮蔽（跨块识别凭据与私钥块） |
