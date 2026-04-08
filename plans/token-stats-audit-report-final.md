# 流量消耗统计功能审计报告

**审计日期**: 2026-04-08  
**审计范围**: APP通用设置界面的流量消耗统计功能  
**严重程度**: 🔴 高危 - 导致应用崩溃

---

## 一、执行摘要

本次审计发现流量消耗统计功能存在**导致应用直接崩溃的严重问题**。根本原因是 `token-stats-store.ts` 缺少 `onRehydrateStorage` 回调函数，当 AsyncStorage 中存储了损坏或不完整的数据时，zustand persist 中间件无法正确恢复状态，导致组件渲染时访问 `undefined` 对象而崩溃。

---

## 二、崩溃原因分析（最重要）

### 🔴 问题1：缺少 onRehydrateStorage 保护机制（根本原因）

**文件位置**: [`src/store/token-stats-store.ts:106-110`](src/store/token-stats-store.ts:106)

**问题描述**:
`token-stats-store.ts` 使用 zustand 的 persist 中间件但没有实现 `onRehydrateStorage` 回调。当 AsyncStorage 中存储的数据损坏、格式不兼容或为 `null` 时，store 的状态可能为 `undefined`，导致组件渲染时崩溃。

**对比其他 store 的实现**:
```typescript
// src/store/settings-store.ts:233-240 - 正确实现
onRehydrateStorage: () => (state) => {
  // Fail-safe: Sanitize hydration
  if (state && (!state.accentColor || !/^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(state.accentColor))) {
    console.warn(`[SettingsStore] Repairing corrupted accentColor: ${state?.accentColor}`);
    if (state) state.accentColor = '#6366f1';
  }
  state?.setHasHydrated(true);
},
```

**token-stats-store.ts 当前实现**:
```typescript
// src/store/token-stats-store.ts:106-110 - 缺少保护
{
  name: 'token-stats-storage',
  storage: createJSONStorage(() => AsyncStorage),
  // ❌ 缺少 onRehydrateStorage
}
```

**修复建议**:
```typescript
{
  name: 'token-stats-storage',
  storage: createJSONStorage(() => AsyncStorage),
  onRehydrateStorage: () => (state) => {
    // Fail-safe: 确保所有字段都有效
    if (!state) return;
    
    if (!state.globalTotal || typeof state.globalTotal !== 'object') {
      console.warn('[TokenStatsStore] Repairing corrupted globalTotal');
      state.globalTotal = JSON.parse(JSON.stringify(initialBilling));
    }
    
    if (!state.byModel || typeof state.byModel !== 'object') {
      console.warn('[TokenStatsStore] Repairing corrupted byModel');
      state.byModel = {};
    }
    
    // 验证嵌套字段
    ['chatInput', 'chatOutput', 'ragSystem'].forEach(field => {
      if (!state.globalTotal[field] || typeof state.globalTotal[field].count !== 'number') {
        state.globalTotal[field] = { count: 0, isEstimated: false };
      }
    });
  },
}
```

---

### 🟡 问题2：BillingUsage 对象缺少 costUSD 属性

**文件位置**: [`src/store/chat/post-processor.ts:197-202`](src/store/chat/post-processor.ts:197)

**问题描述**:
创建 `billingUsage` 对象时缺少 `costUSD` 属性，虽然类型定义中该字段是可选的 (`costUSD?: number`)，但 `token-stats-store.ts` 中的 `accumulate` 函数期望该字段存在。

**当前代码**:
```typescript
// src/store/chat/post-processor.ts:197-202
const billingUsage = {
  chatInput: { count: finalUsage.input, isEstimated: !accumulatedUsage },
  chatOutput: { count: finalUsage.output, isEstimated: !accumulatedUsage },
  ragSystem: ragUsage ? { count: ragUsage.ragSystem, isEstimated: ragUsage.isEstimated } : { count: 0, isEstimated: false },
  total: finalUsage.total + (ragUsage?.ragSystem || 0)
  // ❌ 缺少 costUSD: 0
};
```

**修复建议**:
```typescript
const billingUsage = {
  chatInput: { count: finalUsage.input, isEstimated: !accumulatedUsage },
  chatOutput: { count: finalUsage.output, isEstimated: !accumulatedUsage },
  ragSystem: ragUsage ? { count: ragUsage.ragSystem, isEstimated: ragUsage.isEstimated } : { count: 0, isEstimated: false },
  total: finalUsage.total + (ragUsage?.ragSystem || 0),
  costUSD: 0  // ✅ 添加默认值
};
```

---

### 🟡 问题3：flex 值为 0 时的潜在渲染问题

**文件位置**: [`app/settings/token-usage.tsx:141-143`](app/settings/token-usage.tsx:141)

**问题描述**:
当 `usage.chatInput.count`、`usage.chatOutput.count`、`usage.ragSystem.count` 全部为 0 时，三个 View 的 flex 值都为 0，可能导致布局异常。

**当前代码**:
```typescript
<View style={{ flex: usage.chatInput.count, backgroundColor: '#8b5cf6' }} />
<View style={{ flex: usage.chatOutput.count, backgroundColor: '#f59e0b' }} />
<View style={{ flex: usage.ragSystem.count, backgroundColor: '#10b981' }} />
```

**修复建议**:
```typescript
// 计算总量，避免全为 0 的情况
const totalTokens = usage.chatInput.count + usage.chatOutput.count + usage.ragSystem.count;

{totalTokens > 0 ? (
  <>
    <View style={{ flex: usage.chatInput.count || 0, backgroundColor: '#8b5cf6' }} />
    <View style={{ flex: usage.chatOutput.count || 0, backgroundColor: '#f59e0b' }} />
    <View style={{ flex: usage.ragSystem.count || 0, backgroundColor: '#10b981' }} />
  </>
) : (
  <View style={{ flex: 1, backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : '#e5e7eb' }} />
)}
```

---

## 三、其他发现的问题

### 🟢 问题4：按模型统计时按 modelId 而非 provider + model 组合

**问题描述**:
当前实现按 `modelId` 统计，但同一模型 ID 可能被多个供应商使用（如 `gpt-4` 可能来自 OpenAI 官方或 Azure），无法区分供应商。

**建议改进**:
考虑使用 `providerId:modelId` 的组合键来区分不同供应商的同名模型。

---

### 🟢 问题5：缺少数据版本控制

**问题描述**:
persist 配置中没有版本号，当数据结构变更时无法自动迁移旧数据。

**建议改进**:
```typescript
{
  name: 'token-stats-storage',
  version: 1,  // 添加版本号
  storage: createJSONStorage(() => AsyncStorage),
  // ...其他配置
}
```

---

## 四、数据流审计结果

### 数据收集点（正常工作）

| 来源 | 文件位置 | 统计类型 |
|------|----------|----------|
| 聊天消息 | `src/store/chat/post-processor.ts:206-209` | chatInput, chatOutput |
| 文档向量化 | `src/lib/rag/vectorization-queue.ts:476-486` | ragSystem |
| 记忆归档 | `src/lib/rag/memory-manager.ts:851-861` | ragSystem |
| 摘要生成 | `src/features/chat/utils/ContextManager.ts:146-159` | ragSystem |

### 数据存储架构

```
AsyncStorage ('token-stats-storage')
    ├── globalTotal: BillingUsage  // 全局统计
    │   ├── chatInput: TokenMetric
    │   ├── chatOutput: TokenMetric
    │   ├── ragSystem: TokenMetric
    │   ├── total: number
    │   └── costUSD: number
    └── byModel: Record<string, BillingUsage>  // 按模型分类
```

---

## 五、实现完整性评估

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 统计所有 API 调用的 token | ✅ 正常 | 聊天、向量化、摘要均已覆盖 |
| 按供应商分类 | ❌ 未实现 | 当前仅按 modelId 分类 |
| 按模型分类 | ✅ 正常 | byModel 字段支持 |
| 数据持久化 | ⚠️ 有缺陷 | 缺少 rehydration 保护 |
| 数据显示 | ⚠️ 有缺陷 | flex=0 边界情况未处理 |

---

## 六、修复优先级建议

| 优先级 | 问题 | 影响 |
|--------|------|------|
| 🔴 P0 | 缺少 onRehydrateStorage | 导致崩溃 |
| 🟡 P1 | BillingUsage 缺少 costUSD | 潜在运行时错误 |
| 🟡 P2 | flex=0 渲染问题 | UI 异常 |
| 🟢 P3 | 按供应商分类 | 功能完善 |

---

## 七、相关代码文件清单

| 文件 | 作用 |
|------|------|
| [`app/settings/token-usage.tsx`](app/settings/token-usage.tsx) | 流量统计页面组件 |
| [`src/store/token-stats-store.ts`](src/store/token-stats-store.ts) | Token 统计状态管理 |
| [`src/store/chat/post-processor.ts`](src/store/chat/post-processor.ts) | 聊天后处理（统计收集） |
| [`src/types/chat.ts:38-50`](src/types/chat.ts:38) | BillingUsage/TokenMetric 类型定义 |
| [`src/services/workbench/controllers/StatsController.ts`](src/services/workbench/controllers/StatsController.ts) | Workbench API 控制器 |

---

## 八、结论

流量消耗统计功能的设计方向基本正确，能够收集各来源的 token 消耗数据。但存在一个**严重的崩溃问题**，原因是 zustand persist 中间件缺少 `onRehydrateStorage` 保护机制。建议立即修复此问题后再发布应用。

**建议立即修复的代码**:
1. [`src/store/token-stats-store.ts`](src/store/token-stats-store.ts) - 添加 onRehydrateStorage
2. [`src/store/chat/post-processor.ts:197-202`](src/store/chat/post-processor.ts:197) - 添加 costUSD 字段
3. [`app/settings/token-usage.tsx:141-143`](app/settings/token-usage.tsx:141) - 处理 flex=0 边界情况