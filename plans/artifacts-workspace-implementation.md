# Artifacts与Workspace整合实施方案

| 版本 | 日期 | 作者 | 状态 |
|------|------|------|------|
| 1.0.0 | 2026-04-08 | Architect Mode | 完成 |

---

## 1. 概述

### 1.1 设计目标

将Workspace打造为Artifacts的统一管理入口，实现：

1. **全局Artifact索引**：所有会话生成的Artifacts统一展示
2. **会话关联**：Artifacts与来源会话保持关联，可快速跳转
3. **持久化存储**：Artifacts独立存储，不随消息删除而丢失
4. **导出分享**：支持导出为PNG/SVG/JSON等格式
5. **搜索筛选**：按类型、时间、会话等维度筛选

### 1.2 架构概览

```
UI层: WorkspaceSheet -> ArtifactList -> ArtifactCard -> ArtifactDetailModal
                                        |
状态管理层: ArtifactStore (Zustand) -> artifacts[], filters, actions
                                        |
数据持久层: SQLite Database -> artifacts表
                                        |
自动提取层: ToolExecution -> extractAndStoreArtifacts()
```

---

## 2. 现有系统分析

### 2.1 当前Artifact数据结构

文件: [`src/types/chat.ts:109`](src/types/chat.ts:109)

```typescript
type ToolResultArtifact = {
  type: 'echarts' | 'mermaid' | 'math' | 'image' | 'text';
  content: string;
  name?: string;
};
```

**限制**：缺少唯一标识符、会话/消息关联、时间戳、预览图

### 2.2 当前存储机制

| 层级 | 存储位置 | 问题 |
|------|----------|------|
| 内存 | Message.toolResults | 随消息对象生命周期 |
| 持久化 | messages.tool_results | 无独立索引 |

### 2.3 现有WorkspaceSheet结构

文件: [`src/features/chat/components/WorkspaceSheet/index.tsx`](src/features/chat/components/WorkspaceSheet/index.tsx:1)

当前ArtifactList实现：
- 从文件系统读取 `.artifacts/index.json`
- 依赖Agent sandbox目录结构
- 无全局索引能力

---

## 3. 数据模型设计

### 3.1 Artifact类型定义

**新建文件**: `src/types/artifact.ts`

```typescript
export type ArtifactType = 'echarts' | 'mermaid' | 'math' | 'html' | 'svg' | 'image' | 'code' | 'text';

export interface Artifact {
  id: string;
  type: ArtifactType;
  title: string;
  content: string;
  previewImage?: string;
  sessionId: string;
  messageId: string;
  createdAt: number;
  updatedAt: number;
  isFavorite?: boolean;
}

export interface ArtifactFilters {
  type?: ArtifactType | 'all';
  sessionId?: string;
  keyword?: string;
}

export interface CreateArtifactParams {
  type: ArtifactType;
  title?: string;
  content: string;
  sessionId: string;
  messageId: string;
}
```

### 3.2 类型配置映射

**新建文件**: `src/constants/artifact-config.ts`

```typescript
import { PieChart, GitBranch, SquarePi, Code, Image, FileText } from 'lucide-react-native';

export const ARTIFACT_CONFIG = {
  echarts: { icon: PieChart, color: '#6366f1', label: '图表' },
  mermaid: { icon: GitBranch, color: '#22c55e', label: '流程图' },
  math: { icon: SquarePi, color: '#f59e0b', label: '公式' },
  html: { icon: Code, color: '#3b82f6', label: 'HTML' },
  svg: { icon: Image, color: '#ec4899', label: 'SVG' },
  image: { icon: Image, color: '#8b5cf6', label: '图片' },
  code: { icon: Code, color: '#14b8a6', label: '代码' },
  text: { icon: FileText, color: '#64748b', label: '文本' },
};
```

---

## 4. 状态管理设计

### 4.1 ArtifactStore设计

**新建文件**: `src/store/artifact-store.ts`

```typescript
import { create } from 'zustand';
import { db } from '../lib/db';
import { v4 as uuidv4 } from 'uuid';

export const useArtifactStore = create((set, get) => ({
  artifacts: [],
  filters: { type: 'all' },
  isLoading: false,

  loadArtifacts: async () => {
    set({ isLoading: true });
    const result = await db.execute('SELECT * FROM artifacts ORDER BY created_at DESC');
    set({ artifacts: result.rows?._array || [], isLoading: false });
  },

  addArtifact: async (params) => {
    const artifact = {
      id: uuidv4(),
      ...params,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    await db.execute('INSERT INTO artifacts VALUES (?, ?, ?, ?, ?, ?, ?, ?)', [...]);
    set(state => ({ artifacts: [artifact, ...state.artifacts] }));
    return artifact;
  },

  removeArtifact: async (id) => {
    await db.execute('DELETE FROM artifacts WHERE id = ?', [id]);
    set(state => ({ artifacts: state.artifacts.filter(a => a.id !== id) }));
  },
}));
```

---

## 5. 数据库设计

### 5.1 artifacts表结构

```sql
CREATE TABLE IF NOT EXISTS artifacts (
  id TEXT PRIMARY KEY NOT NULL,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  preview_image TEXT,
  session_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  is_favorite INTEGER DEFAULT 0,
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts(session_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_type ON artifacts(type);
CREATE INDEX IF NOT EXISTS idx_artifacts_created ON artifacts(created_at DESC);
```

### 5.2 迁移脚本

在 [`src/lib/db/migration.ts`](src/lib/db/migration.ts:1) 添加 Migration 11:

```typescript
// Migration 11: 创建artifacts表
const artifactsInfo = await db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='artifacts'");
if (!artifactsInfo.rows?.length) {
  await db.execute(`CREATE TABLE artifacts (...)`);
  await db.execute('CREATE INDEX idx_artifacts_session ON artifacts(session_id)');
}
```

---

## 6. UI组件设计

### 6.1 组件结构

```
src/features/chat/components/WorkspaceSheet/
├── index.tsx                 # 主入口 (已有)
├── ArtifactList.tsx          # 重构: 使用ArtifactStore
├──