# Nexara Metro Debugger 架构与非侵入式捕获设计方案 (V2 - 评审优化版)

> **物理时间**: 2026-05-18
> **探讨主题**: 基于架构大师 GLM-5.1 的深度可行性评审反馈，对 Nexara Native Android 实时调试桥进行架构级方案优化，探讨如何最大化复用已有资产（手动 DI、OkHttp 引擎、NexaraLogger 80+ 点位），实施高内聚、低侵入的全链路观测与桌面 TUI 渲染。

---

## 💡 方案演进与架构共识

经过对项目底座架构的深度剖析与 **GLM-5.1 架构大师** 的精准可行性评估，我们达成了一个极具建设性的**架构共识**：

> **“以最低的侵入性，复用已有资产，以渐进式演进路线，先极速落地 100% 稳定的 Logcat TUI 管道（路线 A），将 80+ 处已有日志埋点瞬间升级为结构化事件，覆盖 80% 以上的可观测性需求；后续再根据双向交互需要，按需叠加自愈式 WebSocket 传输层（路线 B）。”**

本 V2 版方案在此背景下进行了深度重塑，全面修正了对依赖注入（DI）的假设，并提供了针对 Ktor-OkHttp、Room 数据库、`NexaraLogger` 及 ProGuard 的保姆级落地路径。

---

## 一、 可行性评估与项目契合度分析

### 1. 🗄️ Room QueryCallback 方案 —— 零侵入持久化监控 (完全可行)
*   **落地分析**：项目已使用 Room（`NexaraDatabase`），数据库在 [NexaraApplication.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt#L90-L102) 的 `database` 属性中通过 `by lazy` 手动配置初始化。
*   **具体实施**：直接在 `Room.databaseBuilder()` 链式调用中追加 `.setQueryCallback()` 钩子，将每次 SQL 的语句与 bind 参数输出，**对所有 DAO 层代码 0 侵入**。
*   **审计日志复用**：系统现存 `AuditLogEntity` 与 `AuditLogDao`（`audit_logs` 表）。为了避免双重存储，我们可以直接在 QueryCallback 中，将特定核心动作（如 Message 变更、Task 变动）转译成结构化事件，并可根据配置选择是否轻量写入 `audit_logs` 归档。

### 2. 🌐 OkHttp Interceptor 在 Ktor 引擎中的注入 (完全可行)
*   **落地分析**：项目网络层采用 **Ktor Client + OkHttp Engine**，客户端配置位于 [NexaraApplication.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt#L156-L162)。
*   **具体实施**：虽然当前配置非常基础，但 Ktor 的 `OkHttpConfig` 原生支持直接注入拦截器：
    ```kotlin
    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addInterceptor(MetroLogInterceptor()) // 透明拦截 Ktor 发出的所有 OkHttp 网络请求
            }
        }
    }
    ```
*   **流式（SSE）拦截细节**：对于流式响应，需要在拦截器中包装 `ResponseBody`，逐块读取流（Chunk）以防止阻塞正常的流式输出。

### 3. 🪵 `NexaraLogger` 80+ 处已有埋点一键升级 (核心资产)
*   **落地分析**：这是本项目最庞大的“隐藏资产”。在 `ChatViewModel`、`RagViewModel`、`MemoryManager`、`ContextBuilder`、`GraphExtractor` 等 80 多个核心业务位置，已经手动埋入了 `NexaraLogger.log(...)` 调用。
*   **具体实施**：**改造量几乎为零！** 我们完全不需要重新发明 `MetroLogger`。只需重构 [NexaraLogger.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/utils/NexaraLogger.kt) 的 `log(message)` 方法：
    - 在内部对 `message` 进行简单的前缀识别或结构化解析；
    - 当 `BuildConfig.DEBUG` 激活时，将其封装为结构化 JSON，输出为带特定 Tag 的 Logcat 管道流，或者在将来推送给 TCP Socket；
    - **不需要修改业务层一行代码**，80 多个原有日志瞬间变成高密度的“可观测管道事件”！

### 4. 🧠 内存计算与中间件链 (Middleware Chain) 天然契合
*   **落地分析**：项目中的 `UnifiedLlmClient` 已经实现了 `LlmMiddleware` 和 `LlmMiddlewareChain`。
*   **具体实施**：对于大模型生成前后、上下文拼接、后处理等内存级高频动作，我们**不需要重新造一个管道拦截器轮子**。只需实现一个通用的 `MetroLoggingMiddleware : LlmMiddleware`，将其按需排在中间件链的 `PRE` 和 `POST` 位置，便能极其纯净地截获整个生成过程中的 Prompt 参数、图谱星图数据和最终 Token 指标。

---

## 二、 ⚠️ 针对项目实际的架构调整

为了 100% 对齐项目底座，我们对 V1 方案中不切实际的假设进行了以下重大调整：

### 1. 修正 DI 假设：完全基于手动依赖注入
*   **调整**：项目使用的是**纯手动 DI 体系**，而非 Hilt。因此，我们在 `NexaraApplication` 中以 `by lazy` 的形式手动初始化拦截器和日志分发器，并在构造 `UnifiedLlmClient` 或 `TaskRepository` 等相关仓库时，通过构造参数直接注入。
*   **优势**：免去了大量的注解编译开销，架构更加直观，调试门槛大大降低。

### 2. 区分 Debug/Release 日志与 ProGuard 物理剥离
*   **问题**：当前 `NexaraLogger` 无论 Debug 还是 Release 均会全量输出并写盘，且 Release 包中无 ProGuard 物理剔除逻辑。
*   **解决**：
    1.  在 `NexaraLogger` 中引入 `BuildConfig.DEBUG` 守卫：
        ```kotlin
        fun log(message: String) {
            if (com.promenar.nexara.BuildConfig.DEBUG) {
                Log.d(TAG, message)
                // 执行 Logcat 结构化输出
            }
            writeToDisk("DEBUG: $message") // Release 仍可写盘，但限制体积
        }
        ```
    2.  在 `proguard-rules.pro` 中为 Release 构建包增加 `assumenosideeffects` 规则，使得 R8 在编译打包 Release 包时，直接在字节码层面物理移除所有调试上报调用，实现线上版本 100% 纯净与零额外开销。

### 3. 通信层演进：先路线 A (Logcat 管道)，后路线 B (WebSocket 双工)
*   **调整**：当前项目 `build.gradle.kts` 中不含 `okhttp-websocket` 依赖，网络连接库基础设施较薄弱。
*   **策略**：
    - **第一阶段（路线 A - 绝对核心）**：App 端仅通过 Room 回调、OkHttp 拦截器和 `NexaraLogger` 结构化事件直接输出到 `Log.d`。桌面端 Node.js TUI 脚本通过 `adb logcat -s NexaraLogger` 抓取并解析。这一步**不需要引入任何新依赖**，2 天内即可高品质落地！
    - **第二阶段（路线 B - 双向拓展）**：在第一阶段稳定后，如果确有“桌面下发指令操纵真机”的需要，我们再引入 `okhttp-websocket` 依赖，基于我们已在 `NexaraApplication` 中做好的手动 DI 挂接，平滑升级到全双工长链接，而不需要触动任何捕获层逻辑。

---

## 三、 渐进式实施路线图 (Phase-based Implementation Plan)

### 🎯 第一阶段：Logcat TUI 管道 (路线 A - 极速见效版)

#### 1. 改造 `NexaraLogger` 与 ProGuard
- 修改 `NexaraLogger`，对于包含 `[RAG]`、`[THINKING]`、`[TOOL]`、`[DB]` 等标签的日志，自动转化为以下格式：
  `Log.d("NEXARA_METRO", "EVENT_START|${event_type}|${json_payload}|EVENT_END")`
- 在 `proguard-rules.pro` 中添加 R8 剔除配置，确保 Release 打包零负担。

#### 2. 注入 Room 数据库 QueryCallback
- 在 `NexaraApplication.kt` 实例化 `database` 的 lazy 块中，注入查询抓包，监控 `Message` 和 `TaskNodeEntity` 的数据落库时序。

#### 3. 改造 Ktor HttpClient 引擎
- 在 `NexaraApplication.kt` 声明 `httpClient` 的位置，利用 `engine { addInterceptor(MetroLogInterceptor()) }` 注入网络抓包，对于 `/chat/completions` API 的 Server-Sent Events (SSE) 流进行块度解析与 CPS 计算。

#### 4. 实现 `MetroLoggingMiddleware` 并挂载
- 实现 `LlmMiddleware`，并在构建 `UnifiedLlmClient` 时将其编入链条，彻底捕获大模型前后处理、Prompt 上下文和记忆压缩的详细内存对象。

#### 5. 编写桌面 Node.js TUI 解析脚本
- 在项目根目录下，创建一个单文件脚本 `scripts/nexara-metro-tui.js`。
- 它管道式监控 `adb logcat -s NEXARA_METRO` 输出，用 ANSI 控制符清除屏幕并渲染彩色动态日志瀑布屏，体验直追 React Native Metro Server！

---

## 四、 桌面端 TUI 控制台视觉设计 (Terminal UI Design Mockup)

当路线 A 跑起来时，你在 VS Code 的 Terminal 终端中运行 `node scripts/nexara-metro-tui.js` 即可看到如下动态面板，100% 实时反应真机中的所有动作：

```text
┌────────────────────────────────────────────────────────────────────────┐
│  N E X A R A   M E T R O   D E B U G G E R   [v2.0-Logcat-TUI]         │
│  Connection Mode: 🟢 ADB Logcat Stream Parsing (No Socket Overheads)  │
│  Target Device:   📱 Xiaomi 14 Ultra (Android 14, Wireless ADB)         │
└────────────────────────────────────────────────────────────────────────┘

[18:12:01] 👤 USER: "分析 CHANGELOG.md 中的 Room 修复逻辑"

[18:12:02] 🔍 [RAG VECTOR] ──> 🟢 Memory Vectors Searched (Took: 110ms)
   ├─ query: "Room 修复逻辑"
   ├─ similarity_threshold: 0.75 | limit: 5
   └─ 🟢 3 nodes matched (docs/audit/20260518-Nexara-Metro-Debugger-Discussion.md)

[18:12:02] 🧠 [CONTEXT ASSEMBLY] ──> ⏳ sliding window applied (window: 6)
   ├─ raw_tokens: 3410 | optimized_tokens: 1850 (compressed: 45.7%)
   └─ injected_skills: [CalculatorSkill, FileReadSkill, UpdatePlanSkill]

[18:12:03] 🌐 [HTTP REQUEST] ──> 📤 POST https://api.deepseek.com/v1/chat/completions
   ├─ model: "deepseek-reasoner" | temperature: 0.2
   └─ stream: true

[18:12:04] 🧠 [LLM THINKING] ──> ⏳ Started
   ├─ [Thinking Content]: 正在分析上下文，由于需要读取本地 CHANGELOG.md 文件...
   └─ 🟢 Thinking Completed (Took: 1200ms)

[18:12:05] ⚙️ [TOOL DISPATCH] ──> 📥 Tool Requested: "read_file"
   ├─ arguments: { "path": "CHANGELOG.md", "lines": 15 }
   ├─ 审批状态: 🟢 Auto-Approved (Skip Confirmation)
   ├─ [TOOL EXECUTING] ──> 🛠️ Invoking Local OS File API...
   └─ 🟢 Tool Success (Returned 1242 bytes, Took: 85ms)

[18:12:06] ✍️ [LLM GENERATING] ──> ⏳ Streaming Tokens Started...
   ├─ Speed: 72 CPS | Total: 280 tokens
   └─ [Output]: Room 的 QueryCallback 是通过 RoomDatabase.QueryCallback 钩子实现的...

[18:12:09] 🗄️ [ROOM SQL INTERRUPT] ──> 📥 Write Action: Message Inserted
   ├─ sql: INSERT INTO Message (id, role, content) VALUES (?, ?, ?)
   └─ bindArgs: [ai_1779095744176, ASSISTANT, Room 的 QueryCallback...]

[18:12:10] 🏁 [GEN COMPLETED] ──> 🟢 Flow Terminated Safely.
   ├─ finish_reason: "stop"
   ├─ UI State Reset: 🟢 isGenerating = false (Clean exit)
   └─ Total Session Time: 9.0s
```

---
> 本 V2 版方案综合了 GLM-5.1 架构大师关于“复用资产、手动 DI 配合、Ktor OkHttp 支持、Logcat 管道先行”的卓越建议，是 Nexara 调试系统兼具极致稳定性与超低侵入性的终极蓝图。
