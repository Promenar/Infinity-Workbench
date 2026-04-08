# Artifacts 与工作区UI关联设计分析报告

| 版本 | 日期 | 作者 | 状态 |
|------|------|------|------|
| 1.0.0 | 2026-04-08 | Architect Mode | 完成 |

---

## 目录

1. [当前实现状态分析](#1-当前实现状态分析)
2. [行业标杆分析](#2-行业标杆分析)
3. [关联设计评估](#3-关联设计评估)
4. [整合设计方案](#4-整合设计方案)
5. [实施建议](#5-实施建议)

---

## 1. 当前实现状态分析

### 1.1 Artifacts 系统现状

#### 1.1.1 数据结构

```typescript
// src/types/chat.ts:109-113
type ToolResultArtifact = {
  type: 'echarts' | 'mermaid' | 'math' | 'image' | 'text';
  content: string;
  name?: string;
};
```

#### 1.1.2 存储机制

| 层级 | 存储位置 | 说明 |
|------|----------|------|
| 内存 | Message.toolResults | 消息对象的字段 |
| 持久化 | SQLite messages.tool_results | JSON序列化存储 |
| 全局索引 | 不存在 | 无法跨会话查询 |

#### 1.1.3 渲染流程

AI工具调用 → tool-execution.ts → 正则提取 → Message.toolResults → ToolArtifacts组件 → WebView渲染

**关键文件**：
- src/features/chat/components/ToolArtifacts.tsx - 渲染容器
- src/store/chat/tool-execution.ts:310-334 - 产物注入逻辑
- src/lib/db/schema.ts:63 - 数据库列定义

#### 1.1.4 当前限制

| 问题 | 影响 | 优先级 |
|------|------|--------|
| 无全局索引 | 无法跨会话查找Artifacts | P0 |
| 无独立存储 | 随消息删除而丢失 | P0 |
| 无导出功能 | 用户无法保存分享 | P0 |
| 类型有限 | 仅支持echarts/mermaid | P1 |
| 无版本控制 | 无法追溯修改历史 | P2 |

### 1.2 工作区UI现状

#### 1.2.1 现有实现

**关键发现**：工作区按钮和弹窗 **尚未实现**，需要全新设计。

**相关但不同的概念**：
- src/store/workbench-store.ts - 管理Workbench服务器状态，非Artifacts存储
- src/services/workbench/ - WebSocket服务器，供Web客户端连接

#### 1.2.2 UI位置分析

| 组件 | 当前内容 | 工作区按钮位置建议 |
|------|----------|-------------------|
| ChatInput topBar | Model、Tokens、ThinkingLevelButton | 可添加在右侧 |
| GlassHeader rightAction | Settings按钮 | 可并列添加 |
| 新增浮动按钮 | - | 独立FAB |

---

## 2. 行业标杆分析

### 2.1 Claude Artifacts 设计

**核心特点**：

Chat对话 → Artifact生成 → 右侧独立窗口 → 实时交互编辑 / 版本历史 / 分享导出

**关键设计决策**：
1. **分离显示**：Artifact在独立窗口显示，与聊天并排
2. **持久化存储**：Artifact独立于消息存在
3. **Catalog索引**：全局可搜索的Artifact目录
4. **交互能力**：支持直接编辑和操作

### 2.2 ChatGPT Canvas 设计

**核心特点**：

Chat对话 → 内容创建 → Canvas独立窗口 → AI辅助编辑 / 版本对比 / 协作支持

**关键设计决策**：
1. **内容隔离**：长文档/代码在独立窗口编辑
2. **桥接工作流**：聊天与内容创建无缝切换
3. **智能编辑**：AI在Canvas中提供修改建议

### 2.3 对比总结

| 维度 | Claude Artifacts | ChatGPT Canvas | Nexara当前 |
|------|-----------------|----------------|-----------|
| 显示方式 | 独立侧边窗口 | 独立窗口 | 内联卡片 |
| 存储方式 | 独立存储 | 独立存储 | 消息字段 |
| 全局索引 | Catalog支持 | 支持 | 不支持 |
| 编辑能力 | 支持 | 支持 | 不支持 |
| 导出分享 | 支持 | 支持 | 不支持 |

---

## 3. 关联设计评估

### 3.1 功能关联必要性

**当前问题**：
- Artifacts分散在各消息中
- 无统一入口访问
- 随消息删除而丢失

**用户需求**：
- 快速访问所有图表
- 跨会话查找复用
- 独立保存重要内容
- 导出分享给他人

**解决方案**：
- 工作区作为Artifact容器
- 全局索引和搜索
- 独立持久化存储

### 3.2 关联设计价值

| 价值点 | 描述 | 收益 |
|--------|------|------|
| 统一入口 | 工作区按钮提供快速访问所有Artifacts | 提升效率 |
| 持久存储 | Artifacts独立存储，不随消息删除 | 数据安全 |
| 跨会话访问 | 全局索引支持搜索和筛选 | 复用性提升 |
| 导出分享 | 一键导出多种格式 | 实用性增强 |

---

## 4. 整合设计方案

### 4.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        UI层                                  │
├─────────────────────────────────────────────────────────────┤
│  WorkspaceButton  →  WorkspacePanel  →  ArtifactCard        │
│                                              ↓               │
│                                    ArtifactDetailModal       │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                       业务层                                 │
├─────────────────────────────────────────────────────────────┤
│  ArtifactManager  →  ArtifactIndexer  →  ExportService      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                       数据层                                 │
├─────────────────────────────────────────────────────────────┤
│  ArtifactStore (Zustand)  →  artifacts表 (SQLite)           │
│                                      ↓                       │
│                            消息关联映射                       │
└─────────────────────────────────────────────────────────────┘
                              ↑
┌─────────────────────────────────────────────────────────────┐
│                      现有系统                                │
├─────────────────────────────────────────────────────────────┤
│  Message.toolResults  ←→  ToolExecution (自动提取)          │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 数据模型设计

#### 4.2.1 Artifact 实体

```typescript
// src/types/artifact.ts

export interface Artifact {
  id: string;                           // UUID
  type: ArtifactType;                   // 类型
  title: string;                        // 标题
  content: string;                      // 原始内容
  preview?: string;                     // 预览图
  description?: string;                 // 描述
  sourceSessionId: string;              // 来源会话ID
  sourceMessageId: string;              // 来源消息ID
  sourceAgentId?: string;               // 来源AgentID
  createdAt: number;
  updatedAt: number;
  isPinned?: boolean;                   // 是否置顶
  isFavorite?: boolean;                 // 是否收藏
  tags?: string[];                      // 标签
}

export type ArtifactType =
  | 'echarts' | 'mermaid' | 'math' | 'image' | 'code' | 'table' | 'document';
```

#### 4.2.2 数据库Schema

```sql
CREATE TABLE IF NOT EXISTS artifacts (
  id TEXT PRIMARY KEY NOT NULL,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  preview TEXT,
  description TEXT,
  source_session_id TEXT NOT NULL,
  source_message_id TEXT NOT NULL,
  source_agent_id TEXT,
  is_pinned INTEGER DEFAULT 0,
  is_favorite INTEGER DEFAULT 0,
  tags TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (source_session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_artifacts_type ON artifacts(type);
CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts(source_session_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_created ON artifacts(created_at DESC);
```

### 4.3 UI组件设计

#### 4.3.1 WorkspaceButton 组件

位置：ChatInput topBar 右侧

```typescript
interface WorkspaceButtonProps {
  count: number;        // Artifact数量
  onPress: () => void;
}
```

显示图标：FolderOpen，带数量徽章，点击打开 WorkspacePanel

#### 4.3.2 WorkspacePanel 组件

形态：GlassBottomSheet

```typescript
interface WorkspacePanelProps {
  visible: boolean;
  onClose: () => void;
  sessionId?: string;   // 可选：筛选当前会话
}
```

功能：显示Artifact列表、搜索框、筛选器、点击打开详情

#### 4.3.3 ArtifactCard 组件

```typescript
interface ArtifactCardProps {
  artifact: Artifact;
  onPress: () => void;
  onPin?: () => void;
  onExport?: () => void;
  onDelete?: () => void;
}
```

显示：缩略图、标题、来源会话、时间戳、操作按钮

### 4.4 数据流设计

```
AI工具调用 → ToolExecution → 检测Artifact类型 → 自动提取
     ↓
ArtifactManager.addArtifact()
     ↓
ArtifactStore (Zustand) → UI更新
     ↓
SQLite artifacts表 → 持久化
```

### 4.5 自动提取机制

在 tool-execution.ts 中增强现有逻辑：

```typescript
// 现有代码位置: src/store/chat/tool-execution.ts:310-334

if (markdownMatch) {
  // 1. 现有逻辑：添加到消息的toolResults
  const newToolResults = [
    ...(currentMsg.toolResults || []),
    { type: artifactType, content: markdownMatch[0], name: tcName }
  ];
  
  // 2. 新增：同步到全局Artifact Store
  useArtifactStore.getState().addArtifact({
    type: artifactType,
    title: generateArtifactTitle(markdownMatch[0], artifactType),
    content: markdownMatch[0],
    sourceSessionId: sessionId,
    sourceMessageId: targetMsgId,
  });
}
```

---

## 5. 实施建议

### 5.1 实施阶段

| 阶段 | 任务 | 优先级 |
|------|------|--------|
| Phase 1 | 创建Artifact类型和Store | P0 |
| Phase 2 | 创建数据库表和迁移 | P0 |
| Phase 3 | 实现WorkspaceButton和Panel | P0 |
| Phase 4 | 实现自动提取机制 | P0 |
| Phase 5 | 添加导出功能 | P1 |
| Phase 6 | 添加搜索和筛选 | P1 |
| Phase 7 | 添加收藏和标签 | P2 |

### 5.2 文件结构建议

```
src/
├── types/
│   └── artifact.ts              # Artifact类型定义
├── store/
│   └── artifact-store.ts        # Zustand Store
├── lib/db/
│   └── artifact-repository.ts   # SQLite操作
├── features/chat/components/
│   ├── WorkspaceButton.tsx      # 工作区按钮
│   ├── WorkspacePanel.tsx       # 工作区面板
│   ├── ArtifactCard.tsx         # Artifact卡片
│   └── ArtifactDetailModal.tsx  # 详情弹窗
└── services/
    └── artifact-manager.ts      # 业务逻辑
```

### 5.3 关键决策点

1. **存储策略**：采用双写策略，同时保存到消息字段和独立表
2. **UI位置**：推荐在ChatInput topBar右侧添加按钮
3. **面板形态**：使用GlassBottomSheet保持设计一致性
4. **自动同步**：在ToolExecution中自动提取，无需用户手动操作

---

## 附录

### A. 与现有文档的关系

本方案与以下文档互补：
- docs/artifacts-optimization-plan.md - Artifacts渲染器优化
- docs/industry-comparison-report.md - 行业对比分析

### B. 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 数据迁移 | 历史数据需同步 | 提供迁移脚本 |
| 性能影响 | 大量Artifact时卡顿 | 分页加载、虚拟列表 |
| 存储空间 | SQLite文件增大 | 定期清理、压缩 |

---

*报告完成于 2026-04-08*