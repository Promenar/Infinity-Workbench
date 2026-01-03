# NeuralFlow

> **State-of-the-Art AI client with RAG knowledge base & multi-model support.**
> 
> **极致打磨的 AI 客户端，融合 RAG 知识库与多模型对话，兼顾日常交流和专业知识管理。**

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Expo](https://img.shields.io/badge/Expo-SDK%2052-black.svg)

---

## 📖 Introduction / 项目简介

NeuralFlow is a commercial-grade AI assistant designed for Android users who demand privacy, efficiency, and knowledge depth. Unlike comprehensive cloud-based solutions, NeuralFlow operates as a local-first client that connects to various AI providers (OpenAI, Gemini, DeepSeek, etc.) while building a private, secure knowledge base on your device.

NeuralFlow 是一款专为追求隐私、效率和深度知识管理的 Android 用户打造的商业级 AI 助手。与纯云端方案不同，NeuralFlow 采用"本地优先"架构，在连接主流 AI 服务商（OpenAI, Gemini, DeepSeek 等）的同时，在您的设备上构建私有、安全的 RAG 知识库。

## ✨ Key Features / 核心特性

### 🤖 Multi-Model Chat / 多模型对话
- **Streaming Response**: Real-time token streaming with 60fps rendering.
- **Provider Agnostic**: Support for OpenAI, Anthropic, Gemini, VertexAI, DeepSeek, SiliconFlow, and Ollama.
- **Context Management**: Smart sliding windows and manual context clearing.
- **Visuals**: Markdown rendering, LaTeX support, and code syntax highlighting.
- **流式响应**: 实时流式传输，60fps 丝滑渲染。
- **多服务商支持**: 接入 OpenAI, Gemini, DeepSeek, Local Ollama 等 10+ 主流模型。
- **视觉增强**: 完美支持 Markdown、LaTeX 公式及代码高亮。

### 🧠 RAG Architecture / RAG 知识引擎
- **Local Vector Store**: Built-in SQLite + Vector extension for high-performance retrieval.
- **Hybrid Search**: Semantic search combined with keyword matching.
- **Knowledge Graph**: (Beta) Extract entities and relationships from your documents to visualize connections.
- **Privacy First**: Your documents never leave your device (unless you choose cloud sync).
- **本地向量库**: 内置 SQLite + 向量扩展，无需外部数据库。
- **混合检索**: 语义检索 + 关键词匹配，精准定位信息。
- **知识图谱**: (Beta) 自动抽取实体与关系，可视化知识脉络。
- **隐私优先**: 文档数据完全本地化存储。

### 🦸 Super Assistant / 超级助手
- **Global Awareness**: A persistent agent that can search across all your sessions and documents.
- **Customizable FAB**: 5+ animation modes (Nebula, Quantum, Glitch...) and dynamic colors.
- **One-Tap RAG**: Instantly summon knowledge from anywhere in the app.
- **全局感知**: 可跨会话、跨文档库检索的常驻智能体。
- **炫酷悬浮球**: 支持星云、量子、故障艺术等 5+ 种动效，完全可定制。
- **一键唤起**: 随时随地调用您的私人知识库。

### 🎨 Lumina Design / 极致设计
- **Minimalist Aesthetics**: Clean, distraction-free UI inspired by modern design trends.
- **Dark Mode**: Fully optimized dark theme.
- **Haptics**: Subtle, reliable haptic feedback (optional).
- **极简美学**: 干净无干扰的现代化 UI 设计。
- **深色模式**: 完美适配的全局暗黑主题。
- **触感反馈**: 细腻的震动交互体验。

## 🛠 Tech Stack / 技术栈

- **Framework**: [Expo SDK 52](https://expo.dev) + [React Native](https://reactnative.dev)
- **Language**: TypeScript
- **Styling**: [NativeWind (TailwindCSS)](https://www.nativewind.dev)
- **Routing**: [Expo Router](https://docs.expo.dev/router/introduction)
- **State Management**: [Zustand](https://github.com/pmndrs/zustand)
- **Database**: [op-sqlite](https://github.com/OP-Engineering/op-sqlite) (High-performance SQLite)
- **Animations**: [Reanimated 3](https://docs.swmansion.com/react-native-reanimated/)
- **Haptics**: expo-haptics
- **Markdown**: react-native-markdown-display

## 🚀 Getting Started / 快速开始

### Prerequisites / 前置条件
- Node.js > 18
- JDK 17
- Android Studio & Android SDK

### Installation / 安装
```bash
# 1. Clone the repository
git clone https://github.com/NarcisWL/Nexara.git
cd Nexara

# 2. Install dependencies
npm install

# 3. Prebuild (Required for Native Modules like op-sqlite)
npx expo prebuild

# 4. Run on Android Emulator or Device
npm run android
```

### Building for Release / 构建正式版
```bash
# Build APK
cd android
./gradlew assembleRelease
```

## 📜 License / 许可协议

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.
本项目的源代码基于 **GNU General Public License v3.0 (GPLv3)** 开源。

You are free to use, modify, and distribute this software, but all modifications and derived works must also be open-sourced under the same license.
您可以自由使用、修改和分发本软件，但所有修改版及衍生作品必须同样基于 GPLv3 协议开源。

See [LICENSE](./LICENSE) for details.
