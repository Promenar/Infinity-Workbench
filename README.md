# Nexara

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)
![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg)
![Version](https://img.shields.io/badge/version-2.0.0--beta-orange.svg)
![Progress](https://img.shields.io/badge/progress-92%25-brightgreen.svg)

> Android 端 BYOK 开源 AI 客户端 — 对标桌面端 LobeHub / Cherry Studio，原生 Kotlin + Jetpack Compose 实现。

---

## English

Nexara is a BYOK (Bring Your Own Key) AI client for Android, serving as the mobile counterpart to desktop products like LobeHub and Cherry Studio. Built natively with Kotlin and Jetpack Compose Material Design 3, it focuses on local-first data privacy, beautiful native UI, and deep integration of RAG + Knowledge Graph for long-form writing and knowledge management.

### Core Features

**Multi-Provider BYOK Chat**
Connect to OpenAI, Anthropic, Vertex AI, DeepSeek, GLM, KIMI, and any OpenAI-compatible endpoint. Full streaming SSE with real-time Markdown rendering (GFM Alert, LaTeX, Mermaid, ECharts). Image upload with Vision (VLM) support. Smooth streaming speed control. Switch models within a conversation.

**RAG Knowledge Engine**
Built-in vector store (SQLite + FTS5) with semantic search. Import TXT/MD/PDF/Word/HTML documents, auto-chunk, vectorize via remote embedding APIs. Hybrid search (RRF fusion) + Rerank + Query Rewrite. Session memory vectorization with Memory browser view. Full-text FTS5 search.

**Knowledge Graph**
Automatic entity extraction from documents and conversations via LLM. Structured knowledge network in SQLite, with interactive ECharts force-directed graph visualization. Global/Document/Concept multi-view. JIT micro-graph extraction during conversations.

**Agent System**
13 built-in tools (web search ×3 engines, calculator, JS sandbox, file system ×4, image generation, meta-tool). MCP protocol support for external tool integration. Function Calling with Semi-Automatic approval loops. Tool timeline visualization in chat.

**Token Dashboard**
Per-session and global token tracking with Canvas trend charts, model breakdown, and cost estimation.

**HTML Artifacts**
Live WebView preview for HTML/CSS/JS/SVG code blocks, full-screen split mode, and PNG export.

### Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material Design 3 |
| Architecture | MVVM + Repository |
| Database | Room (SQLite + FTS5) |
| Network | OkHttp + Kotlin Coroutines |
| Serialization | kotlinx.serialization |
| Navigation | Compose Navigation |
| Local Inference | llama.cpp (JNI) |

### Quick Start

```bash
cd native-ui
./gradlew :app:assembleDebug
# APK output: native-ui/app/build/outputs/apk/debug/
```

---

## 中文

Nexara 是一款 Android 端 BYOK 开源 AI 客户端，对标桌面端的 LobeHub 和 Cherry Studio。采用 Kotlin + Jetpack Compose Material Design 3 原生开发，聚焦本地优先的数据隐私、精美的原生视觉，以及 RAG + 知识图谱的深度融合，为长篇写作与知识管理提供强大的记忆外脑。

### 核心功能

**多服务商 BYOK 自由接入**
支持 OpenAI、Anthropic、Vertex AI、DeepSeek、GLM、KIMI 及任意 OpenAI 兼容接口。完整 SSE 流式响应，实时 Markdown 渲染（GFM Alert、LaTeX、Mermaid、ECharts）。支持图片上传与 VLM 视觉理解。流式平滑调速。会话内模型自由切换。

**RAG 知识引擎**
内置向量库（SQLite + FTS5），支持语义检索。导入 TXT/MD/PDF/Word/HTML 文档，自动分块，通过远程 Embedding API 向量化。混合检索（RRF 融合）+ Rerank 重排序 + 查询重写。会话记忆向量化与 Memory 浏览视图。FTS5 全文搜索。

**知识图谱**
通过 LLM 从文档和对话中自动抽取实体关系。结构化知识网络存储于 SQLite，支持 ECharts 力导向图交互式可视化。全局/文档/概念三维视图。对话中 JIT 微图抽取。

**Agent 系统**
13 个内置工具（联网搜索 ×3 引擎、数学计算、JS 沙箱、文件系统 ×4、AI 生图、元工具）。MCP 协议接入外部工具。Function Calling + Semi-Automatic 审批循环。对话中工具执行时间轴可视化。

**Token 仪表盘**
会话级与全局级 Token 统计，Canvas 趋势图，模型用量明细，费用估算。

**HTML Artifacts**
HTML/CSS/JS/SVG 代码块 WebView 实时预览，全屏分屏模式，PNG 导出。

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

### 快速开始

```bash
cd native-ui
./gradlew :app:assembleDebug
# APK 输出路径: native-ui/app/build/outputs/apk/debug/
```

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.

本项目基于 **GNU General Public License v3.0 (GPLv3)** 开源协议发布。

See [LICENSE](./LICENSE) for details.
