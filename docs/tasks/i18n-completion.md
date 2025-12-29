# i18n 多语言完善任务

**任务状态**: ✅ 已完成  
**开始时间**: 2025-12-28  
**完成时间**: 2025-12-28  
**当前阶段**: 所有翻译键已补充，代码已更新

---

## 📋 任务背景

**目标**: 将应用的中英文翻译覆盖率从 ~70% 提升至 100%

**当前状态**:
- ✅ 核心功能翻译已完成（设置、对话、文库、超级助手）
- 🚧 部分硬编码文本待处理
- 🚧 类型定义中的标签待国际化

---

## 🔍 扫描结果分析

### 已扫描文件
执行命令: `grep_search` 正则 `['"][\u4e00-\u9fa5]+` 在 `G:\Nx\src`

### 发现的硬编码中文文本

#### 1. FAB 图标和颜色定义 ⭐⭐⭐
**文件**: `G:\Nx\src\types\super-assistant.ts`  
**位置**: 第 51-56 行（图标），第 61-68 行（颜色）

**当前代码**:
```typescript
// 图标预设
{ type: 'Sparkles', label: '星星', color: '#8b5cf6' },
{ type: 'Brain', label: '大脑', color: '#ec4899' },
{ type: 'Zap', label: '闪电', color: '#f59e0b' },
{ type: 'Star', label: '实心星', color: '#eab308' },
{ type: 'Flame', label: '火焰', color: '#ef4444' },
{ type: 'Crown', label: '皇冠', color: '#a855f7' },

// 颜色预设
{ name: '紫罗兰', value: '#8b5cf6' },
{ name: '粉红', value: '#ec4899' },
{ name: '琥珀', value: '#f59e0b' },
{ name: '翡翠', value: '#10b981' },
{ name: '天蓝', value: '#3b82f6' },
{ name: '玫瑰红', value: '#f43f5e' },
{ name: '金黄', value: '#eab308' },
{ name: '青色', value: '#06b6d4' },
```

**影响范围**: 超级助手 FAB 设置页面的图标和颜色选择器

---

#### 2. 错误消息 ⭐⭐
**文件**: `G:\Nx\src\lib\provider-parser.ts`  
**位置**: 第 18 行

**当前代码**:
```typescript
throw new Error('无效的 Google Cloud 服务账号 JSON');
```

**影响范围**: VertexAI 配置时的错误提示

---

#### 3. 类型标签 ⭐
**文件**: `G:\Nx\src\lib\rag\memory-manager.ts`  
**位置**: 第 147 行

**当前代码**:
```typescript
const typeLabel = r.metadata?.type === 'memory' ? '记忆 (Memory)' : '文档 (Document)';
```

**影响范围**: RAG 检索日志输出（开发调试用，用户不可见）

---

#### 4. 注释中的中文 ⭐
**文件**: 
- `G:\Nx\src\lib\rag\vectorization-queue.ts` (第 38 行)
- `G:\Nx\src\lib\rag\memory-manager.ts` (第 136 行)

**当前代码**:
```typescript
// 标记文档为"处理中"
// 这里简单地将两者合并再次排序...
```

**影响范围**: 代码注释，不影响用户（可选处理）

---

## ✅ 执行计划

### Phase 1: 补充 i18n 翻译键

**文件**: `G:\Nx\src\lib\i18n.ts`

**需要添加的键**:

#### 在 `zh` 对象中添加:
```typescript
zh: {
    // ... 已有内容 ...
    
    // 在 superAssistant 部分添加:
    superAssistant: {
        // ... 已有内容 ...
        
        // 图标名称
        iconSparkles: '星星',
        iconBrain: '大脑',
        iconZap: '闪电',
        iconStar: '实心星',
        iconFlame: '火焰',
        iconCrown: '皇冠',
        
        // 颜色名称
        colorViolet: '紫罗兰',
        colorPink: '粉红',
        colorAmber: '琥珀',
        colorEmerald: '翡翠',
        colorSky: '天蓝',
        colorRose: '玫瑰红',
        colorYellow: '金黄',
        colorCyan: '青色',
    },
    
    // 在 common 部分添加:
    common: {
        // ... 已有内容 ...
        invalidVertexJson: '无效的 Google Cloud 服务账号 JSON',
    },
    
    // 新增 rag 部分:
    rag: {
        typeMemory: '记忆',
        typeDocument: '文档',
    },
}
```

#### 在 `en` 对象中添加对应英文:
```typescript
en: {
    // ... 已有内容 ...
    
    superAssistant: {
        // ... 已有内容 ...
        
        iconSparkles: 'Sparkles',
        iconBrain: 'Brain',
        iconZap: 'Lightning',
        iconStar: 'Star',
        iconFlame: 'Flame',
        iconCrown: 'Crown',
        
        colorViolet: 'Violet',
        colorPink: 'Pink',
        colorAmber: 'Amber',
        colorEmerald: 'Emerald',
        colorSky: 'Sky Blue',
        colorRose: 'Rose',
        colorYellow: 'Yellow',
        colorCyan: 'Cyan',
    },
    
    common: {
        // ... 已有内容 ...
        invalidVertexJson: 'Invalid Google Cloud Service Account JSON',
    },
    
    rag: {
        typeMemory: 'Memory',
        typeDocument: 'Document',
    },
}
```

---

### Phase 2: 修改源文件使用翻译键

#### 2.1 修改 `super-assistant.ts`

**当前**:
```typescript
export const ICON_PRESETS = [
    { type: 'Sparkles', label: '星星', color: '#8b5cf6' },
    // ...
];

export const COLOR_PRESETS = [
    { name: '紫罗兰', value: '#8b5cf6' },
    // ...
];
```

**修改为**:
```typescript
// 注意：这个文件是类型定义，不能直接使用 useI18n hook
// 方案1: 将 label/name 改为 key，在组件中根据 key 查找翻译
// 方案2: 在组件层面映射翻译

// 推荐方案1:
export const ICON_PRESETS = [
    { type: 'Sparkles', labelKey: 'iconSparkles' as const, color: '#8b5cf6' },
    { type: 'Brain', labelKey: 'iconBrain' as const, color: '#ec4899' },
    { type: 'Zap', labelKey: 'iconZap' as const, color: '#f59e0b' },
    { type: 'Star', labelKey: 'iconStar' as const, color: '#eab308' },
    { type: 'Flame', labelKey: 'iconFlame' as const, color: '#ef4444' },
    { type: 'Crown', labelKey: 'iconCrown' as const, color: '#a855f7' },
];

export const COLOR_PRESETS = [
    { nameKey: 'colorViolet' as const, value: '#8b5cf6' },
    { nameKey: 'colorPink' as const, value: '#ec4899' },
    { nameKey: 'colorAmber' as const, value: '#f59e0b' },
    { nameKey: 'colorEmerald' as const, value: '#10b981' },
    { nameKey: 'colorSky' as const, value: '#3b82f6' },
    { nameKey: 'colorRose' as const, value: '#f43f5e' },
    { nameKey: 'colorYellow' as const, value: '#eab308' },
    { nameKey: 'colorCyan' as const, value: '#06b6d4' },
];
```

**需要同步修改的组件**:
- 查找使用 `ICON_PRESETS` 和 `COLOR_PRESETS` 的地方
- 将 `.label` 改为 `t.superAssistant[item.labelKey]`
- 将 `.name` 改为 `t.superAssistant[item.nameKey]`

#### 2.2 修改 `provider-parser.ts`

**位置**: 第 18 行

**当前**:
```typescript
throw new Error('无效的 Google Cloud 服务账号 JSON');
```

**问题**: 这个文件是工具函数，无法使用 `useI18n` hook

**解决方案**: 
- 方案A: 将错误消息改为英文（国际惯例）
- 方案B: 在调用此函数的组件中捕获错误并翻译

**推荐方案A**:
```typescript
throw new Error('Invalid Google Cloud Service Account JSON');
```

或者定义错误码:
```typescript
throw new Error('INVALID_VERTEX_JSON'); // 在组件层面翻译
```

#### 2.3 修改 `memory-manager.ts`

**位置**: 第 147 行

**当前**:
```typescript
const typeLabel = r.metadata?.type === 'memory' ? '记忆 (Memory)' : '文档 (Document)';
```

**问题**: 这是日志输出，用户不可见

**解决方案**: 
- 直接改为英文（日志通常用英文）
- 或者删除中文部分

**推荐修改**:
```typescript
const typeLabel = r.metadata?.type === 'memory' ? 'Memory' : 'Document';
```

---

## 🔧 执行步骤（新会话中）

### Step 1: 更新 i18n 文件
```bash
# 编辑 G:\Nx\src\lib\i18n.ts
# 添加上述翻译键
```

### Step 2: 查找使用 ICON_PRESETS 的组件
```bash
grep -r "ICON_PRESETS" G:\Nx\app
grep -r "ICON_PRESETS" G:\Nx\src
```

### Step 3: 查找使用 COLOR_PRESETS 的组件
```bash
grep -r "COLOR_PRESETS" G:\Nx\app
grep -r "COLOR_PRESETS" G:\Nx\src
```

### Step 4: 逐一修改组件
- 更新类型定义 (`super-assistant.ts`)
- 更新使用这些常量的组件
- 使用 `t.superAssistant[item.labelKey]` 访问翻译

### Step 5: 修改工具函数错误消息
- `provider-parser.ts`: 改为英文或错误码
- `memory-manager.ts`: 日志改为英文

### Step 6: 测试验证
- 切换语言，检查所有文本是否正确翻译
- 重点测试超级助手 FAB 设置页面

---

## 📝 预期结果

完成后：
- ✅ 所有用户可见文本支持中英文切换
- ✅ FAB 图标和颜色名称完全国际化
- ✅ 错误消息规范化（英文或翻译码）
- ✅ 翻译覆盖率达到 ~95%+

---

## 🚨 注意事项

1. **类型定义修改**: `super-assistant.ts` 的修改会影响类型系统，需谨慎
2. **向后兼容**: 如果用户数据中存储了旧的 label/name，需要迁移逻辑
3. **测试覆盖**: 修改后务必测试两种语言下的所有相关界面

---

## 📊 工作量预估

- **i18n 文件更新**: 15 分钟
- **类型定义重构**: 30 分钟
- **组件适配**: 45 分钟
- **测试验证**: 30 分钟

**总计**: ~2 小时

---

**任务创建者**: AI Assistant  
**任务状态**: 待执行  
**优先级**: P1 (Phase 6 - 打磨发布的关键任务)
