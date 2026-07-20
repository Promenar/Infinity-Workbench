# Nexara 会话完整文档上下文与消息分支实施计划

> **执行要求：** 按任务串行执行 TDD；每项先观察真实 RED，再写最小生产实现。共享热点 `ChatViewModel.kt`、`ChatRoute.kt`、`ChatScreen.kt`、`PipelineBubble.kt` 和导航文件串行修改。

**Goal:** 支持输入栏上传 TXT/MD 作为完整上下文、发送前预算硬门禁、可回传的会话导出，以及从指定稳定消息创建独立分支。

**Architecture:** 复用 `messages.files` 现有 Room 列保存版本化文本文档快照，不新增数据库表或迁移。`FullContextDocumentFormatter` 负责确定性协议边界，`ContextBudgetGate` 在模型路由和最终 Prompt 完成后、Provider 网络请求前执行唯一硬校验；文件选择阶段仅校验格式/编码/大小，避免用会话别名误判容量。`BranchSessionUseCase` 在仓库事务边界复制稳定历史并重置运行态。UI 只管理待发送 URI，发送后完全依赖持久化快照。

## Task 1：附件数据契约与协议内容

- [x] 新增版本化 `MessageDocumentAttachment` 信封及严格 JSON 映射，复用 `MessageEntity.files` 并保留未知旧 payload。
- [x] RED：TXT/MD 完整内容、多个附件顺序、属性转义、空文件和重启映射。
- [x] GREEN：用户协议消息追加完整附件；图片链路保持不变。
- [x] 验证历史消息、重试和后台 Runtime 均从消息快照重建相同内容。

## Task 2：上下文预算门禁

- [x] 新增纯 Kotlin `ContextBudgetGate`，输入最终协议消息、工具定义、图片、模型容量和输出预留。
- [x] RED：恰好可容纳、超限、未知容量、中文/ASCII、多附件、RAG 后最终超限、稳定/远端模型 ID 分离。
- [x] GREEN：Runtime 在最终路由 Prompt 后阻止任何 Provider 请求；文件阶段不以未冻结别名误拒绝。
- [x] UI 给出切换模型、压缩会话、空白会话或知识库的明确恢复建议；不得截断、摘要或转检索。

## Task 3：输入栏文档交互

- [x] 把左侧图片按钮升级为通用添加菜单，提供“图片”和“文本文档”。
- [x] 使用 `OpenMultipleDocuments` 限定 `text/plain`、`text/markdown` 及兼容扩展名，拒绝伪装和不可解码文件。
- [x] 待发送附件显示文件名、大小与移除操作；仅附件也能发送。
- [x] 用户消息气泡显示附件行，不把完整正文重复渲染到气泡。
- [x] 增加中文/英文、大字体、长文件名和多附件截图覆盖。

## Task 4：导出强化

- [x] RED：角色时间、AI 实际模型、附件元数据与完整内容稳定导出。
- [x] GREEN：Markdown/TXT 输出可直接作为文档附件回传，不包含运行态与内部路径。

## Task 5：从消息分支

- [x] 新增 `BranchSessionUseCase` 与事务型仓库入口。
- [x] RED：截至分支点复制、消息 ID/父关联重映射、配置继承、运行态/摘要/工作区清零、不完整工具回合拒绝。
- [x] GREEN：消息菜单加入“从此处分支”，成功后导航到新会话。
- [x] 新会话创建独立空工作区；不复用旧 `workspaceRootUuid` 或物理路径。

## Task 6：审计与验收

- [x] 独立规格符合性复审与代码质量复审；Terra/Sol 最终结论均为 Critical/Important 0。
- [x] 运行定点与全量 JVM、Lint、截图、AndroidTest Kotlin 编译。
- [x] 启动 API 31/35/36 模拟器验证相关 Compose 流程；API 35/36 检查 actual 与当前签名冷启动画面。
- [x] 更新 CHANGELOG、架构/ADR、registry、发行验证账本和 HLG。
- [x] 重建 release APK 并校验 R8、zipalign、签名与 SHA-256。
- [x] 提交并推送当前分支；不自动 tag、PR 或 GitHub Release。

## Validation

- 定点 JVM：76 项，0 failure/error/skip；包含多轮工具重试失败全量回滚的 RED→GREEN。
- 全量 JVM：2021 项，0 failure/error，14 skip；skip 未记为 PASS。
- Screenshot：64/64；新增中英文完整文档、大字体和长文件名 actual 已人工检查。
- Lint：0 Error/Fatal；AndroidTest Kotlin 编译及 Debug/AndroidTest APK 构建通过。
- API 31/35/36：相关 `ChatScreenContentStateTest` 各 8/8；API 35 使用最终修正版重跑，API 35/36 当前签名 APK 冷安装在最终 hash 形成后复验。
- Release APK：18,250,632 bytes，SHA-256 `50ccd9a2906dff4a7b54f6304be253bdbe1ffc6414df70fb33002acfc9558286`；R8、唯一签名者、登记证书、敏感内容、16 KiB zipalign 及 API 35/36 同哈希冷安装通过。
- 真实 Provider：本会话六项 `NEXARA_TEST_LLM_*` 均未注入，未执行且未把历史 PASS 计入当前候选。
