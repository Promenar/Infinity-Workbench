# Nexara Material 3 第四阶段：DocEditor 可靠性与视觉迁移实施计划

> 日期：2026-07-16
> 分支：`codex/md3-redesign`
> 依据：`docs/superpowers/specs/2026-07-16-nexara-md3-redesign-design.md`
> 状态：实施中（Task 1-3 已完成；Task 4-8 待执行）
> 范围：DocEditor 保存/重命名/索引可靠性、长文档性能、Material 3 视觉、响应式与无障碍；不改 RAG 检索策略

## 目标与边界

本阶段将文档编辑器从遗留 Glass/自定义描边体系迁移到稳定 Material 3，并先关闭只读审计确认的保存取消、保存中返回、重命名并发与索引入队四类数据可靠性缺口。保留现有 `documentEpoch`、expected-hash 正文 CAS、`Mutex.tryLock()`、脏数据保护、大文件只读和 `DocEditorScreenState/Actions` 测试 seam，不为视觉重构重写整个状态机。

产品决策：保存期间顶部返回和系统返回均不得离开编辑器，必须提供可见且可被 TalkBack 宣读的“正在保存，请稍候”反馈；保存成功或失败后恢复正常返回语义。不提供可能造成“标题已提交、正文未提交”的强制离开入口。

统一验收约束：

- 重命名必须使用 `persistedTitle` 作为并发基线，明确返回 Success / Conflict / NotFound，禁止目标不存在时静默成功。
- 文件已物理保存但索引事件未持久入队时，不得伪装为完全成功；UI 必须保留“内容已保存、索引待重试”的独立事实与恢复入口。
- 禁止新增 `NexaraGlassCard`、`GlassSurface`、`GlassBorder`、装饰性渐变、微字号和页面内硬编码颜色/形状；DocEditor 生产文件迁移完成后不得再引用这些遗留令牌。
- 360dp、2.0x 字体、800×360 横屏、840dp 平板与 IME 展开下，标题、模式、通知、编辑区、状态和操作不得不可恢复裁切。
- 所有操作目标至少 48dp；模式选择具有 Tab/Selected 语义；保存、冲突、索引待处理和只读状态不得只靠颜色表达。
- 每个实现块先写 RED，再做最小实现；更新 golden 前必须生成 reference/actual 同尺寸组合图并人工检查。
- 不读取或提交 `artifacts/`、`secure_env/`、签名材料或真实凭证。

## 已验证基线

- `DocEditorViewModelTest` 23/23、`DocEditorScreenStateTest` 4/4 通过。
- `:app:compileDebugAndroidTestKotlin` 与现有 9 张 DocEditor screenshot golden 校验通过。
- 现有可靠 seam：旧加载隔离、重复保存门禁、正文 expected-hash 冲突、局部成功事实、继续编辑不误清 dirty、>1 MiB 元数据只读、顶部/系统返回同源确认。

---

### Task 1：冻结第四阶段契约与计划

**Files:**
- Create: `docs/superpowers/plans/2026-07-16-nexara-md3-phase4-doceditor.md`
- Modify: `.agent/registry.md`

- [x] **Step 1：登记计划和范围**

  将本计划加入 registry，明确本阶段同时包含可靠性硬化与视觉迁移，避免把四类数据缺口延后成截图债务。

- [x] **Step 2：指定遗留静态契约与测试落点**

  计划指定后续在独立 `DocEditorMaterialContractTest` 中增加聚焦扫描：迁移完成后禁止 Glass、旧色彩/排版/形状入口、固定 12/14/15sp、页面自定义描边卡套卡和非生命周期感知收集；不把源码扫描混入纯状态测试。

- [x] **Step 3：提交计划**

  `git commit -m "docs: plan Material 3 DocEditor phase"`

---

### Task 2：硬化保存取消与保存中返回

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorScreen.kt`
- Modify: `native-ui/app/src/main/res/values/strings.xml`
- Modify: `native-ui/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorViewModelTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorScreenStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorInteractionTest.kt`

- [x] **Step 1：写保存取消 RED**

  正文写入在提交前抛出 `CancellationException` 后，仍存活的 ViewModel 不得永久停留在 `Saving`；明确进入 `SaveError + SaveCancelled`，保留当前 dirty，并显示本地化“保存已中断，本地修改仍保留”的可重试反馈。ViewModel 销毁取消不回写；旧文档的迟到 finally 不得污染新文档。重命名提交点后的精确局部事实依赖 Task 3 的新结果契约，在 Task 2 不伪造判断。

- [x] **Step 2：写 Saving 返回 RED**

  `docEditorBackDecision` 增加保存中决策；顶部返回和系统返回均不导航、不弹“放弃修改”对话框，并出现本地化、可访问的等待反馈。保存结束后再次返回恢复既有 clean/dirty 语义。

- [x] **Step 3：实现最小状态恢复与同源返回策略**

  finally 只在同一文档/epoch、ViewModel 仍存活且 phase 仍为 Saving 时恢复为上述 `SaveError`；不得吞掉取消，也不得把 ViewModel 销毁后的无意义状态当作成功。Route 层统一处理两个返回入口。

- [x] **Step 4：运行聚焦 JVM 与设备测试**

  ```bash
  cd native-ui
  ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.rag.DocEditorViewModelTest' --tests 'com.promenar.nexara.ui.rag.DocEditorScreenStateTest'
  ANDROID_SERIAL=<api36> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.rag.DocEditorInteractionTest
  ```

- [x] **Step 5：提交**

  `git commit -m "fix: harden DocEditor save cancellation"`

---

### Task 3：为重命名建立 CAS、NotFound 与索引事实

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/domain/repository/IWorkspaceRepository.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/domain/repository/IFileOperationRepository.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/repository/FileOperationRepository.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/rag/FileIndexEvent.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/rag/VectorizationQueue.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/entity/VectorizationTaskEntity.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/dao/VectorizationTaskDao.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/NexaraDatabase.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/data/local/db/migration/Migration1To2.kt`（若项目已有统一 migration 入口则复用其固定位置）
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagViewModel.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/repository/WorkspaceRepositoryTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/repository/FileOperationRepositoryTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/rag/VectorizationQueueTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/data/local/db/NexaraDatabaseMigration1To2Test.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/local/db/NexaraDatabaseBaselineTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/backup/RoomBackupDataSource.kt`
- Modify: relevant backup schema/version tests discovered by `rg 'identityHash|schemaVersion|NexaraDatabase'`
- Create: `native-ui/app/schemas/com.promenar.nexara.data.local.db.NexaraDatabase/2.json`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorViewModelTest.kt`
- Modify: relevant constructor/call-site tests discovered by `rg 'rename\\('`

- [x] **Step 1：写 RenameResult RED**

  契约必须覆盖 expectedName 匹配成功、远端已改名 Conflict、保存前删除 NotFound、同名冲突异常和普通 RagViewModel 无条件重命名调用。返回值不得依赖异常文本判断。

- [x] **Step 2：实现根级事务内 CAS**

  在 `withRootMutation` 锁内重新读取目标并比较 `expectedName`，再执行物理路径与数据库提交；验证完成后先 `ensureActive()`，物理移动、FileEntry 更新和 rollback commit 使用窄范围 `NonCancellable` 提交点，使调用方能确定 Success/Conflict/NotFound。Success 返回新名称、hash 和单调 targetEpoch；epoch 使用 `max(clock(), previous.updatedAt + 1)`，不能依赖同毫秒 wall clock 自然递增。DocEditor 使用 `persistedTitle`，普通文件管理调用保持明确的无条件语义。

- [x] **Step 3：写索引入队 RED**

  覆盖标题单独保存、正文保存和标题+正文保存三条路径：标题提交后即可安全持久入队；正文成功后以最新 hash 幂等覆盖同一文档的目标代际。事件失败时文件仍算已保存，`persistedTitle/content/hash` 前移并按当前输入重新计算 dirty，索引故障只由独立 `indexPending(targetHash, epoch)` 表达；重试成功后清除对应代际 pending。

- [x] **Step 4：实现显式 reindex 端口和 UI 状态**

  复用现有 `FileIndexEventSink`/持久化 `VectorizationQueue`，在文件操作端口提供可测试的重试入口。队列按 workspaceRootUuid + docId 幂等 upsert 最新目标 hash/代际：标题提交必须先持久入队，正文成功再覆盖，不依赖后续写入必然成功。旧 processor 的完成/失败/删除和旧重试都必须用 targetHash + targetEpoch CAS，不能覆盖或删除新目标；重复点击不得产生重复持久任务。成功回写同步刷新 `sizeBytes`、`lastModified` 和 hash。

- [x] **Step 5：执行 Room v1→v2 正式迁移**

  `vectorization_tasks` 增加 `target_content_hash TEXT NULL` 与 `target_epoch INTEGER NOT NULL DEFAULT 0`，保留 `(workspace_root_uuid, doc_id, type)` 唯一键，以单行版本化 upsert 表达最新目标。迁移对 document_reference 从 FileEntry hash/updatedAt 回填，注册 `MIGRATION_1_2` 并生成 schema 2.json；同步更新备份 schema/version/identity-hash 兼容逻辑。禁止改写冻结的 1.json、使用 destructive fallback，或把目标版本塞进 task ID/subStatus/userContent。

- [x] **Step 6：补齐重命名取消提交点 RED**

  覆盖提交前取消、物理/数据库提交点后取消、标题已成功而正文 Conflict/NotFound/异常；ViewModel 必须据 `RenameResult` 保留真实局部成功，且标题索引事件不会因正文失败而丢失。

- [x] **Step 7：运行仓库、迁移、备份与 ViewModel 回归**

  ```bash
  cd native-ui
  ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.data.repository.WorkspaceRepositoryTest' --tests 'com.promenar.nexara.data.repository.FileOperationRepositoryTest' --tests 'com.promenar.nexara.data.rag.VectorizationQueueTest' --tests 'com.promenar.nexara.ui.rag.DocEditorViewModelTest'
  ANDROID_SERIAL=<api36> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.data.local.db.NexaraDatabaseMigration1To2Test
  ```

- [x] **Step 8：提交**

  `git commit -m "fix: make document rename and indexing explicit"`

Task 3 实际按三个安全提交点完成：`e6ef4f4` 建立重命名 CAS，`1ca2511` 建立 Room 目标版本与正式 v1→v2 迁移，本阶段第三个提交收口索引队列/删除屏障/补偿状态。最终实现额外覆盖：

- `targetHash + targetEpoch` 从 Room、内存队列到事务回写全链路 CAS，旧 worker、旧重试和 completed 清理均不得覆盖或删除 newer target。
- 文件、文件夹与工作区删除使用 cancel-and-join + fence + Room 提交屏障；失败时安全回滚并恢复当前目标，成功后清理共享 pending。
- 应用级 pending coordinator 跨 Home、Folder、DocEditor ViewModel 保留精确失败目标；预取消先短登记，成功保留 latest watermark，永久删除保留进程内 tombstone，拒绝迟到旧事件制造幽灵告警。
- 进程死亡发生在文件提交后、Queue 接收前时，冷启动扫描 `vectorizedAt` 缺失/落后文件并以当前 hash、epoch 与 KG 配置重建 reference task。
- DocEditor 在 Loading、LoadError、SaveError、SaveConflict 等组合状态下仍显示唯一 MD3 索引告警与 48dp 重试入口；新增 360×640dp、2× 字体、SaveConflict 双动作 + pending 的仪器契约。
- 最终主控门禁：1872 个 JVM 测试，0 failure/error、14 skip；AndroidTest 源码编译与 `lintDebug` 通过；两路只读终审最终均为 C0/I0/M0。当前无 ADB 设备，新增仪器测试仅完成编译，真机/模拟器执行仍属于 Task 7/8。

---

### Task 4：降低长文档每次击键的全量工作

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorViewModelTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorScreenStateTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorPerformanceTest.kt`

- [ ] **Step 1：建立长文本 RED 与基准**

  覆盖 10,000 行、接近 1 MiB、超长单行和 Split 连续输入。固定 API 36 AVD 配置并记录镜像/CPU/内存；预热 3 轮、采样 5 轮，每轮追加 30 次输入并切换模式 10 次。使用 `FrameMetricsAggregator`/可复现 instrumentation 采样，门禁为帧时长 p95 ≤50ms、单帧 ≤150ms、测试结束稳定 PSS 相对预热后增量 ≤64MiB；不以单次截图或一次 wall-clock 代替性能证据。若宿主噪声导致连续两轮方差 >20%，先修复测量夹具，不放宽阈值。

- [ ] **Step 2：移除重复 O(n) 路径**

  取消完整行号字符串与全量 Regex 分词的每击键同步重建；统计采用单次扫描/缓存，Split Markdown 使用可取消防抖快照，退出 Split 时取消。若保留行号 gutter，必须用 `clearAndSetSemantics {}` 或等价方式彻底移出无障碍树。编辑区使用有界滚动责任，禁止第三方 Markdown 回调叠加滚动。

- [ ] **Step 3：验证数据语义不变**

  输入值、dirty、总行数、字数/字符数、保存 snapshot 与冲突行为必须保持正确；若 Compose 编辑控件仍无法在近阈值设备门禁内稳定，才基于实测增加独立“复杂文档只读”阈值，不凭猜测降级。

- [ ] **Step 4：提交**

  `git commit -m "perf: bound DocEditor long document work"`

---

### Task 5：重建标准 Material 3 编辑器骨架

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorMaterialContractTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/DocEditorScreenStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorInteractionTest.kt`

- [ ] **Step 1：写 MD3 结构和响应式 RED**

  在独立 Material contract 测试中写入 Glass/旧令牌/硬编码字号/非生命周期收集清零 RED，并断言小型 Top App Bar、文档身份、模式选择、编辑/预览/分屏主面和底部状态是单层结构；360dp/2.0x 与 800×360 中所有主操作可达，720dp 断点行为稳定，IME 展开后仍可编辑和保存。对现有三张中文 golden 中返回箭头消失建立真机/截图双重 RED，先判定是 Preview 宿主还是生产导航图标问题，禁止直接接受缺图基线。

- [ ] **Step 2：改为生命周期感知与复合文档身份**

  使用 `collectAsStateWithLifecycle()`；`rememberSaveable` 身份包含 workspaceRootUuid + docId，避免跨工作区复用模式或确认状态。

- [ ] **Step 3：实现标准 MD3 页面层级**

  使用 `MaterialTheme.colorScheme/typography/shapes`、标准 TopAppBar、SingleChoiceSegmentedButtonRow（或稳定 M3 等价单选组件）、Surface/TextField。顶栏使用唯一导航标题，正文可编辑文件名必须降一级，禁止两个同权重标题重复显示同一名称。移除整页 Glass 卡、卡套卡和重复描边；手机 16dp、宽屏 24/32dp 边距。

- [ ] **Step 4：重排 Edit / Preview / Split**

  编辑区与预览区是主内容表面，不额外套大卡；Split 只在可用宽度 ≥720dp 出现，分隔线使用 outlineVariant。长代码、超长链接和宽表格必须具有明确且可测试的横向可达策略；Split 两侧独立滚动，不得在窄屏产生不可恢复裁切。

- [ ] **Step 5：建立 IME 与光标可达策略**

  使用真实 `WindowInsets.ime`/`imePadding` 责任和 `BringIntoViewRequester`（或稳定等价实现）。设备测试必须点击标题与正文分别唤起真实 IME，把光标移到长文档末行并输入，断言当前行进入可视区、保存或等价恢复入口可达；关闭 IME 后滚动位置、dirty 和输入不丢失，800×360 横屏至少执行一次。Edit/Preview/Split 切换不得跳回文首、丢 dirty 或重复渲染旧快照。

- [ ] **Step 6：提交**

  `git commit -m "feat: migrate DocEditor shell to Material 3"`

---

### Task 6：统一状态、通知、对话框与 TalkBack

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/DocEditorScreen.kt`
- Modify: `native-ui/app/src/main/res/values/strings.xml`
- Modify: `native-ui/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [ ] **Step 1：写状态语义 RED**

  Loading、LoadError、NotFound、Saving、SaveError、SaveConflict、IndexPending、LargeFile 和确认对话框分别有唯一、非重复的可见文案/LiveRegion；装饰图标和行号 gutter 不单独聚焦，正文只暴露一个可编辑语义节点，按钮标签能说明对象和后果。

- [ ] **Step 2：实现标准状态与 notice**

  空态/错误态使用居中但不占满的大型内容区；保存冲突和索引待处理使用单层 tonal notice，双操作在窄屏或 2.0x 纵向堆叠。状态栏改为弱化元数据行，不使用 GlassBorder 或仅靠红绿圆点。

- [ ] **Step 3：保留数据安全对话框语义**

  放弃修改和重新加载迁移到标准 M3 `AlertDialog`（或项目已验证的等价封装），确认/取消均 ≥48dp；Reload 文案必须明确会丢弃本地内容。Saving 返回不复用放弃文案。自动化只称为 TalkBack 语义代理验收，不替代签名 APK 真机听觉检查。

- [ ] **Step 4：设备 TalkBack 自动验收**

  覆盖焦点顺序、行号静音、Role.Tab/Selected、dirty/clean 明确 `stateDescription`、保存禁用/进行中、冲突双动作、索引重试、大文件只读、顶部/系统返回同源行为和 2.0x 操作尺寸。

- [ ] **Step 5：提交**

  `git commit -m "feat: unify DocEditor states and accessibility"`

---

### Task 7：更新 9 张基线并补真实设备矩阵

**Files:**
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: `native-ui/app/src/screenshotTestDebug/reference/com/promenar/nexara/ui/ReleasePreviewScreenshotTestKt/docEditor*.png`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorInteractionTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/DocEditorPerformanceTest.kt`

- [ ] **Step 1：保持并扩展状态矩阵**

  保留 loading、load error 2x、dirty edit、conflict 2x、rich preview、tablet split、large file、discard、landscape 9 张基线；强制新增至少一张 360dp 或 2.0x 的 IndexPending golden，完整呈现“内容已保存、索引待重试”、重试动作、非错误色单独表达和唯一 live region。

- [ ] **Step 2：生成 reference/actual 同尺寸组合图**

  逐图检查：Loading 不再是巨大空卡；2x 错误/冲突标题不裁切；编辑/预览/分屏无卡套卡；代码块不异常折断；横屏和大文件不浪费或遮挡关键空间；对话框层级与触控目标正确。`docEditorLoadErrorChineseLargeFont`、`docEditorSaveConflictChineseLargeFont`、`docEditorLargeFileChineseTablet` 必须与其余所有状态一样实际绘制返回图标；语义节点存在但像素缺失仍算失败。

- [ ] **Step 3：API 31/35/36 设备矩阵**

  对三个 API 分别运行 `DocEditorInteractionTest`；API 36 额外运行性能类、360dp/2.0x、800×360、840dp、840dp+2.0x Split、真实 IME、显示大小放大和减少动效。返回按钮在所有状态/语言/字号/尺寸下必须实际可见、48dp、可点击并有正确 TalkBack 标签。修改字体、显示缩放、方向、动画等系统设置前记录并在 finally 恢复；每个 serial 必须核对 SDK。

- [ ] **Step 4：截图门禁**

  `cd native-ui && ./gradlew :app:validateDebugScreenshotTest`

- [ ] **Step 5：提交**

  `git commit -m "test: lock Material 3 DocEditor release states"`

---

### Task 8：第四阶段全量门禁、评审与 DIA/HLG 收口

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `.agent/handover.md`
- Modify: `.agent/handover-index.md`（仅由 HLG 脚本生成）
- Modify: this plan
- Modify: `docs/release/v0.2-beta-validation.md`（仅新增已完成的可复核证据，不提前关闭真机人工门禁）

- [ ] **Step 1：全量静态、JVM、Lint 与构建**

  `cd native-ui && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDeviceTest`

- [ ] **Step 2：全量截图与设备回归**

  运行完整 screenshot suite；API 31/35/36 至少执行 DocEditor 类，API 36 使用显式 runner 执行完整 AndroidTest。执行前从测试 APK XML/runner 列表记录预期测试数和允许的条件 skip 清单，执行后对 XML 汇总 tests/failures/errors/skipped；任何新增或未解释 skip 均失败，禁止只跑 DocEditor 后误报“完整 AndroidTest”。

- [ ] **Step 3：独立代码与视觉评审**

  代码评审必须达到 C0/I0；视觉评审达到 P0/P1/P2 清零。截图必须与旧基线/方案 3 组合对照，不能只看 actual。

- [ ] **Step 4：DIA/HLG**

  更新 CHANGELOG、计划状态、handover 与索引；`docs/release/v0.2-beta-validation.md` 只记录真实完成的自动化/设备证据。签名 APK 真机 TalkBack 全焦点与听觉检查仍保留为最终人工发行门禁。

- [ ] **Step 5：提交阶段收口**

  `git commit -m "docs: close Material 3 DocEditor phase"`

## 完成定义

只有当可靠性契约、性能门禁、9+ 张视觉基线、API 31/35/36、TalkBack 自动语义、完整 JVM/Lint/构建、独立代码/视觉评审与 DIA/HLG 全部闭环后，本阶段才算完成。最终发行仍不得跳过用户在签名 APK 真机上的 TalkBack 听觉/全焦点和核心业务人工验收。
