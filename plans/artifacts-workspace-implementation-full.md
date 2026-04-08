# Artifacts与Workspace整合实施方案

| 版本 | 日期 | 作者 | 状态 |
|------|------|------|------|
| 1.0.0 | 2026-04-08 | Architect Mode | 完成 |

---

## 目录

1. [概述](#1-概述)
2. [现有系统分析](#2-现有系统分析)
3. [数据模型设计](#3-数据模型设计)
4. [状态管理设计](#4-状态管理设计)
5. [数据库设计](#5-数据库设计)
6. [UI组件设计](#6-ui组件设计)
7. [自动提取机制](#7-自动提取机制)
8. [实施步骤](#8-实施步骤)

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
UI层: WorkspaceSheet -> ArtifactListTab -> ArtifactCard -> ArtifactDetailModal
                                              |
状态管理层: ArtifactStore (Zustand) -> artifacts[], filters, actions
                                              |
数据持久层: SQLite Database -> artifacts表
                                              |
自动提取层: ToolExecutionManager -> extractAndStoreArtifacts()
```

---

## 2. 现有系统分析

### 2.1 当前Artifact数据结构

```typescript
// src/types/chat.ts:109-113
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

当前ArtifactList实现：
- 从文件系统读取 `.artifacts/index.json`
- 依赖Agent sandbox目录结构
- 无全局索引能力

---

## 3. 数据模型设计

### 3.1 Artifact类型定义

**文件**: `src/types/artifact.ts` (新建)

```typescript
export type ArtifactType = 'echarts' | 'mermaid' | 'math' | 'html' | 'svg' | 'image' | 'code' | 'text';

export interface Artifact {
  id: string;              // UUID
  type: ArtifactType;
  title: string;
  content: string;
  previewImage?: string;   // Base64
  description?: string;
  sessionId: string;
  messageId: string;
  agentId?: string;
  createdAt: number;
  updatedAt: number;
  tags?: string[];
  isFavorite?: boolean;
  size?: number;
}

export interface ArtifactFilters {
  type?: ArtifactType | 'all';
  sessionId?: string;
  keyword?: string;
  dateRange?: { start: number; end: number };
  isFavorite?: boolean;
}

export interface CreateArtifactParams {
  type: ArtifactType;
  title?: string;
  content: string;
  previewImage?: string;
  description?: string;
  sessionId: string;
  messageId: string;
  agentId?: string;
}

export type ExportFormat = 'png' | 'svg' | 'json' | 'html';
```

### 3.2 类型配置映射

**文件**: `src/constants/artifact-config.ts` (新建)

```typescript
import { ArtifactType } from '../types/artifact';
import { PieChart, GitBranch, SquarePi, Code, Image, FileText } from 'lucide-react-native';

export const ARTIFACT_CONFIG: Record<ArtifactType, {
  icon: React.ElementType;
  color: string;
  label: string;
}> = {
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

**文件**: `src/store/artifact-store.ts` (新建)

```typescript
import { create } from 'zustand';
import { Artifact, ArtifactFilters, CreateArtifactParams, ArtifactType } from '../types/artifact';
import { db } from '../lib/db';
import { v4 as uuidv4 } from 'uuid';
import { ARTIFACT_CONFIG } from '../constants/artifact-config';

interface ArtifactState {
  artifacts: Artifact[];
  filters: ArtifactFilters;
  isLoading: boolean;
  error: string | null;
}

interface ArtifactActions {
  loadArtifacts: () => Promise<void>;
  loadArtifactsBySession: (sessionId: string) => Promise<void>;
  addArtifact: (params: CreateArtifactParams) => Promise<Artifact>;
  updateArtifact: (id: string, updates: Partial<Artifact>) => Promise<void>;
  removeArtifact: (id: string) => Promise<void>;
  setFilters: (filters: Partial<ArtifactFilters>) => void;
  resetFilters: () => void;
  toggleFavorite: (id: string) => Promise<void>;
  getFilteredArtifacts: () => Artifact[];
}

const DEFAULT_FILTERS: ArtifactFilters = { type: 'all', keyword: '' };

export const useArtifactStore = create<ArtifactState & ArtifactActions>((set, get) => ({
  artifacts: [],
  filters: DEFAULT_FILTERS,
  isLoading: false,
  error: null,

  loadArtifacts: async () => {
    set({ isLoading: true });
    try {
      const result = await db.execute('SELECT * FROM artifacts ORDER BY created_at DESC');
      const rows = result.rows?._array || result.rows || [];
      set({ artifacts: rows.map(mapDbRowToArtifact), isLoading: false });
    } catch (e) {
      set({ error: (e as Error).message,