# ADR-003: 图像生成工具设计

**日期**: 2026-05-14  
**状态**: 已采纳  
**决策者**: AI Agent (全链路审计 + 实现)

---

## 背景

需要为 Nexara 接入图像生成能力，LLM 可通过工具调用生成图片并在对话界面中内联展示。

核心挑战：
1. LLM 聊天模型与图像生成模型可能调用**不同端点**（如 OpenAI 聊天 + Stability AI 生图）
2. 图像生成结果需**持久化**到本地存储（API 返回的 URL 可能有时效）
3. 前端需具备**内联图片渲染**能力（而非仅显示链接）

---

## 决策

### 1. 以 Skill 模式实现（而非内置指令）

**选择**: 实现为 `SkillDefinition`，注册到 `DefaultSkillRegistry`。

**理由**:
- 复用现有 ToolExecutor → LLM 递归生成管道
- LLM 自主决定何时调用（用户说"画一张..."时自动触发）
- 参数通过 JSON Schema 声明，LLM 自然理解
- 与其他 Skill（Calculator、WebSearch）架构一致

**替代方案**: 内置到 ChatViewModel 的生成管道中 → 拒绝，与工具统一架构冲突。

### 2. 图像模型独立于聊天模型选择

**选择**: 图像模型通过 `ProviderManager.preset_image_model` 独立选择，API 端点复用主 LLM Provider 的 `base_url`。

**理由**:
- 用户可能使用 OpenAI 聊天 + LocalAI 图像生成，或反之
- 但大多数兼容 API 共用同一 `base_url`（如 `https://api.openai.com`）
- 端点路径不同：LLM → `/v1/chat/completions`，Image → `/v1/images/generations`

**替代方案**: 独立配置图像生成的 `image_base_url`/`image_api_key` → 拒绝，过度复杂化，用户配置负担重。

### 3. 图片下载到本地存储

**选择**: 生成后立即从 URL 下载或从 b64_json 解码，保存到 `app/files/generated_images/`。

**理由**:
- API 返回的 URL 可能有时效（通常 1 小时）
- 用户回看历史对话时需要图片仍可见
- 文件管理简单（应用卸载时自动清理）

**替代方案**: 仅存储 URL → 拒绝，时效性问题。

### 4. 图片通过 `Message.images` 字段传递

**选择**: ToolResult.data → Message.images（JSON 序列化的 `List<GeneratedImageData>`）。

**理由**:
- `Message.images` 字段已存在于数据模型（预设计）
- ChatBubble 可直接解析渲染
- 与 Message 生命周期一致（持久化到 Room DB）

### 5. ImageGenClient 按需创建

**选择**: 每次 Skill 执行时创建新的 `ImageGenClient` 实例。

**理由**:
- 图像生成请求频率低（非高频调用）
- 模型/端点可能运行时切换（ProviderManager 实时读取）
- 避免长时间持有 HTTP 连接池

**替代方案**: 在 NexaraApplication 中创建全局单例 → 拒绝，配置变更时无法自动刷新。

---

## 影响

- **新增 3 个文件**: `ImageGenClient.kt`, `ImageGenerationSkill.kt`, `GeneratedImageData`
- **修改 4 个文件**: `NexaraApplication.kt`（注册），`ChatScreen.kt`（渲染），`ToolExecutor.kt`（数据传递），`ARCHITECTURE.md`（文档）
- **无数据库迁移**: 复用现有 `Message.images` 字段
- **无 API 变更**: 使用标准 OpenAI Images API 格式

---

## 技术栈

| 组件 | 技术 |
|---|---|
| HTTP 客户端 | Ktor + OkHttp Engine |
| 图片加载 | Coil 3.x (`AsyncImage`) |
| 序列化 | kotlinx.serialization |
| 存储 | 应用内部 files 目录 |
