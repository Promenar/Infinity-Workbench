# Nexara

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)
![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg)
![Version](https://img.shields.io/badge/version-1.0.0-6366F1.svg)
![Stage](https://img.shields.io/badge/stage-early--preview-orange.svg)

> Android 端 BYOK 开源 AI 客户端 — 原生 Kotlin + Jetpack Compose 构建，融合 RAG 知识引擎与知识图谱的智能记忆外脑。
>
> ⚠️ **当前为初期预览版本，部分功能可能存在不稳定，持续迭代中。**

---

## English

Nexara is a BYOK (Bring Your Own Key) AI client for Android, built natively with Kotlin and Jetpack Compose Material Design 3. Similar in category to desktop AI clients, but tailored for mobile — focusing on local-first data privacy, native touch interactions, and deep integration of RAG + Knowledge Graph for long-form writing and knowledge management.

> ⚠️ **Early preview release. Some features may be unstable. Actively iterating.**

### What Makes Nexara Different

- **Truly Native Android**: Built with Jetpack Compose + Material Design 3, delivering 60fps fluid interactions and system-level visual harmony — not a WebView wrapper.
- **RAG + Knowledge Graph Fusion**: Combines vector semantic search, full-text search, and knowledge graph extraction in a unified memory engine. Your AI remembers context across conversations.
- **Full BYOK Freedom**: Bring your own API keys from OpenAI, Anthropic, Google Vertex AI, DeepSeek, GLM, KIMI, and any OpenAI-compatible endpoint.
- **Privacy by Design**: All data stays on your device. No telemetry, no intermediaries.

### Core Features

**Multi-Provider BYOK Chat**
Connect to any LLM provider. Full SSE streaming with real-time Markdown rendering (GFM Alert, LaTeX, Mermaid, ECharts). Image upload with Vision (VLM) support. Streaming speed control. Switch models mid-conversation. Regenerate responses. ✅

**RAG Knowledge Engine**
Built-in vector store with semantic search. Import TXT/MD/PDF/Word/HTML documents, auto-chunk and vectorize via remote embedding APIs. Hybrid search (RRF fusion) with Rerank and Query Rewrite. Session memory vectorization with Memory browser view. Citation panel with search source tracking. ✅

**Knowledge Graph**
Automatic entity extraction from documents and conversations via LLM. Structured knowledge network in SQLite. Interactive ECharts force-directed graph visualization with Global/Document/Concept multi-view. JIT micro-graph extraction during conversations. ✅

**Agent System**
18 built-in tools: web search (3 engines), calculator, JS sandbox, file system operations, image generation, task planning, and more. MCP protocol support for external tool integration. Function Calling with Semi-Automatic approval loop. Tool execution timeline in chat. ✅

**Token Dashboard**
Per-session and global token tracking with Canvas trend charts, model breakdown, and cost estimation. ✅

**HTML Artifacts**
Live WebView preview for HTML/CSS/JS/SVG code blocks, full-screen split mode, and PNG export. ✅

**Local Inference** 🚧 *In Development*
llama.cpp JNI engine for on-device inference. GGUF model import, 3-slot management (Main/Embed/Rerank), Vulkan GPU detection. Engine code complete — end-to-end verification in progress.

**Background Generation** 🚧 *Planned*
Foreground Service for uninterrupted AI generation when switching apps. Architecture designed, implementation pending.

### Runtime Requirements

| Requirement | Minimum |
|---|---|
| Android Version | Android 8.0 (API 26) |
| Recommended | Android 13+ (API 33+) for full Material You theming |
| Storage | ~200 MB free space (model files additional) |
| Network | Active internet connection for cloud API providers |
| Permissions | Internet, Notification (for background generation, optional) |

### Download

[![Download APK](https://img.shields.io/badge/Download-v1.0.0--beta-6366F1?style=for-the-badge&logo=android)](https://github.com/NarcisWL/Nexara/releases/tag/v1.0.0-beta)

---

## 中文

Nexara 是一款 Android 端 BYOK（自带密钥）开源 AI 客户端，采用 Kotlin + Jetpack Compose Material Design 3 原生构建。产品类型与桌面端 AI 客户端近似，但受限于移动端形态，聚焦本地优先的数据隐私、原生触屏交互体验，以及 RAG 知识引擎与知识图谱的深度融合，为长篇写作与知识管理提供智能记忆外脑。

> ⚠️ **当前为初期预览版本，部分功能可能存在不稳定，持续迭代中。**

### Nexara 的独特之处

- **真正原生的 Android 体验**：基于 Jetpack Compose + Material Design 3 纯原生构建，带来 60fps 流畅交互与系统级视觉融合，非 WebView 套壳。
- **RAG + 知识图谱融合**：向量语义检索、全文搜索、知识图谱抽取三位一体的统一记忆引擎，让 AI 真正记住你的上下文。
- **完全 BYOK 自由**：自带 OpenAI、Anthropic、Google Vertex AI、DeepSeek、GLM、KIMI 及任意 OpenAI 兼容接口的 API Key。
- **隐私优先设计**：所有数据存储于设备本地，零遥测，零中间服务器。

### 核心功能

**多服务商 BYOK 自由接入** ✅
支持 OpenAI、Anthropic、Vertex AI、DeepSeek、GLM、KIMI 及任意 OpenAI 兼容接口。完整 SSE 流式响应，实时 Markdown 渲染（GFM Alert、LaTeX、Mermaid、ECharts）。图片上传与 VLM 视觉理解。流式平滑调速。会话内模型自由切换。支持重发/重新生成。

**RAG 知识引擎** ✅
内置向量库，支持语义检索。导入 TXT/MD/PDF/Word/HTML 文档，自动分块并通过远程 Embedding API 向量化。混合检索（RRF 融合）+ Rerank 重排序 + 查询重写。会话记忆向量化与 Memory 浏览视图。引用内容面板，追踪搜索来源。

**知识图谱** ✅
通过 LLM 从文档和对话中自动抽取实体关系。结构化知识网络存储于 SQLite。ECharts 力导向图交互式可视化，支持全局/文档/概念三维视图。对话中 JIT 微图抽取。

**Agent 系统** ✅
18 个内置工具：联网搜索（3 引擎）、数学计算、JS 沙箱、文件系统操作、AI 生图、任务规划等。MCP 协议接入外部工具。Function Calling + Semi-Automatic 审批循环。对话中工具执行时间轴可视化。

**Token 仪表盘** ✅
会话级与全局级 Token 统计，Canvas 趋势图，模型用量明细，费用估算。

**HTML Artifacts** ✅
HTML/CSS/JS/SVG 代码块 WebView 实时预览，全屏分屏模式，PNG 导出。

**本地模型推理** 🚧 *开发中*
基于 llama.cpp JNI 的端侧推理引擎。支持 GGUF 模型导入、三槽位管理（主模型/Embedding/Rerank）、Vulkan GPU 检测。引擎代码已完成，端到端验证进行中。

**后台生成** 🚧 *计划中*
通过 Foreground Service 实现切换 App 后 AI 生成不中断。架构设计已完成，待实施。

### 运行环境要求

| 项目 | 最低要求 |
|---|---|
| Android 版本 | Android 8.0 (API 26) |
| 推荐版本 | Android 13+ (API 33+)，以获得完整 Material You 主题体验 |
| 存储空间 | ~200 MB 可用空间（模型文件另需） |
| 网络 | 使用云端 API 需保持网络连接 |
| 权限 | 网络、通知（后台生成功能可选） |

### 下载

[![下载 APK](https://img.shields.io/badge/下载-v1.0.0--beta-6366F1?style=for-the-badge&logo=android)](https://github.com/NarcisWL/Nexara/releases/tag/v1.0.0-beta)

### 技术栈

| 层级 | 选型 |
|---|---|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material Design 3 |
| 架构模式 | MVVM + Repository |
| 数据库 | Room（SQLite + FTS5） |
| 网络 | OkHttp + Kotlin Coroutines |
| 序列化 | kotlinx.serialization |
| 导航 | Compose Navigation |
| 本地推理 | llama.cpp（JNI） |

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.

本项目基于 **GNU General Public License v3.0 (GPLv3)** 开源协议发布。

详见 [LICENSE](./LICENSE)。
