# Nexara

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Expo](https://img.shields.io/badge/Expo-SDK%2054-black.svg)
![React Native](https://img.shields.io/badge/React_Native-0.81-61dafb.svg)

---

## English

Nexara is an Android AI assistant client with a focus on local-first data management and multi-provider model access. It stores conversations, knowledge bases, and vector embeddings entirely on-device using SQLite, while connecting to 12+ cloud AI providers for inference.

### Core Features

**Multi-Provider Chat**

Connect to OpenAI, Anthropic, Gemini, Vertex AI, DeepSeek, Moonshot, Zhipu, SiliconFlow, GitHub Copilot, Cloudflare, and any OpenAI-compatible endpoint. Supports streaming responses, tool calling, image generation, and reasoning models (Chain of Thought).

**RAG Knowledge Engine**

Built-in vector store using SQLite with FTS5 full-text search. Import documents, chunk and vectorize them with cloud or local embedding models, then retrieve context during conversations. Includes knowledge graph extraction, query rewriting, and reranking.

**Agent System**

Preset agents for common tasks (translation, coding, creative writing), plus custom agent creation with configurable system prompts and model bindings. Agents can be scoped to specific RAG knowledge bases and tool sets.

**MCP Protocol**

Connect to external MCP servers via SSE or HTTP transports. External tools are bridged into the local skill registry and can be used by agents during conversations.

**Local Inference** *(experimental)*

Run GGUF models on-device via `llama.rn`. Three independent model slots (main chat, embedding, rerank) with GPU acceleration support. Enables fully offline usage when combined with local embedding models.

**Workbench** *(experimental)*

Built-in WebSocket server and static file server allow a companion web UI (`web-client/`) to manage conversations, agents, knowledge base, and settings remotely from a browser on the same network.

**Other Features**

- Web search with cascading provider fallback (Google, Tavily, Bing, Bocha, SearXNG)
- Markdown, LaTeX, Mermaid diagrams, and ECharts rendering in chat
- Skill system with built-in tools (web search, code interpreter, file system, chart rendering)
- WebDAV backup with automatic 24h interval
- Bilingual interface (English / Chinese)

### Tech Stack

| Layer | Choice |
|---|---|
| Framework | Expo SDK 54 + React Native (New Architecture) |
| Language | TypeScript |
| Styling | NativeWind (TailwindCSS) |
| Navigation | Expo Router (file-based) |
| State | Zustand |
| Database | op-sqlite (SQLite + FTS5 + vector BLOBs) |
| Local Inference | llama.rn |
| Animation | Reanimated 4 |
| Web Panel | Vite + React 18 + TailwindCSS 4 |

### Quick Start

```bash
git clone https://github.com/NarcisWL/Nexara.git
cd Nexara
npm install
npx expo prebuild
npm run android
```

### Roadmap

- [ ] UI interaction polish (gestures, transitions, haptics consistency)
- [ ] CJK native Markdown layout optimization (typography, list indentation)
- [ ] Additional provider testing and validation
- [ ] Local inference stability and model compatibility
- [ ] Workbench reliability and edge-case handling

---

## 中文

Nexara 是一个面向 Android 平台的 AI 助手客户端，核心特点是本地优先的数据管理和多服务商模型接入。对话记录、知识库、向量嵌入数据全部存储在设备本地的 SQLite 数据库中，推理则通过连接 12+ 云端 AI 服务商完成。

### 核心功能

**多服务商对话**

支持接入 OpenAI、Anthropic、Gemini、Vertex AI、DeepSeek、Moonshot、智谱、SiliconFlow、GitHub Copilot、Cloudflare 以及任何 OpenAI 兼容接口。支持流式响应、工具调用、图像生成和思维链推理模型。

**RAG 知识引擎**

基于 SQLite + FTS5 全文检索的内置向量库。导入文档后，通过云端或本地嵌入模型进行分块和向量化，在对话时检索相关上下文。支持知识图谱抽取、查询重写和重排序。

**Agent 系统**

内置预设 Agent（翻译、编程、创意写作等），支持自定义 Agent，可配置系统提示词和模型绑定。Agent 可绑定特定的 RAG 知识库和工具集。

**MCP 协议**

通过 SSE 或 HTTP 传输层连接外部 MCP 服务器。外部工具会被桥接到本地 Skill 注册表，在对话中可供 Agent 调用。

**本地推理** *(实验性)*

通过 `llama.rn` 在设备端运行 GGUF 格式模型。三个独立模型槽位（主对话、嵌入、重排序），支持 GPU 加速。配合本地嵌入模型可实现完全离线使用。

**Workbench** *(实验性)*

内置 WebSocket 服务器和静态文件服务器，配套 Web 管理面板（`web-client/` 目录），可在同一局域网内通过浏览器远程管理对话、Agent、知识库和设置。

**其他功能**

- 联网搜索，多引擎级联降级（Google、Tavily、Bing、Bocha、SearXNG）
- 对话中支持 Markdown、LaTeX、Mermaid 图表和 ECharts 渲染
- Skill 工具系统，内置网页搜索、代码解释器、文件系统、图表渲染等工具
- WebDAV 远程备份，支持 24 小时自动备份
- 中英双语界面

### 技术栈

| 层级 | 选型 |
|---|---|
| 框架 | Expo SDK 54 + React Native（新架构） |
| 语言 | TypeScript |
| 样式 | NativeWind（TailwindCSS） |
| 路由 | Expo Router（基于文件系统） |
| 状态管理 | Zustand |
| 数据库 | op-sqlite（SQLite + FTS5 + 向量 BLOB） |
| 本地推理 | llama.rn |
| 动画 | Reanimated 4 |
| Web 面板 | Vite + React 18 + TailwindCSS 4 |

### 快速开始

```bash
git clone https://github.com/NarcisWL/Nexara.git
cd Nexara
npm install
npx expo prebuild
npm run android
```

### 待完成

- [ ] UI 交互打磨（手势、转场、触感反馈一致性）
- [ ] 中文原生 Markdown 排版优化（排版、列表缩进）
- [ ] 部分服务商接入层的完整回归测试
- [ ] 本地推理稳定性与模型兼容性
- [ ] Workbench 可靠性与边界情况处理

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.

本项目基于 **GNU General Public License v3.0 (GPLv3)** 开源协议发布。

See [LICENSE](./LICENSE) for details.
