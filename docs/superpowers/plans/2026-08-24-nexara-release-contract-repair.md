# Nexara Release Contract Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Every behavior change follows `superpowers:test-driven-development`; final claims follow `superpowers:verification-before-completion`.

**Goal:** 修复 Nexara 当前发行阻断级的工具调用、Provider 端点、工作区事务和产品端点缺口，在不改变底部三按钮导航与已确认 MD3/SE 视觉基线的前提下，交付可继承现有数据和签名身份的 Release APK。

**Architecture:** 先冻结共享 DTO、风险策略、端点解析和 Room v3 前进迁移，再串行收口会话安全、协议两轮 Tool Call、参数/账本、Skill/MCP、工作区删除与恢复、文件生命周期，最后接入备份/RAG/KG/错误态产品表面。副作用边界全部 fail-closed；所有恢复操作以持久化 journal、稳定调用身份和事务 CAS 为事实源。

**Tech Stack:** Kotlin 2.2、Jetpack Compose、Room、Kotlin Coroutines/Flow、Ktor MockEngine、kotlinx.serialization、JUnit4/JUnit5、Compose UI Test、Compose Preview Screenshot Test、Gradle Android Plugin、Android SDK build-tools。

**Spec:** `docs/superpowers/specs/2026-08-24-nexara-release-contract-repair.md`

## Global Constraints

- 唯一施工目录为当前 checkout `/Users/promenar/Codex/Nexara`，当前分支 `B-native-refactor`；不创建临时 worktree。
- 所有 Agent 都不是仓库中唯一施工者：不得 reset、checkout 覆盖、revert 他人提交或回滚不属于本 Task 的改动；遇到重叠必须适配当前 HEAD。
- 禁止读取或回显 `secure_env/`、keystore、`secure.properties`、环境秘密值；Release 签名只使用现有安全注入。
- 禁止目录级 `git add`。每个 Task 只逐路径暂存本 Task 所有权清单，检查 `git diff --cached --name-only` 后提交。
- 底部三按钮导航及其视觉测试为冻结区：禁止修改 `MainTabScaffold.kt` 中导航实现、导航动画/token 和对应 golden；若测试要求变更该区域，停止并上报。
- 设置、RAG、KG 新 UI 只复用现有 Material 3/SE 视觉原语；不引入新 UI 库、不恢复 Glass/逐项卡片。
- 新增或变更数据转换、状态流转、网络协议、权限、持久化和工具逻辑必须先写测试，运行并记录预期 RED，再做最小实现并运行 GREEN。
- 远程 Provider 测试只用 MockEngine/权威 fixture；不得调用真实或付费模型 API。
- Room Schema 按已验证的数据合同串行演进：Task 1 建立 v3，Task 2 为精确审批/会话选择建立 v4，Task 6 为可重启恢复的 MCP discovery 派生快照建立 v5。既有 `1.json`～`4.json` 必须保持冻结；每次升版都要补前进 migration、schema、迁移设备测试和备份派生数据清理测试，其他 Task 不得自行升版本。
- `NexaraApplication.kt`、`ChatModels.kt`、`LlmProtocol.kt`、`RagViewModel.kt`、strings、README/CHANGELOG/PRD/发行账本均为共享热点，按任务顺序串行。
- 每个实现 Task 完成后先由施工 Agent 自检，再由未参与该 Task 的独立审阅 Agent 检查规格符合性、数据安全、测试质量和 diff；Critical/Important 全部关闭后才能进入下一 Task。
- 每个 Task 提交前运行 `git diff --check`；构建产物、APK、报告临时文件、签名材料和 `.nexara-workspace-*` 不纳入 Git。
- 不创建 tag、PR、GitHub Release，不强推；全部验证完成后由主控按项目规则推送当前分支。
- Gradle 命令默认 cwd 为 `/Users/promenar/Codex/Nexara/native-ui`，Git/文档/HLG 命令默认 cwd 为仓库根。

## Preflight

- [ ] 断言 checkout、分支、干净暂存区和受保护路径。
- [ ] 记录当前 HEAD 与现有未提交文件；后续仅处理本计划文件。
- [ ] 运行受影响模块的施工前 targeted tests，确认当前缺口由测试 RED 证明，而非只靠源码推断。
- [ ] 建立 SDD progress ledger；每个 Task 记录 start ref、owned files、RED/GREEN 命令、commit、review 结论。

---

## Task 1：冻结共享安全合同与 Room v3 Schema

**Dependencies:** none. This task exclusively owns all Room version/migration/schema aggregation.

**Files:**

- Create: `native-ui/app/src/main/java/com/promenar/nexara/domain/tool/ToolExecutionPolicy.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/domain/tool/ToolArgumentsValidator.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/ProviderEndpointResolver.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/entity/WorkspaceMutationEntity.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/dao/WorkspaceMutationDao.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/model/ChatModels.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/domain/model/Agent.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/entity/AgentEntity.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/entity/SessionEntity.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/entity/ToolExecutionLedgerEntity.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/dao/ToolExecutionLedgerDao.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/dao/FileVersionDao.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/dao/FileEntryDao.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/NexaraDatabase.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/NexaraDatabaseMigrations.kt`
- Modify: Room backup schema inventory and `native-ui/app/schemas/**/3.json`
- Test: new codec/policy/validator/endpoint tests and migration/inventory tests.

**Interfaces:**

- `ExecutionModeCodec.parseOrSemi(raw)` and `serialize(mode)`.
- `ToolRisk` and `ToolExecutionPolicy.requiresApproval`.
- `ToolArgumentsValidator` returns canonical `JsonObject`, SHA-256 or stable validation error.
- `ProviderEndpointResolver.resolve(protocol, configuredBaseUrl, operation)`.
- `WorkspaceMutationEntity` with versioned payload and PREPARED / DB_COMMITTED states.
- Tool ledger identity columns: runtime tool id, arguments digest, definition digest.
- AgentEntity persists execution mode; Session defaults are `semi`.
- FileVersion DAO supports subtree/file/root batch reads and deletes.

- [ ] **Step 1 — RED:** add tests for null/empty/unknown mode → SEMI; nested JSON type preservation; canonical object key order; host/`/v1`/full path/provider-prefix endpoints; ledger identity column schema; migration 2→3 preserves existing data; malformed journal payload is rejected.

- [ ] **Step 2 — Verify RED:** run:

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ExecutionModeCodecTest' \
    --tests '*ToolExecutionPolicyTest' \
    --tests '*ToolArgumentsValidatorTest' \
    --tests '*ProviderEndpointResolverTest' \
    --tests '*RoomBackupDataSourceTest' \
    --no-build-cache --rerun-tasks
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

  Expected: tests fail because the new contracts/schema do not exist or current defaults/path resolution are unsafe.

- [ ] **Step 3 — GREEN:** implement DTOs/DAO/migration with no runtime wiring. Existing v2 rows migrate safely; historical `auto` rows remain values, but every new/missing value becomes `semi`. Unknown journal versions fail closed.

- [ ] **Step 4 — Verify GREEN:** rerun targeted tests, migration compile/test on an available emulator, then existing DAO/mapper/backup tests.

- [ ] **Step 5 — Commit:** `feat: establish release safety contracts and room v3 schema`.

---

## Task 2：安全会话默认值与精确审批队列

**Dependencies:** Task 1.

**Files:**

- Modify: `ChatViewModel.kt`, `SessionListViewModel.kt`, `SessionManager.kt`
- Modify: `DefaultChatGenerationRuntime.kt`, `ChatGenerationContentStrategy.kt`
- Modify: `ApprovalManager.kt`, `ToolExecutionLedgerRepository.kt`
- Modify: `SessionSettingsSheet.kt`, `ChatScreen.kt`, approval inline components
- Modify: `AgentMapper.kt` and affected repository mappers
- Modify: strings and targeted preview/UI tests only; bottom navigation frozen.

**Behavior:**

- Both creation paths inherit Agent mode and selected Skill/MCP IDs; invalid/missing mode is SEMI.
- Session settings provides a real persisted three-mode selector.
- ApprovalRequest carries assistant message id and a complete ordered call identity list; legacy fields decode only.
- UI shows every waiting call name, args summary and risk.
- Approve/reject acts on the exact visible identity; double submit and stale hash/assistant id conflict without execution.
- `semi` uses risk metadata; unknown risk requires approval.

- [ ] **Step 1 — RED:** extend `ChatViewModelTest`, `SessionListViewModelTest`, `SessionManagerTest`, `ApprovalManagerTest` and approval UI contract tests for inheritance, fallback, persistence, multiple calls, stale decision and double approval.
- [ ] **Step 2 — Verify RED:** run targeted classes with `--no-build-cache --rerun-tasks`; record failures.
- [ ] **Step 3 — GREEN:** wire mode codec/policy and group approval CAS; do not infer approval calls from message body.
- [ ] **Step 4 — Verify GREEN:** targeted JVM tests + screenshot test for the approval card + AndroidTest compile.
- [ ] **Step 5 — Commit:** `fix: make session tool approval explicit and fail closed`.

---

## Task 3：唯一 Provider 端点、Vertex 凭据与能力真值

**Dependencies:** Task 1. Must finish before Task 4.

**Files:**

- Create: `VertexCredentialParser.kt`
- Create: `data/remote/provider/ProviderConnectionProbe.kt`
- Modify: `LlmProtocol.kt`, `ProtocolFactory.kt`, `LlmProvider.kt`, `ProviderRequestRouter.kt`
- Modify: `OpenAIProtocol.kt`, `OpenAIResponsesProtocol.kt`, `AnthropicProtocol.kt`, `GenericOpenAICompatProtocol.kt`, `VertexAIProtocol.kt`
- Modify: `ProviderFormScreen.kt`, `SettingsViewModel.kt`, Provider presets/strings/tests.

**Behavior:**

- All generation/list/probe code consumes `ProviderEndpointResolver`; protocol classes do not append hard-coded endpoint paths.
- Presets resolve to one exact wire URL; Generic ambiguous paths are rejected.
- Vertex uses serviceAccountJson/project/location, unified credential validation and OAuth-only connection check.
- Vertex generation accepts only the canonical Google origin for the selected location, and standard service-account PEM trailing newlines remain valid.
- Anthropic probe does not treat an empty model-list implementation as connection failure.
- Cohere is removed from advertised presets unless its native v2 adapter and golden tests are implemented in this task.
- Unsupported transports/protocol claims disappear from UI rather than remain disabled.
- Generic host-only addresses are rejected by both resolver and form; editing a legacy unsupported provider never silently rewrites its protocol.

- [ ] **Step 1 — RED:** golden endpoint tests for every preset; Vertex parser/probe tests; Provider form mapping tests; Anthropic validation test; unsupported Cohere visibility contract.
- [ ] **Step 2 — Verify RED:** run endpoint, provider router, form/state JVM tests and AndroidTest compile.
- [ ] **Step 3 — GREEN:** route all operations through resolver and probe; keep configured URL unchanged at rest.
- [ ] **Step 4 — Verify GREEN:** targeted tests, `compileDebugAndroidTestKotlin`, provider settings screenshots.
- [ ] **Step 5 — Commit:** `fix: align provider presets credentials and wire endpoints`.

---

## Task 4：Provider 两轮 Tool Call 与流式失败关闭

**Dependencies:** Tasks 1 and 3.

**Files:**

- Modify: `LlmProtocol.kt`, generation models, `UnifiedLlmClient.kt`
- Modify: `OpenAIProtocol.kt`, `OpenAIResponsesProtocol.kt`, `AnthropicProtocol.kt`, `VertexAIProtocol.kt`, Generic adapter
- Modify: `ChatGenerationRunner.kt`, `DefaultChatGenerationRuntime.kt`, `ChatGenerationContentStrategy.kt`
- Modify: message/protocol mappers only as required.

**Behavior:**

- Remote protocols emit exactly one explicit successful `Completed(END_TURN|TOOL_CALLS, completedToolCallIds)` or structured Error; duplicate terminal, unknown finish/stop reason, Error/cancel/truncated/EOF never executes tools.
- Runner accepts tools only for `TOOL_CALLS`; validates nonempty unique completed IDs exactly match assembled calls, JSON object args, known tool snapshot and the 10-call limit before runtime approval/execution. `END_TURN` must carry no executable tools.
- Sync responses enforce the same finish/stop/status and tool-shape rules as streams; every non-success path clears unconfirmed persisted tool calls before marking the message terminal.
- Responses normalizes both argument-delta events and complete output-item-only calls into one exact unified call without duplication.
- Responses emits/consumes function call and function_call_output with same call_id.
- Anthropic emits tool_use and user/tool_result blocks.
- Vertex keeps per-call thought signature only where an existing authoritative fixture confirms shape; otherwise tools remain capability-gated off for Vertex.
- Fallback sniffer may run only after successful STOP and cannot create executable/approvable calls from failed content.

- [x] **Step 1 — RED:** add MockEngine two-turn golden tests for Chat Completions, Responses and Anthropic; add Vertex contract/capability gate tests; replace the existing “Failure 后执行工具” expectation with zero execution.
- [x] **Step 2 — Verify RED:** run `*ToolRoundTripTest`, protocol tests, `ChatGenerationRunnerTest`, `DefaultChatGenerationRuntimeTest`.
- [x] **Step 3 — GREEN:** implement explicit completion and provider codecs; preserve multiple call ordering/IDs.
- [x] **Step 4 — Verify GREEN:** targeted suites plus all protocol/error classification tests.
- [x] **Step 5 — Commit:** `fix: close provider tool round trips and failed streams`.

---

## Task 5：类型化工具参数、幂等身份与取消传播

**Dependencies:** Tasks 1, 2 and 4.

**Files:**

- Modify: `SkillRegistry.kt`, `ToolExecutor.kt`, `ToolExecutionLedgerRepository.kt`
- Modify: all built-in skill argument adapters, especially `UpdatePlanSkill.kt`
- Modify: generation/runtime execution call sites and tests.

**Behavior:**

- Skill execution receives `JsonObject`; legacy maps are converted only at a narrow compatibility adapter if required.
- Invalid/non-object/schema-mismatched arguments produce one deterministic failed Tool result without calling the Skill.
- `update_plan` consumes a real JsonArray/objects.
- Register/claim compares runtimeToolId + canonical args hash + definition hash; mismatches are CONFLICT.
- `CancellationException` is rethrown; recovered RUNNING becomes unknown/failed outcome and is never replayed.
- 已注册 identity 后出现名称、参数或审批属性失配时，以原完整 identity 做 CAS，只允许 `PENDING_APPROVAL/APPROVED -> FAILED`，并生成一次脱敏失败终态。
- 重启恢复只接受数据库中完整且逐项一致的原始审批请求；缺失或损坏时整组失败关闭，不得降级成 `UNKNOWN` 风险重建。

- [x] **Step 1 — RED:** nested update_plan args; malformed JSON; same key different payload/schema/name; concurrent claim; cancellation/recovery tests.
- [x] **Step 2 — Verify RED:** run `ToolExecutorTest`, `ToolExecutionLedgerRepositoryTest`, all built-in skill tests.
- [x] **Step 3 — GREEN:** implement validator/identity at register and execute-time recheck.
- [x] **Step 4 — Verify GREEN:** targeted tests plus tool/generation module suite.
- [x] **Step 5 — Commit:** `fix: preserve tool arguments and invocation identity`，并以 `fix: fail closed on tool identity replay` 收口独立审阅发现。

---

## Task 6：会话级 Skill/MCP 真值与扩展工具安全

**Dependencies:** Task 5.

**Files:**

- Modify: `DefaultSkillRegistry.kt`, `ModularSkillRegistry.kt`, `UserSkillRegistry.kt`
- Modify: `McpSkillRegistry.kt`, `McpSkill.kt`, `McpClient.kt`
- Modify: `ChatGenerationContentStrategy.kt` and session settings Skill/MCP UI
- Create: `McpToolSnapshotEntity.kt` and Room schema `5.json`
- Modify: Skill/MCP repository/DAO, `NexaraDatabase.kt`, migrations and Room backup derived-data cleanup
- Modify: `SkillsScreen.kt` and related strings/tests.

**Behavior:**

- Resolved set = global enabled built-ins + enabled/active custom + enabled/active/synced MCP, gated by session toolsEnabled.
- Execute time rechecks active/enabled and definition digest.
- MCP aliases are stable and server-qualified; sync atomically replaces one server and removes stale definitions.
- Process restart restores persisted discovery from Room v5; disabled/deleted/URL-changed server cannot resolve, and late sync results cannot resurrect it.
- `mcp_tool_snapshots` is a derived cache: backup exports only MCP server source configuration, restore clears snapshots and requires a new successful sync before advertising tools.
- Transport support is exactly MCP `2026-07-28` modern Streamable HTTP over HTTPS: protocol metadata and headers are mandatory; no legacy initialize/session fallback is claimed.
- Discovery consumes every `tools/list.nextCursor` page before one atomic replace and fails closed on HTTP/RPC/id/schema/name/duplicate/cursor errors.
- `x-mcp-header` primitive parameter mapping is implemented; `isError` and unsupported `input_required` become typed failures rather than successful Tool results.
- Settings permits only new HTTPS HTTP servers. Legacy cleartext HTTP/STDIO records remain visible for delete/migration but cannot sync, advertise or execute; release Network Security Config remains cleartext-denying.
- Custom database/script Skills without sandbox are editable but never advertised/executed and never return fake success.
- Prompt 与执行必须共用唯一 `SessionToolResolver`；每次副作用前重读当前 Session 并重新校验全局/会话授权集合与 definition digest。
- Registry 的同步读取只能访问生命周期内维护的不可变内存快照，不得在 Main 调用链使用 `runBlocking`；同步提交以配置 revision、每服务器 generation 和串行提交锁阻止 ABA 与逆序覆盖。
- `_meta` 使用 `io.modelcontextprotocol/` 命名空间键，`Accept` 明确同时接受 JSON 与 event stream。

- [x] **Step 1 — RED:** master off, session intersection, server disable after prompt, duplicate remote names, stale removal, restart restore, custom unsandboxed, protocol headers, pagination, `x-mcp-header`, `isError`, `input_required` and HTTPS-only tests.
- [x] **Step 2 — Verify RED:** run registry/MCP/content strategy tests.
- [x] **Step 3 — GREEN:** implement resolved snapshot and atomic discovery.
- [x] **Step 4 — Verify GREEN:** targeted tests, settings screenshots and AndroidTest compile.
- [x] **Step 5 — Commit:** `fix: enforce session scoped skill and mcp availability`，并以执行时重验、同步代际和 namespaced metadata 的独立审阅修复提交收口。

---

## Task 7：持久化工作区 Journal 与会话删除事务

**Dependencies:** Task 1 schema; Tasks 2/5 execution identities and cancellation.

**Files:**

- Create: `domain/session/SessionExecutionGate.kt`
- Create: `domain/usecase/DeleteSessionUseCase.kt`
- Create: `data/repository/WorkspaceMutationRecoveryCoordinator.kt`
- Create: `data/repository/SessionDeletionTransaction.kt`
- Modify: generation coordinator/runtime cancellation plumbing, `ToolExecutor.kt`
- Modify: `SessionRepository.kt`, `SessionManager.kt`, `ChatViewModel.kt`, `SessionListViewModel.kt`
- Modify: `WorkspaceRepository.kt`, `NexaraApplication.kt`, indexing/barrier collaborators.

**Behavior:**

- One use case owns Chat and Hub deletion.
- Gate blocks new generation/tools, cancel+joins active jobs, then stages session root, deletes all DB/index/version/ledger/session data in one Room transaction.
- DB failure restores physical root and reopens gate; DB success never resurrects data, and tombstone cleanup can retry.
- Duplicate delete is idempotent; shared/wrong/out-of-root workspace identity fails closed.
- Startup recovery runs workspace journal before tombstone cleanup/index queue resume.
- 所有持久化入口在完整操作期间持有可重入 admission lease；删除原子关闭新入场，先取消并等待 generation，再 drain 既有 lease，最后重解析目标。
- DB_COMMITTED 部分物理清理保留可恢复所有权凭据，并将 staging target 精确绑定到 row operationId；错配或坏 digest 在任何文件操作前失败关闭。

- [x] **Step 1 — RED:** fence/join ordering, all-domain deletion, DB rollback, post-commit cleanup, process-death recovery, duplicate delete, root identity/shared root tests.
- [x] **Step 2 — Verify RED:** run new transaction/recovery tests, coordinator, ToolExecutor, Chat/Hub VM tests.
- [x] **Step 3 — GREEN:** implement journal stages and use case; no UI success before completion.
- [x] **Step 4 — Verify GREEN:** targeted JVM tests, migration/device test where available, application startup tests.
- [x] **Step 5 — Commit:** `fix: make session deletion transactional and recoverable`，并以活跃写入序列化及 operationId 绑定的独立审阅修复提交收口。

---

## Task 8：文件生命周期、回收站、二进制策略与资源上限

**Dependencies:** Task 7.

**Files:**

- Create: `WorkspaceTextContentPolicy.kt`
- Modify: `WorkspaceRepository.kt`, `WorkspaceDeletionTransaction.kt`, `SecureWorkspaceFileOps.kt`
- Modify: active-only `FileEntry`/`FileVersion`/workspace-mutation DAOs plus index/vector/FTS/KG task/candidate queries and coordinators
- Modify: `IFileOperationRepository.kt`, `FileOperationRepository.kt`
- Modify: FileList/Search/Read/Write/Patch/Diff skills
- Modify: `DocEditorViewModel.kt`, `DocEditorScreen.kt`
- Modify: `RagViewModel.kt`, ResourceExplorer/RecycleBin ViewModels and UI/strings.

**Behavior:**

- Normal delete moves to recycle; permanent delete only from recycle/cleanup/creation rollback.
- Recycled rows are invisible to normal tree, file tools, vector/FTS/KG and RAG.
- Recycle synchronously cancels/joins indexing, clears vector/FTS/KG/task/cache data, and restore enqueues exactly one rebuild after the row is active again.
- Permanent delete is accepted only for recycled rows or creation rollback; it stages and removes all FileVersion rows/snapshots and derived data for the full subtree.
- create/mkdir/move/rename/recycle/restore use Task 1 journal and recover idempotently.
- Explicit text policy rejects PDF/DOCX/NUL/invalid UTF-8 for editor/read/write/patch/diff.
- Strict patch coordinates reject end past EOF with zero side effects.
- Enforce physical byte/line/output budgets and stable error codes.

- [ ] **Step 1 — RED:** crash checkpoints, source/target conflict, recycle isolation, version cleanup, binary rejection, EOF zero-side-effect, oversized/overflow args tests.
- [ ] **Step 2 — Verify RED:** run workspace repository/file ops/index/RAG/DocEditor/skills targeted suites.
- [ ] **Step 3 — GREEN:** wire lifecycle and UI semantics; ordinary confirmations say “移入回收站”.
- [ ] **Step 4 — Verify GREEN:** targeted JVM, AndroidTest compile and affected screenshots.
- [ ] **Step 5 — Commit:** `fix: harden workspace file lifecycle and text operations`.

---

## Task 9：备份真值、死路由与基础设置错误闭环

**Dependencies:** Tasks 3, 7 and 8.

**Files:**

- Modify: `BackupSettingsScreen.kt`, `BackupViewModel.kt`
- Modify: `BackupPreferencePolicy.kt`, `AndroidTransactionalBackupPreferenceStore.kt`
- Modify: `NavGraph.kt`; delete only proven unreachable legacy Screens
- Modify: default model clear path, share error strings, Avatar picker/store
- Modify: Agent/Token/Settings ViewModels and corresponding screens/strings/tests.

**Behavior:**

- Remove auto-backup state/API/switch/export; old key is ignored during otherwise-valid restore.
- Backup cannot start with unrecovered PREPARED workspace journals; restored roots are revalidated.
- Delete `SESSION_SETTINGS`, Developer Panel and other verified dead routes; keep chat sheet and real Metro CLI/TUI.
- Default model can be cleared; share errors localized.
- Avatar is image-only, atomically replaced, and preserves old file on failure.
- Agent/Token/Settings operations expose typed error/retry and do not clear state before repository success.

- [ ] **Step 1 — RED:** backup legacy compatibility/current export; dead-route reachability; default clear; avatar empty/video/copy failure; async error-state tests.
- [ ] **Step 2 — Verify RED:** run backup/navigation/settings/agent/token tests.
- [ ] **Step 3 — GREEN:** remove false affordances and wire typed states.
- [ ] **Step 4 — Verify GREEN:** targeted JVM, AndroidTest compile, settings screenshot validation.
- [ ] **Step 5 — Commit:** `fix: align backup settings and release reachable surfaces`.

---

## Task 10：RAG FTS、知识图谱与可访问性闭环

**Dependencies:** Tasks 8 and 9.

**Files:**

- Create: `RagSearchResults.kt`, `KnowledgeGraphAccessibilitySheet.kt`
- Modify: `RagViewModel.kt`, `RagHomeScreen.kt`, `RagAdvancedScreen.kt`
- Modify: `KgEdgeDao.kt`, `GraphStore.kt`, `KnowledgeGraphViewModel.kt`, `KnowledgeGraphScreen.kt`, `InteractiveGraphCanvas.kt`
- Modify: `UiTags.kt`, strings, JVM/Android/screenshot tests.

**Behavior:**

- Search shows stable FTS result/snippet states with debounce/cancellation/out-of-order protection and explicit fallback/error.
- Remove four runtime-unused advanced switches while keeping backup read compatibility.
- Document KG requires explicit doc selection; caches global/document independently; failure keeps last good graph.
- Canvas gets summary semantics and a 48dp list alternative; reduced motion disables decorative pulse.
- RAG failure retains selection/confirmation and shows typed notice.

- [ ] **Step 1 — RED:** body-only match, stale search, FTS error, advanced fake-control absence, doc selection/cache/error, canvas/list semantics tests.
- [ ] **Step 2 — Verify RED:** targeted RAG/KG/DAO tests and AndroidTest compile.
- [ ] **Step 3 — GREEN:** implement continuous-list MD3 states without touching bottom navigation.
- [ ] **Step 4 — Verify GREEN:** targeted JVM, screenshot validation, AndroidTest compile; inspect rendered actuals at normal and 2.0x text where available.
- [ ] **Step 5 — Commit:** `fix: expose real rag search and knowledge graph states`.

---

## Task 11：独立全树审计与修复波次

**Dependencies:** Tasks 1–10.

**Reviewer:** fresh high-capability Agent that did not implement the tasks.

**Scope:**

- Spec line-by-line compliance.
- Endpoint golden requests and Provider second-turn payloads.
- Error/cancel → zero ledger claim / zero Skill execute.
- Approval UI identities vs ledger identities.
- Session deletion/root identity/migration/recovery and recycle isolation.
- All visible settings endpoints and bottom-nav freeze.
- Secret scan, staged file list, generated artifact exclusions.

- [ ] Generate review package from Task commits and current diff.
- [ ] Reviewer records Critical/Important/Minor with exact file/line/test evidence.
- [ ] Assign one minimal fix wave for Critical/Important; write new RED tests for logic gaps.
- [ ] Fresh reviewer rechecks; unresolved Critical/Important blocks release.
- [ ] Commit: `fix: close release contract review findings` only if fixes exist.

---

## Task 12：DIA、全量验证、Release APK、HLG 与 Git 闭环

**Dependencies:** Task 11 closed.

**Files:**

- Modify: `README.md`, `docs/PRD.md`, `CHANGELOG.md`, `docs/release/v0.2.1-beta.md`, `docs/release/v0.2.1-beta-validation.md`
- Modify affected `docs/ADR/` architecture records, especially ADR-019 and database/provider contracts.
- Append: `.agent/handover.md` only through HLG script; regenerate index via Skill.
- Do not commit APK/build outputs.

- [ ] **Step 1 — DIA:** update only facts proven by current implementation. WebDAV remains manual; auto-backup claim removed; Provider live connectivity remains PENDING unless independently authorized/tested; old PASS rows retain old commit identity.

- [ ] **Step 2 — Clean full JVM/UI/static validation:** run fresh commands and capture exact counts:

  ```bash
  ./gradlew clean
  ./gradlew :app:testDebugUnitTest --no-build-cache --rerun-tasks
  ./gradlew :app:lintDebug
  ./gradlew :app:validateDebugScreenshotTest --rerun-tasks
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 3 — Repository/system validation:** from repo root:

  ```bash
  bash scripts/ci/android-device-core-e2e.sh
  bash scripts/ci/android-minified-blackbox-smoke.sh
  node --test scripts/tests/nexara-metro-tui.test.js
  python3 scripts/ci/validate-release-readiness.py
  ```

  Run connected tests only on a controlled emulator/device whose identity is explicitly verified. Missing device is recorded as an environment gate, not PASS.

- [ ] **Step 4 — Build Release securely:** use the existing non-echoing signing path, then verify:

  ```bash
  ./gradlew :app:assembleRelease
  zipalign -c -P 16 -v 4 <release-apk>
  apksigner verify --verbose --print-certs <release-apk>
  aapt dump badging <release-apk>
  shasum -a 256 <release-apk>
  ```

  Never print signing inputs. If signing identity cannot be loaded safely, stop before producing a misleading unsigned “release” artifact.

- [ ] **Step 5 — Install/upgrade proof:** if a controlled target with the existing app is available, record current package/version/signature, run `adb install -r`, verify app data marker and launch. Do not modify an unknown physical device.

- [ ] **Step 6 — HLG:** query current index, prepare JSON record, run append dry-run, inspect result, run `--apply`, rebuild/read back index.

- [ ] **Step 7 — Commit/push:** stage exact docs/HLG/code paths, verify secret/build exclusions, commit final governance evidence, push `B-native-refactor`, verify remote SHA. No tag/PR/Release.

- [ ] **Step 8 — Final handoff:** provide APK clickable absolute path, SHA-256, size, package/version/signing verification, test counts, final commit/push status and any truly external remaining gate.

## Stop Conditions

- Unexpected user changes overlap owned files and cannot be safely preserved.
- Room migration fails on a v2 fixture or schema export differs from runtime.
- Error/cancel causes any ledger claim or Skill execute.
- A session root is outside the application workspace parent, has mismatched identity, or is referenced by multiple sessions.
- Journal version/hash is unknown, or move recovery finds conflicting source and target.
- Generation/tool jobs cannot be joined before session deletion.
- Provider endpoint has two equally valid interpretations; Generic must require a full endpoint.
- A protocol needs live credentials/paid calls or an unverified wire field to claim support.
- Prompt-time and execute-time tool definition digests differ yet execution proceeds.
- MCP server disabled/deleted but remains resolvable.
- Release signing cannot reuse the existing identity without exposing secrets.
- The same implementation path fails twice with contradictory evidence; return to design/review instead of widening guesses.
