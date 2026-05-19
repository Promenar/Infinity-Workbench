# Nexara 开发者/Agent 协同规范 (AGENTS.md)

本文件遵循 [AGENTS.md 开放标准](https://agents.md/)，为所有参与 Nexara 开发的 AI Agent 和人类开发者提供核心开发规范。

## 1. 核心规则引用
本项目严格遵循模型全局开发规则，包含：
- **语言规范**：所有解释、规划、文档、注释必须使用简体中文。技术转译时，禁止直接搬运大段英文日志。
- **闭环工作流**：在声称任务完成前，必须通过 **DIA 检查**。
- **文档治理 (DIA 机制)**：每次修改必须评估对核心及按需文档的影响。核心文档包含 `CHANGELOG.md`、`README.md`、`.agent/handover.md`。

## 2. 项目特定架构与文档注册表
本项目的完整文档治理体系和文档注册表已在以下文件中声明：
- **[文档注册表]** — [.agent/registry.md](file:///Users/promenar/Codex/Nexara/.agent/registry.md)
- **[跨会话交接说明]** — [.agent/handover.md](file:///Users/promenar/Codex/Nexara/.agent/handover.md)

## 3. 技术栈特定红线
本项目是一个包含 Android 原生 Compose UI (`native-ui`) 的多模块项目。
- 遵循 **Jetpack Compose 性能红线** 与 **滚动容器嵌套红线**：
  - 避免在第三方组件回调中叠加 `horizontalScroll()` / `verticalScroll()` 导致崩溃；
  - 网络/IO 必须在 `Dispatchers.IO`。
- **单元测试门禁**：对核心业务逻辑（数据转换、状态流转、ViewModel 等）的更改，必须同步编写并运行通过对应的单元测试。
- **Android/Gradle 编译清理**：当出现诡异的编译问题时，务必首先执行 `./gradlew clean`。

---
*规范更新于 2026-05-19。请每次开启新会话时首先阅读本文件，并检查 `.agent/handover.md` 的 Next Steps！*
