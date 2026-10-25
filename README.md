# Nexara

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/branding/nexara-fold-dark.svg">
  <img src="assets/branding/nexara-fold-light.svg" alt="Nexara 折叠 N 图标" width="112" height="112">
</picture>

![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)
![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4.svg)
![Version](https://img.shields.io/badge/version-0.2.2--beta-6366F1.svg)
![Stage](https://img.shields.io/badge/stage-release%20candidate-6366F1.svg)

> Android 端 BYOK 开源 AI 客户端，以 Kotlin 与 Jetpack Compose 原生构建，集成多服务商对话、RAG、知识图谱、Agent 工具、会话工作区和加密备份。

当前源码候选为 `v0.2.2-beta`（versionCode `4`）。资料页（原“知识库”）使用集中式任务状态微件、平铺目录下钻和会话工作区隔离，页面采用统一操作菜单。当前候选的源码检查、截图、设备和签名制品证据分别见[发行验证记录](docs/release/v0.2.2-beta-validation.md)；历史审计结论见[发行需求验收总账](docs/audit/20260922-requirements-acceptance.md)，不能替代当前候选验收。公开 tag 与 Release 需要单独授权。

模型管理支持独立公共目录更新：GitHub Actions 聚合来源并签名发布，App 验签缓存并保留用户编辑。使用与维护方法见[模型目录说明](docs/model-catalog.md)。

Android 开发调试与设备验收使用本机启动的专用 AVD；执行位置和设备选择边界见 [PDEC 契约](.pdec/README.md)。

移动端承接桌面项目与创作任务的验收入口见[移动工作流能力测试清单](docs/testing/mobile-workflow-capability-checklist.md)：覆盖技能、工作区、执行、远端服务、业务交付和故障边界，并提供高频迭代精简回归组。清单定义测试目标，通过状态以绑定具体 APK 和环境的执行记录为准。

## 主要能力

- **多服务商 BYOK 对话**：支持 OpenAI（Chat Completions 与 Responses）、Anthropic、Google Gemini API、Google Vertex AI、DeepSeek、GLM、Kimi 及 OpenAI-compatible 接口，包含 SSE 流式响应、多模态输入、Markdown/LaTeX/Mermaid/ECharts 渲染和会话内模型切换。表格按单元格内容计算列宽（56–280dp，超出换行），总宽超过屏幕时横向滚动，单元格内保留粗体、斜体、行内代码与链接。
- **历史 Provider 安全恢复**：无法识别的历史主/额外 Provider 会在管理页显式显示且保持零联网；用户可保留名称与地址进入修复流程，明确选择新协议并处理凭证后恢复使用。
- **完整文档上下文与会话分支**：输入栏可把 TXT/Markdown 全文作为当前消息上下文发送，超出最终路由模型预算时在网络请求前阻止；会话可导出为可回传的 Markdown/TXT，并可从稳定消息创建独立分支。
- **设置界面**：设置页共用 48dp/64dp 设置行，窄屏长标题与放大字号可自然增高；首页导航不显示脚注，详情页说明使用阅读换行。同组图标统一有或无，数值由“当前值 + 箭头”进入底部选择弹层，少量整数用行内步进器。会话设置依次为模型、参数、工具、上下文；范围、默认值和偏好键见各设置说明。
- **会话阅读与输入**：单行顶栏、右侧用户气泡与通栏助手正文；思考、工具、检索以自然语言步骤行呈现，详情按需展开。完成回复下方提供操作行和模型时间，复制包含整轮正文；点击模型时间弹出该回复的生成统计（输入/输出 Token、缓存读取、推理 Token、首 Token 耗时、生成速度、端到端耗时与运行时间分布，多轮工具调用按整条回复合计）。思考详情支持 Markdown，审批卡顶部是类别条（内置工具或哪类命令、执行位置、风险标签），标题写“需要批准：动作”并附确切工具 ID，其下是命令或参数预览、类别说明与后台运行方式，“拒绝 / 批准”等宽并排；多项审批各带类别条列出后续调用。子代理需要授权时，当前打开的会话在会话流底部按子代理分组显示同款审批卡（“拒绝 / 允许”），运行坞的待批准计数包含这些请求；其它会话的子代理审批仍以全局弹窗提醒。流式挂载显示已有内容，新文字按到达速率逐帧放出，生成结束后自动跟随尾部布局；输入框、附件、模型选择、上下文用量和发送/停止共用一个与底部导航同款厚实质感的浮岛，浮岛顶部是一行运行坞：显示当前步骤或运行状态、计划进度与用时，右侧依次是待批准跳转、后台任务与状态面板入口，下方 2dp 进度线；点开进入运行详情（计划、后台、状态三页，计划按顶层分组、完成的组折叠，底部是本次运行的轮次、调用数与 Token 统计以及“标记完成 / 继续处理”），隐藏计划后状态胶囊位于浮岛上方。生成中描边亮起绕行流光（Android 13 起带同步环绕光晕），出错时整圈泛红后消退；生成期间可以继续写下一条（含图片与文档附件），发送即进入当前会话的排队托盘，本轮成功结束后按顺序自动发出；排队消息可编辑、删除、移到最前或“插队”——当前轮在下一次工具调用结束时提前收尾、保留已完成的工具结果，随后立即发出该条。本轮失败、被停止、应用重启或结束时不在该会话，队列暂停并说明原因，点“继续”恢复。行首输入 `/` 弹出命令候选：`/技能名 补充说明` 让模型在这一轮直接按该技能工作（客户端预先加载技能说明），内置命令 `/new`、`/rename`、`/prompt`、`/compact`、`/clear`、`/mode`、`/tools`、`/export` 直达需要多步操作的会话动作，其中 `/export` 可把会话导出为 Markdown 或纯文本。助手正文长按即可自由选择文字，用户气泡的长按菜单提供“选择文本”；生成时默认保持亮屏，可在设置中关闭，或用运行坞右侧的开关只改本次回复。AI 可以创建会话画布（可视化展示、交互小工具或临时工作台），生成中以紧凑卡紧跟在创建它的步骤下方，回合结束后在正文下方汇总为“本轮产物”（缩略图按手机宽度排版后缩小），右上角菜单“画布”列出本会话全部画布，全屏查看可切换历史版本、复制源码，页面还能把文字写进输入框草稿供你发送；画布不能联网，页面脚本需要 Google WebView 152 及以上，否则只显示静态预览。文字游戏或写作时，AI 可以建立会话状态面板（文本、数字、进度条、列表字段），每轮自动更新，运行坞的状态入口点开即可查看（数值与进度两两并排，刚更新与你改过的项带标记）并手动修改，状态随重新生成、删除消息与分支一起回退或复制。空会话中央以粒子拼出品牌字标，开始输入时散开、清空后聚拢，有消息后不再运行；系统关闭动画时以上效果均为静态。断网、超时或限流时自动重发本轮请求（默认 10 次，工具设置中可调 0–20），等待期间显示原因、倒计时与次数。发送后输入框与附件立即清空，生成被回滚时填回。OpenAI Chat Completions 兼容协议族按来源字段回传思考内容（满足 DeepSeek、Kimi 带工具调用时的官方要求），Anthropic 协议在工具循环内回传带签名的思考块，签名被拒时去掉思考块重发一次；Gemini API 与 Vertex AI 回传 thought signature 并支持工具调用。视觉合同和验证边界见 [design-qa.md](design-qa.md)。
- **会话上下文管理**：未达阈值时发送全部未摘要历史；达到阈值后先折叠较早工具结果，再将最近 N 轮之前的对话总结。会话设置可调整阈值与保留轮数，用量面板和设置均提供“立即压缩”；压缩使用当前会话模型，完整记录保留。规则与兼容说明见[上下文管理](docs/context-management.md)。
- **会话媒体与实机诊断**：生图、MCP、下载与脚本产出的图片、视频、音频统一保存在会话工作区（默认 `media/<日期>/`），模型以 `![说明](工作区路径)` 引用后在会话内显示为图片、视频、音频或文件卡片，视频与音频可内联或全屏播放，可保存到相册或下载目录、分享；支持多模态的模型可用 `view_media` 查看工作区媒体，只支持图片时视频按可调密度抽关键帧；设置页可导出有界、脱敏的 JSONL 诊断事件，辅助定位真机上的 Provider、生成和会话删除问题。
- **RAG 与知识图谱**：导入 TXT、Markdown、PDF、Word、HTML 文档，提供向量检索、FTS5、Rerank、查询重写、引用追踪、持久知识图谱聊天检索和有界图谱浏览。检索高级设置可调整图谱扩展、即时抽取回退及文档抽取预算；大文档建图需确认，超预算展示部分完成进度。对话记忆按时间线注入：每条记录带所属消息的时间、按先后排列，回顾某段时间的问题（如“上周二我们聊了什么”）按时段检索；记忆设置中可开启“提取事件与状态”（默认关闭，每轮多一次模型调用），同一“对象 · 属性”以最新值为准随检索提供。记忆与文档检索需要在 设置 → 默认模型 中选择嵌入模型；未选择时检索静默跳过，会话页只提示一次。
- **Agent 与工具调用**：内置联网搜索、计算、隔离脚本、工作区文件（读写、补丁、新建、移动、回收、内容搜索）、图像生成和任务规划等工具，按“工作区 / 完全访问”两档范围授权并提供写入预览、幂等执行账本和可纠错的工具结果回传。工具设置默认 50 轮，可选 1–100 轮或“不限”；不限模式同时取消固定调用数限制，配有 token 预算、重复调用暂停、上下文压缩和私有大输出分页读取。首次“不限＋完全访问”执行需确认，聊天页与前台通知显示进度且可停止；预算耗尽后自动总结。技能分两级：用户级位于 `/skills`、所有会话共享，目录型工作区的 `.agents/skills/` 为项目级技能（需用户信任）；Agent 可用文件工具或 shell（`$NEXARA_SKILLS`）查看、新建、修改技能或 clone 技能仓库，写入技能目录逐次审批。模型在任务匹配或用户以 `$技能名` 点名时按需加载技能指令、参考文件并运行附带脚本；用户级技能随备份迁移。任务计划以融入输入框顶部的单行计划条呈现（当前步骤、进度与进度线），点击向上展开步骤列表与“标记已完成 / 继续处理”，出现、收起与展开均带尺寸动画；计划全部完成后停留片刻自动收起；× 隐藏未完成计划（收为状态胶囊，计划推进后自动重新出现）或清除已完成计划。生成停止后仍可人工继续或销项，不会把残留计划误报为仍在生成。
- **联网搜索提供商**：设置 → 工具 → 联网搜索中可选择 DuckDuckGo、Keenable、Tavily 或 SearXNG；模型只看到一个 `web_search` 工具（参数只有 query），工具调用与会话联网检索共用该选择。Keenable 无需密钥即可使用公共共享额度，可选 API Key 使用账户额度；密钥由安全存储管理，仅在用户选择包含密钥的加密备份中导出。搜索结果保留网页来源、链接与摘要。
- **SSH 与远端终端**：设置 → 工具 → SSH 主机与终端中管理主机别名、密码或密钥认证，支持生成 Ed25519、导入口令保护的 OpenSSH 私钥和复制公钥。首次连接需要确认 SHA256 指纹，指纹变化会拦截；Agent 可通过别名执行命令、读写 SFTP 和使用常驻 PTY，工作区档下每条命令逐次审批，并支持大输出分页。用户终端支持 ANSI、滚动历史、控制键与旋转调整窗口，本地转发仅监听 `127.0.0.1`。在同一页开启“允许 Agent 管理 SSH 主机”（默认关闭、不随备份迁移）后，Agent 可列出、新增、修改、删除主机，并为密码主机安装本机生成的 Ed25519 公钥实现免密登录；这些变更在两档权限范围下都逐次审批，密码与指纹只在应用弹窗中由用户输入和确认，工具结果不含凭据。
- **Termux 本机终端**：两个发行渠道均可从设置 → 工具 → Termux 接入与诊断启用集成。`termux_exec` 在 Termux 的 bash 中后台执行，支持工作目录、stdin 和 1–600 秒超时，默认 60 秒；沿用 PROCESS 审批，目标绑定 `termux` 与完整命令。诊断页提供安装、外部调用开关、系统权限、SSH 公钥复制和主目录 SAF 挂载引导。
- **内置命令执行**：无需 Termux，`shell_exec` 用系统 `/system/bin/sh`（POSIX sh 与系统 toybox，命令随设备不同）、`python_exec` 用随 APK 打包的 Python 3.14（仅 arm64-v8a，标准库完整，附带 dulwich）直接在会话工作区根目录运行；shell 中可用 `python3`/`python`，以及基于 dulwich 的 `git`（clone、commit、log、diff、push、pull 等常用子命令，远端只支持 HTTPS，不支持的子命令返回 `GIT_UNSUPPORTED`）；侧载版另有 `pip`，可把纯 Python 包装到应用用户目录（含原生扩展的包无法导入），Play 版不提供 pip。`.git` 内部文件入索引但不进入资料向量化。工作区档逐条审批，完全访问档不审批；审批卡以“内置命令”类别条写明命令以 Nexara 身份运行、可访问全部应用数据、不受工作区限制，删除或覆盖的文件不进回收站。命令结束后检测数据库、`shared_prefs`、`no_backup`、用户级技能目录与诊断日志的变化，在工具行下方常驻标出；系统提示词要求模型改动这些关键数据前取得你的书面同意。运行期间独占工作区写锁（文件工具的读写排队等待），结束后按磁盘对账索引并在 `workspace_changes` 中列出新增、修改、删除与移动；保留路径、符号链接与特殊文件不入索引并给出警告。默认 60 秒、最多 600 秒，超时、取消或命令结束时结束整个进程组，stdout/stderr 各保留尾部 32,000 字符。运行期间持有唤醒锁，侧载版在通知栏显示命令并可停止（Play 版只有唤醒锁）；系统冻结的时间不计入超时，ColorOS 等系统需把 Nexara 设为“完全允许后台行为”，离开应用后命令才能持续运行。
- **内置 Linux 环境（侧载版）**：设置 → 工具 → 本机与远端 → Linux 环境，一键安装随 APK 打包的 Alpine Linux（proot，仅 arm64-v8a，无需 Termux 或 root）。开启“命令在 Linux 中运行”后，`shell_exec` 与 shell 后台任务在 Alpine 中执行，可用 `apk add` 安装软件包；另有本地终端、Linux 文件浏览、软件包与镜像源管理、命令完整输出查看，`/root` 可作为会话工作区并出现在系统“文件”应用中。使用方式与边界见下文“内置 Linux 环境”。
- **内置 Tailscale**：设置 → 工具 → Tailscale 启用后，Nexara 在进程内运行一个 Tailscale 节点（tsnet），不占用系统 VPN，可与其它 VPN 同时使用；支持浏览器登录或 auth key、自建控制服务器（Headscale）、临时节点与设备白名单。SSH 主机、`shell_exec`、`python_exec`（含内置 git）、应用内 HTTPS 请求以及模型提供商（对话、向量化、重排、生图）可直接访问 tailnet IP 与 MagicDNS 名称，其它目标保持原有路径；`web_fetch`、`media_fetch` 与脚本联网访问 tailnet 时固定经内置 Tailscale 代理，未连接时直接失败；Agent 可用只读工具 `tailnet_status` 查看节点与设备。提供商表单按接口地址自动识别“公网 / Tailscale 内网”API 源，并可从 tailnet 设备下拉框选择网关设备填入地址（内网地址需为 https，可在网关设备上用 `tailscale serve` 提供）；发送到内网模型时会等待内置 Tailscale 接入后自动继续，未接入时请求直接失败而不回退直连（内置 Tailscale 关闭时立即提示去开启），模型选择器以公网与 Tailscale 两盏指示灯显示连通状态。节点只在 Nexara 运行期间在线，退出后下线。
- **本机 MCP（高级）**：工具的 MCP 配置中可显式开启本机 HTTP，仅接受 `http://127.0.0.1:<端口>`。访问令牌必填并由 SecretStore 保存，发现和执行均使用 Bearer，工具按 PROCESS 风险审批；关闭开关立即停止暴露与执行环回工具。
- **会话工作区**：资源管理器与资料页同样按目录下钻浏览（页头只有一个返回按钮，子目录内回到上一级；上级路径可点击跳级，系统返回同样逐级退回），搜索结果平铺并显示所在路径，点击即进入所在目录。按 Session 隔离文件根目录，覆盖导入、原子写入、版本、回收站、恢复和路径逃逸防护；文档索引使用版本化目标与失败重试，进程重启后按当前文件版本和 KG 配置补建缺失任务。
- **后台任务**：`shell_exec`、`python_exec`、`termux_exec` 传 `background: true` 即在后台运行并立即返回任务号，默认最长 2 小时（上限 24 小时），结束后可通知或自动唤醒会话发起下一轮；`job_status`、`job_wait`、`job_stop`、`job_list` 查询、等待和停止。会话专用空间中的任务在副本里运行、结束后合入，冲突保留前台版本并把后台版本放进 `jobs/`。侧载版退出应用后继续运行；Play 版只在应用位于前台时保证运行。聊天页活动标签与面板显示实时输出、停止按钮与日志入口。
- **定时任务**：AI 可在会话中创建单次、间隔或 cron 定时任务（创建总需你批准，审批卡列出接下来三次触发时间），适合 Agent 在长任务中挂轮询（如定期检查构建状态、完成后自行取消）；应用在前台时由应用内计时器准点触发，退到后台时由系统闹钟或 JobScheduler 兜底（可能被系统推迟）。到点在会话里插入“定时”触发行并发起一轮回复，沿用会话的权限范围；需要你处理时暂停并通知，点通知回到会话继续。前台固定间隔最短 1 分钟，重复任务退到后台后精确闹钟间隔至少 5 分钟、系统择时至少 15 分钟；单次提醒用 1–10080 分钟倒计时，你给出具体钟点时才用绝对时间。每会话最多启用 5 个、共 20 个。设置 → 工具 → 后台与定时汇总全部任务和运行条件（精确闹钟、电池优化、通知）。定时任务只存在本机，不进入备份。
- **画布文件**：画布当前版本同时保存为会话工作区的 `canvas/<标题>.html`，可在资源管理器或用文件工具、命令编辑，修改在下次打开或读取画布时成为新版本。
- **工作区三分类**：会话专用空间、内置文件系统与（侧载版）设备文件系统；Agent 可设默认工作区，会话可单独切换，文件工具、`shell_exec`、`python_exec` 与内置 git 直接在该目录工作，资源管理器可复制、移动、重命名、删除与恢复文件，删除会话时可把专用空间文件保留到内置文件系统；会话资源面板为 git 仓库提供只读的变更与提交差异面板，文件工具执行卡与审批卡显示逐文件增删行数与差异。
- **外部文件夹挂载**：文件面板可为当前会话添加 SAF 文件夹或（侧载版）存储路径，以 `/mnt/<别名>/...` 供现有文件工具读写；挂载只属于添加它的会话且一律可读写，支持浏览预览、加入资料快照和恢复删除副本。存储路径挂载另可被命令以真实路径访问，SAF 授权的文件夹只对文件工具可见；系统提示词逐个列出两者。侧载版另提供经过系统“所有文件访问”授权的共享存储真实路径入口，Play 版仅提供 SAF。
- **长文档安全编辑**：严格大于 1 MiB 的文件仅加载元数据；已读取正文严格超过 32K 个 UTF-16 代码单元、2,000 行或单行 16K 个 UTF-16 代码单元时自动进入性能保护，只预览约前 16K 字符，但复制和保存始终使用完整全文。一次输入跨过软线时，已修改内容不会被截断，仍可完整完成 CAS 安全保存。
- **安全备份与恢复**：核心数据可本地或通过 HTTPS WebDAV 手动备份；密钥默认不进入备份。备份包可独立启用加密，包含完整密钥时强制加密；设备本机个人头像与 Agent 头像不跨设备迁移，避免恢复出失效绝对路径。
- **本机头像管理**：个人头像与 Agent 自定义头像共用系统图片选择和 1:1 裁剪流程，可移动、缩放、旋转并明确确认；连续替换图片使用新的内容身份，避免图片缓存继续显示旧头像。Agent 自定义图片与内置向量头像都会提交本地数据库，Agent 首页复用同一持久化头像选择展示。
- **后台持续生成**：前台发起的当前生成任务在切后台、锁屏、旋转或 Activity 重建后继续，通过前台服务通知返回会话或停止任务。
- **本地优先**：会话、文档和配置保存在设备本地；云端模型调用直接连接用户配置的服务商。

## 密钥与隐私

- API Key、Vertex 凭据、搜索 Key、WebDAV 凭据、SSH 认证材料及本机 MCP 令牌由 Android Keystore 支撑的 `SecretStore` 管理，不以明文写入普通配置。
- 密钥字段默认显示 `****`；用户可在当前页面临时显示完整值，离开页面或失去焦点后重新隐藏。
- 备份默认排除所有 Key。启用“包含完整密钥”时必须设置备份密码；备份包使用 PBKDF2-HMAC-SHA256 派生密钥和 AES-256-GCM 加密。
- SSH 主机配置、信任记录和全部 SSH 凭据均不进入应用备份，包括启用“包含完整密钥”的加密备份；换设备需要重新配置与确认指纹。SSH 私钥、密码和口令不会交给模型，底层 SSH 日志关闭，工具错误只返回稳定错误代码，远端文本按当前连接的认证材料脱敏。
- Git 凭据（主机、用户名与访问令牌）不进入应用备份，包括启用“包含完整密钥”的加密备份；令牌只由 App 内凭据注入代理使用，不出现在命令、环境变量、文件、输出或模型上下文中。
- Termux 集成、本机 HTTP MCP 的启用开关和本机 MCP 令牌属于当前设备的授权，不随应用备份迁移；另一台设备需要单独配置与启用。
- 内置 Tailscale 的节点状态写入不进备份的应用私有目录，auth key 存于 `SecretStore`；两者不进入备份、日志、命令行与模型上下文，换设备需要重新登录。本机代理只监听 `127.0.0.1` 并只接受每次启动随机生成的凭据；该凭据经代理环境变量交给 `shell_exec`/`python_exec` 子进程，命令可读取，Nexara 退出后失效。
- 云端 Provider、WebDAV 与远端 MCP 使用 HTTPS；网络安全配置仅对精确地址 `127.0.0.1` 放开明文。本机 MCP 需显式启用和 Bearer 认证，因为同机其它应用也可能访问该端口；`localhost`、其它 IPv4 和 IPv6 地址均不作为 HTTP 例外。
- Release 诊断事件不得写入 Prompt、模型输出、请求/响应正文、完整 URL、Authorization、API Key、SQL 参数或完整异常堆栈；文件限制为 512 KiB，并可由用户主动导出。

## 内置 Linux 环境

仅侧载版、arm64-v8a 设备提供；Play 版不打包相关文件，入口不显示。安装包内含 Alpine 3.24.3 minirootfs（约 4 MB）与 proot，安装需约 64 MB 空间，勾选常用工具时约 200 MB 并需联网下载（默认清华镜像，可在镜像源页切换或测速）。安装在 `filesDir/linux/alpine`，不进备份；卸载 Nexara 时一并删除。

环境就绪且“命令在 Linux 中运行”开启时，`shell_exec` 在 Alpine 中以 Nexara 身份运行：工作区映射为 `/workspace`，有真实路径的外部挂载为 `/mnt/<别名>`，用户级技能为 `/mnt/skills`；SAF 授权的挂载只对文件工具可见。Linux 命令不受工作区路径约束，“工作区”档逐条审批，“完全访问”档免审批。关闭开关或环境未就绪时回到系统 `/system/bin/sh`，结果中的 `runtime` 字段说明实际环境。`python_exec` 与 `python_sandbox` 使用打包 Python，与 Alpine 中的 `python3` 和 pip 包相互独立。

重置会清空 `/root` 与已安装软件包、恢复初始系统，会话工作区不受影响；绑定到旧 `/root` 的会话需重新选择工作区。proot 下文件元数据密集的操作（大量小文件解包、编译）比原生慢。当前 Alpine 中原生 git 不使用 Nexara 保存的 HTTPS 凭据与 SSH 桥，需要认证的推拉可暂时关闭“命令在 Linux 中运行”，由系统 sh 中的内置 git 完成。

## Termux 接入

安装 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub](https://github.com/termux/termux-app/releases) 提供的 Termux，并先打开一次完成初始化。RUN_COMMAND 需要 0.109 及以上版本，在 `~/.termux/termux.properties` 开启 `allow-external-apps=true`，再为 Nexara 授予 RUN_COMMAND 权限；诊断页可复制配置命令、请求权限并运行固定测试命令。协议字段和限制以 [Termux 官方说明](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) 为准。

Termux 回传的 stdout/stderr 合计超过约 100KB 时保留尾部；工具分别返回原始长度和截断标记。超时或取消会释放本地等待并丢弃迟到回调，命令可能仍在 Termux 中运行，需要在 Termux 中检查和停止。Android 12+ 可能因子进程限制结束任务；`termux-wake-lock` 可防止休眠，但不能解除系统进程限制，用完执行 `termux-wake-unlock`。

SSH 路线复用 SSH 主机管理：在 Termux 安装 `openssh`、配置密码或公钥并运行 `sshd`，在 Nexara 添加 `127.0.0.1:8022` 和 `whoami` 返回的用户名。主目录通过系统文件选择器授权后，以 `/mnt/<别名>/...` 供文件工具使用；SAF 授权和终端执行权限分别管理。

## 隔离脚本运行时

`exec_js(code, allow_network=false, timeout_seconds=30)` 使用 QuickJS 1.0.15，在独立隔离 UID 服务中执行；优先返回 `result` 变量，未定义时返回最后表达式。支持顶层 `await`、Promise 和 `console.log/warn/error`；异步返回使用顶层 `await` 或将 Promise 赋给 `result`。`run_script(path, args=[], allow_network=false, timeout_seconds=30)` 执行会话工作区或 `/mnt/<别名>/...` 中的 `.js` / `.mjs`；模块通过 `result` 返回值，导入只接受入口所在目录内的相对 `.js` / `.mjs`，允许目录内的父级引用，拒绝越根、绝对路径、远程与裸包导入。每次调用创建全新引擎上下文，同一会话可复用隔离进程。

脚本仅通过主进程代理获得能力：`fs.read(path)` 返回 `{content, sha256, path}`，`fs.readFile(path)` 返回文本；`fs.stat/list/createFile/mkdir/writeFile/deleteFile` 复用文件工具的路径、只读、保留名、挂载身份与回收规则。覆盖写入使用 `fs.writeFile(path, content, {expected_sha256})`，删除可传同名版本条件。默认对写入按 `FILE_WRITE`、删除按 `DELETE` 单独审批，弹窗显示来源会话与文件目标；可记住会话内该文件操作的授权。工具设置的“脚本写入审批”可让写入继承脚本审批，删除仍单独审批。默认开启是因为批准脚本代码不等于已经看过它运行时生成的文件目标与内容。

`fetch(url, options)` 支持文本请求体、请求头、HTTP 方法，以及响应的 `status/ok/headers/text()/json()`。默认禁网，开启时与 `web_fetch`、`media_fetch` 共用同一套目标判定：公网直接放行，局域网与本机（含链路本地）需加入设置 → 工具 → 网络访问中的精确主机白名单（默认为空、不支持通配符），tailnet 经内置 Tailscale，未指定地址与组播地址始终拒绝。Android 系统策略只允许明文 http 访问本机 127.0.0.1，局域网服务需使用 https。网络逐跳验证并固定 DNS，不携带应用 Key、Cookie 或系统代理；白名单不放宽发行版的明文 HTTP 禁令。网络与写入授权设置属于本机状态，不随备份迁移。

超时为 1–300 秒，默认 30 秒；QuickJS 堆上限 64 MiB、栈 512 KiB。代码最多 50,000 字符，单文件和单次网络正文最多 64 KiB；每次执行最多 20 次网络请求（含重定向）、1 MiB 累计响应和 128 次能力调用。总结果限 64 KiB，日志限 16 KiB 并按顺序截断。超时、资源超限、生成取消会终止执行进程，失联后下次重建；主进程的阻塞能力调用最多占用 4 个共享槽，迟到回调不再交给脚本。已批准且已进入文件后端的操作沿用其提交与回滚语义，不能保证随取消原子撤销。真实 UID 权限、进程死亡与 JNI 行为需通过设备测试验收。

例如，读取后按版本保存：`var f = await fs.read('/notes.txt'); result = await fs.writeFile('/notes.txt', f.content + '\n完成', {expected_sha256: f.sha256});`。两个发行渠道提供相同脚本能力。

`python_sandbox(code, stdin="", allow_network=false, timeout_seconds=30)` 在同一个隔离 UID 服务中启动随 APK 打包的 Python 3.14（仅 arm64-v8a 设备）：看不到 Nexara 私有数据与工作区物理目录，没有可写目录，不能直接建立网络连接，只有标准库与 dulwich（侧载版 pip 装的包不可见）。代码用 `import nexara` 经同一套主进程代理访问工作区：`nexara.read(path)` 返回 `{content, sha256, path}`，另有 `read_text`、`stat`（不存在为 `None`）、`list`、`create`、`mkdir`、`delete`、`write(path, text, expected_sha256)`，以及“不存在则新建、存在则按当前哈希覆盖”的 `save(path, text)`；失败抛出 `nexara.NexaraError`（带 `code`）。`allow_network=true` 时 `nexara.fetch(url, method, headers, body)` 走与 `fetch` 相同的网络策略。文件、审批、网络与能力调用上限与 JavaScript 相同。结果沿用命令工具格式：`exit_code`、`stdout`（尾部 32 KiB）、`stderr`（尾部 12 KiB）与各流原始长度，`result` 变量（可 JSON 序列化，16 KiB 以内）另行返回；超时终止 Python 子进程并带回终止前的输出。需要直接读写大文件、子进程、git 仓库或 pip 包时使用 `python_exec`。

## 自选工作区使用

工作区分三类：**会话专用空间**（默认，带文件版本与回收站，计入备份，随会话删除）、**内置文件系统**（应用私有目录下的文件夹，两个渠道都可用，跨会话共用，不进备份、卸载即删除）、**设备文件系统**（侧载版的共享存储文件夹，需授予所有文件访问，卸载后保留，可与文件管理器、Termux 或电脑同步）。后两类合称目录工作区。在 Agent 编辑页设置“默认工作区”，之后新建的会话会继承它；也可以在会话右上角资源管理器顶部的工作区栏点“切换”，在按三类分组的选择器中为单个会话切换或改回会话专用空间。浏览时跳过符号链接与 `Android/`。

切换后，文件工具的 `/` 指向所选目录，`/mnt/<别名>` 挂载照常可用；命令直接在该目录运行，不做改动对账，可用 `git status` 查看改动。聊天中会留下一条切换提示（不发给模型），模型从系统提示词得知当前工作区。目录缺失或授权失效时工具一律拒绝执行，不会改用会话专用空间；会话设置中会显示原因。生成进行中或有待审批调用时不能切换。删除会话、删除 Agent 或把工作区移出列表都不会改动目录中的文件。删除使用会话专用空间且其中有文件的会话时，默认把文件保留到内置文件系统的 `session-年月日-时分` 文件夹并以会话标题登记，也可选择一并删除。

会话专用空间计入备份上限，不适合存放 git 仓库；在会话专用空间执行 `git clone` 会提示改用目录工作区。共享存储大小写不敏感且不支持符号链接，内置 git 会自动写入兼容配置并说明受影响的路径。工作区是 git 仓库时，会话资源面板显示分支与改动数，点开可查看已暂存、未暂存、未跟踪文件和最近提交的差异；面板只读，暂存、提交与推送仍在对话中交给 Agent。资料页“浏览”分组中的“工作区”可只读浏览已登记目录，并把单个文件加入资料。

会话资源面板的文件区跟随当前工作区：目录工作区下直接列出该目录内容，可逐级浏览、预览文本文件或用其它应用打开只读副本；与文件工具一样跳过符号链接和 `.nexara*` 内部文件。长按条目进入多选，可复制、移动、重命名、新建文件夹和删除：改名与移动在原处完成、不受大小限制，复制同名时自动命名为“名称 (2)”；删除先备份到回收站（保留 7 天、总量 32 MB），可在“回收站”页恢复，超出容量的条目会询问是否永久删除。Agent 删除的文件进入同一个回收站。生成进行中或有待审批时文件操作会被拒绝。目录工作区下暂不提供搜索与导入。工作区不可用时文件区只显示原因与重试，不会改为显示会话专用空间。

推拉私有仓库：HTTPS 远端在“设置 → 工具 → Git 凭据”中按主机保存用户名与访问令牌（GitHub 建议使用对 Contents 有读写权限的细粒度令牌），之后 `git clone/pull/push https://主机/...` 自动带认证，无需把令牌写进 URL。`git@主机:路径` 与 `ssh://` 远端使用“SSH 主机与终端”中的主机：例如添加主机 `github.com`、端口 22、用户 `git`，选择私钥认证并把公钥加到代码托管平台，验证并固定主机指纹后即可使用。推送仍按 `shell_exec` 的审批规则确认。

会话资源面板顶部工作区栏的指南针图标打开“Agent 可达范围”，汇总本会话 Agent 此刻能碰到的范围：当前工作区的类型与保护（回收站和版本、备份、Termux 可见性、卸载后是否保留），`/`、`$NEXARA_WORKSPACE`、`/skills`、`/mnt/<别名>` 在不同执行方中的含义，文件工具、内置命令、隔离脚本、Termux、SSH 五类执行方的可用状态，当前权限范围（工作区或完全访问），以及各工具对公网、局域网、本机、tailnet 的访问范围。面板只展示现状，判定仍在每次工具调用时进行；局域网白名单可在面板内直接编辑。设置 → 工具 → 网络访问提供同一张总表与白名单、Tailscale 入口。

## 外部文件夹使用

从会话资源面板或资料页“浏览”分组打开“外部文件夹”面板，在“添加文件夹”分组选择授权方式，设置别名，再选择文件夹；浏览挂载内容与挂载回收区都在同一面板内逐页进入，返回键逐级退回管理页。系统文件选择器可选择已安装 Termux 提供的主目录；Android 11 及以上禁止选择内部存储根、可靠 SD 卡根、Download 目录及 Android/data、Android/obb。详细边界见 [Android SAF 文档](https://developer.android.com/training/data-storage/shared/documents-files)。侧载版的真实路径入口先打开系统所有文件访问设置，返回后输入共享存储目录；应用私有、系统、Android 子树和符号链接均拒绝。

文件工具可用 `/mnt/<别名>/...` 或列表返回的 UUID 访问挂载。`write_file` 与 `patch_file` 必须提供 `expected_sha256` 或 `expectedHash` 之一（取自 `read_file` 返回的 `sha256` 或 `Hash`），私有工作区与挂载路径相同；缺失时返回 `MISSING_VERSION` 指引，版本冲突会拒绝覆盖。SAF 提供商没有可靠重命名能力时拒绝覆盖已有文件。具备重命名能力的提供商也没有跨应用 CAS 保证，外部编辑器与工具同时修改仍有短暂竞态。真实路径先写临时文件，再原子隔离原对象、核对版本并以不覆盖方式发布；原名可能短暂缺失。不支持硬链接的共享卷通过独占创建复制，覆盖写也可能短暂显示未完成内容。其它应用持有已打开的写句柄时，最终版本核对后仍有极短竞态，不能保证跨应用严格 CAS。恢复遇到并发占用或目录无法安全自动还原时，保留隔离副本并返回 `RECOVERY_REQUIRED` 和位置，需人工核查。

若进程在隔离后、发布或恢复前退出，原名可能持续缺失，当前没有启动时自动恢复。请在源目录用支持显示隐藏文件的文件管理器核查 `.nexara-replace-*`、`.nexara-delete-*` 副本，确认内容后移回未被占用的名称；保留并发出现的同名文件，避免覆盖。普通挂载列表隐藏这些内部副本。

挂载删除副本保存在应用私有回收区，内容总额上限 32 MiB、最多 100 项、保留 7 天，可从外部文件夹管理页恢复。副本不足时默认拒绝删除；显式 `irreversible=true` 必须逐次 DELETE 审批，审批卡注明“不可恢复”。资料使用独立快照，源文件修改后可重新导入，不自动实时同步。挂载配置和 URI 授权属于本机状态，不进入跨设备备份。

`/mnt` 是虚拟保留名。升级前已有的私有 `mnt` 目录保留原文件与 UUID，不自动搬移或覆盖；可在原文件面板将其重命名后通过普通路径访问，其后代仍可通过原 UUID 操作。挂载管理的重新授权要求选择同一来源，避免既有审批路径指向其它文件夹。

## 后台生成边界

后台生成覆盖用户在前台主动发起的单个当前任务：

- 支持切后台、锁屏、旋转和 Activity 重建；
- 通知可返回准确会话或停止生成；
- 通知权限被拒绝时可退化为仅前台生成；
- 不支持设备重启后续传或多会话并行生成；定时任务与后台任务唤醒触发的轮次在应用级全局串行排队，见“定时任务”。

## SSH 使用

在“设置 → 工具 → SSH 主机与终端”新增主机，别名使用小写英文字母开头，后接小写字母、数字、下划线或连字符，最多 48 字符。密钥认证支持 Ed25519、ECDSA 和 RSA 的 OpenSSH 新格式，含 bcrypt 口令保护；PEM、PKCS8、PuTTY 和跳板连接尚未提供。生成密钥后仅把公钥复制到服务端的 `authorized_keys`。在可信渠道核对密钥类型与 SHA256 指纹，再点击信任；更换服务端密钥时须在同一页面明确替换信任。

Termux 路径：在 Termux 安装 OpenSSH，运行 `whoami` 获取用户名、设置密码后运行 `sshd`。在 Nexara 配置主机 `127.0.0.1`、端口 `8022` 和该用户名，核对 Termux 中 `ssh-keygen -lf "$PREFIX/etc/ssh/ssh_host_ed25519_key.pub" -E sha256` 显示的指纹后打开终端。需要密钥认证时，把 Nexara 复制的公钥添加到 Termux 的 `~/.ssh/authorized_keys`。

Agent 协助配置：开启“允许 Agent 管理 SSH 主机”后，模型可使用 `ssh_host_list`、`ssh_host_save`、`ssh_host_delete` 与 `ssh_host_install_key`。保存密码主机且没有密码时弹窗请用户输入，保存密钥主机且没有密钥时生成 Ed25519 并只返回公钥；修改主机名或端口会清除已信任指纹。`ssh_host_install_key` 依次确认指纹、使用已存或弹窗输入的密码、生成新密钥并追加到远端 `~/.ssh/authorized_keys`（目录 700、文件 600、已存在则不重复），再用新密钥验证登录，成功后切换为密钥认证，失败则恢复原密码认证。后台限制可能暂停 Termux 自身，需按设备设置允许其运行。

Agent 工具只接受已配置别名，不接受地址或凭据。`ssh_exec` 默认 60 秒、最多 600 秒，stdout/stderr 合计采集有上限（超限时两路分配额度并保留开头），结果与 `termux_exec` 使用同一格式，退出码非 0 或超时记为失败；`sftp_read` 按 UTF-16 偏移分页，拒绝二进制与大于 16 MiB 的文件。`sftp_write` 支持 `expected_sha256` 冲突检查，以临时文件发布并保留目标权限，但 SFTP 不保证跨客户端原子比较写入。取消上传时会尝试删除临时文件；连接中断导致清理失败时，主机页显示提示，之后用相同配置连接会重试。

终端离开页面默认断开，可为单个主机选择保持；用户终端和本地转发运行时使用前台通知，可从通知停止。PTY 闲置 10 分钟自动关闭；Agent 生成结束释放连接，待审批期间最多保留 10 分钟，显式停止或删除会话会关闭所属连接。进程结束不恢复终端或转发，页面状态以运行时资源为准。

## GGUF 本地推理

GGUF/llama.cpp 不属于 `v0.2.2-beta` 稳定发行范围。Release 构建会关闭本地推理并拒绝打包 GGUF、llama 或 ggml 制品；相关代码只能视为实验性研发资产，不能据此承诺可用能力。

## 运行要求

| 项目 | 要求 |
|---|---|
| Android | Android 12 及以上（API 31+） |
| 目标 SDK | API 36 |
| 存储 | 建议至少保留 200 MiB；导入文档另计 |
| 网络 | 云端模型、Embedding、Rerank、联网搜索和 WebDAV 需要网络 |
| 权限 | 网络；通知权限用于后台生成，可拒绝但会退化为仅前台生成 |

`v0.2.2-beta` 使用既有包名；交付 APK 必须核对登记的发行证书。公开 `v0.1-beta` 的数据库为 v17，公开 `v0.2-beta` 为 v2；当前库提供前进迁移到 v23，版本链为 v18→v19（知识图谱实体与证据）→v20（外部挂载与快照）→v21（SSH 主机）→v22（自选工作区登记、会话与 Agent 的工作区绑定、Agent 用户规则及其历史）→v23（对话图谱证据的所属消息及索引、会话按助手的索引）。安装曾经过 `v0.1-beta→v0.2-beta` 时，两份数据库分别保全，启动页提供显式“保留两份并恢复”入口。双库恢复候选保持独立的 v18 合同，完成恢复后由主库迁移到 v23。恢复保留两侧业务和来源，冲突 ID 重映射关联，工作区建立新物理身份，合法旧附件可点击读取；未完成工具和索引任务等待用户重试。`v0.2.1-beta` 的公开单库及三段双库升级合成数据证据属于[历史验证](docs/release/v0.2.1-beta-validation.md)，不能代替当前候选 APK 或真实用户资料验收。真实安装应保留完整备份，不卸载、不清数据；来源不同、被重签名或包名不同的 APK 不能正常覆盖继承。

## 安装

公开制品只从 [Promenar/Nexara Releases](https://github.com/Promenar/Nexara/releases) 发布。截至 2026-10-01，公开 `v0.2-beta` 为 prerelease（2026-07-29 发布），Latest 仍为 `v0.1-beta`；`v0.2.2-beta` 尚未公开发布。当前 `v0.2.2-beta` 若由维护者直接交付，应同时提供 APK 的绝对路径、SHA-256、版本身份和同签名验真结果；未获授权前不会创建 tag 或 GitHub Release。安装时：

1. 取得维护者交付并验真的 APK 与对应 `.sha256` 文件；侧载主资产为 `nexara-v0.2.2-beta.apk`，Play 风味资产为 `nexara-v0.2.2-beta-play.apk`。
2. 校验 APK 的 SHA-256 与发布文件一致。
3. 在 Android 系统中允许当前文件管理器或浏览器“安装未知应用”。
4. 新安装直接侧载；从同签名旧版升级时直接覆盖安装，不卸载、不清数据。

不要安装 Actions 临时制品、Debug APK 或来源不明的重打包版本。

## 开发构建

```bash
cd native-ui
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest :app:lintSideloadDebug
python3 ../scripts/ci/check-distribution-manifests.py
```

首次构建会从 python.org 下载官方 CPython Android 包（每个 ABI 约 23 MB）并按 SHA-256 校验，之后使用 Gradle 缓存。构建期需要 PATH 中有 `python3.14`，用它预编译打包 Python 的字节码（也可用 `-Pnexara.hostPython=<路径>` 指定）。离线方式与打包结构见[打包 Python](docs/distribution-builds.md#打包-python)。Debug 构建不需要发行签名变量。Release 构建必须通过进程环境提供 `NEXARA_KEYSTORE_PATH`、`NEXARA_STORE_PASSWORD`、`NEXARA_KEY_ALIAS` 和 `NEXARA_KEY_PASSWORD`；仓库不保存 keystore 或密码。

内置 Tailscale 由 `native-ui/app/tailnet/` 的 Go 程序编译为 `libnxtailnet.so`：构建需要 PATH 中有 Go 1.21 及以上（经 `GOTOOLCHAIN` 自动切换到固定的 Go 1.27.1，依赖由 `go.sum` 锁定；首次构建需要能访问 Go 模块代理，或预先填好模块缓存后设 `GOPROXY=off`），可用 `-Pnexara.goExecutable=<路径>` 指定 go。临时没有 Go 时可加 `-Pnexara.tailnet=false` 跳过，此时设置页提示安装包未包含内置 Tailscale，发行校验会拒绝这样的 APK。详见[内置 Tailscale](docs/distribution-builds.md#内置-tailscale)。

`distribution` 维度包含 `sideload` 与 `play`，二者共享包名，沿用各构建类型的后缀。仅侧载清单声明所有文件访问权限；`DistributionCapabilities` 提供渠道和能力开关，尚未提供该权限对应的文件管理功能。设置中的“导出诊断”首行包含渠道及版本。Debug APK 位于 `app/build/outputs/apk/<渠道>/debug/`；签名构建、设备测试及完整任务/产物表见[发行渠道构建说明](docs/distribution-builds.md)。

聊天富内容使用受控 HTTPS 本地资源与公网 HTTPS 子资源；拒绝文件、内容提供器、内网地址与页面跳转。KaTeX、Mermaid、ECharts 和知识图谱模板保留本地加载；预览中的链接直接阻止，不会自动打开外部应用。安全策略及设备验收入口见[构建与渲染说明](docs/distribution-builds.md#富内容-webview)。

应用图标采用[折叠 N 矢量母版](assets/branding/nexara-fold-master.svg)，白底黑标为主，夜间资源为黑底白标，并提供 Android 自适应与单色主题图层。各变体通过 Gradle Variant API 自动生成图标，IDE、ADE、命令行和 CI 的正常构建均使用同一母版；无需本机图像工具。制作规格、覆盖防护和验证方法见[图标契约](assets/branding/design-spec.md)。

### Metro CLI/TUI

Nexara 没有面向最终用户、与 Android UI 等价的 CLI。仓库中的 `scripts/nexara-metro-tui.js` 是 **Debug 构建专用的开发者日志终端**，用于解析 `adb logcat` 中的脱敏 Metro 事件，不替代应用功能：

```bash
node scripts/nexara-metro-tui.js --help
node scripts/nexara-metro-tui.js --serial emulator-5554
adb logcat -s NEXARA_METRO | node scripts/nexara-metro-tui.js --stdin --no-color
```

该 TUI 支持中文帮助、设备/Tag 选择、标准输入、TTY/非 TTY 输出、明确退出码和缺失字段容错。Release 会关闭并剥离 Metro/调试日志入口；发行版仅保留应用内有界、脱敏且由用户主动分享的诊断事件文件。

## 技术栈

| 层级 | 选型 |
|---|---|
| 语言/UI | Kotlin、Jetpack Compose、Material 3 |
| 架构 | MVVM、Repository、Flow/Coroutines |
| 数据 | Room、SQLite/FTS5、设备文件系统 |
| 网络 | OkHttp、Ktor、SSE |
| 安全 | Android Keystore、AES-256-GCM、PBKDF2-HMAC-SHA256 |
| 导航 | Compose Navigation |
| 构建/发行 | Gradle、R8、GitHub Actions、GitHub Release |

架构快速参考见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，当前发行说明见 [docs/release/v0.2.2-beta.md](docs/release/v0.2.2-beta.md)，发行验证状态见 [docs/release/v0.2.2-beta-validation.md](docs/release/v0.2.2-beta-validation.md)。

## License

Nexara 基于 [GNU General Public License v3.0](LICENSE) 发布。设置列表、底部导航与外观页包含基于同为 GPLv3 的 [Bettbox](https://github.com/appshubcc/Bettbox) 视觉实现进行的 Compose 转译，固定来源与范围见 [第三方通知](docs/legal/THIRD_PARTY_NOTICES.md)。对应 Release 的完整源码由同名 Git tag 固定。
