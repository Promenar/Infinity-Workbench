# Nexara

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)
![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg)
![Version](https://img.shields.io/badge/version-0.2--beta-6366F1.svg)
![Stage](https://img.shields.io/badge/stage-beta%20prerelease-6366F1.svg)

> Android 端 BYOK 开源 AI 客户端，以 Kotlin 与 Jetpack Compose 原生构建，集成多服务商对话、RAG、知识图谱、Agent 工具、会话工作区和加密备份。

`v0.2-beta` 已于 2026-07-29 获得 beta prerelease 放行。后台持续生成等功能已经实现；2119 项 JVM、96 张截图、Lint、真实 API 四模型 smoke、分支 Android CI、API 31/35/36 设备矩阵，以及同源稳定证书签名候选的 API 35/36 冷安装均已通过。2026-07-15 真机首轮反馈中的引导跳转、三个 RAG 设置入口闪退、聊天/模型管理布局及模型元数据问题已在当前候选中修复。最终 tag workflow 会重新构建、验签并冷安装远端 APK；物理真机 TalkBack 人工听觉、完整焦点遍历和更广泛 OEM/IME 体验继续作为 beta 期间的人工验证项，不冒充已完成的自动化门禁。

## 主要能力

- **多服务商 BYOK 对话**：支持 OpenAI、Anthropic、Google Vertex AI、DeepSeek、GLM、Kimi 及 OpenAI-compatible 接口，包含 SSE 流式响应、多模态输入、Markdown/LaTeX/Mermaid/ECharts 渲染和会话内模型切换。
- **完整文档上下文与会话分支**：输入栏可把 TXT/Markdown 全文作为当前消息上下文发送，超出最终路由模型预算时在网络请求前阻止；会话可导出为可回传的 Markdown/TXT，并可从稳定消息创建独立分支。
- **RAG 与知识图谱**：导入 TXT、Markdown、PDF、Word、HTML 文档，提供向量检索、FTS5、Rerank、查询重写、引用追踪和知识图谱可视化。
- **Agent 与工具调用**：内置联网搜索、计算、受限脚本、文件操作、图像生成和任务规划等工具，支持审批、幂等执行账本和工具结果回传。
- **会话工作区**：按 Session 隔离文件根目录，覆盖导入、原子写入、版本、回收站、恢复和路径逃逸防护；文档索引使用版本化目标与失败重试，进程重启后按当前文件版本和 KG 配置补建缺失任务。
- **长文档安全编辑**：严格大于 1 MiB 的文件仅加载元数据；已读取正文严格超过 32K 个 UTF-16 代码单元、2,000 行或单行 16K 个 UTF-16 代码单元时自动进入性能保护，只预览约前 16K 字符，但复制和保存始终使用完整全文。一次输入跨过软线时，已修改内容不会被截断，仍可完整完成 CAS 安全保存。
- **安全备份与恢复**：核心数据可本地或通过 HTTPS WebDAV 备份；密钥默认不进入备份，用户可显式选择把可备份的完整密钥加密写入备份包。
- **后台持续生成**：前台发起的当前生成任务在切后台、锁屏、旋转或 Activity 重建后继续，通过前台服务通知返回会话或停止任务。
- **本地优先**：会话、文档和配置保存在设备本地；云端模型调用直接连接用户配置的服务商。

## 密钥与隐私

- API Key、Vertex 凭据、搜索 Key 和 WebDAV 凭据由 Android Keystore 支撑的 `SecretStore` 管理，不以明文写入普通配置。
- 密钥字段默认显示 `****`；用户可在当前页面临时显示完整值，离开页面或失去焦点后重新隐藏。
- 备份默认排除所有 Key。启用“包含完整密钥”时必须设置备份密码；备份包使用 PBKDF2-HMAC-SHA256 派生密钥和 AES-256-GCM 加密。
- Release 云端 Provider 与 WebDAV 仅允许 HTTPS。局域网 HTTP 只允许进入显式 Debug/Integration 测试，不属于发行能力。
- Release 日志不得写入 Prompt、模型输出、Authorization、API Key、SQL 参数或完整异常堆栈。

## 后台生成边界

后台生成覆盖用户在前台主动发起的单个当前任务：

- 支持切后台、锁屏、旋转和 Activity 重建；
- 通知可返回准确会话或停止生成；
- 通知权限被拒绝时可退化为仅前台生成；
- 不支持设备重启后续传、多会话并行、无人值守队列或定时任务。

## GGUF 本地推理

GGUF/llama.cpp 不属于 `v0.2-beta` 稳定发行范围，本轮不继续推进端到端实现。Release 构建会关闭本地推理并拒绝打包 GGUF、llama 或 ggml 制品；相关代码只能视为实验性研发资产，不能据此承诺可用能力。

## 运行要求

| 项目 | 要求 |
|---|---|
| Android | Android 12 及以上（API 31+） |
| 目标 SDK | API 36 |
| 存储 | 建议至少保留 200 MiB；导入文档另计 |
| 网络 | 云端模型、Embedding、Rerank、联网搜索和 WebDAV 需要网络 |
| 权限 | 网络；通知权限用于后台生成，可拒绝但会退化为仅前台生成 |

本版本采用全新 `v0.2-beta` 数据基线，不兼容 `v0.1-beta` 用户数据。安装前请先自行导出需要保留的旧数据，再卸载旧版。

## 安装

正式制品只从 [Promenar/Nexara Releases](https://github.com/Promenar/Nexara/releases) 发布。安装 `v0.2-beta`：

1. 下载 `nexara-v0.2-beta.apk` 与同名 `.sha256` 文件。
2. 校验 APK 的 SHA-256 与发布文件一致。
3. 在 Android 系统中允许当前文件管理器或浏览器“安装未知应用”。
4. 侧载 APK；首次启动按引导配置语言、Provider、默认模型、Agent 和首条对话。

不要安装 Actions 临时制品、Debug APK 或来源不明的重打包版本。

## 开发构建

```bash
cd native-ui
./gradlew :app:assembleDebug
```

Debug 构建不需要发行签名变量。Release 构建必须通过进程环境提供 `NEXARA_KEYSTORE_PATH`、`NEXARA_STORE_PASSWORD`、`NEXARA_KEY_ALIAS` 和 `NEXARA_KEY_PASSWORD`；仓库不保存 keystore 或密码。

### Metro CLI/TUI

Nexara 没有面向最终用户、与 Android UI 等价的 CLI。仓库中的 `scripts/nexara-metro-tui.js` 是 **Debug 构建专用的开发者日志终端**，用于解析 `adb logcat` 中的脱敏 Metro 事件，不替代应用功能：

```bash
node scripts/nexara-metro-tui.js --help
node scripts/nexara-metro-tui.js --serial emulator-5554
adb logcat -s NEXARA_METRO | node scripts/nexara-metro-tui.js --stdin --no-color
```

该 TUI 支持中文帮助、设备/Tag 选择、标准输入、TTY/非 TTY 输出、明确退出码和缺失字段容错。Release 会关闭并剥离 Metro/调试日志入口。

## 技术栈

| 层级 | 选型 |
|---|---|
| 语言/UI | Kotlin、Jetpack Compose、Material 3 |
| 架构 | MVVM、Repository、Flow/Coroutines |
| 数据 | Room、SQLite/FTS5、设备文件系统 |
| 网络 | OkHttp、Ktor、SSE |
| 安全 | Android Keystore、AES-256-GCM、PBKDF2-HMAC-SHA256 |
| 导航 | Compose Navigation |
| 构建/发行 | Gradle、R8、GitHub Actions、GitHub Release |

架构快速参考见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，发行说明见 [docs/release/v0.2-beta.md](docs/release/v0.2-beta.md)，发行验证状态见 [docs/release/v0.2-beta-validation.md](docs/release/v0.2-beta-validation.md)。

## License

Nexara 基于 [GNU General Public License v3.0](LICENSE) 发布。对应 Release 的完整源码由同名 Git tag 固定。
