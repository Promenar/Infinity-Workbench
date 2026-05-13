# Nexara Architecture 全景

> **注意**: 本文档为快速参考。完整架构设计见 [ARCHITECTURE_DESIGN.md](./ARCHITECTURE_DESIGN.md)（理想架构 + 技术路线择优），实现进度与差距分析见 [IMPLEMENTATION_ANALYSIS.md](./IMPLEMENTATION_ANALYSIS.md)。

## 核心架构
本项目是一个基于 Kotlin/Jetpack Compose 的原生 AI 助手应用，采用了典型的 MVVM 架构。

### 模块依赖关系
```mermaid
graph TD
    App[com.promenar.nexara] --> UI[ui层: Screens/ViewModels]
    UI --> Data[data层: Repository/DAO/Entities]
    Data --> DB[Room Database]
    Data --> LLM[LLM/RAG Engine]
    App --> Utils[utils: NexaraLogger/LocaleHelper]
```

### 关键组件
- **NexaraApplication**: 全局上下文管理与服务初始化。
- **NavGraph**: 基于 Compose Navigation 的路由中心（27 条路由）。
- **ContextBuilder**: 负责多源上下文（RAG/Web/KG/History）的异步调度、打分与 Prompt 合成，支持实时观测回调。
- **RagOmniIndicator**: 基于磨砂玻璃设计的全能检索指示器，集成在对话流中展示检索深度与进度。
- **NexaraLogger**: 拦截未捕获异常并持久化崩溃日志。
- **AgentHubScreen**: Agent 列表中枢（已移除 Super Assistant FAB）。

### 架构决策记录 (ADR)
- **ADR-001 (2026-05-13)**: **取消 Super Assistant 概念** — 统一 Agent 模型，移除 `isSuperAssistant` 特殊逻辑，将 `spa_settings` 迁移为通用默认 Agent 配置。详见 [IMPLEMENTATION_ANALYSIS.md §8](./IMPLEMENTATION_ANALYSIS.md#8-超级助手super-assistant取舍分析)。

### 诊断体系
- **Developer Panel**: 二级设置页面，用于导出日志 (`nexara_logs.txt`)。
- **Log Persistence**: 路径为应用私有 files 目录。
