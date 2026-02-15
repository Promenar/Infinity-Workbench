# Nexara RAG & KG 指示器体系重构需求文档 (PRD)

## 1. 项目概述

### 1.1 背景分析
当前 Nexara 系统实现了高度集成的 RAG+KG 复合指示器系统，虽然功能强大但在用户体验上存在以下问题：
- 指示器职责不够单一，用户难以理解具体的处理阶段
- 缺乏清晰的前后处理阶段区分
- 可解释性和控制感有待提升

### 1.2 目标愿景
构建职责单一、阶段明确的四独立可视化模块，提升用户对推理过程的理解和控制体验。

## 2. 功能需求详述

### 2.1 RAG与知识图谱指示器体系重构

#### A. 模型生成前阶段（Pre-generation Phase）

**1. RAG检索指示器**
- **位置**：消息输入区上方原集成指示器区域，宽度压缩为50%，左对齐布局
- **职责范围**：
  - 语义检索（Retrieval）
  - 结果重排序（Rerank）  
  - 查询重写（Query Rewriting）
- **功能特性**：
  - 实时状态显示（进行中/完成/失败）
  - 命中文档数量统计
  - 进度条或环形加载动画
  - 简洁紧凑设计，避免信息过载

**2. KG检索指示器**
- **位置**：紧邻RAG检索指示器右侧剩余50%空间，右对齐或居中对齐
- **职责范围**：
  - 实体链接匹配
  - 三元组匹配检索
- **功能特性**：
  - 展开/折叠交互（默认收起）
  - 展开后显示具体KG节点与关系
  - 收起时显示摘要图标与状态
  - 与全局处理状态机联动同步

#### B. 模型生成后阶段（Post-generation Phase）

**1. RAG生成指示器**
- **位置**：每条助理消息气泡底部左侧50%空间
- **职责范围**：
  - 向量块写入（Embedding Storage）
  - 自动摘要生成（Auto-summarization）
- **功能特性**：
  - 展开查看实际写入的文本片段
  - 重新生成摘要等轻交互入口
  - 状态反馈（已完成/写入中/失败）

**2. KG生成指示器**
- **位置**：同条消息气泡底部右侧50%空间，对称布局
- **职责范围**：
  - 新实体提取
  - 关系抽取
  - 图谱更新
- **功能特性**：
  - 展开查看提取的结构化三元组
  - 跳转至知识图谱全局视图
  - 图示化元素增强识别度

### 2.2 创造物框架（Artifacts-like System）建设

#### 2.2.1 核心功能要求
- **模型能力**：Agent通过自然语言调用工具创建、修改、读取Markdown文档
- **存储基础**：基于 `./workspace/artifacts/` 的内置文件系统
- **文档类型**：任务计划、中间结论、最终报告等

#### 2.2.2 前端呈现设计
- **UI面板**：独立侧边栏或底部抽屉展示关联创造物列表
- **浏览功能**：按时间、类型、所属任务分类浏览
- **交互特性**：
  - 点击打开文档预览与编辑
  - 内置MD编辑器
  - 实时同步模型写入内容
- **工作流融合**：
  - 多步骤任务中持续更新同一文档
  - 结合Task Management Skill作为状态追踪看板
  - 支持导出或分享特定Artifact

### 2.3 写作模式功能

#### 2.3.1 核心概念
- 将长期内容创作支持能力拓展为书籍项目管理
- 会话高质量输出沉淀为章节内容
- 基于创造物框架API快速构建

#### 2.3.2 功能设计

**写作模式入口**
- 会话界面"进入写作模式"按钮
- 书籍项目管理：新建、打开、重命名、删除
- 目录结构：`./workspace/artifacts/books/{title}/`

**章节提取机制**
- 助理消息气泡上下文菜单增加"提取到章节"选项
- 弹出选择框：目标书籍 + 章节标题输入
- 自动保存为章节MD文件，追加至书籍目录
- 写作模式中默认存入当前打开书籍

## 3. 系统架构设计

### 3.1 组件模块划分

```
UI Layer
├── Pre-generation Indicators (输入区上方)
│   ├── RAG Retrieval Indicator (左侧50%)
│   └── KG Retrieval Indicator (右侧50%)
├── Post-generation Indicators (消息气泡底部)
│   ├── RAG Generation Indicator (左侧50%)
│   └── KG Generation Indicator (右侧50%)
├── Artifacts Panel (侧边栏/抽屉)
│   ├── Document Browser
│   ├── MD Editor
│   └── Task Integration
└── Writing Mode Interface
    ├── Book Management
    └── Chapter Extraction
```

### 3.2 状态流转逻辑

#### 3.2.1 指示器状态机

**Pre-generation 流程：**
```
Idle → Rewriting → Searching → Reranking → Done
     ↘ KG_Searching ↗
```

**Post-generation 流程：**
```
Generation_Complete → Embedding → Summarizing → Archiving
                   ↘ KG_Extraction ↗
```

#### 3.2.2 数据流设计

```
User Input
    ↓
[Pre-gen Indicators Active]
    ↓
LLM Processing
    ↓
[Post-gen Indicators Active]
    ↓
Artifacts System (if applicable)
    ↓
Writing Mode (if triggered)
```

### 3.3 组件依赖关系

```
Core Dependencies:
├── RagStore (状态管理)
├── ChatStore (会话管理)
├── FileSystem Skills (文件操作)
├── Task Management (工作流)
└── Knowledge Graph (图谱处理)

Integration Points:
├── Existing RAG Pipeline
├── Current Skill System
├── File Operation APIs
└── UI Component Library
```

## 4. 技术实现规划

### 4.1 关键技术选型

**前端框架**：React Native + TypeScript
**状态管理**：Zustand (延续现有架构)
**UI组件**：现有组件库扩展
**数据持久化**：SQLite + 文件系统

### 4.2 核心组件清单

#### 4.2.1 指示器组件
- `PreGenRagIndicator.tsx`
- `PreGenKgIndicator.tsx`
- `PostGenRagIndicator.tsx`
- `PostGenKgIndicator.tsx`

#### 4.2.2 创造物系统组件
- `ArtifactsPanel.tsx`
- `ArtifactBrowser.tsx`
- `MdEditor.tsx`
- `ArtifactItem.tsx`

#### 4.2.3 写作模式组件
- `WritingModeButton.tsx`
- `BookManager.tsx`
- `ChapterExtractor.tsx`
- `WritingWorkspace.tsx`

### 4.3 数据结构设计

```typescript
// 指示器状态扩展
interface ExtendedRagState {
  preGen: {
    rag: ProcessingStage;
    kg: ProcessingStage;
  };
  postGen: {
    rag: ProcessingStage;
    kg: ProcessingStage;
  };
}

// 创造物数据结构
interface Artifact {
  id: string;
  title: string;
  type: 'document' | 'task' | 'book' | 'chapter';
  content: string;
  createdAt: Date;
  updatedAt: Date;
  metadata: Record<string, any>;
}

// 书籍章节结构
interface BookProject {
  id: string;
  title: string;
  chapters: Chapter[];
  createdAt: Date;
  lastModified: Date;
}

interface Chapter {
  id: string;
  title: string;
  content: string;
  order: number;
  createdAt: Date;
}
```

## 5. 实施路线图

### 5.1 第一阶段：指示器体系重构（4周）

**Week 1-2：基础架构**
- 设计新的状态管理结构
- 创建指示器组件基础框架
- 实现状态机逻辑

**Week 3-4：UI实现与集成**
- 开发四个独立指示器组件
- 集成到现有聊天界面
- 完善交互和视觉效果

### 5.2 第二阶段：创造物框架建设（3周）

**Week 1：核心功能开发**
- 实现文件系统集成
- 开发基本的文档CRUD操作
- 创建Artifacts Panel UI

**Week 2：高级功能实现**
- 集成任务管理系统
- 实现实时同步机制
- 添加分类和搜索功能

**Week 3：测试与优化**
- 完整功能测试
- 性能优化
- 用户体验微调

### 5.3 第三阶段：写作模式功能（2周）

**Week 1：基础功能**
- 实现书籍项目管理
- 开发章节提取机制
- 创建写作模式界面

**Week 2：集成与完善**
- 与创造物系统深度集成
- 完善用户体验
- 文档和教程编写

## 6. 质量保证

### 6.1 测试策略
- 单元测试覆盖核心逻辑
- 集成测试验证组件协作
- UI测试确保交互正确性
- 性能测试监控响应速度

### 6.2 兼容性考虑
- 向后兼容现有RAG功能
- 渐进式功能启用
- 配置开关控制新特性

### 6.3 用户体验指标
- 指示器响应时间 < 100ms
- 页面加载时间 < 2秒
- 用户操作成功率 > 99%

## 7. 风险评估与缓解

### 7.1 技术风险
- **状态同步复杂性**：通过清晰的状态机设计降低复杂度
- **性能影响**：异步处理和懒加载优化性能
- **数据一致性**：事务性操作确保数据完整性

### 7.2 用户接受度风险
- **学习成本**：渐进式引导和直观设计降低门槛
- **功能冲突**：充分的用户测试和反馈收集
- **迁移问题**：提供平滑的升级路径

## 8. 成功标准

### 8.1 功能完成度
- [ ] 四个独立指示器全部实现并集成
- [ ] 创造物框架完整功能上线
- [ ] 写作模式功能稳定运行
- [ ] 所有原有功能保持正常

### 8.2 用户体验指标
- 用户满意度评分 ≥ 4.5/5.0
- 新功能使用率 ≥ 60%
- 系统稳定性 ≥ 99.9%
- 平均响应时间 ≤ 200ms

---

*本文档将持续更新，根据技术评审和用户反馈进行迭代优化。*