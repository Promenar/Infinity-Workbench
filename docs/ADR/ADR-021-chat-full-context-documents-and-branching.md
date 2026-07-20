# ADR-021：会话完整文档上下文与消息分支

> 状态：已实施
> 日期：2026-07-20
> 范围：聊天附件、Prompt 预算、重试、会话导出与分支

## 背景

知识库文档适合按相关度检索，但用户从输入栏明确上传 TXT/Markdown 时，语义是要求模型完整阅读。把两者混用会造成内容缺失、不可解释的召回差异，也无法把会话导出稳定地重新送回模型。旧重试流程还会在新请求成功前删除上一条 AI 回复，异步失败可能造成数据丢失。

## 决策

1. 输入栏 TXT/Markdown 保存为版本化 `MessageDocumentAttachment` 快照，复用 `messages.files`，不新增 Room schema。正文、MIME、原始字节数和 SHA-256 随用户消息持久化；未知旧 payload 原样保留。
2. `FullContextDocumentFormatter` 用 SHA-256 绑定的确定性边界把文档追加到用户协议内容。附件属于不可信用户资料，不进入系统提示词；纯文本与多模态 Provider 共享同一正文。
3. 文件选择阶段只校验类型、扩展名、大小、二进制特征和 UTF-8/UTF-16 BOM。容量硬门禁只在 Provider 路由和最终 Prompt 完成后执行，覆盖系统提示、活动历史、文档、图片、工具和输出预留，并在任何网络请求前结束。
4. 容量覆盖用 provider-scoped 稳定模型 ID 读取用户设置；目录元数据用最终 `remoteModelId` 解析。未知容量和超限均 fail-closed，不自动截断、摘要或转成 RAG。
5. 重试时旧 AI 回复继续保存在数据库，但 `GenerationRequest.assistantMessageIdToReplace` 要求协议构建排除它。新回复成功持久化后才删除旧回复；Busy、准备、路由、预算、流式、取消或持久化失败按生成前消息基线清理本轮新增的全部 Assistant、工具结果与占位，并保留旧回复。
6. 文本、图片和文档草稿统一在 `GenerationPresentationState.promptAccepted` 后消费。失败或未进入 Provider 边界时保留草稿；错误清理后无活动任务的输入栏恢复可发送状态。
7. Markdown/TXT 导出是人类可读、可重新上传的稳定文本，不是数据库恢复格式。分支只复制到指定稳定消息的历史，重建消息 ID/父引用，验证完整工具链，并清空摘要、任务、审批、向量派生状态和旧工作区身份。

## 结果与边界

- 输入栏完整文档与知识库检索语义彻底分离。
- 重启、重试、后台生成、导出和分支都依赖持久化快照，不再次读取 `content://` URI。
- 首轮仅支持 TXT/Markdown；PDF、DOCX、网页、音视频和 Provider Files API 仍是非目标。
- 分支不复制工作区文件、知识库索引、向量或知识图谱派生物。

## 验证

- 定点 JVM 76 项；全量 JVM 2021 项，0 failure/error、14 skip。
- Screenshot 64/64，Lint 0 Error/Fatal，AndroidTest Kotlin 编译通过。
- API 31/35/36 相关 Compose 测试各 8/8；API 35/36 当前签名 APK 完成冷安装、字节回读、前台启动和 crash/ANR 观察。
- Terra/Sol 独立复审的 Critical/Important 在提交前全部关闭；Sol 指出的多轮工具重试残留已先补 RED，再以消息基线统一回滚后 GREEN。
- 本会话未注入六项真实 Provider 环境变量，因此没有把历史 Provider PASS 记为本候选复验；整体发行保持 NO-GO。
