# 架构全景 (ARCHITECTURE.md)

## 核心架构

Nexara 原生版本采用标准的 Jetpack Compose + MVVM 架构，结合 Room 数据库进行本地持久化。

```mermaid
graph TD
    subgraph UI ["UI 层 (Compose)"]
        A[MainActivity] --> B[NexaraNavGraph]
        B --> C[MainTabScaffold]
        C --> D1[ChatScreen]
        C --> D2[AgentHubScreen]
        C --> D3[RagHomeScreen]
        D1 --> D1V[ChatViewModel]
        D2 --> D2V[AgentHubViewModel]
        D3 --> D3V[RagViewModel]
    end

    subgraph Logic ["业务逻辑层 (Manager/ViewModel/Skill)"]
        D1V --> CM[MessageManager]
        D1V --> CB[ContextBuilder]
        D1V --> TE[ToolExecutor]
        TE --> SRG[SkillRegistry]
        SRG --> WS[WebSearchSkill]
        WS --> WSP[WebSearchProviders]
        CM --> RP[PostProcessor]
        D1V --> SM[SummaryManager]
        CB --> KG[KgProvider]
    end

    subgraph Data ["数据层 (Repository/DAO)"]
        D1V --> SR[SessionRepository]
        D1V --> MR[MessageRepository]
        BV[BackupViewModel] --> BR[BackupRepository]
        SR --> SD[SessionDao]
        MR --> MD[MessageDao]
        BR --> DB[(NexaraDatabase)]
        SD --> DB
        MD --> DB
    end

    subgraph RAG ["RAG/LLM 引擎"]
        D1V --> LC[EmbeddingClient]
        D1V --> VS[VectorStore]
        D1V --> GE[GraphExtractor]
        D3V --> DI[DocumentImporter]
        DI --> VQ[VectorizationQueue]
        VQ --> VS
        VQ --> GE
        VQ --> LC
        D1V --> LP[LlmProvider]
        LP --> Prot[LlmProtocol]
    end

    subgraph LocalInference ["本地推理引擎 (规划中)"]
        LIE[LocalInferenceEngine]
        LLC[LlamaContext JNI]
        MSM[ModelStorageManager]
        LLC --> LCPP[llama.cpp .so]
        LCPP --> GPU[Vulkan GPU]
        LCPP --> CPU[CPU NEON]
    end

    Prot --> LIE
    LC --> LIE
    UI --> LocalInference
    Logic --> RAG
```

## 目录结构说明

| 目录 | 说明 |
| :--- | :--- |
| `data/local` | Room 数据库定义、实体 (Entities) 与数据访问对象 (DAOs) |
| `data/remote` | LLM 协议实现 (OpenAI, Anthropic, VertexAI) 与流式解析器 |
| `data/rag` | RAG 核心逻辑：向量存储 (VectorStore)、知识图谱 (GraphStore)、文本切分等 |
| `data/repository` | 数据仓库层，封装本地数据库与业务逻辑的交互 |
| `ui/chat` | 聊天会话核心界面及逻辑管理 (MessageManager, ContextBuilder, SummaryManager) |
| `ui/hub` | 智能体中心 (Agent Hub) 相关界面 |
| `ui/rag` | 知识库管理与 RAG 配置界面 |
| `ui/renderer` | Markdown 增强渲染器 (LaTeX, Mermaid, ECharts) |
| `data/remote/search` | Web 搜索提供商 (DuckDuckGo, SearXNG, Tavily) |
| `ui/chat/manager/skills` | 智能体技能实现 (WebSearch, Calculator, Time) |
| `ui/settings` | 设置中心，包括模型配置与搜索设置 (SearchConfigViewModel) |
| `ui/common` | 通用 UI 组件与业务枚举，如 `ModelPicker` 和 `ModelCapability` 映射 |
| `ui/theme` | 全局设计系统 (Colors, Typography, Theme) |
| `data/local/inference` | (规划中) 本地推理引擎 (LocalInferenceEngine, LlamaContext, ModelStorageManager) |
| `cpp` | (规划中) llama.cpp JNI 桥接层 |

## 关键技术栈

- **UI**: Jetpack Compose (Material 3)
- **数据库**: Room (SQLite) + FTS5
- **Markdown**: `multiplatform-markdown-renderer` (mikepenz)
- **公式/图表**: KaTeX, Mermaid.js, ECharts (通过 WebView 渲染)
- **网络**: Ktor (用于 LLM API 通信)
