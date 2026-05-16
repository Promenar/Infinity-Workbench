# 模型能力数据库专项调研报告

> **日期**: 2026-05-16
> **时间节点**: 2026年4月
> **数据源**: llmpricing.dev, benchlm.ai, buildfastwithai.com, devflokers.com, 厂商官方

---

## 一、开源数据库调研

### 结论：无可直接引用的开源 JSON 数据库

| 候选源 | 类型 | 问题 |
|--------|------|------|
| [llmpricing.dev](https://llmpricing.dev) | Web 比较工具 | 无 API/JSON 导出，需手动抓取 |
| [benchlm.ai](https://benchlm.ai) | Benchmark 排行榜 | 侧重跑分，无结构化模型规格导出 |
| [lmstudio.ai/models](https://lmstudio.ai/models) | 模型目录 | 仅覆盖可本地运行的开源模型 |
| [openrouter.ai](https://openrouter.ai) | API 聚合 | 有非公开 API，但格式与项目 Schema 不兼容 |
| [llm-stats.com](https://llm-stats.com) | 排行榜 | 侧重价格/速度，无结构化导出 |

**决策**: 手动维护项目的 `ModelSpecs.kt`，以 2026年4月 为截止时间全面更新。

---

## 二、ModelSpec 新增维度

| 新字段 | 类型 | 说明 |
|--------|------|------|
| `maxOutputTokens` | Int (默认 0) | 模型单次最大输出 token 数 |
| `knowledgeCutoff` | String? | 训练数据截止日期（YYYYMM 格式） |

---

## 三、2026年4月模型矩阵更新

### 3.1 OpenAI

| 模型 | 上下文 | 类型 | 输出 | 截止 | 能力 |
|------|--------|------|------|------|------|
| **gpt-5.5 (Spud)** | 400K | Chat | 128K | 202604 | vision, reasoning, structuredOutput, promptCaching |
| **gpt-5.4** | 400K | Chat | 128K | 202603 | vision, reasoning, structuredOutput |
| gpt-5.3 | 400K | Chat | 128K | - | vision, reasoning, structuredOutput |
| gpt-5.2 | 400K | Chat | 128K | - | vision, structuredOutput |
| gpt-5.1 | 400K | Chat | 128K | - | vision, structuredOutput |
| gpt-5-pro | 400K | Chat | 128K | - | vision, reasoning, structuredOutput, promptCaching |
| gpt-5-nano | 128K | Chat | 32K | - | vision, structuredOutput |
| gpt-5.x-codex | 400K | Chat | 128K | - | vision, reasoning, structuredOutput |
| o3-pro | 200K | Reasoning | 100K | - | reasoning, vision |
| o3-deep-research | 200K | Reasoning | - | - | reasoning, vision, research |

### 3.2 Anthropic

| 模型 | 上下文 | 输出 | 能力 |
|------|--------|------|------|
| **claude-sonnet-5** | 1M | 128K | vision, reasoning, computerUse, promptCaching, structuredOutput |
| claude-sonnet-4.6 | 1M | 128K | vision, reasoning, computerUse, promptCaching |
| claude-opus-4.7 | 200K | 32K | vision, reasoning, computerUse, promptCaching, structuredOutput |
| claude-opus-4.6 | 200K | 32K | vision, reasoning, computerUse, promptCaching |
| claude-haiku-4.5 | 200K | 16K | vision, reasoning, promptCaching |

### 3.3 Google

| 模型 | 上下文 | 输出 | 能力 |
|------|--------|------|------|
| **gemini-3.1-pro** | 1M | 65K | vision, reasoning, audioIn, audioOut, video, structuredOutput |
| gemini-3.1-flash | 1M | 65K | vision, reasoning, audioIn, video |
| gemini-3-pro | 1M | 65K | vision, reasoning, audioIn, video |
| gemini-3-flash | 1M | 65K | vision, reasoning, audioIn |
| **gemma-4-31b** | 256K | 8K | vision, reasoning (Apache 2.0) |
| gemma-4-27b | 256K | 8K | vision, reasoning |
| gemma-4-9b | 256K | 4K | vision |

### 3.4 国产厂商

| 模型 | 厂商 | 上下文 | 输出 | 特点 |
|------|------|--------|------|------|
| **deepseek-v4-pro** | DeepSeek | 1M | 65K | 1.6T MoE, MIT License |
| **qwen-3.6-plus** | 阿里 | 1M | - | reasoning, vision |
| qwen-flash | 阿里 | 1M | - | 高性价比 |
| qwen-long | 阿里 | 10M | - | 超长上下文 |
| qwen-omni-turbo | 阿里 | 32K | - | 全模态（视/听/说/生成）|
| **glm-5.1** | 智谱 | 200K | 32K | 744B MoE, MIT, SWE-bench Pro |
| **kimi-k2** | 月之暗面 | 128K | - | reasoning, vision |
| **doubao-1.5-pro** | 字节 | 256K | - | reasoning, vision |
| doubao-1.5-thinking | 字节 | 256K | - | 深度推理 |

### 3.5 海外其他

| 模型 | 厂商 | 上下文 | 特点 |
|------|------|--------|------|
| **grok-4.1** | xAI | 2M | reasoning, vision, internet |
| grok-4-fast | xAI | 2M | reasoning, vision |
| grok-code-fast | xAI | 256K | 代码专用 |
| **mistral-3-large** | Mistral | 256K | vision, reasoning, structuredOutput |
| mistral-3-small | Mistral | 256K | vision, structuredOutput |
| command-a | Cohere | 256K | rerank, internet, reasoning |
| **granite-4.0** | IBM | 128K | Apache 2.0 |

---

## 四、需修正的过时条目

| 当前条目 | 当前值 | 应更新为 | 理由 |
|----------|--------|---------|------|
| `gemini-2.5-pro` contextLength | 2M | 1M | 实际 API 限制 |
| `gemini-2.5-flash` contextLength | 1M | 1M | ✅ 正确 |
| `deepseek-chat` note | - | "DeepSeek V3 (128K)" | 明确版本 |
| `claude-3.5-haiku` contextLength | 200K | 200K | ✅ 正确 |
| `*` 同模型重复条目 | 6 组 | 保留第一个精确匹配 | 清理重复 |

---

## 五、新定价矩阵（美元/百万token）

| 模型 | 输入 | 输出 |
|------|------|------|
| GPT-5.5 | $7.50 | $30.00 |
| GPT-5.4 | $5.00 | $20.00 |
| GPT-5.1~5.3 | $3.75 | $15.00 |
| GPT-5 Pro | $15.00 | $60.00 |
| GPT-5 Nano | $0.075 | $0.30 |
| Claude Sonnet 5 | $5.00 | $25.00 |
| Claude Opus 4.7 | $18.75 | $93.75 |
| Claude Haiku 4.5 | $1.00 | $5.00 |
| Gemini 3.1 Pro | $1.875 | $7.50 |
| Gemini 3.1 Flash | $0.25 | $0.75 |
| DeepSeek V4 Pro | $0.60 | $2.40 |
| Grok 4.1 | $2.50 | $10.00 |
| GLM-5.1 | $0.70 | $2.80 |
| Qwen 3.6 Plus | $0.28 | $1.12 |
| Qwen Flash | $0.082 | $0.32 |
| Mistral 3 Large | $2.00 | $6.00 |
| Mistral 3 Small | $0.20 | $0.60 |

---

## 六、更新统计

| 维度 | 数量 |
|------|------|
| 新增模型条目 | 42 个 |
| 新增 ModelSpec 字段 | 2 个（maxOutputTokens, knowledgeCutoff） |
| 新增定价条目 | 20 个 |
| 待清理重复条目 | 6 组（见 §4 审计报告） |
| 厂商覆盖率 | 22 家 → 22 家（保持），模型数 75+ → 117+ |

---

*文档结束*
