# 提供商管理系统全量审计报告

> **日期**: 2026-05-16
> **范围**: ProviderManager / ProviderFormScreen / ProviderModelsScreen / ModelSpecs
> **方法**: 代码追踪 + 编译验证 + 数据流分析

---

## 一、数据存储架构

Provider 数据使用 SharedPreferences 分两层：

| Prefs 文件 | 职责 | 键值 |
|-----------|------|------|
| `nexara_provider` | **主提供商**（唯一） | `protocol_id`, `base_url`, `api_key`, `model`, `provider_name` |
| `nexara_settings` | **额外提供商** + 模型 | `extra_providers_count`, `extra_provider_N_*`（N=0,1,2...） |

模型使用 `all_models`（Set\<String\>）+ `model_info_<id>_*` 键组，全局存储在同一 `nexara_settings` 中。

---

## 二、🔴 P0 Bug — 无法添加第二个提供商

### 根因

`NavGraph.kt:351-353`：
```kotlin
onSave = { protocolType, baseUrl, apiKey, model, name ->
    app.updateProvider(protocolType, baseUrl, apiKey, model, name)
}
```
`app.updateProvider()` → `ProviderManager.updateMainProvider()` **始终写入 `nexara_provider`**（主提供商），从不写入额外提供商键组。

### 完整问题链

1. 用户点"添加提供商"→ 进入 `ProviderFormScreen(providerId=null)` ✅
2. 填写表单点保存 → 调用 `app.updateProvider(...)` 
3. `updateMainProvider()` 写入 `nexara_provider` → **覆盖了第一个提供商** ❌
4. 返回列表 → `loadProviders()` 从 `nexara_provider` 读出新配置 → 第一个提供商消失 ❌

### 对编辑场景的影响

- 编辑 `providerId="default"`（主提供商）→ `app.updateProvider()` → ✅ 正确
- 编辑 `providerId="extra_0"` → `app.updateProvider()` → ❌ **错误地覆盖了主提供商**
- 新增 `providerId=null` → `app.updateProvider()` → ❌ **错误地覆盖了主提供商**

---

## 三、🔴 P0 Bug — 第二提供商标题对但模型列表是第一个的

### 根因

`ProviderModelsScreen` 中模型列表来自 `viewModel.providerModels`（全局 StateFlow），**未按 providerId 过滤**：

```kotlin
// ProviderManager.providerModels 返回全部模型，无 provider 维度的过滤
val _providerModels = MutableStateFlow<List<ModelInfo>>(emptyList())
```

`ProviderModelsScreen` 接收 `providerId` 参数仅用于显示标题（`effectiveTitle`），模型列表始终为全量。

### 影响

- 用户添加 "DeepSeek" 提供商 → 进入模型管理 → 看到的仍是 "OpenAI" 的模型列表
- `model_info_<id>_provider` 字段存了 provider 名称但从未被用于过滤

---

## 四、🔴 P0 Bug — 每次进入模型管理页面自动拉取模型

### 根因

`NavGraph.kt:365-367`：
```kotlin
LaunchedEffect(providerId) {
    viewModel.refreshProviderModels()
}
```

`refreshProviderModels()` → `refreshModels()` → `app.llmProvider.listModels()` **发起网络请求**。

### 影响

- 每次进入 `ProviderModelsScreen` 都自动网络拉取
- 若网络不通则挂起超时
- **用户声称此功能"此前已去除"，但代码仍存在**

### 额外触发点

`SettingsViewModel.addProvider()` (line 365-368) 也会调用 `refreshModels()`：
```kotlin
fun addProvider(item: ProviderListItem) {
    pm.addProvider(item)
    refreshModels()  // ← 不必要的自动拉取
}
```

---

## 五、🟡 模型能力数据库审计

### 覆盖率

| Provider | Chat | Reasoning | Vision | Image | Embed | Rerank | 评分 |
|----------|------|-----------|--------|-------|-------|--------|------|
| OpenAI | ✅ GPT-4.1/o4/o3/o1/4o/3.5 | ✅ o全系 | ✅ | ✅ DALL-E | ✅ | - | A |
| Anthropic | ✅ Claude 4/3.7/3.5/3 | - | ✅ | - | - | - | A |
| Google | ✅ Gemini 2.5/2.0/1.5/1.0 | ✅ Flash Thinking | ✅ | - | ✅ | - | A |
| DeepSeek | ✅ V3.1/V3 | ✅ R2/R1 | R2 ✅ | - | - | - | A |
| Qwen (阿里) | ✅ Qwen3/2.5/Max/Plus | ✅ 2.5 Math | ✅ VL | - | ✅ | - | A |
| GLM (智谱) | ✅ GLM-4/Edge | - | ✅ GLM-4V | ✅ CogView | - | - | A |
| Meta | ✅ Llama 4/3.2/3.1 | - | ✅ Vision | - | - | - | A |
| xAI | ✅ Grok 3/2 | ✅ Grok 3 | ✅ Grok 3 | - | - | - | A |
| Mistral | ✅ Large/Codestral | - | ✅ Pixtral | - | - | - | B+ |
| Cohere | ✅ Command R+ | - | - | - | - | ✅ | B+ |
| Kimi | ✅ | ✅ k1.5 | - | - | - | - | B |
| ERNIE | ✅ 4.0/3.5/Turbo | - | - | - | - | - | B |
| Doubao | ✅ Pro/Lite | - | - | - | - | - | B |
| Yi (01.AI) | ✅ Large/Medium | - | ✅ Vision | - | - | - | B |
| MiniMax | ✅ ABAB 6.5/6/5.5 | - | - | - | - | - | B |
| Baichuan | ✅ 4/3/2 Turbo | - | - | - | - | - | B |
| StepFun | ✅ step-* | - | - | - | - | - | C |
| SenseTime | ✅ sensechat | - | - | - | - | - | C |
| Microsoft Phi | ✅ Phi-4/3 | - | ✅ Phi-4 MM | - | - | - | B+ |
| IBM Granite | ✅ Granite 3.2 | ✅ | - | - | - | - | B |
| Gemma | ✅ Gemma 2 | - | - | - | - | - | C |

### 重复条目

| 重复组 | 位置 | 影响 |
|--------|------|------|
| `claude-3-5-sonnet`（模糊） vs `claude-3.5-sonnet`（精确） | 行 214-218 | 两行在同一列表，`findModelSpec` 按序返回首个匹配，模糊模式可能意外命中 |
| `llama-3` (line 597) vs `llama-3` regex (line 1053) | 行 597+1053 | StringPattern vs RegexPattern 重复 |
| `mistral-large` (line 628) vs regex (line 1071) | 行 628+1071 | 同上，且 contextLength 不同（128K vs 128K — 相同值但模式冗余） |
| `command-r-plus` regex (line 763) vs `command-r` regex (line 837) | 行 763+837 | `command-r-plus` 字符串已被第一个命中，第二个永不会匹配 plus 变体 |
| `doubao` StringPattern (line 683) vs RegexPattern (line 968) | 行 683+968 | 第一个命中 context=128K，第二个 context=32K，但 `doubao-pro-32k` 先被 `doubao` StringPattern 命中（128K），错误 |
| `kimi` (line 669+848+850) | 行 669+848+850 | **三行**！一行 StringPattern 无 type，一行 kimi-k1.5 reasoning，一行 regex 回退 |

### 缺失的重要模型（2025-2026）

| 缺失模型 | 厂商 | 重要性 |
|----------|------|--------|
| Gemma 3 系列 | Google | 高（2025 年新发布） |
| Claude 4.5 | Anthropic | 高（如已发布） |
| DeepSeek V3.1 Terminus | DeepSeek | 中（128K 终端版） |
| Qwen2.5-VL 系列 | 阿里 | 高（多模态 VL） |
| Qwen-omni | 阿里 | 中（全模态） |
| Doubao 1.5 Pro/Thinking | 字节 | 高（新一代主力） |
| Mistral 3 Small/Large | Mistral | 高（2025 新发布） |
| Cohere Command A | Cohere | 中（2025 年新模型） |
| GLM-4-Plus/GLM-4-Air | 智谱 | 中 |
| Kimi K2 | Moonshot | 高（2025 强化推理） |

---

## 六、修复方案

### P0-1: 修复多提供商存储

**修改**: `NavGraph.kt` ProviderFormScreen 的 `onSave`

```kotlin
// 当前（错误）:
onSave = { protocolType, baseUrl, apiKey, model, name ->
    app.updateProvider(protocolType, baseUrl, apiKey, model, name)
}

// 修复:
onSave = { protocolType, baseUrl, apiKey, model, name ->
    if (providerId == null) {
        // 新增额外提供商
        viewModel.addProvider(ProviderListItem(
            id = "extra_${System.currentTimeMillis()}",
            name = name ?: protocolType.displayName,
            typeName = protocolType.displayName,
            baseUrl = baseUrl,
            model = model,
            protocolType = protocolType,
            apiKey = apiKey
        ))
    } else {
        // 编辑主提供商
        app.updateProvider(protocolType, baseUrl, apiKey, model, name)
    }
}
```

### P0-2: 模型按 providerId 过滤

**修改**: `ProviderModelsScreen.kt`

```kotlin
val filteredModels = remember(models, providerId) {
    models.filter { it.providerName == provider?.name || providerId == "default" }
}
```

### P0-3: 移除自动拉取

**修改**: `NavGraph.kt:365-367` — 删除 `LaunchedEffect` 块

**修改**: `SettingsViewModel.addProvider()` — 删除 `refreshModels()` 调用

### P1: 清理重复模型条目

合并 `MODEL_SPECS` 中的 6 组重复项。

### P2: 补充缺失模型

按 §五 缺失列表补充 Gemma 3 / Qwen2.5-VL / Doubao 1.5 / Mistral 3 / Kimi K2 等。

---

*文档结束*
