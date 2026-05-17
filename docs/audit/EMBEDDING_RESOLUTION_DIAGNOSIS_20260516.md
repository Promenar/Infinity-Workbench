# 向量化 Embedding 配置解析失败 — 合并诊断报告

> **审计来源**: Opus4.6 审计报告 (implementation_plan.md.resolved) + Agent 全链路审计  
> **审计日期**: 2026-05-16  
> **测试环境**: 纯全新安装，两种场景均失败：  
> (A) 同一提供商提供 LLM + Embedding  
> (B) 两个独立提供商分别提供 LLM 和 Embedding

---

## §0 执行摘要：三座冰山同时撞击

Embedding 配置解析链路有三层降级，但**每一层都因独立的设计缺陷而失效**。这是一个"瑞士奶酪模型"，只有当三层全部断裂时才暴露症状 —— 这正是为什么在全新安装环境下 100% 必现。

```
Tier 1: embedding_base_url (幻影键)    → 永远为空 (RC-1)
Tier 2: preset_embedding_model → 模型→提供商→baseUrl  → 因 ID 漂移/缺失而断链 (RC-3, RC-4)
Tier 3: base_url (nexara_provider)      → 永远为空，因为从未被写入 (RC-2)
                                             ↓
                                    baseUrl = "" → 崩溃
```

---

## §1 数据流全链路（从点索引按钮到报错）

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 步骤 1: 用户导入文档 → RagViewModel.importDocuments()                  │
│         → app.vectorizationQueue.enqueueDocument(uuid, title, content)  │
├─────────────────────────────────────────────────────────────────────────┤
│ 步骤 2: NexaraApplication.vectorizationQueue (getter)                  │
│         懒创建 VectorizationQueue(embeddingClient = embeddingClient)     │
├─────────────────────────────────────────────────────────────────────────┤
│ 步骤 3: NexaraApplication.embeddingClient (getter)                     │
│         懒创建 EmbeddingClient = buildEmbeddingClient()                 │
├─────────────────────────────────────────────────────────────────────────┤
│ 步骤 4: buildEmbeddingClient() 三层解析 (NexaraApplication.kt:272-300)  │
│                                                                         │
│   Tier 1: prefs["embedding_base_url"]                                   │
│     └→ 全代码库无任何 putString("embedding_base_url",...)  ← RC-1     │
│     └→ 结果: baseUrl = ""                                              │
│                                                                         │
│   Tier 2: preset_embedding_model → getProviderConfigByModelId()        │
│     └→ 模型表 → providerId → 提供商配置 → baseUrl                      │
│     └→ 三个断点 (见 §2)                                   ← RC-3, RC-4│
│     └→ 结果: baseUrl = "" (如果任一断点断裂)                            │
│                                                                         │
│   Tier 3: prefs["base_url"] + prefs["api_key"]                         │
│     └→ 读取 nexara_provider SharedPreferences                          │
│     └→ 全新安装下该文件从未被写入                        ← RC-2       │
│     └→ 结果: baseUrl = ""                                              │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ 步骤 5: baseUrl = "" → EmbeddingClient.isConfigured = false            │
│         VectorizationQueue.processDocumentTask() 预检失败               │
│         → throw IllegalStateException("Embedding 服务未配置...")        │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## §2 四个根因详解

### 🔴 RC-1: "幻影"存储键 — Tier 1 永远为空

| 项 | 详情 |
|----|------|
| **Opus 报告** | §1 幻影存储键 |
| **交叉验证** | ✅ **完全确认** |
| **位置** | `NexaraApplication.kt:275` `buildEmbeddingClient()` |
| **代码** | `var baseUrl = prefs.getString("embedding_base_url", "") ?: ""` |
| **致命事实** | 全局搜索 `putString.*embedding_base_url` → **0 结果** |

`embedding_base_url`、`embedding_api_key`、`embedding_model` 是三个只读不写的"幻影键"。没有 UI、没有设置页、没有后台服务向这些键写入任何值。Tier 1 从第 1 行代码开始就是死代码，99.9% 的情况下返回空字符串。

**影响**: 低。因为 Tier 1 本应该是"高级用户手动覆盖"的入口，它的缺失只是让降级链变短了一层。但它在 `providerListener` (NexaraApplication.kt:347) 中仍然被监听，造成了一种"这个配置存在"的**认知错觉**。

---

### 🔴 RC-2: 全新安装下无"主提供商"创建路径 — Tier 3 永远为空

| 项 | 详情 |
|----|------|
| **Opus 报告** | 末提及（此为 Agent 审计过程中从 NavGraph 发现的新致命问题） |
| **交叉验证** | ✅ **来自 NavGraph.kt:352-365 的铁证** |
| **位置** | `navigation/NavGraph.kt:352-365` |

**致命代码**:

```kotlin
// NavGraph.kt:352-365
onSave = { protocolType, baseUrl, apiKey, model, name ->
    if (providerId == null) {
        // 新增额外提供商  ← 注释说了"额外"!
        val id = "extra_${java.util.UUID.randomUUID()}"
        val item = ProviderListItem(id = id, ...)
        viewModel.addProvider(item)           // ← 写入 nexara_settings
    } else if (providerId == "default") {
        app.updateProvider(...)               // ← 写入 nexara_provider
    }
}
```

**三个分支的行为**:

| providerId | 行为 | 写入文件 | Tier 3 可读到? |
|------------|------|----------|----------------|
| `null` (新建) | `addProvider(item)` → 额外提供商 | `nexara_settings` | ❌ 不能 |
| `"default"` | `app.updateProvider()` → 主提供商 | `nexara_provider` | ✅ 能 |
| `"extra_N"` | `updateExtraProvider()` → 额外提供商 | `nexara_settings` | ❌ 不能 |

**关键问题**: 

1. **全新安装时，没有任何路由可以进入 `providerId == "default"` 分支**。用户只能通过 Settings → Provider → "Add Provider" 进入 `provider_form`（没有 providerId 参数），这**永远**落入 `providerId == null` 分支。

2. `app.updateProvider()` 写入 `nexara_provider` 文件（`ProviderManager.kt:72-78`），包含 `base_url` 和 `api_key`。而 `addProvider(item)` 写入 `nexara_settings` 文件（`persistExtraProviders()`），键名完全不同的 `extra_provider_0_base_url`。

3. Tier 3 回退代码：
   ```kotlin
   // NexaraApplication.kt:291-292
   baseUrl = prefs.getString("base_url", "") ?: ""    // ← 从 nexara_provider 读
   apiKey = prefs.getString("api_key", "") ?: ""
   ```
   它只读 `nexara_provider` 文件。而这个文件在全新安装中**从未被写入过**。

**结论**: 在全新安装的 NEXARA 应用中，`nexara_provider` SharedPreferences 文件内 `base_url` 和 `api_key` **从诞生到销毁都是空字符串**。Tier 3 不是"有时失效"——它是"永远失效"。

---

### 🔴 RC-3: 额外提供商 ID 漂移 — Tier 2 精确定位被破坏

| 项 | 详情 |
|----|------|
| **Opus 报告** | §3 extra_ 提供商 ID 漂移 |
| **交叉验证** | ✅ **完全确认** |
| **位置** | `ProviderManager.kt:183-212` `persistExtraProviders()` ↔ `loadProviders()` |

**创建时** (NavGraph.kt:354):
```kotlin
val id = "extra_${java.util.UUID.randomUUID()}"  // 例如: "extra_a1b2c3d4"
viewModel.addProvider(item)  // item.id = "extra_a1b2c3d4"
```

**持久化时** (ProviderManager.kt:183-212):
```kotlin
private fun persistExtraProviders() {
    extras.forEachIndexed { index, item ->
        val prefix = "extra_provider_$index"  // ← 按位置索引存储!
        // 保存 extra_provider_0_name, extra_provider_0_base_url, ...
        // 但 item.id = "extra_a1b2c3d4" → 丢失!
    }
}
```

**加载时** (ProviderManager.kt:160-176):
```kotlin
for (i in 0 until count) {
    items.add(ProviderListItem(
        id = "extra_$i",  // ← 重命名为 "extra_0"
        ...
    ))
}
```

**后果**: 在整个生命周期中，同一个额外提供商至少有三个不同的 ID：

| 阶段 | ID |
|------|-----|
| 创建时 | `extra_a1b2c3d4` (UUID) |
| `refreshProviderModels()` 同步时 | 模型被赋予 `providerId = "extra_a1b2c3d4"` |
| `persistExtraProviders()` 后 | 提供商被重命名为 `extra_0` |
| 应用重启 `loadProviders()` 后 | 提供商确认是 `extra_0`，但模型的 `providerId` 仍是 `extra_a1b2c3d4` |
| `getProviderConfigByModelId()` | 用 `extra_a1b2c3d4` 查配置 → `getProviderConfig()` 只匹配 `extra_` 前缀 → 提取 index → "a1b2c3d4" 不是数字 → **返回 null** |

**仅当用户手动触发 `loadModels()` — 在 `refreshProviderModels()` 中会重新写入模型 — 时，模型的 `providerId` 才会被对齐到 `extra_0`。但这要求用户知道要这样做。**

---

### 🟡 RC-4: 模型 `providerId` 回填依赖主提供商存在

| 项 | 详情 |
|----|------|
| **Opus 报告** | §2 ModelInfo 元数据持久化断层 |
| **交叉验证** | ⚠️ **部分过时**（providerId 已序列化），但回填逻辑有隐蔽缺陷 |
| **位置** | `ProviderManager.kt:262-274` `loadModels()` 回填逻辑 |

**现状**: `persistModels()` 已经包含 `providerId` 序列化 (line 424)，Opus 报告中指出的"遗漏"已被修复。

**但隐蔽缺陷仍然存在**:

```kotlin
// ProviderManager.kt:268-273 — 回填逻辑
if (migratedModel.providerId == null && migratedModel.providerName != "Cloud") {
    val matchedPid = _providers.value.find { it.name == migratedModel.providerName }?.id
    if (matchedPid != null) {
        migratedModel = migratedModel.copy(providerId = matchedPid)
    }
}
```

回填通过 `providerName` 匹配。但 `loadModels()` 调用前必须先执行 `loadProviders()` 以填充 `_providers`。这个顺序在 `init` 块中是保证的：

```kotlin
init {
    loadProviders()
    loadModels()
    loadPresetModels()
}
```

但 `loadProviders()` 中的主提供商加载需要 `apiKey.isNotBlank()`：

```kotlin
if (config != null && config.apiKey.isNotBlank()) {
    items.add(ProviderListItem(id = "default", ...))
}
```

在全新安装中，`getMainProviderConfig()` 返回 `null`（无 `protocol_id`），所以 `_providers` 中永远没有 `"default"`。这意味着：**即使 RC-2 被修复、主提供商被正确创建，回填逻辑也只能在模型原本就有 providerId 时才工作**。对于旧数据升级场景，回填逻辑依赖 providerName 匹配，而这个匹配在全新安装中根本不会触发（因为模型也是全新的，providerId 已经在创建时正确设置）。

**实际影响**: RC-4 在当前场景下影响较小，但它使得格式升级路径脆弱——任何导致 providerId 丢失的数据迁移都可能引入此问题。

---

## §3 两场景推演：为什么 BOTH 都失败

### 场景 A: 同一提供商用作 LLM + Embedding

```
1. 用户: Settings → Provider → "Add Provider"
2. NavGraph: providerId=null → addProvider(item) → EXTRA 提供商 (extra_<UUID>)
3. 用户: 选择该提供商 → "同步模型" → 模型列表出现 "BAAI/bge-m3"
   → 模型 providerId = "extra_<UUID>" (当前内存中的 ID)
4. 用户: Settings → App → 向量模型 → 选 "BAAI/bge-m3"
   → settingsListener 触发 rebuildEmbeddingClient()
5. buildEmbeddingClient():
   Tier 2: getProviderConfigByModelId("BAAI/bge-m3")
     → 模型存在 (providerId = "extra_<UUID>")
     → getProviderConfig("extra_<UUID>")
       → 检查 "extra_<UUID>".startsWith("extra_") → true
       → index = "<UUID>" → 去掉 "extra_" 前缀 → 剩余是 UUID 不是数字!
       → getInt/setString 读取失败 → 返回 null  ← RC-3 生效!
     → Tier 2 失败
   Tier 3: prefs["base_url"] → EMPTY (nexara_provider未被写入) ← RC-2 生效!
   → baseUrl = "" → 崩溃
```

**关键**: 即使模型已同步且有正确的 providerId，providerId 是一个 UUID 格式，而配置存储是按数字索引的。创建后到重启前，内存中的 providerId 与持久化的索引不匹配。

### 场景 B: 两个独立提供商

```
1. 用户: 创建提供商 A (LLM) → 同上, extra_<UUID-A>
2. 用户: 创建提供商 B (Embedding) → extra_<UUID-B>
3. 用户在提供商 B 中同步模型 → "BAAI/bge-m3" 的 providerId = "extra_<UUID-B>"
4. buildEmbeddingClient():
   Tier 2: same UUID/serial mismatch as Scenario A → RC-3 生效
   Tier 3: prefs["base_url"] → EMPTY → RC-2 生效
   → baseUrl = "" → 崩溃
```

**结论**: 两个场景的失败路径几乎相同——Tier 2 因 ID 漂移而断链，Tier 3 因主提供商从未被创建而空白。两者叠加，baseUrl 永远为空。

---

## §4 修复方案 (四步闭环)

### Fix 1 (P0): 修复 NavGraph — 首次配置创建主提供商

**文件**: `navigation/NavGraph.kt:352-365`

```kotlin
onSave = { protocolType, baseUrl, apiKey, model, name ->
    if (providerId == null) {
        // 判断：如果当前没有任何提供商配置，视为首次设置主提供商
        val mainConfig = app.getSavedProviderConfig()
        if (mainConfig == null || mainConfig.apiKey.isBlank()) {
            // 全新安装：第一次配置 → 创建主提供商
            app.updateProvider(protocolType, baseUrl, apiKey, model, name)
        } else {
            // 已有主提供商 → 新增额外提供商
            val id = "extra_${java.util.UUID.randomUUID()}"
            viewModel.addProvider(ProviderListItem(id = id, ...))
        }
    } else if (providerId == "default") {
        app.updateProvider(protocolType, baseUrl, apiKey, model, name)
    } else {
        viewModel.updateExtraProvider(providerId, ProviderListItem(id = providerId, ...))
    }
}
```

**效果**: Tier 3 兜底机制立即恢复工作。`nexara_provider` 首次被写入 `base_url`/`api_key`。

### Fix 2 (P0): 修复 ProviderManager — 对齐额外提供商持久化 ID

**文件**: `data/manager/ProviderManager.kt`

```kotlin
// persistExtraProviders() — 保存真实 ID
private fun persistExtraProviders() {
    val extras = _providers.value.filter { it.id != "default" }
    settingsPrefs.edit()
        .putInt("extra_providers_count", extras.size)
        .putString("extra_providers_ids", extras.joinToString(",") { it.id })
        .apply()
    extras.forEachIndexed { index, item ->
        val prefix = "extra_provider_$index"
        // ... 现有逻辑 ...
        settingsPrefs.edit()
            .putString("${prefix}_id", item.id)  // ← 新增：保存真实 ID
            .apply()
    }
}

// loadProviders() — 读取真实 ID
val extraIds = settingsPrefs.getString("extra_providers_ids", null)
    ?.split(",")?.map { it.trim() }
for (i in 0 until count) {
    val prefix = "extra_provider_$i"
    val realId = settingsPrefs.getString("${prefix}_id", null) ?: "extra_$i"
    items.add(ProviderListItem(id = realId, ...))
}
```

**效果**: 提供商在生命周期内 ID 保持稳定，不再发生漂移。模型引用的 `providerId` 始终有效。

### Fix 3 (P1): 扩展 buildEmbeddingClient Tier 3 — 遍历所有提供商

**文件**: `NexaraApplication.kt:290-295`

```kotlin
// 3. 兜底：遍历所有已配置提供商
if (baseUrl.isBlank()) {
    val pm = ProviderManager.getInstance()
    for (provider in pm.providers.value) {
        val config = pm.getProviderConfig(provider.id)
        if (config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) {
            baseUrl = config.baseUrl
            apiKey = config.apiKey
            resolvedBy = "any-provider-fallback"
            break
        }
    }
}
```

**效果**: 即使主提供商未被正确创建、模型查找失败，只要有任何有效提供商配置，Embedding 就能工作。

### Fix 4 (P1): 增强诊断日志

**文件**: `NexaraApplication.kt:272-300` 和 `ProviderManager.kt:132-138`

在 `buildEmbeddingClient()` 和 `getProviderConfigByModelId()` 的每一步添加详细日志：
- 标记每个 Tier 是否命中
- 记录实际读取的键和值（脱敏 apiKey）
- 标记模型找到/未找到、providerId 值、配置读取结果

---

## §5 优先级与排序

| # | 修复 | 级别 | 解决什么 | 改动量 |
|---|------|------|----------|--------|
| Fix 1 | NavGraph 首次配置创建主提供商 | 🔴 P0 | Tier 3 复活 | ~15 行 |
| Fix 2 | 额外提供商持久化真实 ID | 🔴 P0 | Tier 2 精准定位 | ~25 行 |
| Fix 3 | Tier 3 遍历所有提供商兜底 | 🟡 P1 | 防御性加固 | ~12 行 |
| Fix 4 | 诊断日志增强 | 🟡 P1 | 可观测性 | ~20 行 |

**建议执行顺序**: Fix 1 → Fix 2 → 验证 → Fix 3 → Fix 4

Fix 1 单独即可解决场景 A（同一提供商）的问题。Fix 2 解决场景 B（独立提供商）的问题。两者结合覆盖所有场景。Fix 3 作为防御层防止未来类似问题。Fix 4 确保后续排查不再需要全量审计。

---

## §6 Opus 报告交叉验证清单

| Opus 发现 | 交叉验证结论 | 融合位置 |
|-----------|-------------|----------|
| §1 幻影键 | ✅ 确认 — 0 write, 3 reads | RC-1 |
| §2 ModelInfo 未序列化 providerId | ⚠️ 已修复 — 当前代码包含序列化 | RC-4 (回填逻辑仍有缺陷) |
| §3 额外提供商 ID 漂移 | ✅ 确认 — UUID→index 重命名 | RC-3 |
| 核心设计决策 (ProviderManager 作为单一事实源) | ✅ 同意 — Fix 3 沿此方向 | §4 Fix 3 |
| ~~Verification Plan~~ | 无需验证 — 已知失败 | — |

**Opus 遗漏的关键发现**:
- **NavGraph 中无主提供商创建路径** (RC-2): Opus 报告关注了 ProviderManager 内部的数据断层，但未追溯到导航层的根因——用户实际上**无法**通过正常 UI 流程创建主提供商。
- **Tier 3 只读取 nexara_provider 文件** (RC-2 推论): 即使 RC-1 和 RC-3 全部修复，如果主提供商不存在，Tier 3 仍然失效。

---

*审计完成。遵循 DIA 协议，此报告存档至 `docs/audit/EMBEDDING_RESOLUTION_DIAGNOSIS_20260516.md`。*
