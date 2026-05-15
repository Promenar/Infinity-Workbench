# 架构设计方案：Nexara 内置任务规划与追踪工具 (Task Planner) V2

**日期**: 2026-05-15
**状态**: 方案深化/最高优先级
**领域**: AI 代理、任务树、状态持久化、UI 集成

## 1. 核心设计原则

### 1.1 会话唯一性 (Singleton Task)
*   **强制约束**：每个会话（Session）在同一时刻**强制只能有一个**激活的任务树。
*   **排他性**：新任务的创建（`initialize_plan`）将自动归档或替换旧任务。

### 1.2 状态一致性与持久化
*   **抗中断性**：任务状态必须实时持久化至 Room 数据库。
*   **断点续行**：无论因用户决策、网络故障、系统崩溃还是关机重启，回到对应会话后，任务面板必须根据持久化状态**自动恢复**，AI 必须感知到之前的断点。

### 1.3 生命周期
*   **终止状态**：当任务被标记为 `DONE` 或 `DROPPED`（由 LLM 指令或用户 UI 操作触发）时，任务面板从 UI 上隐藏。

## 2. 数据模型 (Data Model)

### 2.1 递归任务节点 (TaskNode)
*   **id**: UUID。
*   **parentId**: UUID? (支持树状层级)。
*   **title/description**: 描述。
*   **status**: 节点状态，所有层级共享枚举：`[TODO, DOING, DONE, DROPPED]`。
*   **metadata**: 包含 UI 状态（如是否折叠）、关联资源 URI、执行时间戳等。

### 2.2 状态机行为
*   **父子联动**：当所有子节点为 `DONE` 时，父节点建议（或自动）标记为 `DONE`。
*   **DROPPED 传播**：父节点被 `DROPPED`，所有子节点强制递归 `DROPPED`。

## 3. 工具接口 (AI Tools API)

*   `initialize_plan(goal: String, tree: List<Node>)`: 初始化任务，定义多级步骤。
*   `update_plan_batch(taskId: String, updates: List<NodeUpdate>)`: **批量修改**任务内容、结构或状态。
*   `set_step_status(stepId: String, status: String, note: String?)`: 精确更新某个子步骤。
*   `drop_task(taskId: String, reason: String)`: 放弃当前任务，触发 UI 隐藏。

## 4. UI/UX 设计方案

### 4.1 输入框集成式任务 HUD
*   **位置**：位于聊天输入框（`ChatInput`）正上方，作为输入区的动态扩展。
*   **交互逻辑**：
    *   **自动展开**：任务初次创建（`initialize_plan`）时，面板自动滑出并展开。
    *   **折叠持久化**：用户手动点击“折叠/展开”后，该状态立即持久化。后续 AI 更新状态时，面板**不应**擅自改变用户的折叠偏好，但需通过微小的视觉动画（如呼吸灯或进度条闪烁）提示后台有更新。
*   **视觉风格**：采用玻璃拟态（Glassmorphism），半透明背景，左侧显示当前 `DOING` 步骤的简写，右侧显示总进度。

## 5. 系统提示词注入 (Context Injection)

*   **动态切片**：每轮对话前，ContextBuilder 仅提取：
    1.  Root 目标。
    2.  当前正在进行的步骤（`status = DOING`）及其上下文环境。
    3.  接下来的 2-3 个待办项（`status = TODO`）。
*   **断点重连提示**：若会话重启，System Prompt 会显式加入：`"Current mission resumed: [Title]. Last finished step was [X]. Current focus is [Y]."`

## 6. 实施路线图
1.  [ ] **数据库迁移**：新增 `task_tree` 和 `task_nodes` 表。
2.  [ ] **Protocol 增强**：在 `UpdateMessageOptions` 中增加 `task_update` 标识。
3.  [ ] **UI 实装**：开发 `TaskFloatingPanel` 组件并集成至 `ChatInput`。
4.  [ ] **逻辑联调**：验证关机重启后的任务恢复鲁棒性。

---
*本方案为论证阶段，未经明确授权禁止修改源代码。*
