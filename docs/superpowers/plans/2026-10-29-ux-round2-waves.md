# 体验改进第二批：分波实施计划

> 方案与验收依据：[体验改进第二批：方案与决定](../specs/2026-10-28-ux-round2-decisions.md)。本文只定分工、文件归属、代码位置与验收命令；行为细节以方案文档为准。
> 交互原型由主控持有（私有链接），本计划中需要的参数已全部写进方案文档。

## 总体安排

| 波次 | 分支会话 | 内容 | 执行者 | 模型 |
| --- | --- | --- | --- | --- |
| 第一波 | W1-B 检索编号、查询改写默认值、进展语 | D2、D6、P4 | 后端 Codex CLI（GPT 6.1 Sol xhigh），Claude 写任务书并复核 | Sonnet 5.5 |
| 第一波 | W1-H 智能体头像、新建弹窗、首页入口 | D1、D3、D4、D5 | 前端 Claude；数据库与默认值交 Codex | Sonnet 5.5 |
| 第一波 | W1-G 浮岛光效、点阵屏、运行坞 | P2、M1、M2、R1 | 前端 Claude | Opus 5.5 |
| 第一波 | W1-S 设置页底部、白名单标注、折叠思考预览 | D7、D8、P3 | 前端 Claude | Sonnet 5.5 |
| 第二波 | W2-Q 选字引用批注 | P1 | 后端 Codex + 前端 Claude | Opus 5.5 |
| 第二波 | W2-D 不生效即灰显排查 | D9 | 前端 Claude | Sonnet 5.5 |
| 收尾 | 主控 | 合并、全量门禁、0.3.0-beta（versionCode 5）签名包、真机验收 | 主控 | — |

第二波在第一波全部合并后开工：P1 需要第二次数据库迁移，并改动 W1-G 持有的输入区文件；D9 是全站修改，需要在其它改动落地后排查。

## 所有分支会话的共同规则

- 只读本计划“代码位置”中列出的文件，以及它们直接调用到的代码；不做全仓库探索。需要越出本会话的文件归属时，先向主控说明。
- 每个会话在自己的工作树与分支上提交，不推送、不合并，完成后回报主控：分支名、提交 SHA、改动摘要、定向测试命令与结果、CHANGELOG 建议条目、未完成或待真机确认的事项。
- 只跑新增与受影响测试的定向运行和编译（例如 `./gradlew :app:testSideloadDebugUnitTest --tests '<类名>'`、`./gradlew :app:compileSideloadDebugKotlin`）。不跑完整单测、lint、截图校验、APK 打包，不碰真机。
- 不改 `CHANGELOG.md`、`README.md`、`AGENTS.md`、`.agent/` 下任何文件；文档同步由主控统一做。
- 新增字符串放在本会话专属的资源文件里，`values/` 与 `values-zh-rCN/` 各一份，文件名见各会话小节；修改已有字符串只限本会话归属的条目。
- 不读 `secure_env/`、签名材料、`local.properties` 与测试资源中的密钥。
- Codex 任务书遵循 `.agent/tmp-agent-reports/common-rules.md`，报告写入 `.agent/tmp-agent-reports/ux-round2/<会话代号>/`；任务书写明不跑全量回归。
- 代码注释与文档使用简体中文。

## 第一波

以下路径都相对 `native-ui/app/src/main/java/com/promenar/nexara/`。

### W1-B 检索编号、查询改写默认值、进展语（D2、D6、P4）

文件归属：

- `data/rag/MemoryManager.kt`：`formatRetrievedChunks`（约 516 行），注入块现为 `[Document]: …` 与 `[Memory · 时间]: …`。
- `data/knowledge/KnowledgeTurnState.kt`：`citation(sessionId, turnId, id)`（约 66 行），以及 `citations` 映射。
- `ui/chat/manager/skills/KnowledgeSearchSkills.kt`：`knowledge_search` 工具结果，对已注入条目回复“[K1] 已在上方参考资料中”。
- `data/generation/DefaultChatGenerationRuntime.kt`、`DefaultChatGenerationRunnerFactory.kt`、`ChatGenerationRunner.kt`：自动注入调用处，以及 P4 的运行时提醒（统计连续工具轮数和距上一句面向用户文字的时长，在下一次请求末尾追加系统提醒，只进协议历史）。
- `data/rag/RagModels.kt` 第 69 行、`domain/usecase/RagConfigPersistence.kt` 第 98 行：`enableQueryRewrite` 默认值改为 `false`。
- `data/agent/AgentConfigModels.kt` 第 32 行：只改 `enableQueryRewrite` 默认值这一行。W1-H 可能在同一文件新增字段，合并由主控处理。
- `ui/chat/manager/SystemPromptSections.kt`：`toolGuidance`（约 67 行），补充进展语要求。
- `ui/rag/KnowledgeBaseSettingsScreen.kt`：查询改写开关脚注。
- 新字符串：`res/values/ux2_retrieval_strings.xml` 与对应中文文件。

测试：注入块与工具结果对同一资料给出相同 K 编号；同一轮多次检索编号不变；未存值时查询改写读到关闭，已存的开启值保持开启；连续 4 轮工具调用或距上一句超过 60 秒后，下一次请求末尾带提醒，且提醒不进入会话消息；4 轮内有面向用户的文字时计数清零。

### W1-H 智能体头像、新建弹窗、首页入口（D1、D3、D4、D5）

文件归属：

- `ui/hub/AgentHubScreen.kt`：`agentIconVector`（约 1414 行，只映射 💻📝🌐/A）、底色混合（约 1411 行，向次要文字色混 35%）、`AddAgentDialog`（约 272 行）、列表底部“添加智能体”行与展开后的“新建会话”行。
- `ui/hub/HubThreePane.kt`：内屏侧边栏头像（约 484 行）。
- `ui/hub/AgentEditScreen.kt`：`presetIcons`（约 49 行，10 个预设）、技能只读摘要（约 109–114 行）、“新会话默认”分组新增“新会话默认开启工具”开关、描述字段。
- `ui/hub/AgentHubViewModel.kt`、`ui/hub/SessionListViewModel.kt`：创建智能体与新建会话入口。
- 新文件 `ui/common/AgentAvatar.kt`：统一的图标表与底色函数，四处共用。
- `domain/usecase/NewSessionDefaults.kt`：第 31 行停止复制 `agent.skills` 到 `activeSkillIds`；新建会话时用智能体的“新会话默认开启工具”写入 `toolsEnabled`。
- `ui/chat/manager/SessionManager.kt`：约 202 行 `activeSkillIds` 的读写，界面不再读写该名单。
- 数据层（交 Codex）：`data/local/db/entity/AgentEntity.kt` 新增列，`data/local/db/NexaraDatabase.kt` 版本 29→30，`data/local/db/NexaraDatabaseMigrations.kt` 新增 `MIGRATION_29_30`（现有为 `MIGRATION_28_29`），导出 schema，同步智能体领域模型与映射、备份导出与恢复。现有智能体迁移后取“开启”。
- `onboarding/OnboardingAgentSessionCreator.kt`：如创建智能体的签名变化则同步。
- 新字符串：`res/values/ux2_agent_strings.xml` 与对应中文文件。

测试：编辑页每个预设图标都映射到对应图标，不落入默认值；底色函数在首页与编辑页一致；迁移测试覆盖 29→30 与旧备份恢复（缺列时取“开启”）；对话型与智能体型模板写入方案 D4 表中的参数；新建会话不再带入 `agent.skills`。

### W1-G 浮岛光效、点阵屏、运行坞（P2、M1、M2、R1）

文件归属：

- `ui/common/motion/GenerationMotion.kt`：`THINKING` 归入等待（约 54 行）改为流式家族；`OutputRateMeter`（约 106 行）统计正文加思考字数；`GenerationMotionFullRate`（约 100 行）。
- `ui/common/motion/GenerationIndicatorStyles.kt`、`ui/common/GenerationDots.kt`：生成状态指示灯思考阶段与流式共用配色。
- `ui/chat/ComposerGlowStyles.kt`：`drawScopeScreen`（约 381 行，M1 信息屏、位置与数字开关、结束停留显示平均速率）、`drawSweepGrid`（约 478 行，M2 有序版：写入头沿固定行前进留下余辉，同斜率等距光带 1–3 道，闪点回到现状密度，速率按 0.6 次方映射）、`drawBottomStrip`（约 285 行，供运行坞细灯带复用）。
- `ui/chat/ComposerIsland.kt`：浮岛布局、顶沿点阵屏增高、运行坞嵌入浮岛顶层。
- `ui/chat/components/RunDock.kt`：`RunDockRow`（约 58 行）、`runDockMetaText`（约 247 行）；用时改为 `m:ss` / `h:mm:ss`，每秒刷新，等宽数字。
- `ui/chat/ChatScreen.kt`：速率输入（约 573、651 行，现只传 `streamingContent.length`）、运行坞每 15 秒刷新的计时（约 779 行附近）、运行坞挂载处（约 1223 行）。本波只有本会话改此文件。
- 设置项：`ui/settings/MotionStyleSettings.kt`、`ui/settings/ThemeScreen.kt`、`ui/settings/ThemeViewModel.kt`、`ui/theme/ThemePreferenceStore.kt`、`ui/theme/NexaraMotionStyles.kt`：点阵屏位置（输入行右侧 / 浮岛顶沿，默认右侧）与显示数字（默认开）两个子选项；动效预览页演示序列补上思考段。
- 新字符串：`res/values/ux2_motion_strings.xml` 与对应中文文件。

规则（方案 M1、R1）：同一浮岛只保留一条点阵带。点阵屏为顶沿模式且运行坞出现时，点阵屏放在运行坞与输入区之间的接缝处，顶替细灯带；运行坞收起时回到浮岛顶沿；点阵屏相对输入区不跳位置。灯珠墙类样式不铺到运行坞文字下方。

性能红线：绘制不在每帧分配对象，沿用现有 `LedBatch` 与画笔缓存；不在第三方组件回调中叠加滚动修饰符。

测试：速率表计入思考字数；只有思考内容到达时阶段为流式家族；无数据到达时回落等待；用时格式化（59 秒、1:00、59:59、1:00:00）；点阵屏数字取值（等待秒数、每秒字数、工具轮次、倒计时、平均速率）；位置规则在四种组合（右侧/顶沿 × 有无运行坞）下的布局结果。

### W1-S 设置页底部、白名单标注、折叠思考预览（D7、D8、P3）

文件归属：

- `ui/hub/UserSettingsHomeScreen.kt`：底部信息（约 597 行，`settings_ia_home_footer`）改为“Nexara 版本名（versionCode）· 侧载版 / Play 版”，点击复制版本信息，行尾 GitHub 标志按钮（48dp 触控区、无障碍标签）打开 `https://github.com/Promenar/Nexara`。
- `native-ui/app/build.gradle.kts`：只新增 `BuildConfig` 提交短 SHA 字段，源码不在 Git 仓库时写 `unknown` 且构建不失败；不改 `versionCode` / `versionName`，升版本由主控在出包时做。
- 新增 GitHub 官方单色标志矢量资源，颜色跟随主题前景色。
- `ui/settings/NetworkAccessTable.kt`、`ui/settings/NetworkAccessSettingsScreen.kt`：每行适用范围标注与 Linux 脚注。
- `ui/workspace/AgentReachSheet.kt`、`ui/workspace/AgentReachViewModel.kt`：会话内网络可达范围面板按运行模式灰显不生效的行并写明原因（灰显规范见[重设计规格](../specs/2026-10-17-nexara-settings-chat-redesign.md) §3.5）。
- `ui/chat/PipelineBubble.kt`：`ThinkingTrace`（约 812 行）、`streamingReasoningPreview`（约 162 行）、`fadingReasoningTail`。P3 采用单行与按行翻滚结合：进入阈值 45 字/秒、退出 35 字/秒，每行至少停留 0.5 秒，积压超过约 1.6 行跳到最新一行，模式切换约 0.2 秒交叉淡化，行宽按实际字形测量；单行模式匀速追赶末尾、新字渐显、标题微光速度随速率；系统关闭动画时直接显示末尾。
- 新字符串：`res/values/ux2_settings_strings.xml` 与对应中文文件；`settings_ia_home_footer` 所在条目归本会话修改。

测试：版本信息文本拼装（含 `unknown` 回退）；折叠思考的模式切换回差、行切分（按测量宽度）、积压跳行与最短停留；网络表适用范围标签按运行模式取值。

## 第二波

### W2-Q 选字引用批注（P1）

在第一波合并后派发。数据库版本 30→31，消息新增引用列表（来源消息 ID、引文、批注），备份、导出与分支复制带上；请求序列化为“引文块 + 批注”；输入区引用卡（超过 2 张折叠成一行，引用区最高约屏高三分之一）、发送按钮可用条件、未写批注的引用发送时丢弃；用户气泡显示引用，点击定位来源。实施前核实 Compose BOM 2026.06 的自定义文本菜单接口。文件归属在派发时按第一波合并后的代码确定。

### W2-D 不生效即灰显排查（D9）

先出排查清单（报告写入 `.agent/tmp-agent-reports/ux-round2/W2-D/`），列出按场景隐藏的设置与操作；再逐项改为灰显加原因，避开 W2-Q 的文件。已知一例：内置提供商的删除行。

## 收尾（主控）

1. 逐个复核分支 diff 与定向测试，合并进 `B-native-refactor`，清理分支、工作树与会话。
2. 全部合并后跑一次全量门禁：`:app:testSideloadDebugUnitTest`、`:app:lintSideloadDebug`、`:app:assembleSideloadDebug`、`:app:validateSideloadDebugScreenshotTest`、`:app:assemblePlayDebug`，以及仓库根的 `python3 scripts/ci/check-distribution-manifests.py`、`python3 scripts/ci/check-native-page-alignment.py`。
3. 按 `AGENTS.md` 第 6 节升到 `0.3.0-beta`（versionCode 5），切 CHANGELOG，签名包经 `scripts/verify-release-apk.py` 校验后装 PKH110 真机验收。
4. 真机验收清单：四处头像一致；新建弹窗两种类型写入的参数；思考阶段光效与指示灯；点阵屏四种位置组合与数字开关；扫光有序版观感；运行坞秒级计时与细灯带；折叠思考两种模式切换；进展语节奏；引用批注选字工具条（ColorOS）与跨段落、代码块、表格选择；查询改写默认值；设置页底部复制与 GitHub 按钮；网络表标注与灰显。
