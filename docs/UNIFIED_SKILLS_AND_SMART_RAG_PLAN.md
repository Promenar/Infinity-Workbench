# 统一技能引擎与智能 RAG 实施方案 (最终合并版)

> [!NOTE]
> 本文档基于 v1-v6 版本的迭代沟通整理而成，汇集了关于架构扩展性、工具动态路由、显式记忆管理及可视化交互的所有核心需求。这是后续执行的**唯一事实来源 (SSOT)**。

## 1. 核心架构与设计理念 (Architecture)

### 1.1 插件化设计 (Plugin-based Architecture)
为了满足“极高的可扩展性”，系统采用**接口驱动 + 注册机制**模式。
*   **协议 (Protocol)**:所有工具必须实现统一的 `Skill` 接口。
*   **单文件定义**: 新增工具只需在 `src/lib/skills/definitions/` 下并在 Registry 中注册，**无需修改** ChatStore 或 Provider 的核心逻辑。

```typescript
interface Skill {
  id: string;          // 标识符 (e.g., 'generate_image')
  name: string;        // 模型/UI可见名称
  description: string; // Prompt 描述 (决定模型何时调用)
  schema: z.ZodSchema; // 参数校验 Schema
  execute: (params: any, context: SkillContext) => Promise<SkillResult>;
}
```

### 1.2 代理循环模型 (Agentic Loop)
*   **转变**: 从线性的 `User -> LLM -> Response` 转变为递归的 `User -> LLM -> [Tools] -> LLM -> Response`。
*   **控制**: 引入 `maxLoopCount` (可配置，默认 5) 防止死循环和 Token 消耗失控。

### 1.3 基础设施适配 (Provider Adapter)
*   **适配器模式**: 引入 `ProviderAdapter` 接口，抹平 OpenAI (tools/tool_calls) 与 Gemini (functionDeclarations/functionCall) 及其它厂商在 API 格式上的差异。

## 2. 关键业务逻辑 (Business Logic)

### 2.1 智能 RAG (Smart RAG)
*   **逻辑**: 不再对每条消息强制从向量库检索。LLM 将获得 `query_vector_db` 工具，自主判断只需闲聊还是需要查阅知识库。
*   **权衡**: 闲聊响应更快（省去检索），复杂问答精度更高（按需检索），但复杂任务延迟会因多轮推理而增加。

### 2.2 动态生图路由 (Dynamic Image Gen)
*   **解耦**: `generate_image` 工具**不绑定** DALL-E。
*   **路由**: 工具执行时，动态查询 `ApiStore`，寻找已启用且具备 `image` 能力的模型（优先匹配 Google Imagen/Banana，其次 OpenAI/Zhipu 等）。复用现有 API 基础设施。

### 2.3 显式核心记忆 (Explicit Core Memory)
*   **定义**: 不同于隐式 RAG，这是 AI 主动“记笔记”的能力。
*   **工具**: `save_core_memory`。
*   **存储**: 独立 SQLite 表 `core_memories`。
*   **使用**: 高优先级注入 System Prompt，确保 AI 绝对记住用户的核心偏好（如“我是程序员”）。

### 2.4 知识图谱 (Knowledge Graph)
*   **查询**: 工具化 (`query_knowledge_graph`)，AI 按需查询实体关系。
*   **构建**: 保持为后台静默流程，不占用对话轮次。

## 3. 用户界面与配置 (UI & Config)

### 3.1 技能设置面板 (Skills Settings)
*   **位置**: `ChatSettings` -> `Skills`。
*   **功能**:
    *   **开关管理**: 启用/禁用特定技能（如关闭“联网搜索”）。
    *   **参数配置**: 配置搜索结果数量等。
    *   **循环限制**: `Agent Reasoning Depth` 滑动条 (1-10)。

### 3.2 任务可视化 (Task Timeline)
*   **组件**: `ToolExecutionTimeline`。
*   **交互**: 位于气泡下方，实时展示思考链：`思考 -> 调用工具(参数) -> 获取结果 -> 最终回复`。支持折叠/展开。

### 3.3 记忆管理 (Memory UI)
*   **组件**: `CoreMemoryList`。
*   **功能**: 查看所有核心记忆，支持左滑删除。

## 4. 详细实施步骤 (Implementation Steps)

### Phase 1: 基础设施升级 (Infrastructure)
1.  **Type System**: 修改 `types.ts`，增加 `Tool`, `ToolCall` 定义。
2.  **Provider Upgrade**: 升级 `providers/openai.ts` 和 `gemini.ts`，实现 Function Call 的参数构造与结果解析。

### Phase 2: 技能引擎核心 (The Engine)
1.  **Registry**: 实现 `SkillRegistry` 单例。
2.  **Core Skills Definition**: 
    - `query_vector_db` (RAG)
    - `query_knowledge_graph` (KG)
    - `search_internet` (Web)
    - `generate_image` (Dynamic Router)
    - `save_core_memory` (Explicit Memory)
    - `system_tools` (Compile-time helpers)

### Phase 3: 核心组件与 UI (UI Components)
1.  **Memory UI**: 实现 `CoreMemoryList` 及管理页。
2.  **Timeline**: 实现 `ToolExecutionTimeline`。
3.  **Settings**: 实现 `SkillsSettingsPanel`。

### Phase 4: 代理循环集成 (The Brain)
1.  **ChatStore Refactor**: 重构 `generateMessage`。
    - 集成 `AgentLoop` 控制器。
    - 实现 `maxLoopCount` 熔断。
    - 维护 `executionSteps` 状态供 Timeline 渲染。

## 5. 验证计划

1.  **扩展性验证**: 添加一个简单的 Mock Skill，验证无需修改核心代码即可被调用。
2.  **全链路场景**:
    - **生图**: 验证是否正确路由到 Google Imagen/Banana。
    - **记忆**: 验证 "记住我不吃辣" -> 存入数据库 -> UI 可见 -> 下次对话生效。
    - **多步**: 验证 "查库未果 -> 联网搜索" 的自动推理链及 Timeline 展示。
