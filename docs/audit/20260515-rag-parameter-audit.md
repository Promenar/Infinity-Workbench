# RAG 与参数控制系统审计报告 & 修复方案

> **审计日期**: 2026-05-15
> **审计范围**: RAG 配置标准化 + 极客参数控制中心 + 全协议参数透传
> **状态**: 审计完成，待执行

---

## 一、审计结论摘要

| 维度 | 评分 | 关键发现 |
|------|------|---------|
| 语义清晰度 | ⭐⭐⭐⭐☆ | 中文命名优秀，"长期记忆"/"上下文管理" 对非专业用户友好 |
| 交互反馈 | ⭐⭐⭐☆☆ | 折叠设计合理，RAG 全局关闭无反馈，极端参数无提示 |
| 参数透传 | ⭐⭐☆☆☆ | **GenericOpenAICompat 是唯一全参实现**，其余协议各有缺失 |
| 单元测试 | ⭐⭐⭐☆☆ | 测试方法论正确，协议覆盖不完整，缺极端值/多模态场景 |
| 架构设计 | ⭐⭐⭐⭐☆ | 分层清晰，NexaraCollapsibleSection 复用性好 |

---

## 二、P0 修复项（立即处理）

### 2.1 🔴 全协议参数透传统一

**问题**: 用户调整极客参数后，部分协议实现静默丢弃该参数。

**参数透传矩阵（当前状态）**:

| 参数 | OpenAI | Anthropic | VertexAI | GenericCompat | Local |
|------|--------|-----------|----------|---------------|-------|
| temperature | ✅ | ✅ | ✅ | ✅ | ✅ |
| topP | ✅ | ✅ | ✅ | ✅ | ✅ |
| maxTokens | ✅ | ✅ | ✅ | ✅ | ✅ |
| frequencyPenalty | ✅ | **❌** | ✅ | ✅ | **❌** |
| presencePenalty | ✅ | **❌** | ✅ | ✅ | **❌** |
| topK | **❌** | ✅ | ✅ | ✅ | ✅ |
| repetitionPenalty | **❌** | **❌** | **❌** | ✅ | ✅ |

**涉及文件**:

| 文件 | 缺失参数 | 修复方向 |
|------|---------|---------|
| `OpenAIProtocol.kt:247-251` | `topK`, `repetitionPenalty` | 添加 `put("top_k", ...)` / `put("repetition_penalty", ...)` |
| `AnthropicProtocol.kt:248-250` | `frequencyPenalty`, `presencePenalty`, `repetitionPenalty` | Anthropic API 不支持前两者，添加注释说明；repetitionPenalty 评估是否可用 topK 间接表达 |
| `VertexAIProtocol.kt:363-370` | `repetitionPenalty` | Gemini `generation_config` 支持该字段，直接添加 |
| `LocalProtocol.kt:34-40` | `frequencyPenalty`, `presencePenalty` | 需先扩展 `GenerateConfig` 增加对应字段 |
| `InferenceBackend.kt:33-39` (`GenerateConfig`) | `frequencyPenalty`, `presencePenalty` | 新增字段 |

### 2.2 🔴 RAG 全局关闭状态反馈

**问题**: 当用户在 SettingsPanel 中同时关闭 `enableMemory` 和 `isGlobal` 后，没有任何视觉提示表明"所有 RAG 功能已禁用"。

**文件**: `SessionSettingsSheet.kt` → `SettingsPanel()` (约第 777-951 行)

**方案**: 在 "长期记忆" section 底部增加状态指示器：

```kotlin
// 伪代码：在 ToolToggleRow 列表后添加
val isRagFullyDiabled = !memoryEnabled && !globalMemoryEnabled && !docsEnabled && !kgEnabled
if (isRagFullyDiabled) {
    NexaraHintBanner(
        icon = Icons.Rounded.Info,
        text = stringResource(R.string.sheet_settings_rag_disabled),
        hint = stringResource(R.string.sheet_settings_rag_disabled_hint)
    )
}
```

**新增 strings.xml**:
- `sheet_settings_rag_disabled` → "所有记忆功能已关闭" / "All memory features disabled"
- `sheet_settings_rag_disabled_hint` → "AI 将仅基于当前对话上下文回答" / "AI will only use current conversation context"

---

## 三、P1 修复项（本迭代处理）

### 3.1 🟡 LocalProtocol 极端值防护

**问题**: 用户在本地模型下设置极端参数可能导致推理异常（如 `repetitionPenalty=2.0` 导致 NaN）。

**文件**: `LocalProtocol.kt:34-40` 或新增 `GenerateConfigValidator.kt`

**方案**: 在构建 `GenerateConfig` 时增加 clamping：

```kotlin
fun clampGenerateConfig(config: GenerateConfig): GenerateConfig {
    return config.copy(
        temperature = config.temperature.coerceIn(0.0f, 2.0f),
        topK = if (config.topK <= 0) 1 else config.topK,
        repeatPenalty = config.repeatPenalty.coerceIn(1.0f, 1.5f)
    )
}
```

### 3.2 🟡 RAG 参数传递完整性审查

**问题**: 审计确认 `ChatViewModel:335-349` 已将全部参数传入 `PromptRequest`，但 `ContextBuilder.buildContext()` 是否正确传递 `ragOptions`（尤其是 `isGlobal` 修饰 `enableMemory` 的组合语义）未经集成测试验证。

**文件**: `ContextBuilder.kt`、`MemoryManagerRagAdapter.kt`

**方案**: 编写集成测试验证以下场景：
- `enableMemory=true, isGlobal=false` → 仅检索当前会话
- `enableMemory=true, isGlobal=true` → 检索跨会话记忆
- `enableMemory=false, isGlobal=true` → 应忽略 isGlobal

---

## 四、P2 优化项（后续迭代）

### 4.1 🟢 思考级别联动扩展

**问题**: 点击思考级别卡片（Minimal/Low/Medium/High）仅修改 `temperature`，用户期望所有参数联动调整。

**文件**: `SessionSettingsSheet.kt:430-439`

**方案**: 预设参数组合：

| Level | Temperature | TopP | TopK | FrequencyPenalty |
|-------|------------|------|------|-----------------|
| Minimal | 0.1 | 0.5 | 10 | 0.0 |
| Low | 0.4 | 0.8 | 30 | 0.0 |
| Medium | 0.7 | 0.9 | 50 | 0.0 |
| High | 1.0 | 1.0 | 100 | 0.0 |

### 4.2 🟢 NexaraCollapsibleSection 小屏优化

**问题**: 在 `< 360dp` 小屏手机上，展开 4 个滑块控件的 300ms 动画可能卡顿。

**文件**: `NexaraCollapsibleSection.kt:45`

**方案**: 根据屏幕宽度动态调整动画时长：
```kotlin
val duration = if (screenWidthDp < 360) 150 else 300
animateContentSize(animationSpec = tween(duration))
```

---

## 五、单元测试补全计划

### 5.1 新增测试文件: `CrossProtocolParamAuditTest.kt`

```kotlin
class CrossProtocolParamAuditTest {

    // P0: 验证 OpenAI 协议补全后包含全部 7 参
    @Test fun `OpenAIProtocol transmits all 7 parameters`()

    // P0: 验证 Anthropic 协议不支持的参数不崩溃
    @Test fun `AnthropicProtocol handles unsupported params gracefully`()

    // P0: 验证 VertexAI 协议补全后包含 repetitionPenalty
    @Test fun `VertexAIProtocol includes repetitionPenalty`()

    // P0: 验证 LocalProtocol GenerateConfig 扩展后包含 penalty 字段
    @Test fun `LocalProtocol GenerateConfig includes frequencyPenalty and presencePenalty`()

    // P1: 极端场景 — 长期记忆启用 + 极高 Frequency Penalty + 上下文管理关闭
    @Test fun `RAG enabled with extreme frequency penalty without context management`()

    // P1: 极端场景 — 本地模型 TopK=1 确定性输出
    @Test fun `LocalProtocol with topK equals 1 produces deterministic config`()

    // P1: 多模态 + 全参数同时承载
    @Test fun `PromptRequest carries images and all advanced sampling params`()
}
```

### 5.2 现有测试增强

| 文件 | 增强内容 |
|------|---------|
| `ProtocolParamTest.kt` | 新增 `testOpenAIProtocol_parametersMapping`（当前缺失 OpenAI 协议测试） |
| `ProtocolParamTest.kt` | 新增 `testLocalProtocol_parametersMapping`（当前缺失 Local 协议测试） |
| `ProtocolParamTest.kt` | `testAnthropicProtocol_parametersMapping` 增加 penalty 参数验证（或显式验证其被跳过） |
| `ProtocolParamTest.kt` | `testVertexAIProtocol_parametersMapping` 增加 `repetitionPenalty` 验证 |

---

## 六、执行顺序

```
第 1 步: OpenAIProtocol 补全 topK + repetitionPenalty  ─┐
第 2 步: VertexAIProtocol 补全 repetitionPenalty        ├── 一起做
第 3 步: AnthropicProtocol 添加不支持参数注释             │
第 4 步: GenerateConfig 扩展 + LocalProtocol 补全        ─┘
第 5 步: 补全单元测试（CrossProtocolParamAuditTest）    ← 验证第 1-4 步
第 6 步: RAG 全局关闭状态反馈 UI                        ← UX 修复
第 7 步: LocalProtocol 极端值防护                        ← 安全加固
第 8 步: P2 优化项（思考级别联动 + 小屏动画）            ← 体验优化
```

---

## 七、DIA 影响

- [x] CHANGELOG.md — 需更新（参数透传修复 + RAG 状态反馈）
- [x] ARCHITECTURE.md — 需更新（GenerateConfig 扩展影响架构图）
- [ ] DATA_SCHEMA.md — 无影响
- [ ] API.md — 无影响（本项目无此文件）
- [ ] DEPLOY.md — 无影响

---

*本文档由 2026-05-15 审计生成，待执行*
