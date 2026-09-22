# 独立模型目录与身份匹配规范

## 目标与授权

模型管理从独立发布的公开目录获取模型信息，保留供应商元数据和用户编辑。用户已批准 GitHub Actions + Pages 免费托管方案并要求开始实施；目录发布属于该授权，APK、tag、GitHub Release 不在范围内。Android 构建、测试和模拟器验证在本机执行。当前工作分支为 `B-native-refactor`，已有 `artifacts/` 不纳入提交。

## 数据与身份

- 原始 `remoteModelId` 全程逐字符保留；内部稳定 ID 与实际请求 ID 不得混用。规范 ID、供应商销售条目和网关路由 ID 分开。
- 目录由 models.dev canonical、models.dev provider、LiteLLM 和 OpenRouter 公开数据构建。保留来源 URL、下载时间、原文 SHA-256、许可与作用域；不同供应商的额度和能力不得合并成基础模型事实。
- 规范模型按精确 ID/别名匹配；供应商条目仅在明确供应商作用域下参与。`newapi/` 与 `openai-chatgpt/` 路由前缀仅在模型列表 `owned_by` 对应 NEWAPI/OpenAI ChatGPT 时允许拆解为查找候选，发送请求仍使用完整 ID。未知前缀、同名冲突和版本后缀不做猜测。
- 缺失字段保持未知；明确 false 表示不支持。优先级为用户编辑、实际供应商元数据、明确匹配作用域的供应商目录、规范模型维护覆盖与公共规范记录、保守回退。上下文额度、最大输入和最大输出分开。
- 精确官方补充映射可以引用官方资料；不得通过相似名称生成能力。目录记录中的来源不等于运行能力验证。

## 发布契约（v1 传输，v3 数据）

发布路径为 `https://promenar.github.io/Nexara/model-catalog/v1/`。`manifest.json` 是签名信封：`keyId`、`payload`（原始 UTF-8 JSON 字节的 Base64）、`signature`（SHA256withECDSA / P-256 / DER 的 Base64）。客户端固定公钥，不接受远端公钥。

签名 payload 字段：`schemaVersion:1`、`catalogVersion`（UTC Unix 秒，正整数）、`generatedAt`（UTC ISO8601）、`catalogFile`（`catalog-<64位小写sha256>.json`）、`catalogBytes`、`catalogSha256`、`recordCount`。只允许同一固定 HTTPS 目录内的内容文件；不跟随重定向。manifest 最大 64 KiB，目录最大 16 MiB，记录最大 30000。

目录顶层：`schemaVersion:3`、`generatedAt`、`sources`、`records`。每个 source 包含 `id`、`url`、`fetchedAt`、`sha256`、`license`。每个 record 必含 `canonicalModelId`（稳定记录键）、`displayName`、`source`（MODELS_DEV/LITELLM/OPENROUTER/NEXARA_OVERRIDE）；可选 `providerScope`（非空时是供应商条目，不自动作为基础模型身份）、`exactAliases`、`family`、`workload`（ModelWorkload 枚举名）、`reasoning`、`tool_call`、`structured_output`、`contextTokens`、`inputTokens`、`outputTokens`、`knowledge`、`modalities`、`release_date`、`last_updated`、`status`。未知字段可忽略；已知字段类型错误、同一 (source, providerScope, canonicalModelId) 的重复记录身份或越界额度拒绝整个候选。

canonical 源保留规范模型；provider 源保留供应商作用域；LiteLLM/OpenRouter 默认也是作用域条目。明确人工别名作为受版本控制的映射，不跨供应商自动取最大额度。多源抓取任一必需源失败时不发布残缺目录，继续提供最后有效发布。

## 更新与存储

- 云端每 6 小时运行，支持手动触发；通过校验后签名并以 Pages 原子部署。工作流仅发布目录与说明文件，不发布仓库、密钥或 Android 产物。
- App 启动恢复完成后在 IO 线程检查，成功检查间隔 24 小时；模型管理提供手动更新。无需新增常驻后台服务；关闭 App 期间不承诺准点检查。
- 安装包保留可用快照；启动先加载本地验签缓存，失败回退内置快照。下载、验签、hash、schema、数量和单调版本验证全部通过后才切换解析器与缓存。旧版回放、相同版本不同内容均拒绝；网络或文件错误保持现用目录。指针损坏或丢失时从已验签信封恢复高水位；没有可信恢复依据时失败关闭。缓存是持久提交点，后续模型投影失败单独显示“已缓存、应用失败”，下次重试从已验签缓存重新应用。
- 缓存使用版本文件与原子指针，保留最后有效版本与版本高水位；未完成事务不得成为活动目录。缓存不进入用户备份。目录刷新对已有模型重新解析，并保留用户编辑、启用状态、测试结果、稳定 ID 与供应商原始字段。
- UI 显示有效目录来源/时间、更新中与失败状态，不把抓取时间或配置声明当成推理验证通过。

## 验收

Python 离线 fixtures 覆盖四源、缺失与 false、供应商作用域、畸形/重复/巨大输入、稳定输出、签名及拒绝残缺发布。Kotlin 覆盖精确匹配与冲突、原始 ID 不变、丰富模型字段、用户编辑保留、持久化回读、签名篡改、hash/大小/schema错误、回放、磁盘失败、并发与离线回退。针对当前五个网关 ID 验证身份和未知边界，不要求通过付费推理证明目录字段。

主控串行运行针对性 JVM、必要全量回归、Lint、构建和本机 Android 模拟器测试；独立审阅者检查网络信任边界、缓存原子性、身份作用域与覆盖保留。Pages 发布后读取公网信封、验签及哈希，并验证客户端真实 HTTPS 更新。
