# ADR-019：工作区文件、派生索引与删除恢复采用事务候选切换

> 状态：已实施
> 日期：2026-07-13（2026-07-17 补充目标版本、删除屏障与冷启动策略）
> 范围：Session 工作区、RAG/FTS/KG、分享/SAF 导入、回收站永久删除

## 背景

文件内容、Room 文件记录、向量/FTS、知识图谱、标签关联和向量化任务过去由不同入口分别更新。写入、重索引、永久删除或进程死亡时，可能出现新文件配旧索引、删除后仍可检索、索引失败破坏旧结果，或物理文件已移入 tombstone 但数据库尚未提交等不一致状态。

## 决策

1. 文件写入成功后发布带 `workspaceRootUuid`、文件 UUID 和内容哈希的索引事件；事件入队失败不伪装成文件写入失败，而由 `indexQueued=false` 和启动 stale 扫描恢复。
2. 向量与 KG 候选在数据库事务外构建；事务内再次核对内容哈希，随后原子替换向量、FTS 和 KG。候选构建、哈希竞态或事务写入失败时保留旧可用索引。
3. 重建保留用户标签关联；永久删除才在同一 Room 事务内清理向量/FTS、KG、标签关联、任务和文件记录。
4. 物理删除先移动到以文件 UUID 哈希派生的稳定 tombstone。进程恢复时，数据库记录仍存在则恢复原路径，记录已删除则清理 tombstone；单个损坏 tombstone 和单个工作区失败不得阻断其它恢复或向量队列恢复。
5. Provider、Embedding 或 KG 配置变化时，旧向量队列完成取消并把处理中任务标记为 `interrupted`，唯一新队列随后立即接管；失败、部分完成和中断任务允许通过真实 Room `UPDATE` 重排队。
6. `v0.2-beta` 采用 clean schema，不为旧版缺失 `fileUuid` 的 KG 节点猜测迁移归属；新数据必须完整携带文件作用域。
7. `document_reference` 持久任务使用 `target_content_hash + target_epoch` 标识精确目标；DAO 更新、完成、失败、重试和删除均使用目标 CAS。新目标可主动取消旧 processor，旧 processor 的迟到结果不得覆盖、标记失败或清理新目标。
8. 文件/目录/工作区永久删除在 Room 提交前取得目标级删除屏障：取消并等待 worker，建立 enqueue fence，提交成功后释放且清理 pending；提交失败则撤销 fence 并按当前 FileEntry 重新恢复目标。
9. 文件事务已提交但 Queue 未确认接收时，应用级 pending coordinator 保存精确目标并向 Home、Folder、DocEditor 提供同一重试事实。首次登记使用短小不可取消临界段；成功后保留进程内 latest watermark，永久删除保留进程内 UUID tombstone，避免迟到旧事件重新制造 pending。生产创建入口必须为新文件分配新 UUID，备份恢复通过独立进程重启清空进程内水位。
10. 若进程在文件提交后、Queue 持久化前死亡，启动恢复扫描 `vectorizedAt IS NULL OR updatedAt > vectorizedAt` 的受支持 FileEntry，并以当前 hash、updatedAt 与当前 KG 配置重建 reference task；KG 启用时写入显式 `full` 策略，避免恢复任务因禁用配置回退而漏建图谱。

## 结果

- 重索引不会先破坏旧检索结果。
- 文件、派生数据和永久删除的数据库状态保持原子。
- 应用被杀后可恢复未提交物理删除和中断索引。
- 所有资源管理器、RAG 页面、回收站及自动清理入口复用同一仓储边界。
- 文件已保存与索引已接收成为两个可独立表达、可重试的事实；取消、导航或队列重置不再把已提交文件伪装为失败，也不会静默丢失索引目标。
- 冷启动 missing-scan 能覆盖 Queue 尚未接收的提交窗口，并恢复当前向量与 KG 语义。

## 验证

- 事务索引、工作区删除、Queue 重入/切换、tombstone 恢复等组合测试 80 项通过。
- 全量 JVM：1232 项，0 失败，14 跳过。
- Android 设备：28 项，0 失败，2 个多阶段用例按设计跳过；多阶段备份与恢复另行显式通过。
- Lint 无 Error/Fatal，Debug APK 构建成功。
- 2026-07-17 DocEditor 可靠性检查点：1872 个 JVM 测试，0 failure/error、14 skip；AndroidTest 源码编译与 `lintDebug` 通过。文件型 Room reopen 覆盖无任务、旧/空 `vectorizedAt`、当前 hash/epoch 及 KG 开启/关闭策略；协调器覆盖预取消、删除 tombstone、newer watermark 与重试线性化。
