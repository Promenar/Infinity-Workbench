# Nexara 模型元数据注册中心与会话尾注实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用可追溯、可自动更新、精确匹配的模型元数据注册中心替换手写模糊匹配表，并让 Provider 管理、首次引导、会话输入区与 AI 消息尾注统一显示可信的友好模型名称和能力。

**Architecture:** 构建阶段把固定快照的 models.dev provider-agnostic 数据规范化为仓库内离线 JSON，再由 Kotlin `ModelMetadataResolver` 按“用户覆盖 > 提供商精确元数据 > Nexara 精确修正 > 精确公共目录 > 家族展示信息 > unknown”逐字段合并。模型工作负载、推理能力与 Chat Completions 端点兼容性相互独立；系列规则只允许补充家族名和图标，不能覆盖精确名称、类型、能力或 token 限制。会话页只消费同一个纯函数友好名称解析结果，AI 尾注的“模型名 + 时间”整体左对齐，用户消息时间继续右对齐。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Kotlinx Serialization、SharedPreferences 兼容迁移、Python 3 标准库、JUnit4/JUnit5、Compose UI Test、Compose Preview Screenshot Test、GitHub Actions。

## Global Constraints

- 工作目录固定为 `/Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta`，分支固定为 `codex/md3-redesign`。
- 当前工作树已有 Agent Hub/MD3、发行文档、截图基线和交接文档改动；任何执行者都不得回滚、覆盖或清理这些既有改动。
- APK 运行时不得依赖 models.dev 或任何第三方目录服务；公共目录只在维护脚本中下载，规范化快照提交到仓库并离线读取。
- 初始上游快照使用 `https://models.dev/models.json`，本计划核验时为 258 条、209370 bytes、SHA-256 `d2baab07d79be35c9e0b6aa8fb70f3d13d31ef63735889bde4ea7a15181462db`；执行更新时必须把实际条数、字节数、SHA-256 和抓取时间写入 manifest，禁止静默接受漂移。
- models.dev 采用 MIT License；引入数据快照时必须新增第三方归属说明。数据源只作为广覆盖补充，不作为所有字段的单一权威。
- `remoteModelId` 是提交给远端协议的原始 ID，必须原样保留；`stableModelId` 仍为 `providerId::remoteModelId`，不得改动现有会话、Agent、预设和路由引用语义。
- 未知模型不得默认归类为 `chat`；“最小聊天请求成功”只证明 `chatEndpointCompatible = SUPPORTED`，不能把主要类型改成“对话”。
- 友好名称只能来自用户自定义名称或精确型号记录；没有精确名称时显示去除稳定前缀后的原始调用 ID。家族泛称永远不能覆盖精确调用 ID。
- 用户在模型管理页编辑过的名称、类型、能力、上下文或最大输出不得被目录刷新覆盖；迁移只修正仍与旧自动生成指纹完全一致的字段。
- 模型能力使用 `SUPPORTED / UNSUPPORTED / UNKNOWN` 三态。`reasoning` 与 `chatEndpointCompatible` 是独立能力；推理模型在 UI 中的主要标签必须是“推理”。
- 不更改消息持久化中的 `Message.modelId`；历史消息通过当前注册中心解析显示名，目录不存在时回退到原始远端 ID。
- AI 消息尾注按 `友好模型名  时间` 左对齐；用户消息时间保持气泡右侧。长模型名单行省略，时间不得被挤出、换行或与正文重叠。
- 会话输入区模型 Chip 与 AI 消息尾注必须调用同一解析函数，禁止各自使用 `findModelSpec(id)?.note`。
- 继续遵守已批准的 Material 3 方案 3：弱化元数据、8dp 节奏、无新增玻璃/发光/永久描边、所有交互目标至少 48dp。
- 截图基线只允许在逐张查看实际图后更新；至少覆盖中文、英文、大字体、长模型名、历史未知模型和推理模型。
- 不读取或输出 `.env`、API Key、签名文件、`secure_env/`、浏览器资料或其它敏感文件。

---

## Target Data Contracts

新增的核心类型必须保持以下语义，字段名在全部任务中统一：

领域解析唯一入口冻结为 `com.promenar.nexara.data.model.catalog.ModelMetadataResolver.resolve(...)`。不得新增顶层 `resolveModelMetadata(...)` facade；Task 2 的测试和后续消费端都直接依赖该类接口，避免两个入口产生漂移。签名固定为：

```kotlin
fun resolve(
    remoteModelId: String,
    providerId: String? = null,
    providerMetadata: ModelMetadataOverride? = null,
    userOverride: ModelMetadataOverride? = null,
): ResolvedModelMetadata
```

```kotlin
package com.promenar.nexara.data.model.catalog

enum class SupportState { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class ModelWorkload {
    GENERATIVE_TEXT,
    EMBEDDING,
    RERANK,
    IMAGE_GENERATION,
    AUDIO,
    VIDEO,
    UNKNOWN,
}

enum class ModelCapability {
    REASONING,
    CHAT_ENDPOINT,
    VISION_INPUT,
    AUDIO_INPUT,
    AUDIO_OUTPUT,
    VIDEO_INPUT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    PROMPT_CACHING,
    COMPUTER_USE,
    WEB_ACCESS,
}

enum class MetadataSource {
    USER,
    PROVIDER,
    NEXARA_OVERRIDE,
    MODELS_DEV,
    FAMILY,
    FALLBACK,
}

data class ResolvedModelMetadata(
    val remoteModelId: String,
    val canonicalModelId: String?,
    val displayName: String,
    val familyName: String?,
    val workload: ModelWorkload,
    val capabilities: Map<ModelCapability, SupportState>,
    val contextTokens: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val knowledgeCutoff: String?,
    val sourceByField: Map<String, MetadataSource>,
    val diagnostics: Set<String> = emptySet(),
)
```

`ModelInfo` 仍作为 Provider 管理与持久化 DTO，但默认值和新增字段必须调整为：

```kotlin
data class ModelInfo(
    val name: String,
    val id: String,
    val description: String,
    val enabled: Boolean,
    val type: String = "unknown",
    val contextLength: Int = 0,
    val capabilities: List<String> = emptyList(),
    val providerName: String = "Cloud",
    val providerId: String? = null,
    val remoteModelId: String = id.substringAfter("::", id),
    val testStatus: String? = null,
    val maxOutputTokens: Int = 0,
    val knowledgeCutoff: String? = null,
    val familyName: String? = null,
    val canonicalModelId: String? = null,
    val chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
    val autoMetadataFingerprint: String? = null,
    val userEditedFields: Set<String> = emptySet(),
)
```

兼容字符串映射固定为：

```text
workload GENERATIVE_TEXT + reasoning SUPPORTED -> type "reasoning"
workload GENERATIVE_TEXT + reasoning 非 SUPPORTED -> type "chat"
workload EMBEDDING -> type "embedding"
workload RERANK -> type "rerank"
workload IMAGE_GENERATION -> type "image"
workload AUDIO -> type "audio"
workload VIDEO -> type "video"
workload UNKNOWN -> type "unknown"
```

---

### Task 1: 建立不阻断后续任务编译的运行期 RED 契约

**Files:**
- Verify unchanged: `native-ui/app/src/test/java/com/promenar/nexara/data/model/ModelSpecsTest.kt`
- Verify unchanged: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelModelClassificationTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/welcome/OnboardingModelProbeTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/PipelineBubbleTest.kt`

**Interfaces:**
- Consumes: 当前 `findModelSpec`、`classifyFetchedModelType`、`verifyOnboardingModelCandidate` 和 `PipelineBubble` 源码契约。
- Produces: 可编译但在运行期因真实旧行为失败的 RED 测试集合；不得直接引用 Task 2、5、7、8 尚未实现的 Kotlin 符号。
- Sequencing: `--tests` 只筛选执行用例，Gradle 仍会先编译整个 `debugUnitTest` 源集。解析器强类型 RED 归 Task 2，`chatEndpointCompatible` 字段断言归 Task 6，友好名称纯函数与双消费端数据流 RED 归 Task 7；Task 1 不用反射、临时 stub、`@Disabled` 或注释绕过编译。

- [x] **Step 1: 运行施工前基线**

在写入新 RED 契约前运行：

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.data.model.ModelSpecsTest' \
  --tests 'com.promenar.nexara.ui.settings.SettingsViewModelModelClassificationTest' \
  --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest'
```

Expected: PASS。`SettingsViewModelModelClassificationTest` 已覆盖 unknown 不默认 chat，不重复新增同义测试。

- [x] **Step 2: 增加首次引导不篡改主要类型的运行期 RED**

在 `OnboardingModelProbeTest.kt` 增加：

```kotlin
@Test
fun `unknown 模型探测成功只记录聊天端点兼容`() = runTest {
    val source = model(type = "unknown", capabilities = emptyList())
    val verified = verifyOnboardingModelCandidate(source) { true }

    assertThat(verified).isNotNull()
    assertThat(verified!!.type).isEqualTo("unknown")
    assertThat(verified.capabilities).doesNotContain("chat")
}
```

- [x] **Step 3: 增加 AI 尾注左对齐静态契约**

在 `PipelineBubbleTest.kt` 抽取单一 `pipelineBubbleSource()` helper 并增加源码契约。必须先显式断言 `AssistantMetadataRow` 签名存在，避免 `substringAfter` 在签名缺失时从整份源码继续截取而偶然通过；参数列表本身必须声明 `modelDisplayName: String` 和 `timestamp: String`。权重只允许 `.weight(1f, fill = false)`，明确拒绝裸 `.weight(1f)` 和 `.weight(1f, fill = true)`：

```kotlin
@Test
fun `AI 元信息模型名与时间作为左对齐同组渲染`() {
    val source = pipelineBubbleSource()
    val signature = "private fun AssistantMetadataRow("
    assertThat(source).contains(signature)
    val metadataSignatureRemainder = source.substringAfter(signature)
    val signatureTerminator = ")"
    assertThat(metadataSignatureRemainder).contains(signatureTerminator)
    val metadataParameters = metadataSignatureRemainder.substringBefore(signatureTerminator)
    assertThat(metadataParameters).contains("modelDisplayName: String")
    assertThat(metadataParameters).contains("timestamp: String")
    val metadata = metadataSignatureRemainder.substringAfter(signatureTerminator)
        .substringBefore("private fun")
    val compactMetadata = metadata.replace(Regex("\\s+"), "")
    assertThat(metadata).contains("horizontalArrangement = Arrangement.spacedBy")
    assertThat(metadata).contains("modelDisplayName")
    assertThat(metadata).contains("timestamp")
    assertThat(compactMetadata).contains(".weight(1f,fill=false)")
    assertThat(compactMetadata).doesNotContainMatch("\\.weight\\(1f\\s*\\)")
    assertThat(compactMetadata)
        .doesNotContainMatch("\\.weight\\(1f\\s*,\\s*fill\\s*=\\s*true\\)")
}
```

- [x] **Step 4: 运行 Task 1 RED 门禁**

Run:

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.ui.welcome.OnboardingModelProbeTest' \
  --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest'
```

Expected: 测试源码编译成功，随后在运行期 FAIL。失败仅来自两个真实旧行为：onboarding 把 unknown 改为 chat/补入 `chat` capability，以及 `AssistantMetadataRow` 尚不存在；不得出现 unresolved reference 或 Gradle 基础设施错误。

- [x] **Step 5: 生成 Task 1 限定 review diff 并完成双复审**

review diff 只包含本 Task 的 `OnboardingModelProbeTest.kt` 与 `PipelineBubbleTest.kt`；计划顺序修订通过当前完整计划和重新生成的 `task-1-brief.md` 作为独立规格输入，不混入测试 diff。独立审阅必须同时读取 brief、实施报告、测试 diff 和当前计划，并分别给出规格符合性与代码质量结论；Critical/Important 全部关闭后，Task 1 才可标记完成并进入 Task 2。当前用户禁止自动 stage/commit，本计划中的提交动作一律不执行。

---

### Task 2: 建立独立的模型元数据领域类型与精确解析器

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/ModelMetadata.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/ModelMetadataResolver.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/data/model/catalog/ModelMetadataResolverTest.kt`

**Interfaces:**
- Consumes: `stableModelId` 的 `providerId::remoteModelId` 约定。
- Produces: `ModelMetadataResolver.resolve(remoteModelId, providerId, providerMetadata, userOverride): ResolvedModelMetadata`。
- Boundary: 不新增顶层 `resolveModelMetadata(...)`，也不在 Task 2 引入依赖 `ModelInfo` 的显示名 facade；Provider DTO 仍位于 `com.promenar.nexara.ui.settings`，统一迁移只在 Task 5 发生。
- Test seam: `ModelMetadataResolver` 公开无参构造；仅允许同包测试通过 `internal constructor(exactRecords: Collection<ModelMetadataRecord>)` 注入冲突记录，禁止扩大公开解析 API。

- [x] **Step 1: 写唯一解析入口、精确名称与家族越权失败测试**

`ModelMetadataResolverTest.kt` 必须覆盖：

```kotlin
@Test
fun `用户名称高于提供商与公共目录`() {
    val resolved = resolver.resolve(
        remoteModelId = "model-x",
        providerId = "default",
        providerMetadata = metadata(displayName = "Provider X"),
        userOverride = override(displayName = "我的模型"),
    )
    assertThat(resolved.displayName).isEqualTo("我的模型")
    assertThat(resolved.sourceByField["displayName"]).isEqualTo(MetadataSource.USER)
}

@Test
fun `家族匹配只补 familyName 不覆盖精确字段`() {
    val resolved = resolver.resolve("vendor/deepseek-new-variant", "default")
    assertThat(resolved.displayName).isEqualTo("vendor/deepseek-new-variant")
    assertThat(resolved.familyName).isEqualTo("DeepSeek")
    assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
    assertThat(resolved.contextTokens).isNull()
}

@Test
fun `精确模型 ID 不得被系列泛称覆盖`() {
    val resolved = resolver.resolve("deepseek-v4-flash")
    assertThat(resolved.displayName).isEqualTo("DeepSeek V4 Flash")
    assertThat(resolved.familyName).isEqualTo("DeepSeek V4")
}

@Test
fun `未知模型保留原始远端 ID 与 unknown 类型`() {
    val resolved = resolver.resolve("vendor-new-reasoning-x")
    assertThat(resolved.displayName).isEqualTo("vendor-new-reasoning-x")
    assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
    assertThat(resolved.capabilities[ModelCapability.REASONING])
        .isEqualTo(SupportState.UNKNOWN)
}

@Test
fun `歧义精确候选保持 unknown 并返回可观察诊断`() {
    val resolver = ModelMetadataResolver(
        exactRecords = listOf(
            exactRecord("vendor/a", aliases = setOf("ambiguous-exact-id")),
            exactRecord("vendor/b", aliases = setOf("ambiguous-exact-id")),
        ),
    )
    val resolved = resolver.resolve("ambiguous-exact-id")
    assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
    assertThat(resolved.diagnostics).contains("ambiguous_exact_match")
}
```

先运行本测试并观察因 `ModelMetadataResolver` 和 catalog 类型尚不存在而产生的 Task 2 专属编译 RED；随后才进入 Step 2 生产实现。Task 1 不再提前引用这些符号。

- [x] **Step 2: 实现领域类型**

按本计划 `Target Data Contracts` 原样建立枚举和 `ResolvedModelMetadata`，并增加：

```kotlin
data class ModelMetadataRecord(
    val canonicalModelId: String,
    val exactAliases: Set<String>,
    val displayName: String,
    val familyName: String?,
    val workload: ModelWorkload,
    val capabilities: Map<ModelCapability, SupportState>,
    val contextTokens: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val knowledgeCutoff: String?,
    val source: MetadataSource,
)

data class ModelMetadataOverride(
    val displayName: String? = null,
    val workload: ModelWorkload? = null,
    val capabilities: Map<ModelCapability, SupportState> = emptyMap(),
    val contextTokens: Int? = null,
    val outputTokens: Int? = null,
)
```

- [x] **Step 3: 实现无损 ID 规范化**

只允许大小写、稳定前缀和 API 固定前缀规范化：

```kotlin
internal fun normalizeRemoteModelId(value: String): String = value
    .trim()
    .substringAfter("::", value.trim())
    .removePrefix("models/")
    .lowercase()
```

禁止删除日期、版本号、`highspeed`、`flash`、`pro`、`reasoning` 等语义后缀。

- [x] **Step 4: 实现逐字段合并**

解析顺序固定为精确 alias map，再逐字段合并 provider、user；家族规则只写 `familyName`。无精确 `displayName` 时：

```kotlin
val displayName = exact?.displayName
    ?: providerMetadata?.displayName
    ?: remoteModelId.substringAfter("::", remoteModelId)
```

精确候选先按 `NEXARA_OVERRIDE > MODELS_DEV` 的冻结来源优先级选择最高层；只有最高优先级层内仍存在不同记录时，才保留 `UNKNOWN` 并在返回值 `ResolvedModelMetadata.diagnostics` 中记录 `ambiguous_exact_match`。禁止按列表顺序取第一条，也不得让低优先级公共目录压过 Nexara 精确修正。`exactRecords` 组合入口必须保留记录的短 alias，保证 Task 4 合并离线目录后仍可按远端短 ID 解析。不得引入 resolver 全局可变诊断状态、额外 facade 或其它旁路接口。

- [x] **Step 5: 实现小型精确修正表**

`NexaraModelOverrides.kt` 只允许 `mapOf(normalizedExactId to record)`，首批覆盖现有真实测试型号：

```kotlin
internal val NEXARA_EXACT_MODEL_OVERRIDES = mapOf(
    "deepseek-v4-flash" to exactTextModel(
        canonicalId = "deepseek/deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        familyName = "DeepSeek V4",
        reasoning = SupportState.SUPPORTED,
    ),
    "minimax-m3" to exactTextModel(
        canonicalId = "minimax/minimax-m3",
        displayName = "MiniMax M3",
        familyName = "MiniMax M3",
        reasoning = SupportState.SUPPORTED,
    ),
    "minimax-m2.7-highspeed" to exactTextModel(
        canonicalId = "minimax/minimax-m2.7-highspeed",
        displayName = "MiniMax M2.7 Highspeed",
        familyName = "MiniMax M2.7",
        reasoning = SupportState.UNKNOWN,
    ),
    "sensenova-6.7-flash-lite" to exactTextModel(
        canonicalId = "sensenova/sensenova-6.7-flash-lite",
        displayName = "SenseNova 6.7 Flash Lite",
        familyName = "SenseNova 6.7",
        reasoning = SupportState.UNKNOWN,
    ),
)
```

能力和 token 限制只有在已有官方或当前聚合站可验证证据时填写；没有证据的字段保持 `UNKNOWN/null`。

- [x] **Step 6: 运行解析器测试**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.data.model.catalog.ModelMetadataResolverTest'`

Expected: PASS。

- [x] **Step 7: 提交领域层（当前用户禁止提交，按授权边界跳过）**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog \
  native-ui/app/src/test/java/com/promenar/nexara/data/model/catalog
git commit -m "feat: add exact model metadata resolver"
```

---

### Task 3: 建立 models.dev 离线快照生成与供应链门禁

**Files:**
- Create: `scripts/model-catalog/update-model-catalog.py`
- Create: `scripts/tests/test_update_model_catalog.py`
- Create: `native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json`
- Create: `native-ui/app/src/main/assets/model-catalog/manifest.json`
- Create: `docs/legal/THIRD_PARTY_NOTICES.md`
- Modify: `.github/workflows/android-ci.yml`

**Interfaces:**
- Consumes: `https://models.dev/models.json` 的 provider-agnostic JSON。
- Produces: key 排序、字段白名单、稳定缩进的离线快照；manifest；CI `--check` 门禁。

- [x] **Step 1: 写脚本失败测试**

`test_update_model_catalog.py` 使用临时目录和固定夹具，验证：

```python
def test_normalize_keeps_exact_identity_and_reasoning(tmp_path):
    source = {
        "vendor/model-reasoning-2026": {
            "id": "vendor/model-reasoning-2026",
            "name": "Model Reasoning 2026",
            "family": "model",
            "reasoning": True,
            "limit": {"context": 131072, "output": 8192},
            "modalities": {"input": ["text"], "output": ["text"]},
        }
    }
    normalized = normalize_catalog(source)
    assert normalized[0]["canonicalModelId"] == "vendor/model-reasoning-2026"
    assert normalized[0]["displayName"] == "Model Reasoning 2026"
    assert normalized[0]["reasoning"] is True
    assert normalized[0]["contextTokens"] == 131072
```

另加重复 ID、空名称、负 token、未知模态和 schema 类型错误的拒绝测试。当前真实目录包含合法的 image/audio/video 输出模型，已知模态 `text/image/audio/video/pdf` 必须原样保留；不得把“非文本输出”当作错误或过滤条件，只有未知输入/输出模态才拒绝。

- [x] **Step 2: 实现仅标准库更新脚本**

脚本 CLI 固定为：

```text
python scripts/model-catalog/update-model-catalog.py \
  --source-url https://models.dev/models.json \
  --output native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json \
  --manifest native-ui/app/src/main/assets/model-catalog/manifest.json

python scripts/model-catalog/update-model-catalog.py \
  --check \
  --input native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json \
  --manifest native-ui/app/src/main/assets/model-catalog/manifest.json
```

脚本只保留 `id/name/family/reasoning/attachment/tool_call/structured_output/knowledge/release_date/last_updated/limit/modalities/status`，其中 `limit.context/input/output` 分别规范化为 `contextTokens/inputTokens/outputTokens`；拒绝 NaN/Infinity、原始或 Task 4 规范化语义下重复的 canonical ID、空 ID、空名称、未知/额外模态字段和超出 `Int.MAX_VALUE` 的 token 值。网络下载使用 `urllib.request`，超时 30 秒并设置明确 User-Agent；冻结原始 source SHA 与规范化 snapshot SHA，更新和 `--check` 都必须验证。写入先创建父目录，再使用同目录唯一临时文件、文件 `fsync` 和 `os.replace()`，目录 `fsync` 仅在平台支持时执行，异常时清理临时文件。

- [x] **Step 3: 生成初始快照与 manifest**

manifest 格式固定为：

```json
{
  "source": "https://models.dev/models.json",
  "license": "MIT",
  "fetchedAt": "2026-07-17T00:00:00Z",
  "sourceSha256": "d2baab07d79be35c9e0b6aa8fb70f3d13d31ef63735889bde4ea7a15181462db",
  "recordCount": 258,
  "schemaVersion": 1
}
```

执行时 `fetchedAt` 必须写真实 UTC 时间；其余值以实际下载结果为准。如果实际 source SHA 已变化，先审阅 diff，再更新计划记录与 manifest，不能伪造为本计划核验值。

- [x] **Step 4: 增加第三方归属说明**

`docs/legal/THIRD_PARTY_NOTICES.md` 写明：models.dev 项目名称、仓库链接、上游版权声明与完整 MIT License 文本、快照用途、抓取日期、SHA-256，以及“Nexara 会叠加厂商元数据和本地修正，不保证该目录单独完整”的边界。

- [x] **Step 5: 将纯离线校验接入 Android CI**

在 `.github/workflows/android-ci.yml` 的 JVM 门禁前增加：

```yaml
- name: Validate bundled model catalog
  run: >-
    python3 scripts/model-catalog/update-model-catalog.py
    --check
    --input native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json
    --manifest native-ui/app/src/main/assets/model-catalog/manifest.json
```

CI 不联网更新目录，只校验仓库内快照与 manifest。

- [x] **Step 6: 验证脚本与确定性**

Run:

```bash
python3 -m unittest scripts.tests.test_update_model_catalog
python3 scripts/model-catalog/update-model-catalog.py \
  --check \
  --input native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json \
  --manifest native-ui/app/src/main/assets/model-catalog/manifest.json
git diff --exit-code -- native-ui/app/src/main/assets/model-catalog
```

Expected: 全部 PASS，第二次校验不改文件。

`--check` 必须严格验证 manifest 冻结值与类型、UTC `fetchedAt`、规范化 snapshot 的冻结 SHA-256、记录字段白名单与类型、canonical ID 排序/原始及规范化唯一性，以及仓库 JSON 的稳定 `sort_keys + indent=2 + 单换行` 字节格式；不得联网。若产物为未跟踪新文件，`git diff --exit-code` 不足以证明不变，必须额外比较两次 `--check` 前后快照与 manifest 的 SHA-256。

- [x] **Step 7: 提交供应链快照（当前用户禁止提交，按授权边界跳过）**

```bash
git add scripts/model-catalog scripts/tests/test_update_model_catalog.py \
  native-ui/app/src/main/assets/model-catalog \
  docs/legal/THIRD_PARTY_NOTICES.md \
  .github/workflows/android-ci.yml
git commit -m "build: vendor validated model catalog snapshot"
```

---

### Task 4: 离线加载目录并替换 `ModelSpecs.kt` 身份判断职责

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/BundledModelCatalog.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/data/model/catalog/BundledModelCatalogTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/ModelMetadataResolver.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog/NexaraModelOverrides.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/model/ModelSpecs.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/model/ModelSpecsTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/manager/ProviderManagerTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt`

**Interfaces:**
- Consumes: `models-dev.normalized.json`、`NEXARA_EXACT_MODEL_OVERRIDES`。
- Produces: 进程内不可变 exact alias map；`ModelCatalogRuntime.resolver`；兼容 `findModelSpec()` 的过渡适配器。
- Runtime boundary: `ModelCatalogRuntime` 定义在 `BundledModelCatalog.kt`，进程启动前默认持有仅包含 Nexara 精确修正的安全 resolver；`NexaraApplication.onCreate()` 在主进程日志初始化和 relay 进程提前返回之后调用 `ModelCatalogRuntime.initialize(this)`，原子替换为离线目录 resolver。不得新增顶层 facade。
- Exact boundary: 在 `ModelMetadataResolver` 增加 `internal fun resolveExactOrNull(remoteModelId: String): ResolvedModelMetadata?`，只在无歧义且命中 canonical 记录时返回结果；`findModelSpec()` 只通过该入口做旧类型适配。
- Migration boundary: Task 5 前保留 `MODEL_SPECS`、`ModelPattern` 和 `ModelSpec` 供现有 `SettingsViewModel`/`ProviderManager` 调用点编译，但它们不再参与 `findModelSpec()` 身份解析。不得提前迁移 `ModelInfo` 包路径。
- Compatibility boundary: 对已由旧单测和现有消费端依赖、但不在当前快照中的明确型号，只能加入 `NEXARA_EXACT_MODEL_OVERRIDES` 精确兼容 overlay；不得恢复 family/generic key、正则或 `contains()`。首批矩阵冻结为 `o1-preview`、`gemini-1.5-pro`、`gemini-2.0-flash-thinking`、`deepseek-v3`、`qwen2.5-72b`、`llama-3.1-405b`、`qwen-long`、`grok-4.1`、`gemma-4-31b`、`bge-reranker-v2-m3`、`gemini-3.1-pro`、`gemini-3-flash`、`glm-4v`。
- Workload boundary: 当前快照没有独立 workload 字段；`google/gemini-embedding-001`、`nvidia/llama-nemotron-embed-vl-1b-v2`、`nvidia/llama-nemotron-rerank-vl-1b-v2` 必须由 Nexara 精确 overlay 分别标记为 `EMBEDDING`、`EMBEDDING`、`RERANK`，禁止从名称做模糊推断，也不得因 `output=[text]` 归为生成文本。

- [x] **Step 1: 写全目录唯一性与解析覆盖测试**

`BundledModelCatalogTest.kt` 必须断言：

```kotlin
@Test
fun `规范化目录所有 canonical ID 唯一且精确可达`() {
    val records = catalog.records
    assertThat(records.map { it.canonicalModelId }.distinct()).hasSize(records.size)
    records.forEach { record ->
        assertThat(catalog.findExact(record.canonicalModelId)).isEqualTo(record)
    }
}

@Test
fun `所有 reasoning true 记录解析为推理能力支持`() {
    catalog.records.filter { it.capabilities[ModelCapability.REASONING] == SupportState.SUPPORTED }
        .forEach { record ->
            assertThat(resolver.resolve(record.canonicalModelId).capabilities[ModelCapability.REASONING])
                .isEqualTo(SupportState.SUPPORTED)
        }
}
```

测试还必须用真实快照字节覆盖，并另行验证打包后的 debug assets；覆盖 canonical ID 与唯一短 alias 精确可达；同名短 alias 歧义时 `findExact()` 返回 null 且 resolver 返回 `ambiguous_exact_match`；`assets.open()` 返回畸形 JSON 时经 `ModelCatalogRuntime.initialize()` 回退后只保留 `NEXARA_EXACT_MODEL_OVERRIDES`，未知模型仍为 unknown；上述 workload 与精确兼容矩阵保持可达。

- [x] **Step 2: 实现一次性安全加载**

`BundledModelCatalog` 从 `Application.assets` 读取 JSON，使用：

```kotlin
private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
```

解析失败时记录单条结构化错误并回退到 `NEXARA_EXACT_MODEL_OVERRIDES`；不得让 App 启动崩溃，也不得回退到 unknown=chat。

每条公共目录记录至少建立 canonical ID 与去掉首段 provider 前缀后的短 ID 两个精确候选；所有 key 复用 `normalizeRemoteModelId()`。短 ID 若对应多个同优先级记录，不得按顺序取第一条，`findExact()` 返回 null，resolver 保留歧义诊断。目录记录与 Nexara 修正共同注入 `ModelMetadataResolver(exactRecords = ...)`，继续由 Task 2 冻结的来源优先级解决覆盖。

- [x] **Step 3: 收窄旧 `ModelSpecs.kt`**

保留定价兼容入口和仍被调用的适配类型，但完成以下约束：

```kotlin
fun findModelSpec(modelId: String): ModelSpec? =
    ModelCatalogRuntime.resolver.resolveExactOrNull(modelId)?.toLegacyModelSpec()
```

`findModelSpec()` 及 `findContextLength()` 不得再扫描 `MODEL_SPECS`，也不得调用 `StringPattern.matches()` 的 `contains()` 身份判断；家族正则只能经 resolver 的 family 逻辑使用。旧 `note` 仅作为旧调用点过渡兼容描述，任何新代码不得把它当作 `displayName`。定价入口与 Task 5 尚未迁移的直接 `MODEL_SPECS` 消费端保持可编译，待所属任务按计划移除。

不得以删除旧 `ModelSpecsTest` 的关键精确型号断言来制造 GREEN。把旧测试收敛为参数化精确兼容矩阵：目录已覆盖的型号验证公共目录，目录缺失但仍受支持的明确型号验证 Nexara exact overlay；只有旧 family/generic/partial 行为改为 null。至少保留上下文、推理/视觉/多模态、embedding/rerank 和 unknown 的回归断言。

`ProviderManagerTest` 中 DeepSeek V4 Flash 旧自动指纹迁移后的主要类型同步按冻结映射断言为 `reasoning`；能力列表仍保留 `chat`、`reasoning` 和已证实能力。此处只更新受新 resolver 直接影响的旧测试期望，Provider 数据层迁移仍属于 Task 5。

- [x] **Step 4: 运行目录与旧兼容测试**

Run:

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.data.model.catalog.BundledModelCatalogTest' \
  --tests 'com.promenar.nexara.data.model.ModelSpecsTest'

./gradlew :app:mergeDebugAssets
cmp app/src/main/assets/model-catalog/models-dev.normalized.json \
  app/build/intermediates/assets/debug/mergeDebugAssets/model-catalog/models-dev.normalized.json
```

Expected: PASS，目录记录全部通过 canonical ID 精确可达，唯一短 alias 可达，歧义 alias 不按声明顺序解析，未知模型返回 null/unknown，打包后的 debug asset 与仓库快照逐字节一致。

- [x] **Step 5: 提交运行时目录（当前用户禁止提交，按授权边界跳过）**

按用户授权边界，本步骤仅完成 Task 4 限定 review diff、主控验证与 Terra/Sol 双复审；未执行 stage、commit、push、tag、PR 或 Release。

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/data/model/catalog \
  native-ui/app/src/main/java/com/promenar/nexara/data/model/ModelSpecs.kt \
  native-ui/app/src/test/java/com/promenar/nexara/data/model
git commit -m "refactor: resolve models from exact offline catalog"
```

---

### Task 5: 改造 Provider 同步、持久化与用户覆盖迁移

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/model/ModelInfo.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/manager/ProviderManager.kt`
- Modify: 所有原 `com.promenar.nexara.ui.settings.ModelInfo` import 使用点
- Create: `native-ui/app/src/test/java/com/promenar/nexara/data/manager/ProviderModelMetadataMigrationTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelModelClassificationTest.kt`

**Interfaces:**
- Consumes: `ModelMetadataResolver`、现有 SharedPreferences `model_info_<stableId>_*`。
- Produces: 数据层 `ModelInfo`；用户字段保护；不再 unknown→chat；统一精确名称。
- Migration boundary: Tasks 2-4 保持现有 `com.promenar.nexara.ui.settings.ModelInfo` import 可编译；只有本任务 Step 1 才机械迁移到 `com.promenar.nexara.data.model.ModelInfo`。不得为了未来包路径让早期任务提前失败。

- [x] **Step 1: 将 `ModelInfo` 移到数据层并保持调用点可编译**

把 `ModelInfo` 按 `Target Data Contracts` 放入 `com.promenar.nexara.data.model`，机械更新 import。不得顺便修改 UI 或业务行为。

- [x] **Step 2: 写用户覆盖与旧自动指纹迁移测试**

测试必须覆盖：

```kotlin
@Test
fun `用户编辑名称不被目录刷新覆盖`() {
    val stored = model(name = "我的 DeepSeek", userEditedFields = setOf("name"))
    val migrated = migrate(stored, resolved(displayName = "DeepSeek V4 Flash"))
    assertThat(migrated.name).isEqualTo("我的 DeepSeek")
}

@Test
fun `旧系列泛称自动指纹升级为精确名称`() {
    val stored = legacyAutoModel(name = "DeepSeek Series")
    val migrated = migrate(stored, resolved(displayName = "DeepSeek V4 Flash"))
    assertThat(migrated.name).isEqualTo("DeepSeek V4 Flash")
}

@Test
fun `新 ModelInfo 默认保持 unknown 且不猜测能力和 token`() {
    val model = minimalModelInfo(id = "provider::future-model")
    assertThat(model.type).isEqualTo("unknown")
    assertThat(model.contextLength).isEqualTo(0)
    assertThat(model.maxOutputTokens).isEqualTo(0)
    assertThat(model.capabilities).isEmpty()
    assertThat(model.chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
}
```

`SettingsViewModelModelClassificationTest` 已覆盖 `classifyFetchedModelType()` 的 unknown 分支，不得重复造同义断言；本任务新增的是 DTO 默认值、能力映射、持久化和用户覆盖的不同契约。

- [x] **Step 3: 修改 Provider 拉取合并**

对每个远端 ID 先调用 resolver。新模型使用：

```kotlin
ModelInfo(
    name = resolved.displayName,
    id = stableModelId(providerId, remoteId),
    remoteModelId = remoteId,
    description = resolved.familyName ?: remoteId,
    enabled = false,
    type = resolved.toLegacyType(),
    contextLength = resolved.contextTokens ?: 0,
    capabilities = resolved.toLegacySupportedCapabilities(),
    providerName = provider.name,
    providerId = providerId,
    maxOutputTokens = resolved.outputTokens ?: 0,
    knowledgeCutoff = resolved.knowledgeCutoff,
    familyName = resolved.familyName,
    canonicalModelId = resolved.canonicalModelId,
    chatEndpointCompatible = resolved.capabilities[ModelCapability.CHAT_ENDPOINT]
        ?: SupportState.UNKNOWN,
    autoMetadataFingerprint = resolved.autoFingerprint(),
)
```

已有模型逐字段检查 `userEditedFields` 后合并，禁止 `copy(name = resolved.displayName)` 无条件覆盖。

- [x] **Step 4: 修改默认值和能力映射**

`classifyFetchedModelType()` 未知分支保持 `unknown`。`buildModelCapabilities()` 的 `else` 分支返回空列表：

```kotlin
when (type) {
    "chat" -> add("chat")
    "reasoning" -> { add("chat"); add("reasoning") }
    "image" -> add("image")
    "embedding" -> add("embedding")
    "rerank" -> add("rerank")
    "audio" -> add("audio")
    "video" -> add("video")
    "unknown" -> Unit
}
```

`addCustomModel()` 与 `ensureConfiguredModel()` 使用 resolver，未知类型、上下文和输出分别写 `unknown/0/0`，不再使用 `chat/8192` 猜测默认值。

- [x] **Step 5: 扩展 SharedPreferences 持久化**

新增以下键：

```text
model_info_<stableId>_family
model_info_<stableId>_canonical_id
model_info_<stableId>_chat_endpoint
model_info_<stableId>_auto_fingerprint
model_info_<stableId>_user_edited_fields
```

`chat_endpoint` 保存枚举名；读取非法值回退 `UNKNOWN`。模型管理页更新名称、类型、能力、context、maxOutput 时把对应字段加入 `userEditedFields`。

- [x] **Step 6: 运行 Provider 与路由回归**

Run:

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.data.manager.ProviderModelMetadataMigrationTest' \
  --tests 'com.promenar.nexara.ui.settings.SettingsViewModelModelClassificationTest' \
  --tests 'com.promenar.nexara.ui.settings.SettingsViewModelTest' \
  --tests 'com.promenar.nexara.data.remote.ProviderRequestRouterTest'
```

Expected: PASS。

- [x] **Step 7: 提交 Provider 改造（当前用户禁止提交，按授权边界跳过）**

```bash
git add native-ui/app/src/main/java native-ui/app/src/test/java
git commit -m "feat: merge provider models with traced metadata"
```

Task 5 完成证据：目标测试 65/65、Task 4 回归 36/36、AndroidTest/ScreenshotTest 编译通过；限定 review diff 已刷新，Terra/Sol 最终均为 `C0/I0/M0 PASS`。未执行 stage、commit、push、tag、PR 或 Release。

---

### Task 6: 修正首次引导模型探测语义

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/welcome/OnboardingModelProbe.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/welcome/OnboardingModelPolicy.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/welcome/OnboardingModelProbeTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/welcome/OnboardingModelPolicyTest.kt`

**Interfaces:**
- Consumes: `ModelInfo.chatEndpointCompatible`。
- Produces: 待验证候选与最终可用模型分层；探测成功但不篡改主要类型。

- [x] **Step 1: 扩展 Task 1 测试并观察 Task 6 专属 RED**

Task 5 已引入 `chatEndpointCompatible` 后，在 `OnboardingModelProbeTest.kt` 的既有断言中增加：

```kotlin
assertThat(verified.chatEndpointCompatible).isEqualTo(SupportState.SUPPORTED)
```

先运行该测试，确认编译成功但因探测结果没有独立写入端点能力而在运行期 FAIL；不得在 Task 1 提前引用该字段。

- [x] **Step 2: 实现探测结果分离**

`verifyOnboardingModelCandidate()` 改为：

```kotlin
internal suspend fun verifyOnboardingModelCandidate(
    model: ModelInfo,
    probe: suspend (String) -> Boolean,
): ModelInfo? = when {
    model.type in setOf("chat", "reasoning") -> model.copy(
        chatEndpointCompatible = SupportState.SUPPORTED,
    )
    model.chatEndpointCompatible == SupportState.SUPPORTED -> model
    model.type.lowercase() == "unknown" && probe(model.remoteModelId) -> model.copy(
        chatEndpointCompatible = SupportState.SUPPORTED,
    )
    else -> null
}
```

- [x] **Step 3: 区分待验证候选与最终可用模型**

`onboardingModelCandidates()` 用于 NavGraph 的可点击待验证列表：同 provider 且 `type in {chat, reasoning, unknown}`，或已有 `chatEndpointCompatible == SUPPORTED`。unknown 只获得一次用户触发的验证机会，不等于直接可用；不得在刷新时自动批量探测。

`eligibleOnboardingModels()` 保持严格最终条件：同 provider 且 `type in {chat, reasoning}`，或 `chatEndpointCompatible == SUPPORTED`。embedding、rerank、image、audio、video 不因名称或 capability 中出现 `chat` 误进入。

NavGraph 必须使用 `onboardingModelCandidates()` 构造选择列表；用户选中后调用 `verifyOnboardingModel()`，只有返回非 null 才持久化并推进。测试必须覆盖 `candidate -> verify -> eligible` 的连续数据流，禁止保留生产不可达的 unknown probe 死分支。

- [x] **Step 4: 保持结构化并发**

真实请求包装必须区分：内部 15 秒超时返回 false；普通请求异常返回 false；父协程 `CancellationException` 原样抛出。不得用 `runCatching(...).getOrDefault(false)` 吞掉取消。

- [x] **Step 5: 运行引导测试**

Run:

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.ui.welcome.OnboardingModelProbeTest' \
  --tests 'com.promenar.nexara.ui.welcome.OnboardingModelPolicyTest'
```

Expected: PASS。

- [x] **Step 6: 提交引导语义修复（当前用户禁止提交，按授权边界跳过）**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/welcome \
  native-ui/app/src/test/java/com/promenar/nexara/ui/welcome
git commit -m "fix: separate chat endpoint probe from model type"
```

Task 6 完成证据：强化外层 timeout 测试先在旧实现上确定性 RED；最终 Probe 10/10、Policy 4/4、Task 5 回归 32/32，共 46/46 PASS。479 行 review diff 严格包含六个计划文件；Terra/Sol 最终均为 `C0/I0/M0 PASS`。未执行 stage、commit、push、tag、PR 或 Release。

---

### Task 7: 统一会话输入区与历史消息的友好名称数据流

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ModelDisplayNameResolver.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatRoute.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ModelDisplayNameResolverTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatRouteContractTest.kt`

**Interfaces:**
- Consumes: `ProviderManager.providerModels`、`ModelMetadataResolver`。
- Produces: `ChatScreenState.modelDisplayNames: Map<String, String>`、纯函数 `resolveModelDisplayName()`、`PipelineBubble(modelDisplayNames: Map<String, String>)`。
- Contract: Route 只调用一次 `resolveModelDisplayName()` 构造 map；输入区 Chip 与历史 AI 尾注都只消费该 map，禁止任一消费端再次调用 `findModelSpec(id)?.note` 或独立解析。

- [x] **Step 1: 写纯函数与双消费端数据流 RED**

在本任务开始时创建 `ModelDisplayNameResolverTest.kt`，直接调用本任务即将实现的强类型函数：

```kotlin
@Test
fun `稳定 ID 优先使用用户或同步后的精确名称`() {
    val models = listOf(modelInfo(
        id = "default::deepseek-v4-flash",
        remoteModelId = "deepseek-v4-flash",
        name = "DeepSeek V4 Flash",
    ))
    assertThat(resolveModelDisplayName("default::deepseek-v4-flash", models))
        .isEqualTo("DeepSeek V4 Flash")
}

@Test
fun `历史模型不存在时回退原始远端 ID 而非系列泛称`() {
    assertThat(resolveModelDisplayName("deleted::vendor-model-2026-07", emptyList()))
        .isEqualTo("vendor-model-2026-07")
}
```

在 `ChatRouteContractTest.kt` 增加源码数据流契约，明确要求：

```text
ChatRoute 使用 resolveModelDisplayName(...) 构造 modelDisplayNames
ChatScreenState 声明 modelDisplayNames
ChatInputTopBar 的 modelName 来自 state.modelDisplayNames
PipelineBubble 接收 modelDisplayNames = state.modelDisplayNames
PipelineBubble 的模型名来自 map，缺失时只回退原始远端 ID
ChatScreen 与 PipelineBubble 均不再调用 findModelSpec(id)?.note
```

先运行两个测试并观察 Task 7 专属 RED，再实施以下步骤。

- [x] **Step 2: 实现纯函数解析**

```kotlin
internal fun resolveModelDisplayName(
    stableOrRemoteId: String?,
    providerModels: List<ModelInfo>,
    catalogResolver: ModelMetadataResolver = ModelCatalogRuntime.resolver,
): String {
    if (stableOrRemoteId.isNullOrBlank()) return ""
    val remoteId = stableOrRemoteId.substringAfter("::", stableOrRemoteId)
    val stored = providerModels.firstOrNull {
        it.id == stableOrRemoteId ||
            (it.providerId == stableOrRemoteId.substringBefore("::", "") && it.remoteModelId == remoteId)
    }
    return stored?.name?.takeIf { it.isNotBlank() }
        ?: catalogResolver.resolve(remoteId).displayName
}
```

- [x] **Step 3: 在 Route 层收集一次模型表**

`ChatRoute` 使用 `ProviderManager.getInstance().providerModels.collectAsStateWithLifecycle()`，为当前 session ID 和消息中出现的 model ID 构造不可变显示名 map：

```kotlin
val modelDisplayNames = remember(providerModels, uiState.session?.modelId, uiState.messages) {
    buildSet {
        uiState.session?.modelId?.let(::add)
        uiState.messages.mapNotNullTo(this) { it.modelId }
    }.associateWith { id -> resolveModelDisplayName(id, providerModels) }
}
```

把 map 放入 `ChatScreenState`。截图测试和设备测试可直接注入固定 map，不访问单例。

- [x] **Step 4: 两个消费端都改用统一 map**

删除 `findModelSpec(id)?.note ?: id`。改为：

```kotlin
val sessionModelId = uiState.session?.modelId
val modelDisplayName = sessionModelId
    ?.let(state.modelDisplayNames::get)
    .orEmpty()
```

同时把 `modelDisplayNames = state.modelDisplayNames` 传给 `PipelineBubble`，并在 `PipelineBubble` 中只做 map 查找与原始远端 ID 回退。此任务只改变名称数据来源，保留现有元信息 Row 布局；左对齐和权重调整属于 Task 8。

- [x] **Step 5: 运行纯函数与 Route 契约测试**

Run:

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.ui.chat.ModelDisplayNameResolverTest' \
  --tests 'com.promenar.nexara.ui.chat.ChatRouteContractTest'
```

Expected: PASS。

- [x] **Step 6: 提交显示名数据流（当前用户禁止提交，按授权边界跳过）**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat \
  native-ui/app/src/test/java/com/promenar/nexara/ui/chat
git commit -m "refactor: share model display names across chat surfaces"
```

Task 7 完成证据：初始强类型 RED 因缺少 `resolveModelDisplayName` 失败；首轮复审发现 Route 数据流静态测试可假绿，返修再以缺少 `resolveModelDisplayNames` 观察真实 RED。最终 Task 7 14/14、Task 6/5 回归 46/46 PASS；PipelineBubble 8 项仅保留 Task 8 左对齐 1 条预期失败。349 行六文件 review diff，Terra/Sol 最终均为 `C0/I0/M0 PASS`。未执行 stage、commit、push、tag、PR 或 Release。

---

### Task 8: 将 AI 消息“模型名 + 时间”左对齐并完成 MD3 可视化门禁

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ChatScreenContentStateTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: 受影响的 `native-ui/app/src/screenshotTestDebug/reference/` 已批准图片

**Interfaces:**
- Consumes: `ChatScreenState.modelDisplayNames`。
- Produces: 左对齐 `AssistantMetadataRow`；`PipelineBubble(modelDisplayNames: Map<String,String>)` 已由 Task 7 接通。

- [x] **Step 1: 复跑 Task 1 的 AI 尾注运行期 RED**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest'`

Expected: 编译成功，`AI 元信息模型名与时间作为左对齐同组渲染` 因尚未抽取 `AssistantMetadataRow`、仍使用旧布局而 FAIL。

- [x] **Step 2: 确认 Task 7 的显示名 map 接入**

Task 7 已新增以下默认参数以保持测试夹具兼容，本任务不得另建第二条名称解析路径：

```kotlin
fun PipelineBubble(
    group: PipelineGroup,
    isGenerating: Boolean,
    status: GenerationStatus = GenerationStatus.IDLE,
    streamingContent: String,
    fontSize: Int,
    modelDisplayNames: Map<String, String> = emptyMap(),
    // 其余回调保持不变
)
```

模型名解析：

```kotlin
val modelDisplayName = lastMsg.modelId
    ?.let(modelDisplayNames::get)
    ?.takeIf(String::isNotBlank)
    ?: lastMsg.modelId?.substringAfter("::", lastMsg.modelId).orEmpty()
```

- [x] **Step 3: 抽取左对齐元信息组件**

```kotlin
@Composable
private fun AssistantMetadataRow(
    modelDisplayName: String,
    timestamp: String,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    val metaStyle = NexaraTypography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontSize = (fontSize - 2).coerceAtLeast(9).sp,
    )
    Row(
        modifier = modifier
            .fillMaxWidth(0.5f)
            .padding(top = 4.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (modelDisplayName.isNotBlank()) {
            Text(
                text = modelDisplayName,
                style = metaStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA),
            )
        }
        Text(
            text = timestamp,
            style = metaStyle,
            maxLines = 1,
            modifier = Modifier.testTag(UiTags.CHAT_ASSISTANT_TIME_METADATA),
        )
    }
}
```

注意：这里必须是 `.weight(1f, fill = false)`；Task 1 静态测试应精确禁止 `.weight(1f)` 或 `fill = true`，不能误禁 `fill = false`。元信息 Row 限制在父宽度左半区，并通过尾部 padding 保证时间右边界严格位于中线左侧；短名称时模型名和时间紧邻左侧，长名称时模型名省略、时间仍可见。

- [x] **Step 4: 增加设备布局断言**

在 `ChatScreenContentStateTest` 中注入：

```kotlin
modelDisplayNames = mapOf(
    "default::deepseek-v4-flash" to "DeepSeek V4 Flash",
)
```

通过 `fetchSemanticsNode().boundsInRoot` 断言：

```kotlin
assertThat(modelBounds.left).isLessThan(timeBounds.left)
assertThat(timeBounds.right).isLessThan(screenWidth / 2f)
assertThat(modelBounds.bottom).isEqualTo(timeBounds.bottom)
```

另用 60 字符友好名称验证时间仍存在、两者不重叠、无第二行。

Task 8 设备 RED 证据：原计划的全宽 Row 在 Pixel_7 API 36 上把长名称时间右边界推至 `1038px`，超过屏幕中线 `540px`。因此冻结为左半区 Row；不改变 `.weight(1f, fill = false)`、单行省略、时间可见及 Completion Definition。

- [x] **Step 5: 增加截图场景并逐图验收**

增加或更新以下场景：

```text
chatReadyReleasePreview：精确友好名称 + 左侧时间
chatLongModelMetadataReleasePreview：超长名称省略 + 时间可见
chatReasoningModelReleasePreview：推理模型友好名称
chatLargeFontReleasePreview：2.0x 字体无碰撞
```

Run:

```bash
cd native-ui
./gradlew :app:validateDebugScreenshotTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.chat.ChatScreenContentStateTest
```

Expected: 首轮 screenshot 差异只来自预期元信息布局；设备测试 PASS。逐张打开 actual 图片确认后才执行 `:app:updateDebugScreenshotTest`。

- [x] **Step 6: 主控进行多模态视觉复核**

至少检查手机竖屏、横屏、中文、英文、大字体实际图，确认：

```text
AI 尾注左侧形成“模型名 时间”弱化信息组
用户气泡时间仍位于右侧
右侧不再形成 AI/用户时间戳密集列
输入区 Chip 与 AI 尾注名称完全一致
长名称不挤走时间、不换行、不遮挡正文
```

- [x] **Step 7: 提交会话 UI（当前用户禁止提交，按授权边界跳过）**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat \
  native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt \
  native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat \
  native-ui/app/src/screenshotTest \
  native-ui/app/src/screenshotTestDebug/reference
git commit -m "fix: align assistant model metadata on the left"
```

Task 8 完成证据：JVM 目标组 22/22、API 36 Pixel_7 设备组 7/7、完整截图 61/61 PASS。主控逐张检查 10 张受影响 chat actual，确认常规、长名称、推理、2.0x 字体、中英文与横竖屏均满足冻结布局；6 张修改和 4 张新增 chat reference 已纳入限定 review diff，Agent Hub reference 哈希不变且不在该 diff 中。首轮复审意见逐项裁决并返修后，Terra/Sol 第二轮均为 `C0/I0/M0 PASS`。未执行 stage、commit、push、tag、PR 或 Release。

---

### Task 9: 建立目录刷新工作流与冲突报告

**Files:**
- Create: `.github/workflows/model-catalog-refresh.yml`
- Modify: `scripts/model-catalog/update-model-catalog.py`
- Modify: `scripts/tests/test_update_model_catalog.py`
- Modify: `native-ui/app/src/main/assets/model-catalog/manifest.json`
- Modify: `docs/legal/THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Consumes: models.dev 最新快照。
- Produces: 每周手动/定时更新分支、覆盖率与冲突报告；不自动合并。

- [x] **Step 1: 增加差异报告模式与可刷新锁值**

脚本新增以下 CLI 更新模式必需参数（程序内 `run_update` 可不传二者以复用底层规范化测试）：

```text
--diff-report <path>
--overrides-source <NexaraModelOverrides.kt>
```

报告必须包含：新增、删除、重命名、reasoning 变化、context/output 变化、deprecated 变化、精确 ID 冲突、Nexara override 已被上游覆盖、总记录数和未知字段计数。

Task 3 的首版源码常量锁会在上游合法变化时先于报告生成而失败，无法形成可审阅 PR。Task 9 将 source bytes/SHA、catalog SHA、record count 和 unknown field count 迁移到 manifest schema v2；`--check` 继续离线验证 manifest 类型、固定来源/许可/schema、snapshot 确定性字节、catalog SHA、记录数、排序与唯一性。更新仍严格校验输入 schema 与冲突，只是把“当前批准锁值”从脚本源码迁移到与 snapshot 同一审阅 diff 的 manifest，不降低 Task 3 完成标准。

完整报告键、冲突定义、override 只读扫描和 fail-closed 行为以 `.superpowers/sdd/task-9-brief.md` 为冻结契约；施工 Agent 不得自行新增重复 override 清单。

- [x] **Step 2: 写报告测试**

夹具从 `reasoning=false/context=128k` 变为 `reasoning=true/context=256k` 时，报告必须同时出现 `reasoning_changed` 和 `context_changed`；上游删除本地 override 对应型号时不得自动删除 override。

- [x] **Step 3: 新增只创建 PR 的工作流**

工作流触发器：

```yaml
on:
  workflow_dispatch:
  schedule:
    - cron: '23 3 * * 1'
```

步骤固定为：checkout、Python 3、更新快照并生成 Markdown 差异报告、运行脚本测试、运行目录 Kotlin 测试、创建或更新 `chore/model-catalog-refresh-<UTC date>` draft PR。PR body 使用差异报告，`add-paths` 只允许 snapshot、manifest 和第三方说明；禁止自动 merge，禁止在 workflow 中使用产品 API Key。所有第三方 action 固定完整 commit SHA。

- [x] **Step 4: 本地验证 workflow 与脚本**

Run:

```bash
python3 -m unittest scripts.tests.test_update_model_catalog
cd native-ui
./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.data.model.catalog.*'
```

Expected: PASS。

- [x] **Step 5: 提交维护自动化（用户禁止 Git 写操作，按授权边界跳过）**

```bash
git add .github/workflows/model-catalog-refresh.yml \
  scripts/model-catalog/update-model-catalog.py \
  scripts/tests/test_update_model_catalog.py
git commit -m "ci: propose reviewed model catalog refreshes"
```

Task 9 完成证据：Python 64/64、当前 snapshot/manifest 离线 check、Kotlin catalog 26/26、Workflow YAML/静态契约与 `git diff --check` 全部通过；限定 review diff SHA-256 为 `4414db3077a0d004f22920c25d681f72a1d7d70b65e25f74f1968d8149189020`，Terra/Sol 最终均为 `C0/I0/M0 PASS`。未执行 stage、commit、push、tag、PR 或 Release。

---

### Task 10: 全量回归、真实 Provider 验证与文档治理收口

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/ARCHITECTURE.md`
- Create: `docs/ADR/ADR-020-layered-model-metadata-registry.md`
- Modify: `docs/audit/MODEL_DATABASE_RESEARCH_20260516.md`
- Modify: `docs/release/v0.2-beta-validation.md`
- Modify: `.agent/registry.md`
- Modify: `.agent/handover.md`
- Regenerate: `.agent/handover-index.md`

**Interfaces:**
- Consumes: Tasks 1-9 全部实现与验证证据。
- Produces: 可发行判断所需的测试账本、架构决策和恢复点。

- [x] **Step 1: 运行定点 JVM 门禁**

```bash
cd native-ui
./gradlew :app:testDebugUnitTest \
  --tests 'com.promenar.nexara.data.model.*' \
  --tests 'com.promenar.nexara.data.model.catalog.*' \
  --tests 'com.promenar.nexara.data.manager.ProviderModelMetadataMigrationTest' \
  --tests 'com.promenar.nexara.ui.settings.SettingsViewModelModelClassificationTest' \
  --tests 'com.promenar.nexara.ui.welcome.*Model*Test' \
  --tests 'com.promenar.nexara.ui.chat.ModelDisplayNameResolverTest' \
  --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest'
```

Expected: PASS。

完成证据：强制重跑 10 个模型相关 suite，共 89 项，0 failure/error/skipped，`BUILD SUCCESSFUL`。

- [x] **Step 2: 运行全量 JVM、Lint、截图和 Android Test 编译**

```bash
cd native-ui
./gradlew :app:testDebugUnitTest :app:lintDebug :app:validateDebugScreenshotTest :app:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL；Lint 0 Error/Fatal；截图差异为 0。

完成证据：首轮发现新增模型元数据偏好未纳入备份白名单，先以 6 项定点测试观察 2 项 RED，再补齐动态 key 与 `_user_edited_fields` 类型；全量重跑最终 1945 JVM（0 failure/error、14 skip）、Lint 0 Error/Fatal、61/61 Screenshot、AndroidTest Kotlin 编译全部通过。

- [x] **Step 3: 运行 API 35/36 设备门禁**

分别在 API 35、36 执行 Provider 模型页、onboarding、ChatScreenContentStateTest 和 ChatImeInteractionTest。至少验证：精确型号、推理型号、unknown 型号、长 ID、删除后历史消息、2.0x 字体和横屏。

完成证据：API 35 与 API 36 聚焦矩阵各执行 31 项，均 0 failure/error、1 个设计内 `forceStopPhaseCheckpoint` skip。2026-07-19 再运行发行脚本同源矩阵：API 31 minimum、API 35/36 full 全部通过；预编译阶段真实暴露 `mainactivity-e2e` 两处仍引用旧 `ui.settings.ModelInfo`，修正为冻结后的 `data.model.ModelInfo` 后重新构建并在三档设备复验通过。通用 onboarding 集合中的 checkpoint 仍按设计 skip，专用分阶段调用已单独通过，不把 skip 记为 PASS。

- [x] **Step 4: 使用已授权内网聚合站做真实但脱敏的 Provider 验证**

API Key 只从当前 shell 环境变量读取，日志和测试报告中只写 endpoint 类型、模型 ID、HTTP 状态与解析结果，不写 Key。验证：

```text
MiniMax-M3 -> 精确友好名称，不显示系列泛称
MiniMax-M2.7-highspeed -> 精确友好名称
deepseek-v4-flash -> 推理主要标签；Chat endpoint 兼容独立记录
sensenova-6.7-flash-lite -> 精确友好名称；未知能力保持 unknown
```

同时验证会话输入区和 AI 尾注名称一致，AI 时间左侧并列，用户时间仍在右侧。

完成证据：2026-07-19 由用户重新提供内网聚合站运行时凭据，主控通过关闭终端回显的交互输入仅注入当前 Gradle 子进程，未写入源码、计划、报告或命令参数。首轮使用站点根地址时，真实 RED 为 `MiniMax-M2.7-highspeed` 错误流；脱敏 HTTP 探针确认四模型均为 HTTP 200、SSE payload 与 `[DONE]`，并定位聚合站 Chat Completions 实际位于标准 `/v1` API 前缀。仅规范化本次运行时 Base URL 后，使用 `--no-build-cache --rerun-tasks :app:realLlmIntegrationTest` 强制重跑，四模型在同一 Router 中各请求一次并全部取得有效 payload 与 Done，`BUILD SUCCESSFUL`（1m12s，29 tasks 全部执行）。MiniMax/DeepSeek 的 `delta.reasoning_content` 与 SenseNova 的 `delta.reasoning` 均被现有协议层正确解析；未放宽发行版 HTTPS-only 策略。

- [x] **Step 5: 更新架构与旧审计结论**

`ADR-020` 记录：分层来源、逐字段优先级、离线快照、三态能力、推理/端点分离、精确匹配、用户覆盖和回滚策略。旧 `MODEL_DATABASE_RESEARCH_20260516.md` 不删除历史正文，追加 2026-07-17 勘误，说明“没有可用结构化目录”的旧结论已被 models.dev 机器可读目录和厂商富元数据接口取代。

- [x] **Step 6: 更新发行账本和交接**

在 `docs/release/v0.2-beta-validation.md` 记录真实型号矩阵、截图、设备和测试结果；在 `CHANGELOG.md` 记录用户可见变化；registry 登记 ADR、第三方说明和本计划。handover 使用 `continuity-key: nexara-model-catalog`，明确 Done/Validation/Next/Risks/DIA/HLG。

追加证据：2026-07-19 已用当前源码强制重建稳定证书签名 R8 APK，并在 API 35/36 完成冷安装、设备 base.apk 字节回读和 release 等价 PDF/DOCX/TXT 黑盒；该证据不解除 Step 4 真实 Provider、真机 TalkBack、远端 CI 与发布外部状态门禁。

- [x] **Step 7: 重建 HLG 索引并检查 diff**

```bash
python /Users/promenar/.codex/skills/handover-lifecycle-governance/scripts/hlg-handover.py \
  index \
  --root /Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta \
  --days 7
git diff --check
git status --short
```

Expected: index 成功、`git diff --check` 无输出；状态只包含本计划、既有 MD3 工作和本轮预期实现文件。

完成证据：HLG Skill 脚本成功重建七天索引；四张新增聊天 actual 已由主控逐张检查。`git diff --check` 与最终状态清单在双复审后再次执行。

- [x] **Step 8: 最终主控验收**

主控独立检查：目录条数与 manifest、解析冲突报告、所有模型默认类型、用户覆盖迁移、截图 actual、设备坐标断言、真实 Provider 输出、完整 diff、DIA 和 HLG。任何 Agent 的“已完成”不得替代这些证据。

完成证据：主控已核对 manifest/目录条数、解析与迁移源码、备份策略、四张 actual、API 31/35/36 设备结果、当前签名 APK、完整 diff、DIA/HLG 与本次真实 Provider 输出。四个调用 ID 分别精确解析为 `MiniMax M3`、`MiniMax M2.7 Highspeed`、`DeepSeek V4 Flash`、`SenseNova 6.7 Flash Lite`；DeepSeek 推理能力为 `SUPPORTED`，MiniMax M2.7 与 SenseNova 缺乏证据的能力保持 `UNKNOWN`，工作负载/推理能力与 Chat endpoint 兼容性继续分字段保存。输入区和 AI 尾注复用同一显示解析器，AI 左侧名称时间组、用户右侧时间已由单测、设备断言和 actual 共同验证。Task 10 独立 Terra 规格复审与 Sol 事实一致性复审均为 C0/I0/M0 PASS，Critical/Important 已全部关闭。

---

## Agent Routing for Execution

新会话首先执行原生 Spark 最小兼容性检查，不传 `service_tier`、`reasoning_effort`，不读写文件。只有实际返回 `SPARK_NATIVE_SMOKE_OK` 才视为该会话 Spark 可用。

建议分工：

```text
主控/Terra/Sol：Task 2 架构接口、Task 4 运行时集成、Task 5 迁移裁决、Task 10 最终验收
原生 Spark：Task 1 测试施工、Task 3 Python 生成脚本、Task 6 onboarding 窄改、Task 8 UI 按明确契约施工、Task 9 workflow
Gemini 3.5 Flash：Task 8 截图多模态第二意见；不得单独批准视觉
GLM-5.2：复杂 Provider 迁移或 resolver 冲突排障；北京时间 14:00-18:00 默认规避
```

多个写入 Agent 不得同时修改 `SettingsViewModel.kt`、`ProviderManager.kt`、`PipelineBubble.kt`、`ChatScreen.kt`、`CHANGELOG.md`、registry 或 handover。共享文件必须串行独占。

## Stop Conditions

出现以下任一情况立即停止扩大改动并交回主控：

```text
models.dev schema 与计划字段不兼容且无法无损忽略
同一 normalized exact ID 指向多个不同 canonical model
用户编辑字段无法与旧自动元数据可靠区分
迁移要求改写 Session/Agent/Message 的稳定模型 ID
截图需要牺牲时间可见性或模型名称真实性才能通过
原生 Spark 出现参数校验、reasoning.summary 或运行期错误
任何测试需要读取或输出真实 API Key/签名材料
```

## Completion Definition

只有同时满足以下条件才可声称完成：

```text
unknown 不再默认 chat
聊天探测不再修改模型主要类型
推理模型显示“推理”主要标签
精确调用 ID 不再被系列泛称覆盖
输入区与 AI 尾注使用同一友好名称
AI “模型名 + 时间”左对齐，用户时间仍右对齐
离线目录可复现、可校验、有许可证归属和更新报告
用户编辑字段不被刷新覆盖
JVM/Lint/Screenshot/Android 设备/真实 Provider 门禁全部有证据
DIA 与 HLG 完成收口
```

完成结论：2026-07-19 上述条件全部取得当前工作树证据，Task 1-10 完成。该结论仅关闭模型元数据与会话尾注计划；v0.2-beta 仍受真机 TalkBack/核心业务人工验收、当前提交远端 CI、可验证 tag、tag workflow 与 GitHub Release 等发行门禁约束，整体发行状态继续 NO-GO。
