# 架构设计方案：Nexara 全局资源管理器与会话工作区深度联动

**日期**: 2026-05-15
**状态**: 方案设计/待校对
**领域**: 资源管理、RAG、工作区、AI 产物持久化

## 1. 核心设计哲学
将 Nexara 从一个“对话框”升级为“以资源为中心的生产力系统”。
*   **知识库**：物理存储与全局管理视图（Global Perspective）。
*   **会话工作区**：基于路径锚定的逻辑视图（Scoped Perspective），本质上是一个“项目目录”。

## 2. 核心机制：锚定视角 (Anchored Perspective)

### 2.1 会话与目录的绑定
*   每个 `Session` 对应一个 `workspace_root_id`（指向 `FolderEntity` 的 UUID）。
*   **自动初始化逻辑**：当会话产生文件操作需求且未指定目录时，系统自动执行：
    1.  检查是否存在根目录 `/workspace/`。
    2.  创建子目录 `YYYYMMDD-NNN`（如 `20260515-001`）。
    3.  将该目录 ID 绑定至当前会话。

### 2.2 视角差异实现
*   **全局模式**：资源管理器展示 root 为 `null` 的完整树。
*   **工作区模式**：资源管理器展示 root 为 `workspace_root_id` 的子树。
*   **能力对齐**：两种模式复用相同的 UI 组件（列表、操作菜单、批量处理、RAG/KG 触发）。

## 3. 技术实现要点

### 3.1 UUID 锚定协议 (UUID Anchoring)
为了解决文件移动导致的引用失效：
*   **RAG/KG 关联**：所有向量块（Vector Chunks）和图谱节点（KG Nodes）必须通过 `document_uuid` 而非文件路径进行关联。
*   **路径隔离**：文件的物理存储路径与逻辑管理路径分离，数据库仅通过 `folder_id` 维护层级，移动操作仅需修改父 ID，不影响任何 AI 任务状态。

### 3.2 产物持久化链路 (Artifact Lifecycle)
*   **过程产物**：对话记录导出、临时代码文件、创作草稿，默认写入锚定的工作区。
*   **AI 迭代能力**：
    *   赋予 AI 工具 `readFile`, `writeFile`, `patchFile` 能力。
    - AI 操作范围锁定在当前会话的 `workspace_root_id` 及其子目录下。
    - 实现类似 IDE 的“增量更新”能力，AI 可以对已有文件进行 Diff 修改。

### 3.3 预备物料机制
*   支持“带料入场”：用户在全局资源管理器提前创建文件夹 -> 上传素材 -> 发起 RAG 处理 -> 开启会话 -> 手动锚定到该文件夹。

## 4. 数据库 Schema 变更预案

### SessionEntity 扩展
```kotlin
@ColumnInfo(name = "workspace_root_id")
val workspaceRootId: String? = null // 指向 folders 表
```

### FolderEntity 增强
*   增加 `type` 字段，区分 `USER_CREATED`, `SYSTEM_WORKSPACE`。

## 5. 后续细化方向
1.  **冲突处理**：如果两个会话锚定到同一个目录，如何处理文件竞争？
2.  **版本回溯**：是否需要为工作区产物引入轻量级的 Git-like 版本快照？
3.  **UI 集成**：对话界面侧边栏如何更优雅地展示当前锚定的“工作区”状态。

---
*本计划由 Antigravity (AI Coding Assistant) 根据用户需求整理，待 DeepSeek 审计与用户确认。*
