# ADR-020：分层模型元数据注册中心

> 状态：已实施；当前候选真实 Provider 复验待完成
> 日期：2026-07-18
> 范围：模型目录、Provider 模型持久化、首次引导探测、会话模型名称与 AI 消息尾注

## 背景

原有 `ModelSpecs` 同时承担模型身份、名称、类型与能力推断，并使用部分字符串匹配。系列规则可能覆盖精确型号，unknown 会被默认成 chat，聊天探测还会改写模型主要类型。Provider 模型页、会话输入区与历史消息各自生成名称，导致同一远端 ID 显示不一致。手写表也缺少可复现的广覆盖更新来源。

## 决策

1. 唯一领域入口为 `ModelMetadataResolver.resolve(...)`，不提供第二个顶层 facade。调用者传入稳定远端模型 ID、可选 Provider 精确元数据和用户覆盖。
2. 解析采用逐字段优先级：`USER > PROVIDER > NEXARA_OVERRIDE > MODELS_DEV > FAMILY > FALLBACK`。其中 `FAMILY` 只补充家族展示信息；缺失字段保持 unknown 或原始远端 ID，不从家族猜测精确能力。
3. 目录匹配只接受规范化后的精确 canonical ID 或精确 alias。最高优先级出现冲突时返回诊断并回退，不使用 `contains()` 猜测身份。
4. 工作负载、推理能力和 Chat endpoint 兼容性是三个独立事实。能力使用 `SUPPORTED / UNSUPPORTED / UNKNOWN` 三态，未知不等于不支持，也不等于 chat。
5. APK 运行时不联网读取第三方目录。维护脚本把固定 `https://models.dev/models.json` 规范化为仓库内快照；manifest 记录来源、许可、源字节与 SHA-256、目录 SHA-256、条数、schema 和未知字段数。Nexara 精确修正覆盖目录中的已知错误或缺口。
6. Provider 持久化保存 family、canonical ID、Chat endpoint 三态、自动指纹和用户编辑字段。自动刷新只更新未被用户编辑的字段；旧数据仅在可证明仍是旧自动值时机械迁移。
7. 首次引导的真实聊天探测只更新 Chat endpoint 兼容性，不改变主要工作负载或补写 chat capability。会话输入区和 AI 尾注共享同一纯函数名称解析，历史消息优先使用已持久化名称。
8. AI 尾注把模型名与时间作为左侧弱化信息组；用户消息时间继续右对齐。长名称允许收缩，但不得用填充权重吞掉时间。

## 供应链与回滚

- 刷新流程只创建或更新 draft PR，不自动 merge；第三方 action 固定到完整 commit SHA，产品 API Key 不进入 workflow。
- 更新前后生成差异报告并运行 Python、离线清单和 Kotlin 目录门禁。重复 JSON key、alias 冲突、排序或哈希漂移均 fail-closed。
- 快照与 manifest 使用同一事务替换；任一 replace/fsync 失败时尝试同时回滚两者并聚合回滚错误。
- 上游异常时继续使用上一个已审阅快照。回滚只需恢复快照、manifest 与对应精确修正，不需要改动 Provider 或会话稳定 ID。

## 结果

- 精确型号不再被系列泛称覆盖，unknown 不再默认 chat。
- 用户覆盖、Provider 元数据、Nexara 修正和公共目录能够按字段共存，来源可追踪。
- 目录更新可离线复现、审阅和回滚，APK 不增加第三方运行时依赖。
- 当前本地 JVM、Lint、截图及 API 35/36 聚焦设备门禁已通过；由于当前 shell 缺少真实 Provider 的六项环境变量，本候选的网络复验仍为 PENDING，整体发行保持 NO-GO。

## 验证

- Task 1-9 均完成独立 Terra/Sol 复审；Task 9 最终限定 diff SHA-256 为 `4414db3077a0d004f22920c25d681f72a1d7d70b65e25f74f1968d8149189020`。
- Python 更新脚本 64/64、离线 snapshot/manifest check、Kotlin catalog 26/26 通过。
- 当前工作树定点模型 JVM 89/89；全量 JVM 1945 项，0 failure/error、14 skip；Lint 0 Error/Fatal；Screenshot 61/61；AndroidTest Kotlin 编译通过。
- API 35 与 API 36 聚焦设备矩阵各 31 项，均 0 failure/error、1 个设计内 force-stop checkpoint skip。
- 真实 Provider 当前候选复验未执行，原因是六项 `NEXARA_TEST_LLM_*` 环境变量均未提供；默认测试跳过不能作为网络证据。
