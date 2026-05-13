# Nexara

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)
![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg)

> Android 端 BYOK 开源 AI 客户端 — 对标桌面端 LobeHub / Cherry Studio，原生 Kotlin + Jetpack Compose 实现。

---

## English

Nexara is a BYOK (Bring Your Own Key) AI client for Android, serving as the mobile counterpart to desktop products like LobeHub and Cherry Studio. Built natively with Kotlin and Jetpack Compose Material Design 3, it focuses on local-first data privacy, beautiful native UI, and deep integration of RAG + Knowledge Graph for long-form writing and knowledge management.

### Core Features

**Multi-Provider BYOK Chat**
Connect to OpenAI, Anthropic, Vertex AI, DeepSeek, GLM, KIMI, and any OpenAI-compatible endpoint. Full streaming SSE support with real-time Markdown rendering. Switch models within a conversation.

**RAG Knowledge Engine**
Built-in vector store (SQLite + FTS5) with semantic search. Import documents (TXT/MD), auto-chunk, vectorize via remote embedding APIs, and retrieve context during conversations. Hybrid search combining vector similarity and full-text matching.

**Knowledge Graph**
Automatic entity extraction from documents and conversations via LLM. Structured knowledge network stored in SQLite, enabling relationship queries and visualization.

**Agent System**
Custom agents with configurable system prompts, model bindings, RAG scope, and tool sets. Function Calling with approval loops (Semi-Automatic mode). MCP protocol support for external tool integration.

**Local Inference** *(experimental)*
Optional on-device LLM via llama.cpp JNI bindings. GGUF model parsing, GPU detection, and model download management.

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
支持 OpenAI、Anthropic、Vertex AI、DeepSeek、GLM、KIMI 及任意 OpenAI 兼容接口。完整 SSE 流式响应，实时 Markdown 渲染，会话内模型自由切换。

**RAG 知识引擎**
内置向量库（SQLite + FTS5），支持语义检索。导入 TXT/MD 文档，自动分块，通过远程 Embedding API 向量化，在对话中检索相关上下文。支持向量相似度 + 全文混合检索。

**知识图谱**
通过 LLM 从文档和对话中自动抽取实体关系。结构化知识网络存储于 SQLite，支持关系查询与可视化。

**Agent 系统**
自定义 Agent，可配置系统提示词、模型绑定、RAG 范围与工具集。支持 Function Calling + 审批循环（Semi-Automatic 模式）。MCP 协议支持接入外部工具。

**本地推理** *(实验性)*
可选设备端 LLM 运行（llama.cpp JNI 绑定）。GGUF 模型解析、GPU 检测、模型下载管理。

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
