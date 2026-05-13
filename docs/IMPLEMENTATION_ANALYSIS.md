# Nexara — 当前实现分析与开发进度文档

> **版本**: 2.0.0-alpha
> **分析日期**: 2026-05-13
> **分析范围**: `native-ui/` 目录（Kotlin/Jetpack Compose 原生版）
> **对照基准**: [PRD.md](./PRD.md)（产品需求） + [ARCHITECTURE_DESIGN.md](./ARCHITECTURE_DESIGN.md)（理想架构）

---

## 1. 代码库概览

### 1.1 规模统计

| 指标 | 数值 |
|------|------|
| Kotlin 源文件 | 235 个 |
| Room 实体 | 19 个 |
| Room DAO | 19 个 |
| Repository 实现 | 3 个（Session / Message / Skill） |
| ViewModel | 15+ 个 |
| Composable Screen | 25+ 个 |
| 导航路由 | 27 个 |
| 内置模型规格 | 50+ 个（12 能力维度） |
| 协议实现 | 3 个（OpenAI / Anthropic / VertexAI） |

### 1.2 包结构映射

```
com.promenar.nexara/
├── MainActivity.kt              # Compose 宿主 Activity
├── NexaraApplication.kt         # 全局单例容器 + 默认数据注入
│
├── data/
│   ├── agent/                   # AgentRagConfig / AgentRetrievalConfig
│   │   └── AgentConfigModels.kt
│   │
│   ├── local/
│   │   ├── db/
│   │   │   ├── NexaraDatabase.kt        # Room DB (19 entities, v6)
│   │   │   ├── Converters.kt
│   │   │   ├── entity/                   # 19 个 @Entity
│   │   │   │   ├── AgentEntity.kt
│   │   │   │   ├── SessionEntity.kt
│   │   │   │   ├── MessageEntity.kt
│   │   │   │   ├── AttachmentEntity.kt
│   │   │   │   ├── FolderEntity.kt
│   │   │   │   ├── DocumentEntity.kt
│   │   │   │   ├── VectorEntity.kt       # BLOB 浮点向量
│   │   │   │   ├── VectorFtsEntity.kt    # FTS5 全文检索
│   │   │   │   ├── ContextSummaryEntity.kt
│   │   │   │   ├── TagEntity.kt
│   │   │   │   ├── DocumentTagEntity.kt
│   │   │   │   ├── KgNodeEntity.kt       # 知识图谱节点
│   │   │   │   ├── KgEdgeEntity.kt
│   │   │   │   ├── KgJitCacheEntity.kt
│   │   │   │   ├── VectorizationTaskEntity.kt
│   │   │   │   ├── AuditLogEntity.kt
│   │   │   │   ├── ArtifactEntity.kt
│   │   │   │   └── SkillEntities.kt      # CustomSkill / McpServer
│   │   │   └── dao/                       # 19 个 DAO
│   │   │
│   │   └── inference/                    # 本地推理引擎
│   │       ├── LocalInferenceEngine.kt   # llama.cpp JNI
│   │       ├── LlamaContext.kt
│   │       ├── GgufParser.kt
│   │       ├── GpuDetector.kt
│   │       ├── ModelDownloader.kt
│   │       ├── ModelStorageManager.kt
│   │       └── InferenceBackend.kt
│   │
│   ├── manager/
│   │   └── ProviderManager.kt            # 全站 Provider 单例
│   │
│   ├── model/
│   │   ├── ChatModels.kt                 # Agent / Message / Session / TokenUsage
│   │   ├── Mappers.kt                    # Entity ↔ Model
│   │   ├── ModelSpecs.kt                 # 50+ 模型规格
│   │   └── ProviderModels.kt             # ProviderConfig / ProviderListItem
│   │
│   ├── remote/
│   │   ├── MessageFormatter.kt           # Message → ProtocolMessage
│   │   ├── MessageFormatterFactory.kt
│   │   ├── ThinkingDetector.kt
│   │   ├── parser/                       # SSE 流解析管线
│   │   │   ├── StreamParser.kt
│   │   │   ├── ResponseNormalizer.kt
│   │   │   ├── ErrorNormalizer.kt
│   │   │   └── StreamBufferManager.kt
│   │   └── protocol/                     # 三协议实现
│   │       ├── LlmProtocol.kt            # 核心接口 + 密封类
│   │       ├── OpenAIProtocol.kt
│   │       ├── AnthropicProtocol.kt
│   │       └── VertexAIProtocol.kt
│   │
│   ├── repository/                       # 仓库实现
│   │   ├── SessionRepository.kt
│   │   ├── MessageRepository.kt
│   │   └── SkillRepository.kt
│   │
│   └── rag/                              # RAG 引擎
│       ├── VectorStore.kt
│       ├── EmbeddingClient.kt
│       ├── DocumentImporter.kt
│       ├── ContextBuilder.kt
│       └── ...
│
├── ui/
│   ├── chat/                             # 对话相关
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt
│   │   ├── SpaSettingsScreen.kt          # 超级助手设置
│   │   ├── SpaViewModel.kt
│   │   ├── SessionSettingsScreen.kt
│   │   └── components/                   # 对话组件
│   │
│   ├── hub/                              # 中枢
│   │   ├── AgentHubScreen.kt            # Agent 列表
│   │   ├── AgentHubViewModel.kt
│   │   ├── AgentEditScreen.kt           # Agent 编辑
│   │   ├── AgentEditViewModel.kt
│   │   ├── AgentSessionsScreen.kt       # 会话列表
│   │   ├── AgentRagConfigScreen.kt
│   │   └── AgentAdvancedRetrievalScreen.kt
│   │
│   ├── rag/                              # 知识库 UI
│   │   ├── GlobalRagConfigScreen.kt
│   │   ├── RagFolderScreen.kt
│   │   ├── DocEditorScreen.kt
│   │   ├── KnowledgeGraphScreen.kt
│   │   ├── AdvancedRetrievalScreen.kt
│   │   └── RagDebugScreen.kt
│   │
│   ├── settings/                         # 设置 UI
│   │   ├── SettingsScreen.kt
│   │   ├── SettingsViewModel.kt
│   │   ├── ProviderFormScreen.kt
│   │   ├── ProviderModelsScreen.kt
│   │   ├── BackupSettingsScreen.kt
│   │   ├── DeveloperScreen.kt
│   │   └── ...
│   │
│   ├── welcome/
│   │   └── WelcomeScreen.kt
│   │
│   ├── common/                           # 通用组件
│   │   ├── ModelPicker.kt
│   │   ├── NexaraGlassCard.kt
│   │   └── ...
│   │
│   └── theme/                            # MD3 主题
│       ├── NexaraColors.kt
│       ├── NexaraTypography.kt
│       └── NexaraTheme.kt
│
├── navigation/
│   └── NavGraph.kt                       # 27 路由定义
│
└── utils/
    ├── NexaraLogger.kt
    └── LocaleHelper.kt
```

---

## 2. 架构合规性分析（对照理想架构）

### 2.1 分层架构 ✅ / 🟡 / ❌

| 层级 | 理想状态 | 当前实现 | 评级 | 差距 |
|------|---------|---------|------|------|
| **UI 层** | Screens + ViewModels + Components | ✅ 完整实现 | 🟢 | 组件拆分可继续优化 |
| **Domain 层** | 纯 Kotlin Use Cases | ❌ 缺失 | 🔴 | 业务逻辑散落在 ViewModel 和数据层 |
| **Data 层** | Repository 接口 + 实现 | 🟡 部分实现 | 🟡 | 仅 3 个 Repository；Agent/Document/Provider 等直接操作 DAO |
| **Infra 层** | Room + OkHttp + FileSystem | ✅ 完整实现 | 🟢 | — |

**关键问题**：Domain 层缺失导致业务逻辑分散。

```kotlin
// 现状：ViewModel 直接操作 DAO（绕过 Repository）
// AgentHubViewModel.kt
fun createAgent(...) {
    val entity = AgentEntity(...)
    agentDao.insert(entity)  // ← 直接 DAO 调用
}
```

**理想状态**应该是：
```kotlin
// Domain 层
interface IAgentRepository {
    suspend fun create(name: String, ...): Agent
}

// ViewModel
fun createAgent(...) {
    viewModelScope.launch {
        agentRepository.create(name, ...)  // ← Repository 接口
    }
}
```

### 2.2 Repository 体系覆盖率

| 聚合根 | Repository 接口 | Repository 实现 | 使用方式 |
|--------|:---:|:---:|------|
| Agent | ❌ | ❌ | ViewModel → AgentDao 直调 |
| Session | ❌ | ✅ SessionRepository | ViewModel → SessionRepository |
| Message | ❌ | ✅ MessageRepository | ViewModel → MessageRepository |
| Document | ❌ | ❌ | ViewModel → DocumentDao 直调 |
| Vector | ❌ | ❌ | ContextBuilder → VectorDao 直调 |
| KnowledgeGraph | ❌ | ❌ | 直接操作 KgNodeDao/KgEdgeDao |
| Provider | ❌ | ❌ | ProviderManager 单例（非 Repository 模式） |
| Skill | ❌ | ✅ SkillRepository | — |

**评级**: 🟡 **Repository 覆盖率 3/8（37.5%）**

### 2.3 LLM 抽象层

| 组件 | 理想状态 | 当前实现 | 评级 |
|------|---------|---------|------|
| `LlmClient` 接口 | Domain 层定义 | ❌ 无统一接口 | 🔴 |
| 协议实现 | OpenAI / Anthropic / VertexAI | ✅ 三协议完整 | 🟢 |
| SSE 解析管线 | StreamParser → Normalizer | ✅ 完整实现 | 🟢 |
| Thinking 分离 | ThinkingDetector | ✅ 完整实现 | 🟢 |
| Embedding 客户端 | 统一接口 + 多后端 | 🟡 EmbeddingClient 仅远程 API | 🟡 |

**评级**: 🟢 **协议层健壮，但缺少 Domain 层抽象接口**

### 2.4 RAG 引擎

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| 文档导入（TXT/MD） | ✅ DocumentImporter | 🟢 |
| PDF/Word/HTML 导入 | ❌ 未实现 | 🔴 |
| 自动分块 | ✅ Recursive Character Splitter | 🟢 |
| 向量存储（SQLite） | ✅ VectorEntity + VectorDao | 🟢 |
| FTS5 全文检索 | ✅ VectorFtsEntity | 🟢 |
| 余弦相似度检索 | ✅ VectorStore | 🟢 |
| 混合检索（向量 + FTS） | 🟡 结构就绪，未集成 | 🟡 |
| Embedding 本地降级 | ❌ 未实现 | 🔴 |
| 查询重写 | 🟡 Config 字段存在，逻辑待实现 | 🟡 |
| Rerank 重排序 | 🟡 Config 字段存在，逻辑待实现 | 🟡 |
| ContextBuilder（多源调度） | ✅ 核心调度器已实现 | 🟢 |
| RAG 进度指示器 | ✅ RagOmniIndicator | 🟢 |
| 会话记忆向量化 | ✅ 已实现 | 🟢 |

**评级**: 🟡 **核心管线就绪，降级方案和高级检索待完善（~70%）**

### 2.5 知识图谱

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| LLM 实体抽取 | ✅ GraphExtractor | 🟢 |
| Summary-First 策略 | ✅ 已实现 | 🟢 |
| 增量更新（Hash 校验） | ✅ 已实现 | 🟢 |
| 图谱存储（kg_nodes/edges） | ✅ Room 实体 + DAO | 🟢 |
| JIT 图缓存 | ✅ KgJitCacheEntity | 🟢 |
| **交互式可视化** | ❌ 未实现 | 🔴 |
| 多维视图（全局/会话/文件夹） | ❌ 未实现 | 🔴 |

**评级**: 🟡 **存储与抽取就绪，可视化层缺失（~60%）**

### 2.6 Agent 引擎

| 功能 | 实现状态 | 评级 |
|------|---------|------|
| Function Calling | ✅ 三协议支持 | 🟢 |
| 审批循环（Semi-Automatic） | ✅ ApprovalManager | 🟢 |
| 工具注册表 | 🟡 基础工具（搜索、代码执行） | 🟡 |
| 执行时间轴 | 🟡 基础 Timeline 实现 | 🟡 |
| MCP 协议 | 🟡 McpServerEntity 存在，客户端未实现 | 🟡 |
| HTML Artifacts | 🟡 ArtifactEntity 存在，编辑器未实现 | 🟡 |
| 网络搜索 | 🟡 Gemini/VertexAI 原生搜索可用 | 🟡 |

**评级**: 🟡 **基础管线就绪，工具生态待丰富（~30%）**

---

## 3. 功能模块实现进度

```
模块                     进度        关键缺失
───────────────────────────────────────────────────────────
对话引擎 (Chat)          ████████░░  80%   Markdown 渲染优化
RAG 知识引擎             ███████░░░  70%   本地降级/混合检索/PDF导入
知识图谱 (KG)            ██████░░░░  60%   可视化 / 多维视图
Agent 引擎               ███░░░░░░░  30%   MCP/工具丰富/执行时间轴
Provider 管理            █████████░  90%   本地推理集成
Token 统计               ██████░░░░  60%   仪表盘 UI 待完善
数据备份与恢复           ████░░░░░░  40%   WebDAV / 完整恢复
设置与主题               ████████░░  80%   部分设置页面未接
Welcome 引导             ██████████ 100%   已完成
导航与路由               █████████░  90%   workspace 等预留路由
本地推理                 ██████░░░░  60%   llama.cpp 集成待调通

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
总体进度                  ██████░░░░  62%
```

### 3.1 已完成（✅）

| 模块 | 关键成果 |
|------|---------|
| 项目基础设施 | Kotlin/Compose 项目搭建、Room DB v6、Compose Navigation 27 路由、MD3 主题 |
| LLM 协议层 | OpenAI / Anthropic / VertexAI 三协议完整实现、SSE 流式解析管线、ThinkingDetector |
| Provider 管理 | ProviderFormScreen、ProviderModelsScreen、ModelPicker、50+ 模型规格库 |
| Agent 基础 | AgentHubScreen、AgentEditScreen（含自动保存）、AgentRagConfig、AgentAdvancedRetrieval |
| 对话核心流 | ChatScreen + ChatViewModel、流式对话、消息气泡、会话创建/删除 |
| RAG 存储 | VectorStore、EmbeddingClient（远程 API）、DocumentImporter（TXT/MD）、FTS5 |
| 知识图谱存储 | KgNodeEntity/KgEdgeEntity、GraphExtractor（LLM 抽取）、JIT 缓存 |
| 上下文构建 | ContextBuilder 多源调度、RagOmniIndicator 检索指示器 |
| 本地推理引擎 | llama.cpp JNI 绑定、GGUF 解析、GPU 检测、模型下载管理 |
| 通用组件 | NexaraGlassCard、ModelPicker、Markdown 渲染（mikepenz 定制） |

### 3.2 进行中（🚧）

| 模块 | 当前状态 |
|------|---------|
| Markdown 渲染优化 | CJK 间距、GFM Alert、流式平滑、标题锚点 — 11 个 Agent 任务已规划（见 `docs/IMPLEMENTATION_PLAN.md`） |
| RAG 检索增强 | 本地 Embedding 降级、混合检索集成、PDF/Word 导入 — 6 个会话已规划（见 `native-ui/.agent/plans/20260513-fix-plan-prompts.md`） |
| 设置颗粒度统一 | 模型过滤逻辑、RAG 配置阈值滑块 — 近期 CHANGELOG 记录中 |

### 3.3 待开始（📋）

| 模块 | 依赖 |
|------|------|
| 知识图谱可视化 | 需集成 WebView + D3.js，或 Compose Canvas 实现 |
| MCP 协议客户端 | 需完成 McpServerEntity 的完整客户端实现 |
| HTML Artifacts 预览 | 需 WebView 沙箱环境 |
| WebDAV 备份恢复 | 需网络层支持 + 完整数据序列化 |
| Token 统计仪表盘 | TokenUsageScreen 已占位，需完善 UI 与数据流 |
| CMP 跨端迁移 | 需先完成 Android 端功能闭环 |

---

## 4. 技术债务识别

### 4.1 架构债（🔴 高危）

| 编号 | 问题 | 影响范围 | 推荐处理 |
|------|------|---------|---------|
| **AD-1** | **Domain 层缺失** — 业务逻辑散落在 ViewModel 和数据层 | 全局 | Phase 3 前引入 UseCase 层 |
| **AD-2** | **Repository 覆盖率不足** — Agent/Document/Vector/KG/Provider 直接操作 DAO | 对应模块 | Phase 2 补全 Repository |
| **AD-3** | **ProviderManager 单例模式** — 非标准 Repository 模式，测试困难 | Provider 模块 | 迁移到 IProviderRepository |
| **AD-4** | **"super" Agent 硬编码** — ID 约定而非类型安全 | Agent 模块 | **去繁就简，取消特殊逻辑**（见 §8） |

### 4.2 代码债（🟡 中危）

| 编号 | 问题 | 位置 |
|------|------|------|
| CD-1 | `AgentHubScreen` 中 `onNavigateToSuperChat` 被 `@Suppress("UNUSED_PARAMETER")` | `AgentHubScreen.kt:35-37` |
| CD-2 | `PostProcessor` 中 `isSuperAssistant` 字符串匹配检查 | `PostProcessor.kt:69-70` |
| CD-3 | `SpaSettingsScreen` 中的占位统计数据（"Coming soon"） | `SpaSettingsScreen.kt` |
| CD-4 | `ChatViewModel` 中 RAG 引用写入逻辑缺失 | 已纳入近期修复计划 |
| CD-5 | `VectorStore` 缺少向量维度不匹配的日志告警 | 已纳入近期修复计划 |

### 4.3 UI 债（🟢 低危）

| 编号 | 问题 |
|------|------|
| UD-1 | 部分设置页面仍使用 `PlaceholderScreen`（workspace / session_settings_sheet） |
| UD-2 | CJK 排版在部分场景下间距不一致（已纳入 Markdown 渲染审计） |
| UD-3 | 模型选择器中能力标签在某些暗黑模式下可读性差 |

---

## 5. 数据库架构评估

### 5.1 表设计

19 个 Room Entity — **覆盖面完整**，覆盖了从对话到 RAG 到知识图谱的全链路。

| 优势 | 劣势 |
|------|------|
| ✅ 核心实体覆盖全面 | ⚠️ 表数量较多（19 个），DAO 数量同比例增长 |
| ✅ FTS5 全文索引已就绪 | ⚠️ 缺少复合索引优化高频查询 |
| ✅ KG JIT 缓存体现设计前瞻性 | ⚠️ `VectorEntity` 使用 BLOB 存储向量，JSON 序列化开销 |
| ✅ AuditLog 支持操作审计 | — |

### 5.2 建议优化

1. **向量存储优化**：考虑使用 FloatArray + 自定义 Room Converter（减少 JSON 序列化开销）
2. **索引补全**：`messages(session_id, timestamp DESC)` 复合索引
3. **数据库版本管理**：当前 v6，需确保迁移脚本覆盖所有变更

---

## 6. 导航架构评估

### 6.1 当前状态

- 27 条路由，覆盖所有主要流程
- `NavDestinations` 使用密封类模式确保类型安全
- 转场动画统一配置（300ms tween + slide）
- 3 条预留路由：`SESSION_SETTINGS_SHEET`、`WORKSPACE_SHEET` → `PlaceholderScreen`

### 6.2 问题

| 问题 | 影响 |
|------|------|
| `SPA_SETTINGS` 无参数 — 硬编码为"super" Agent 专用 | 无法为其他 Agent 复用 |
| `WelcomeScreen` 直接操作 SharedPreferences | 违反分层原则 |
| 占位路由未实现 | 用户体验断层 |

---

## 7. 性能架构评估

### 7.1 已实现的优化 ✅

- LazyColumn/LazyVerticalGrid（列表虚拟化）
- Inverted List 渲染（消息列表 O(1) 插入）
- 流式响应异步处理（Kotlin Flow）
- Room Flow 响应式查询
- Markdown 分量渲染 + 缓存

### 7.2 待实现的优化 📋

- 向量检索批处理（当前逐条计算余弦相似度）
- 大文档加载的分页/懒加载
- 图片压缩与磁盘缓存策略
- Compose `derivedStateOf` 优化重组合并

---

## 8. 超级助手（Super Assistant）取舍分析 ⚡

> **这是本次审计的核心议题之一。**

### 8.1 历史沿革

```
RN 时代 (v1.x)
  ┌────────────────────────────────────────────┐
  │ Super Assistant = 专属 FAB + 全局 RAG      │
  │                                            │
  │ • 5 种动画模式 (Pulse/Nebula/Quantum/...)  │
  │ • GIF 动图头像                             │
  │ • 专属设置页 (spa_settings)                │
  │ • 全局 RAG 检索（跨所有文档和会话）        │
  │ • 专用的 spa-store (Zustand)               │
  │ • 独立的导航路由                            │
  │ • FAB 是用户感知的核心差异点               │
  └────────────────────────────────────────────┘
            │
            ▼  Kotlin 原生迁移
            │
  ┌────────────────────────────────────────────┐
  │ 原生版现状                                  │
  │                                            │
  │ • FAB 已从 AgentHubScreen 移除 ← 关键变化  │
  │ • SpaSettingsScreen 仍存在                 │
  │ • PostProcessor.isSuperAssistant 检查仍存在 │
  │ • "super" 仅作为 Agent ID 字符串约定        │
  │ • 所有 Agent 均可配置 RAG                   │
  │ • 字符串资源仍保留 "超级助手" 相关翻译      │
  │ • onNavigateToSuperChat 被 @Suppress       │
  └────────────────────────────────────────────┘
```

### 8.2 当前架构中的残留

```kotlin
// 1. 硬编码 Agent ID
// NexaraApplication.kt:297
Agent("super", "Nexara 超级助手", ...)

// 2. 被抑制的导航回调
// AgentHubScreen.kt:35-37
@Suppress("UNUSED_PARAMETER")
onNavigateToSuperChat: () -> Unit

// 3. 字符串匹配检查
// PostProcessor.kt:69-70
val isSuperAssistant = 
    sessionId == "super_assistant" || session.agentId == "super_assistant"

// 4. 专属全局路由
// NavGraph.kt:61
const val SPA_SETTINGS = "spa_settings"  // 无参数，硬编码

// 5. 字符串资源
// strings.xml:80
<string name="hub_fab_super">Super Assistant</string>
```

### 8.3 决策分析

```
选项 A: 恢复超级助手
  ┌─────────────────────────────────────────────┐
  │ 优势                                        │
  │ • 恢复 RN 时代的产品差异化特性              │
  │ • FAB 动画视觉吸引力强                      │
  │                                              │
  │ 劣势                                        │
  │ • 需重写 5 种动画模式的 Compose 实现        │
  │ • 恢复 FAB → 恢复特殊路由 → 恢复特殊逻辑    │
  │ • 用户已有普通 Agent = 超级 Agent 的认知    │
  │ • "超级" 概念与 BYOK 精神有张力             │
  │   (为什么某个 Agent "超级"？因为 ID 是       │
  │    "super"？这不优雅)                        │
  │ • 增加 UI 维护负担（特殊设置页 + 通用设置）  │
  │                                              │
  │ 工期: ~5 人天 (5 种动画 + UI 恢复)          │
  └─────────────────────────────────────────────┘

选项 B: 去繁就简 ✅ 推荐
  ┌─────────────────────────────────────────────┐
  │ 优势                                        │
  │ • 所有 Agent 平等，用户自由创建/配置        │
  │ • RAG 能力 = Agent 的基础属性，非特权       │
  │ • 消除 isSuperAssistant 等分支逻辑          │
  │ • 架构更清晰（少一个特殊路由和特殊设置页）  │
  │ • 符合 BYOK 精神 — 用户定义"超级"          │
  │ • 更易扩展（Agent 模板市场不受"超级"概念限制）│
  │                                              │
  │ 劣势                                        │
  │ • 丢弃了 RN 时代投入的 FAB 动画资产         │
  │ • 需要在 CHANGELOG 中记录此破坏性变更       │
  │                                              │
  │ 工期: ~2 人天 (清理 + 重命名)               │
  └─────────────────────────────────────────────┘
```

### 8.4 决策结论：**选项 B — 去繁就简**

**决策理由**：

1. **功能已同质化**：所有 Agent 均支持 RAG 配置。超级助手的"全局 RAG"能力已下沉为 Agent 基础能力。
2. **FAB 已移除，用户感知无差异**：最关键的 UX 差异点已消失，恢复 FAB 只是创造无意义的视觉噪音。
3. **BYOK 精神**：Nexara 的定位是 "让用户自由接入模型与配置"，而不是 "我们定义什么是超级"。
4. **CMP 迁移友好**：统一 Agent 模型减少跨端迁移的复杂度。
5. **RN 时代的合理设计在原生时代成为包袱**：RN 时代 RAG 不完善，需要一个 "超级" Agent 承载全局 RAG。原生时代 RAG 已是 Agent 的标准能力。

### 8.5 迁移方案

```diff
待清理项:
- Agent("super", ...) 默认构造          → 默认 Agent 命名为 "assistant"，id="default"
- onNavigateToSuperChat 回调           → 移除，用 onNavigateToNewChat()
- PostProcessor.isSuperAssistant       → 移除检查，所有 Agent 统一逻辑
- SPA_SETTINGS 路由                    → 重命名为 DEFAULT_AGENT_SETTINGS，支持 agentId 参数
- SpaSettingsScreen → 重命名           → AgentDefaultSettingsScreen
- SpaViewModel → 重命名                → AgentDefaultSettingsViewModel
- strings.xml 中 "超级助手" 相关字符串  → 替换为 "默认助手" 或删除
- 动画模式相关的配置键                 → 归档（RN 时代的动画引擎不再适用）

保留项:
- Agent 的 FAB 定制能力 (icon/color)   → 作为 Agent 级属性保留，不限于特定 Agent
- 全局 RAG 配置继承机制                → 保留，所有 Agent 共享
- 上下文管理（自动摘要、手动触发）     → 保留，作为会话级功能
```

---

## 9. 与对标产品差距分析

### 9.1 功能矩阵

| 能力 | LobeChat (Web) | Cherry Studio (Desktop) | Nexara 原生版 (当前) | Nexara 理想 |
|------|:---:|:---:|:---:|:---:|
| BYOK 多服务商 | ✅ | ✅ | ✅ 90% | ✅ |
| MD3 原生视觉 | ❌ (Web) | ❌ (Electron) | ✅ | ✅ |
| RAG 知识库 | ✅ | 🟡 基础 | 🟡 70% | ✅ |
| 知识图谱 | ❌ | ❌ | 🟡 60% | ✅ |
| Agent 工具调用 | ✅ Plugin | ✅ MCP | 🟡 30% | ✅ |
| Markdown 渲染 | ✅ 完整 | ✅ 完整 | 🟡 70% | ✅ |
| 多模态（图片/VLM） | ✅ | 🟡 | ❌ 未实现 | ✅ |
| 流式响应 | ✅ | ✅ | ✅ | ✅ |
| Token 统计 | ✅ | 🟡 | 🟡 60% | ✅ |
| 本地推理 | ❌ | ✅ Ollama | 🟡 60% | ✅ |
| 数据备份 | ✅ | 🟡 | 🟡 40% | ✅ |
| 跨端 | ✅ Web | 🟡 Electron | ❌ Android-only | ✅ CMP |

### 9.2 Nexara 的差异化优势

| 优势 | 说明 |
|------|------|
| **原生 MD3 视觉** | LobeChat 和 Cherry Studio 都是 Web/Electron，无法达到原生级性能与触感 |
| **RAG + 知识图谱融合** | 桌面端对标产品均未实现此能力 |
| **移动端优先** | 填补了 BYOK 开源 AI 客户端在 Android 端的空白 |
| **隐私优先** | 全本地存储 + BYOK，数据不经过任何中间服务器 |

---

## 10. 分阶段开发建议

### 10.1 Phase 2 剩余工作（RAG + KG 完善）— 当前优先级 🔥

| 优先级 | 任务 | 预估工期 | 前置依赖 |
|--------|------|---------|---------|
| P0 | 补全 AgentRepository（架构债 AD-2） | 1d | — |
| P0 | Embedding 本地降级方案 | 1d | — |
| P0 | 向量维度不匹配告警 + 日志 | 0.5d | — |
| P1 | PDF/Word/HTML 文档导入 | 2d | DocumentImporter |
| P1 | 混合检索集成（向量 + FTS5） | 1.5d | VectorStore |
| P1 | 知识图谱 WebView 可视化 | 2d | KgNodeDao/KgEdgeDao |
| P2 | 查询重写 LLM 调用 | 1d | ContextBuilder |

### 10.2 Phase 3（Agent 能力增强）— 中优先级

| 优先级 | 任务 | 预估工期 | 前置依赖 |
|--------|------|---------|---------|
| P0 | 引入 Domain UseCase 层（架构债 AD-1） | 2d | — |
| P0 | 清理 Super Assistant 架构残留（§8.5） | 1d | — |
| P1 | MCP 协议客户端实现 | 3d | McpServerEntity |
| P1 | Token 统计仪表盘完善 | 1.5d | TokenUsageScreen |
| P1 | HTML Artifacts WebView 预览与编辑 | 2d | ArtifactEntity |
| P2 | 会话导出（TXT/Markdown） | 1d | — |
| P2 | WebDAV 备份恢复 | 2d | — |

### 10.3 Phase 4（打磨发布）— 远期

| 优先级 | 任务 | 预估工期 |
|--------|------|---------|
| P0 | Markdown 渲染行业对齐（GFM Alert / LaTeX / HTML Artifacts） | 3d |
| P0 | CJK 排版专项优化 | 1d |
| P1 | 多模态图片上传 + VLM 预览 | 2d |
| P1 | 性能 Profile + 启动优化 | 1.5d |
| P2 | Compose 自动化测试 | 2d |
| P2 | 正式版 APK 签名与发布流程 | 1d |

---

## 11. 关键风险

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| **Repository 体系缺失导致代码腐化** | 🔴 高 | Phase 2 补齐 Repository，先 Agent/Document |
| **Domain 层缺失导致测试困难** | 🔴 高 | Phase 3 引入 UseCase，逐步提取 |
| **Markdown 渲染 CJK 兼容性** | 🟡 中 | 已有完整审计方案，按优先级执行 |
| **RAG 大规模文档库性能** | 🟡 中 | 混合检索 + 批处理优化 |
| **CMP 迁移阻塞 Android 交付** | 🟢 低 | 严格按渐进式策略，先完成 Android 闭环 |
| **本地推理引擎稳定性** | 🟡 中 | 作为可选增强，不阻塞主线功能 |

---

**文档维护者**: AI Assistant  
**最后更新**: 2026-05-13  
**下次审查**: Phase 2 完成时（预计 2026-05-20）
