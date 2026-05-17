# 服务商管理与模型管理 — 全量架构审计

> **审计日期**: 2026-05-16  
> **审计范围**: ProviderFormScreen → ProviderModelsScreen → ProviderManager → ModelPicker 全链路  
> **审计人**: Agent DIA  
> **严重级别**: P0=立即修复, P1=本迭代, P2=下迭代

---

## §1 架构全景

```
┌─────────────────────────────────────────────────────────────────────┐
│                         NavGraph.kt                                 │
│  provider_form?providerId={id}   provider_models/{providerId}       │
└──────────┬───────────────────────────┬──────────────────────────────┘
           │                           │
           ▼                           ▼
┌──────────────────────┐    ┌──────────────────────────────┐
│  ProviderFormScreen  │    │   ProviderModelsScreen        │
│  - 添加/编辑提供商    │    │   - 模型列表 + CRUD           │
│  - 预设选择器         │    │   - EnhancedModelCard (内联编辑)│
│  - Name/URL/Key 表单  │    │   - 同步/添加/禁用/删除操作    │
│  - Test + Save 按钮   │    │   - AddCustomModel BottomSheet │
└──────────┬───────────┘    └──────────────┬───────────────┘
           │                               │
           │     ┌─────────────────────────┘
           │     │
           ▼     ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     SettingsViewModel                                │
│  refreshProviderModels()  addCustomModel()  toggleModel()           │
│  addProvider()  updateExtraProvider()  deleteProvider()             │
│  providerModels: StateFlow<List<ModelInfo>>                         │
│  providers: StateFlow<List<ProviderListItem>>                       │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     ProviderManager (Singleton)                      │
│  SharedPreferences: "nexara_provider" / "nexara_settings"           │
│  _providers: MutableStateFlow<List<ProviderListItem>>               │
│  _providerModels: MutableStateFlow<List<ModelInfo>>                 │
│  ─ CRUD ─────────────────────────────────────────────────────────── │
│  loadModels()  updateModel()  addModel()  deleteModel()  toggleModel│
│  loadProviders()  addProvider()  updateExtraProvider()              │
│  buildModelCapabilities()  migrateModelIfNeeded()                   │
│  persistModels()  persistExtraProviders()                           │
└──────────────────────────────────────────────────────────────────────┘

调用方: UserSettingsHomeScreen (ModelPicker → setPresetModel)
       ChatViewModel (getMainProviderConfig)
       TokenUsageViewModel (provider models)
       RagAdvancedScreen / AgentEditScreen (capability → ModelCapability)
```

---

## §2 问题诊断

### 🔴 P0 — Issue 1: 同步模型按钮失效

**症状**: 点击 ProviderModelsScreen 中的"同步模型"按钮后，没有任何模型被添加或更新到列表中。

**根因分析**:

#### 根因 A: 非 OpenAI 协议 `listModels()` 返回空列表

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\remote\protocol\LlmProtocol.kt:224`
```kotlin
suspend fun listModels(): List<String> = emptyList()  // 默认实现 — 返回空
```

只有 `OpenAIProtocol` 和 `GenericOpenAICompatProtocol` 覆写了该方法。`AnthropicProtocol`、`VertexAIProtocol`、`LocalProtocol` 等都使用默认的空实现。

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\settings\SettingsViewModel.kt:244-262`
```kotlin
val fetchedIds = tmpProvider.listModels()
if (fetchedIds.isNotEmpty()) {
    // ... 只在此分支有作用
}
// 否则静默跳过 — 无任何用户反馈！
```

**影响**: 对于 Anthropic、Gemini、Cohere、Mistral、Local 等提供商，同步按钮除了转动转圈外无任何作用，且完全没有错误提示。

#### 根因 B: `pm.loadModels()` 被过早调用，重置了列表

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\settings\SettingsViewModel.kt:225`
```kotlin
fun refreshProviderModels(providerId: String) {
    pm.loadModels()  // 先重置列表 → 如果后续网络失败，用户看到的是 SP 旧数据
    
    viewModelScope.launch {
        _isFetchingModels.value = true
        // ...
    }
}
```

正确的流程应该是：先获取远程模型 → 合并/更新 → 再持久化。当前这样做意味着：即使 `listModels()` 失败（网络错误），`loadModels()` 也已经执行过了，本地模型列表状态被重置了一次。

#### 根因 C: 仅新增、不更新已存在模型

```kotlin
val newModels = fetchedIds.filter { it !in existingIds }.map { ... }
newModels.forEach { pm.addModel(it) }
```

如果提供商侧模型元数据（名称、能力、上下文长度）发生变化，已存在于本地的模型不会得到更新。例如：OpenAI 将 `gpt-4o` 的上下文从 128K 扩展到 200K，本地模型元数据将永久停滞。

**修复方案**:

1. **为所有协议实现 `listModels()`**（或至少 Anthropic / VertexAI / Cohere 等主流协议）。Anthropic 无官方 `/models` 端点，可改为读取 `ModelSpecs` 数据库中匹配该提供商名称的所有模型 ID 作为"可用列表"。
2. **移除去同步前的 `pm.loadModels()` 调用**，改为在网络请求成功后更新。
3. **对已存在的模型执行元数据合并更新**，而非仅新增缺失的。
4. **添加 User Feedback**：空结果或异常时以 Toast/Snackbar 告知用户。

---

### 🔴 P0 — Issue 2: 模型列表排序不稳定（改名后顺序变化）

**症状**: 在 EnhancedModelCard 中修改模型名称后，LazyColumn 中的模型展示顺序发生不可预期的变化。

**根因分析**:

核心问题在于 **`SharedPreferences.getStringSet()` 不保序**。

#### 存储层排序丢失

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\manager\ProviderManager.kt:371-377`
```kotlin
private fun persistModels() {
    val models = _providerModels.value
    val allIds = models.map { it.id }.toSet()    // LinkedHashSet → 保序
    val enabled = models.filter { it.enabled }.map { it.id }.toSet()  // LinkedHashSet → 保序
    settingsPrefs.edit()
        .putStringSet("all_models", allIds)      // SP 内部存储为 HashSet → 失序！
        .putStringSet("enabled_models", enabled)
        .apply()
```

Android `SharedPreferences.putStringSet/getStringSet` 内部使用 `HashSet`，不保证迭代顺序。虽然 Kotlin 的 `toSet()` 默认产生 `LinkedHashSet`（保序），但写入 SP 后顺序丢失。

#### 加载时重新排序即不稳定

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\manager\ProviderManager.kt:202-241`
```kotlin
fun loadModels() {
    val allIds = settingsPrefs.getStringSet("all_models", null)  // HashSet → 乱序
    // ...
    val models = allIds.map { id ->
        // ... 从 SP 逐字段读取
        val migratedModel = migrateModelIfNeeded(model)
        migratedModel
    }.sortedByDescending { it.enabled }  // 仅按启用状态排序，组内完全随机
    _providerModels.value = models
}
```

`loadModels()` 在以下场景被调用：
- App 启动（`init`）
- `refreshAll()` → `refreshProviders()`（切换到 Provider Tab）
- `refreshProviderModels()`（点击同步按钮）

每次调用后，同组内（全部启用或全部禁用）的模型顺序取决于 `HashSet` 的迭代序，**这个顺序在同一次 App 运行中可能一致，但跨 App 重启后必然变化**。

#### 内存中的 `updateModel` 确实保序，但存在竞态路径

```kotlin
fun updateModel(updated: ModelInfo) {
    _providerModels.update { models ->
        models.map { if (it.id == updated.id) updated else it }  // ✓ 保序
    }
    persistModels()  // 写入 SP → all_models 失序
}
```

仅在当前会话中，`updateModel` 的 `map` 操作确实保持了顺序。但当 `persistModels()` 将 `allIds` 的 `Set` 视图写入 SP 后，下次任意触发 `loadModels()` 的场景都会重新洗牌。

#### 修复方案

**方案 A（推荐，治本）**: 将模型列表顺序以确定性的方式持久化。

在 `persistModels()` 中新增一个按序存储的 ID 列表：
```kotlin
// 新增：按序存储模型 ID
settingsPrefs.edit()
    .putString("all_models_order", models.joinToString(",") { it.id })
    .apply()
```

在 `loadModels()` 中优先使用此有序列表：
```kotlin
val orderedIds = settingsPrefs.getString("all_models_order", null)
    ?.split(",")?.filter { it.isNotBlank() }
val allIds = if (orderedIds != null) orderedIds.toSet()
    else settingsPrefs.getStringSet("all_models", emptySet()) ?: emptySet()
```

**方案 B（次选）**: 增加排序维度 — 至少按 `name` 或 `id` 字母序作为第二排序键：
```kotlin
.sortedWith(compareByDescending<ModelInfo> { it.enabled }.thenBy { it.name.lowercase() })
```

这会提供可预测的排序，但不如方案 A 灵活（用户可能期望手动排序）。

---

### 🔴 P0 — Issue 3: 能力标签前端/数据库维度不一致

**症状**: ModelPicker 中模型的"Internet/Web"能力标签丢失，其他场景也存在偶发性缺失。

**根因分析**:

三条能力标签管线使用了不同的命名约定：

#### 管线 1 — 数据存储层 (ProviderManager.buildModelCapabilities)

```kotlin
// k:\Nexara\...\ProviderManager.kt:304-332
fun buildModelCapabilities(type: String, spec: ModelSpec?) = buildList {
    when (type) {
        "chat" -> add("chat")
        "reasoning" -> { add("chat"); add("reasoning") }
        // ...
    }
    spec?.capabilities?.let { caps ->
        if (caps.internet && "internet" !in this) add("internet")   // ← "internet"
        // ...
    }
}
```

输出: `["chat", "vision", "internet", ...]` — **全小写字符串**

#### 管线 2 — ModelPicker 转换层 (UserSettingsHomeScreen + ModelPicker)

```kotlin
// k:\Nexara\...\ui\hub\UserSettingsHomeScreen.kt:262-268
capabilities = model.capabilities.mapNotNull { cap ->
    try {
        ModelCapability.valueOf(cap.uppercase())  // "INTERNET" → ❌ 找不到！
    } catch (_: Exception) {
        null  // 静默丢弃
    }
},
```

```kotlin
// k:\Nexara\...\ui\common\ModelPicker.kt:52-55
enum class ModelCapability {
    REASONING, VISION, WEB, RERANK, EMBEDDING, CHAT, IMAGE,
    //               ↑  枚举值是 "WEB"，不是 "INTERNET"
    AUDIOINPUT, AUDIOOUTPUT, VIDEOUNDERSTANDING, STRUCTUREDOUTPUT, PROMPTCACHING, COMPUTERUSE
}
```

**关键不匹配**: `buildModelCapabilities` 生成 `"internet"` → `cap.uppercase()` = `"INTERNET"` → `ModelCapability.valueOf("INTERNET")` → **抛出 `IllegalArgumentException`** → catch 返回 `null` → 能力标签被静默丢弃。

#### 同样的问题出现在

| 文件 | 位置 | 影响 |
|------|------|------|
| `UserSettingsHomeScreen.kt` | 262-268 | 主设置页 ModelPicker 能力丢失 |
| `RagAdvancedScreen.kt` | 392-394 | RAG 高级设置 ModelPicker 能力丢失 |
| `AgentEditScreen.kt` | 131-133 | Agent 编辑 ModelPicker 能力丢失 |

#### 管线 3 — ProviderModelsScreen 直接渲染 (不受影响)

```kotlin
// k:\Nexara\...\ProviderModelsScreen.kt:69-85
private val CapabilityTags = listOf(
    CapabilityTag("internet", "Internet", "public", ...),  // ← 直接匹配 "internet"
    // ...
)
```

`ProviderModelsScreen` 的 `EnhancedModelCard` 使用 `activeCaps`（来自 `model.capabilities` 的 `Set`），直接与 `CapabilityTags` 的 `key` 比对，不经过 `ModelCapability` 枚举转换，所以 **此页面显示正确**。但不一致根因仍然存在。

#### 修复方案

**统一的解决方案**: 统一三条管线的能力标签命名：

**选项 A（推荐）**: 修改 `ModelCapability` 枚举，将 `WEB` 重命名为 `INTERNET` 或增加别名：

```kotlin
enum class ModelCapability {
    REASONING, VISION, INTERNET, RERANK, EMBEDDING, CHAT, IMAGE,
    //                   ↑ 改为 INTERNET
    AUDIOINPUT, AUDIOOUTPUT, VIDEOUNDERSTANDING, STRUCTUREDOUTPUT, PROMPTCACHING, COMPUTERUSE
}
```

同时更新 `ModelPicker.capabilityColors` 中 `WEB` → `INTERNET` 的键名。

**选项 B**: 在转换层添加映射兼容：

```kotlin
private val capabilityStringToEnum = mapOf(
    "internet" to ModelCapability.WEB,  // 手动映射
    "chat" to ModelCapability.CHAT,
    // ...
)

capabilities = model.capabilities.mapNotNull { cap ->
    capabilityStringToEnum[cap.lowercase()]
}
```

**推荐选项 A**，因为它是从源头统一，消除所有下游映射问题。

---

### 🟡 P1 — Issue 4: 添加/编辑提供商时键盘避让高度不足

**症状**: 在 ProviderFormScreen 中编辑第三个输入框（API Key）时，键盘弹出后仅刚好避让了当前输入行，Name 和 Base URL 两个输入框被键盘遮挡在屏幕上方。

**根因分析**:

#### 布局结构

`ProviderFormScreen` 使用 `NexaraPageLayout`（`scrollable=true, imePadding=true`）：

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\common\NexaraPageLayout.kt:92-109`
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .then(if (imePadding) Modifier.imePadding() else Modifier)  // ← IME padding 在这里
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        content()  // ← ProviderFormScreen 的表单内容
    }
}
```

#### 问题推演

当用户点击 API Key 字段时：
1. 键盘弹起，高度约 **屏幕 40-50%**
2. `Modifier.imePadding()` 使外层 Column 底部增加 `imeHeight` 的内边距
3. 内容区域 `weight(1f)` 填满剩余空间
4. `verticalScroll` 允许滚动内容区域

**但实际上**: 表单的 Name (1)+ Base URL (2)+ API Key (3) 三个带标签的输入框总高度约为 **~220dp**，加上预设选择器 (~360dp) 和描述文本 (~40dp)，表单总高度远超键盘后的可见区域。

用户看到的效果：
```
┌──────────────────────┐ ← TopAppBar
│  [描述文本]           │ ← 可见或部分可见
│  [预设选择器]         │ ← 部分可见
│  [Name 标签]         │ ← 可能在屏幕上方被裁切
│  [Name 输入框]       │ ← 不可见
│  [Base URL 标签]     │ ← 不可见
│  [Base URL 输入框]   │ ← 不可见
│  [API Key 标签]      │ ← 可见
│  [API Key 输入框]    │ ← 可见 ✓ (当前焦点)
│  [测试][保存] 按钮   │ ← 可见
│══════════════════════│ ← 键盘顶部
│  [   键盘区域     ]  │
└──────────────────────┘
```

#### 为什么"仅正好避让当前输入行"

这是 `imePadding()` 的标准行为：它只是**为整个窗口添加键盘高度级别的内边距**，而不是"将焦点输入框滚动到视口中心"。Android 系统自身会根据 `windowSoftInputMode` 尝试调整，但在 Compose 的 `Scaffold` + `imePadding` 组合下，这个自动行为被覆盖了。

`NexaraPageLayout` 设置的 `contentWindowInsets = WindowInsets.systemBars` 中未包含 IME insets 的消费策略，`imePadding` 仅做了被动避让。

#### 修复方案

**方案 A（最小改动）**: 在 ProviderFormScreen 中使用 `LocalFocusManager` + `BringIntoViewRequester`，当任意输入框获取焦点时将 Name 输入框带入视野：

```kotlin
val focusManager = LocalFocusManager.current
val bringIntoView = remember { BringIntoViewRequester() }

// 在 Name 输入框上
Modifier.bringIntoViewRequester(bringIntoView)

// 在 Base URL / API Key 获取焦点时
LaunchedEffect(/* focus state */) {
    bringIntoView.bringIntoView()
}
```

**方案 B（推荐，体验最佳）**: 将三行配置表单（Name + Base URL + API Key）从可滚动区域中独立出来，在键盘弹起时作为固定底部面板展现：

在 `NexaraPageLayout` 增加 `bottomStickyContent` 插槽，或在 ProviderFormScreen 中自己管理键盘状态，当键盘弹起时应用 `Modifier.animateContentSize()` + 紧凑布局确保三行同时可见。

**方案 C（快速修补）**: 在 ProviderFormScreen 的配置表单区域增加一个足够大的 `Spacer(modifier = Modifier.height(120.dp))` 作为内容底部填充，确保即使键盘弹起后，用户仍可向上滚动查看前两行输入框。

**方案 D**: 将 ProviderFormScreen 切换为非滚动的 `imePadding=true`，但将预设选择器区域设为 `weight(1f)` 可滚动，配置表单区域 `weight(0f)` 紧贴底部。这样键盘弹起时配置表单自然位于键盘正上方。

---

## §3 其他发现的设计问题

### 3.1 `persistExtraProviders()` 索引不稳定

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\manager\ProviderManager.kt:183-198`
```kotlin
private fun persistExtraProviders() {
    val extras = _providers.value.filter { it.id != "default" }
    // ...
    extras.forEachIndexed { index, item ->
        val prefix = "extra_provider_$index"  // 按位置索引存储
```

当删除中间位置的额外提供商后，`persistExtraProviders()` 会用新的索引重写所有键。这是正确的（因为 `forEachIndexed` 是连续索引），但如果 `addProvider` 和 `persistExtraProviders` 之间有并发调用，可能导致存储不一致。

### 3.2 `migrateModelIfNeeded` 的 `changed` 检测逻辑缺陷

文件: `k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\manager\ProviderManager.kt:259`

```kotlin
val newName = if (model.name == model.id && spec.note?.isNotEmpty() == true) {
    changed = true
    spec.note!!
} else model.name
```

当 `model.name` 已经是用户自定义名称（不同于 `model.id` 且不同于 `spec.note`）时，它不会被覆盖。这是正确的。但问题是：如果用户**手动**将模型名改成了与 `spec.note` 不同的值，但后又有人更新了数据库中的 `spec.note`，用户的自定义名将被保留。这个行为本身是正确的。但当用户删除了自定义名（清空），`model.name == model.id` 为 true，迁移会把名字改为 `spec.note` — 此时行为也正确。

**无明显 bug**，但建议添加注释说明迁移策略："仅当 name == id 时认为名称未自定义，自动替换为数据库中的友好名称"。

### 3.3 `EnhancedModelCard` 中 `LaunchedEffect` 无防抖

```kotlin
LaunchedEffect(selectedType, editName, editContext, activeCaps) {
    val updated = model.copy(...)
    if (updated != model) {
        onUpdate(updated)
    }
}
```

每次键盘输入都会触发 `onUpdate` → `pm.updateModel` → `persistModels()`。对于快速打字，这意味着每个字符都会：
1. 创建新的 `ModelInfo` 对象
2. 更新 StateFlow（触发所有观察者）
3. 写入 SharedPreferences（I/O 操作）

**建议**: 为编辑操作添加 300ms 防抖，减少不必要的 SP 写入。

### 3.4 `CapabilityTags` 中 `"chat"` 能力不可见

在 `ProviderModelsScreen.kt:569-571`:
```kotlin
CapabilityTags.filter { 
    it.key !in listOf("reasoning", "image", "embedding", "rerank") 
}.forEach { cap -> ... }
```

过滤掉了 reasoning/image/embedding/rerank（它们由类型选择器管理），但 `"chat"` 干脆不在 `CapabilityTags` 中。这是有意为之（Chat 是所有模型的基础能力），但缺少视觉指示器可能导致困惑。

---

## §4 修复优先级汇总

| # | 问题 | 级别 | 根因 | 修复方向 |
|---|------|------|------|----------|
| 1 | 同步按钮失效 | 🔴 P0 | 非 OpenAI 协议 `listModels()` 返回空 + 无反馈 | 为各协议实现 `listModels()`, 添加 User Feedback |
| 2 | 列表顺序不稳定 | 🔴 P0 | SP `getStringSet()` 不保序 + 仅按 `enabled` 排序 | 持久化有序 ID 列表或增加 name 二级排序 |
| 3 | 能力标签不一致 | 🔴 P0 | `ModelCapability` 枚举用 `WEB` 但存储用 `internet` | 统一 `ModelCapability.WEB` → `INTERNET` |
| 4 | 键盘避让不足 | 🟡 P1 | `imePadding` 只有被动缩进，不保证表单完整可见 | 添加 BringIntoView 或重构为内联面板 |
| 5 | 编辑无防抖 | 🟢 P2 | 每次按键都触发 `persistModels()` | 添加 300ms debounce |
| 6 | 同步不更新已存模型 | 🟡 P1 | 仅 `filter { it !in existingIds }` | 对已存在模型执行元数据合并更新 |

---

## §5 修复实施顺序建议

```
Session 1: 修复 Issue 3 (能力标签) + Issue 2 (列表排序)
           → 都是纯代码修改，无 UI 重构，影响面小

Session 2: 修复 Issue 1 (同步按钮)
           → 为每个协议实现 listModels(), 增加错误反馈 UI

Session 3: 修复 Issue 4 (键盘避让)
           → UI 结构调整，需要视觉验证
```

---

*审计完成。遵循 DIA 协议，此报告已存档至 `docs/audit/`。*
