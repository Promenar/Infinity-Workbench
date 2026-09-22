# 独立模型目录验收记录

> 日期：2026-09-22；状态：本机功能与签名 APK、Pages 发布通过。
> 应用与发布器源码：`6f019e095697f2b93bf25edafe5d70108712ca2b`。
> Android 验收位置：本机专用模拟器；APK 为本地签名候选。

## 已上线的数据目录

[公开签名入口](https://promenar.github.io/Nexara/model-catalog/v1/manifest.json)由[发布工作流 35715420432](https://github.com/Promenar/Nexara/actions/runs/35715420432)部署成功，工作流 head SHA 与上述源码一致。每六小时抓取 models.dev 的规范模型与 provider 数据、LiteLLM、OpenRouter，并合入有官方证据的受控补充。定时运行可能延迟，不等同实时推送。

| 字段 | 回读结果 |
| --- | --- |
| 生成时间 | 2026-09-22T10:20:29Z |
| 目录版本 | 1790072429 |
| 记录数 | 13,144；包含供应商条目，不等于基础模型数 |
| 字节数 | 4,783,480 |
| 目录 SHA-256 | `3f53e615fc11db5b8a68a86fc3ec782ad98aaadb77c06ab6c788a55a419bda71` |
| 公网校验 | 固定公钥验签、字节数、SHA-256、JSON 记录数、第三方通知均通过 |
| App 行为 | 成功检查间隔 24 小时，失败自动尝试节流 1 小时，支持手动刷新；关闭 App 时不保证后台更新 |

内置 models.dev 快照为 422 条，作为无缓存离线入口。受控官方补充不猜测未知额度；例如 SenseNova 的未知上下文与输出上限保持空值。

## 本机质量与设备证据

| 验收 | 结果 | 本机原始证据（artifacts 不纳管） |
| --- | --- | --- |
| Python 脚本 | 102 项通过 | `model-catalog-python-final.log` |
| 最终全量 JVM | 2761 项，0 failure/error，24 条件跳过，2737 项执行通过；启用了真实公开目录解析测试 | `model-catalog-quality-final-02.log`、`model-catalog-final-quality/jvm-xml/` |
| 截图 | 100/100；其后仅调整队列处理标志和对应测试，没有 UI 修改 | `model-catalog-quality-final.log`、`model-catalog-final-quality/screenshot-xml/` |
| Lint / Debug | 0 Error/Fatal，527 Warning、22 Hint；构建通过 | `model-catalog-final-quality/lint.xml`、最终质量日志 |
| DeviceTest 构建 | 通过，arm64-v8a | `model-catalog-device-build.log` |
| API31 专项 | 4/4：平台签名与缓存、公网更新与缓存重开、网关列表身份匹配、2 倍字号刷新操作 | `model-catalog-device-api31.txt`、`model-catalog-ui-api31.png` |
| API36 专项 | 已验证网络条件下 4/4，同一组测试 | `model-catalog-device-api36-network-ready.txt`、`model-catalog-ui-api36.png` |
| 正式签名 APK | API31 / API36 同哈希冷安装、包身份、前台进程与启动通过 | `model-catalog-release-api31/`、`model-catalog-release-api36/` |
| 正式混淆包自动更新 | API31 实际自动下载并验签；缓存正文哈希与公网一致，成功应用后的检查时间已落盘 | `model-catalog-release-api31-catalog-proof.json` |
| 独立审阅 | Sol 分别负责云端发布与元数据，Luna 独立审阅缓存/信任与队列边界；主控复核并修复发现项 | diff、回归输出与 HLG |

API36 首轮没有默认网络，两项联网测试失败；启用 Wi-Fi/移动数据后的过渡轮首项仍失败。保存连接诊断并确认 VALIDATED 网络后，未修改代码或降低断言，同组四项通过。过渡轮未保留下载内部异常，不能进一步断言具体网络故障类型。上述记录均保留，不能把失败尝试计为通过。

五个网关 ID 为 `newapi/deepseek-v4-flash`、`newapi/gemini-3.8-flash`、`newapi/sensenova-6.8-flash-lite`、`openai-chatgpt/gpt-5.6-luna`、`newapi/MiniMax-M3`。真实 `/models` 列表的原始 ID 均保留并匹配规范模型；此验收不发送推理请求，不覆盖端点推理、工具调用或账号额度。

全量回归发现并修复了备份字段类型/输入额度往返、取消传播，以及索引恢复扫描尚未完成就启动处理器导致重复入队的问题。队列测试覆盖扫描门禁、扫描失败后普通入队与成功重试、已有任务结束时的处理状态；生产恢复不负责暂停之前已经开始的任务。

## 签名 APK 与源码绑定

| 字段 | 值 |
| --- | --- |
| 文件 | `artifacts/model-catalog-20260922/release/nexara-v0.2.1-beta-model-catalog.apk` |
| 大小 | 20,310,520 bytes |
| SHA-256 | `25ade4f6f5f2ac60b11a37501c8096f7ec4adff78b10f372bda0a95ccaaeca35` |
| 包与版本 | `com.promenar.nexara.native` / code 3 / `0.2.1-beta` |
| 唯一发行证书 SHA-256 | `00be4cdd8378aafbd70ebc43e971523791cc07deead631e06dcf155deeee3802` |
| native-ui Git tree | `824756e630981114db45ebb9394e5f6de4b14954` |
| 1112 个源码输入聚合 SHA-256 | `316049d3fbaf9a2ae14a98efdec1a3c044236ec394296f6b67683733dc9d8caa` |
| 绑定文件 | `artifacts/model-catalog-source-inputs.json`、APK 目录中的 `delivery-manifest.json` |

构建前后逐文件验证输入未变。签名、版本、敏感内容扫描、本地推理制品排除、R8 四类非空输出与 16 KiB ZIP 对齐均通过。包内 native 库逐字节与已审计候选相同，可复用其 ELF 静态对齐证据；不代表 16 KiB 页设备运行已验。两档设备的 base.apk 均回拉并与交付 APK 按字节比较。ADB COLD 显示 341ms / 484ms，仅为启动显示计时。

## 交付边界

目录覆盖度取决于上游公开资料，不能宣称全网模型字段完整、实时同步或实际端点能力均通过。物理真机、TalkBack 人工体验、所有供应商推理和既有全项目发行门禁保持独立。旧候选的完整升级/性能矩阵仍属于旧制品证据；此 APK 没有重复执行全部历史升级矩阵。

Android CI 会由提交推送自动触发，其远端结果另查对应工作流；本记录的 Android 结论来自上述本机命令，不将运行中或被后续提交替代的 CI 写为通过。Pages 数据发布已成功，APK 未执行公开 Release。专用模拟器与本任务 adb reverse 已停止/移除。
