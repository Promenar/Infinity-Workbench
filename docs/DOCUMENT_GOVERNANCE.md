# 文档治理方案

> **版本**: 1.0.0
> **制定日期**: 2026-05-13
> **覆盖范围**: 全项目文档体系（`.agent/`、`docs/`、`native-ui/.agent/`、`.qoder/`）

---

## 1. 现状诊断

### 1.1 文档分布

| 位置 | 文件数 | 年代 | 状态 |
|------|--------|------|------|
| `docs/` | 7 | 原生时代 | ✅ 全部有效 |
| `.agent/` (根) | ~86 | 混合（90% RN） | 🔴 大量过时 |
| `.agent/docs/` | 57 | RN 时代 | 🔴 全部过时/存根 |
| `.agent/memory/` | 4 | RN 时代 | 🔴 全部过时 |
| `.agent/plans/` | 27 | 原生过渡期 | 🟡 历史归档 |
| `native-ui/.agent/` | ~30 | 原生时代 | 🟡 与根重复 |
| `native-ui/.agent/plans/` | 10+ | 原生时代 | ✅ 活跃计划 |
| `.qoder/repowiki/` | 145+ | RN 时代 | 🔴 全部过时 |
| `.roo/skills/` | 少量 | 混合 | 🟡 待评估 |

### 1.2 核心问题

1. **双 `.agent/` 目录**：根目录和 `native-ui/` 下各有一套，`handover.md`/`registry.md` 内容重复
2. **Qoder repowiki 遗产**：145+ 自动生成的 RN 架构文档，已完全过时。且其 SSOT（单一事实源）理念在当前原生项目中不适用——Kotlin/Compose 的 IDE 导航能力远超 TypeScript/RN，无需如此重量的文档系统
3. **指针存根废纸**：6 个仅含 repowiki 链接的 Markdown 文件（`CODE_STRUCTURE.md` 等）已失效
4. **`PROJECT_RULES.md` 过时**：仍声明 "React Native (Expo) + TypeScript"，包含无效的 Worktree 发布规则、npm/test-llm 引用
5. **RN 时代 PRD 冗余**：`.agent/docs/PRODUCT_REQUIREMENTS.md` (v1.2.1) 已被 `docs/PRD.md` (v2.0) 取代

---

## 2. Qoder Repowiki 评估

### 2.1 原始设计意图

```
代码库 ──自动扫描──→ .qoder/repowiki/zh/content/ (145+ 篇)
                         ↑
                    .agent/docs/*.md (指针存根)
                         指向 repowiki 的 6 个存根文件
```

### 2.2 为何不适用当前项目

| 维度 | RN 时代 (需 repowiki) | 原生时代 (无需) |
|------|----------------------|----------------|
| IDE 导航 | TypeScript 大型项目导航困难 | Kotlin/Compose — Go to Definition / Find Usages 精确 |
| 代码结构 | 动态 `src/` 目录，重构频繁 | Room Entity / DAO / Repository 结构稳定 |
| 数据模型 | Zustand Store + AsyncStorage，类型分散 | Room @Entity 注解自文档化 |
| 文档同步 | 自动生成保证同步 | DIA 机制 + 手工维护，量小质高 |

### 2.3 决策：**不采用 repowiki 级文档系统**

当前 `docs/` 下的 7 份手工维护文档 + DIA 机制的覆盖度已足够。核心原则：

> **Kotlin/Compose 项目的文档策略**：架构全景图 + ADR 决策记录 + 分阶段计划。不追求代码级自动生成文档，IDE 本身已是代码导航的最佳工具。

---

## 3. 新文档结构设计

### 3.1 目标层级

```
Nexara/
│
├── docs/                              ← 公共项目文档（唯一出口）
│   ├── PRD.md                         # 产品需求文档 v2.0
│   ├── ARCHITECTURE_DESIGN.md         # 全局架构设计
│   ├── ARCHITECTURE.md                # 架构快速参考（含 ADR 索引）
│   ├── IMPLEMENTATION_ANALYSIS.md     # 当前实现分析与进度
│   ├── IMPLEMENTATION_PLAN.md         # 分阶段实施计划
│   ├── MARKDOWN_RENDERING_AUDIT.md    # Markdown 渲染审计
│   ├── DOCUMENT_GOVERNANCE.md         # 本文档（文档治理方案）
│   └── ADR/                           # 架构决策记录
│       └── 001-super-assistant-simplification.md
│
├── .agent/                            ← Agent 工作区（DIA 必需）
│   ├── registry.md                    # 文档注册表
│   ├── handover.md                    # 跨会话交接
│   └── plans/                         # 活跃实施计划
│       ├── 20260513-fix-plan-prompts.md
│       └── ...
│
├── native-ui/                         ← 原生项目代码
│   ├── AGENTS.md                      # 项目规则与约定
│   ├── .gitignore
│   └── app/...
│
├── CHANGELOG.md                       # 全局版本变更记录
├── README.md                          # 项目门面
├── LICENSE
└── .gitignore
```

### 3.2 清理清单

#### 删除 — 根 `.agent/docs/`（全部 57 文件，含子目录）

| 子目录 | 文件数 | 原因 |
|--------|--------|------|
| `docs/` 顶层 | 15 | 全部 RN 时代、指针存根、已被取代的 PRD |
| `docs/audits/` | 7 | 历史审计，git 历史可回溯 |
| `docs/plans/` | 27 | 原生迁移过渡计划（已完成），git 历史可回溯 |
| `docs/todos/` | 若干 | 已过时 |
| `docs/architecture/` | 若干 | RN 时代架构，已过时 |

#### 删除 — 根 `.agent/memory/`（全部 4 文件）

| 文件 | 原因 |
|------|------|
| `PROJECT_MEMORY.md` (64 KB) | RN 时代 v1.2.x 记忆 |
| `CHANGELOG.md` | RN 时代 changelog，根 `CHANGELOG.md` 已涵盖 |
| `SESSION_HANDOVER.md` | 旧的 RN→原生迁移交接，已过时 |
| `TESTING_GUIDE.md` | RN 时代 tsx 测试指南 |

#### 删除 — 根 `.agent/` 控制文件

| 文件 | 原因 |
|------|------|
| `README.md` | RN 时代知识库索引，所有链接已失效 |
| `PROJECT_RULES.md` (5 KB) | 全部规则基于 RN/Expo/Worktree，已过时 |
| `todo.md` | 任务过时且混合 RN/原生 |

#### 删除 — 第三方工具目录

| 目录 | 原因 |
|------|------|
| `.qoder/` | repowiki 145+ 文件，全部 RN 时代 |
| `.roo/skills/` | 旧维护技能，不再需要 |

#### 合并 — `native-ui/.agent/plans/` → 根 `.agent/plans/`

| 操作 | 说明 |
|------|------|
| 移动活跃计划文件 | 10+ 计划文件移入根 `.agent/plans/` |
| 删除 `native-ui/.agent/` | `handover.md`/`registry.md` 与根重复 |

#### 保留 — 根 `.agent/`

| 文件 | 操作 |
|------|------|
| `handover.md` | 更新 |
| `registry.md` | 更新 |

### 3.3 关于 Worktree / 发行分支

RN 时代需要独立 `worktrees/release` 分支的原因：
- `expo prebuild` 会修改 `android/` 和 `ios/` 目录
- Release 签名需要替换 `android/app/build.gradle`
- Debug/Release 环境互斥，同目录构建会污染

**原生 Kotlin 时代不再需要**：Android Studio + Gradle 原生支持 `debug` / `release` Build Variant，一键切换，无环境污染。唯一的独立需求是 `secure_env/` 签名密钥隔离——已在 `.gitignore` 中处理。

结论：**废弃 Worktree 模式**。删除 `PROJECT_RULES.md` 中相关规则，CHANGELOG 记录此变更。

---

## 4. 新 DIA 流程对齐

### 4.1 文档注册表（`registry.md`）精简为

```markdown
# 文档注册表

## 核心文档（始终同步，不可跳过）
- CHANGELOG.md — 版本变更记录
- README.md — 项目概览
- .agent/handover.md — 跨会话交接

## 按需文档
- docs/PRD.md — 产品需求文档 v2.0
- docs/ARCHITECTURE_DESIGN.md — 全局架构设计
- docs/IMPLEMENTATION_ANALYSIS.md — 实现分析与进度
- docs/ARCHITECTURE.md — 架构快速参考 + ADR 索引
- docs/IMPLEMENTATION_PLAN.md — 分阶段实施计划
- docs/MARKDOWN_RENDERING_AUDIT.md — Markdown 渲染审计
- docs/DOCUMENT_GOVERNANCE.md — 文档治理方案
- docs/ADR/ — 架构决策记录
```

### 4.2 DIA 检查表（与全局规则 §4.3 对齐）

```
□ 数据结构是否变更？       → 更新 IMPLEMENTATION_ANALYSIS.md
□ 接口/API 是否变更？      → 更新 ARCHITECTURE_DESIGN.md
□ UI 组件是否变更？        → 更新 IMPLEMENTATION_ANALYSIS.md
□ 架构/文件结构是否变更？   → 更新 ARCHITECTURE.md
□ 有用户可见的功能变更？    → 更新 CHANGELOG.md
□ 做出了架构权衡决策？     → 新增 ADR 文件
```

---

## 5. 执行顺序

```
Step 1: 删除 .agent/docs/ (57 文件)
Step 2: 删除 .agent/memory/ (4 文件)
Step 3: 删除 .agent/README.md .agent/PROJECT_RULES.md .agent/todo.md
Step 4: 删除 .qoder/ (145+ 文件)
Step 5: 删除 .roo/skills/
Step 6: 迁移 native-ui/.agent/plans/ → .agent/plans/
Step 7: 删除 native-ui/.agent/
Step 8: 更新 .agent/registry.md
Step 9: 更新 .agent/handover.md
Step 10: 更新 CHANGELOG.md
```

---

**文档维护者**: AI Assistant
**最后更新**: 2026-05-13
