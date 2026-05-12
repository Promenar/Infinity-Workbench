# Nexara Architecture全景

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
- **NavGraph**: 基于 Compose Navigation 的路由中心。
- **RagViewModel**: 向量化任务调度与 RAG 状态维护。
- **NexaraLogger**: 拦截未捕获异常并持久化崩溃日志。

### 诊断体系
- **Developer Panel**: 二级设置页面，用于导出日志 (`nexara_logs.txt`)。
- **Log Persistence**: 路径为应用私有 files 目录。
