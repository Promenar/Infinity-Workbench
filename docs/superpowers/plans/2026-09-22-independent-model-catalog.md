# 独立模型目录实施计划

> 执行：按已批准范围直接实施；以 spec 和文件所有权为交接契约。

**目标：** GitHub 定时构建并签名发布模型目录，Android 独立获取更新，准确保留模型身份、供应商字段与用户修改。

**架构：** 公开多源归一化 → P-256 签名 manifest → GitHub Pages → App 验签与原子缓存 → 不可变解析器 → 模型管理。内置快照与缓存提供离线回退。

**技术：** Python 标准库、OpenSSL、GitHub Actions/Pages、Kotlin/kotlinx.serialization/Ktor、Material 3。

**规范：** [独立模型目录规范](../specs/2026-09-22-independent-model-catalog.md)。

## 全局约束与所有权

在当前已清洁的受跟踪工作树实施，保留已有 Android 本机工具链与缓存。当前分支直接承接用户授权的提交推送；不创建额外发布分支。`artifacts/` 与 `secure_env/` 均不提交。主控独占 Gradle、ADB、Git、云设置、签名材料、共享文档和资源文件；编译期间全部 native 源码冻结。

1. 云端工作包：独占新增 `scripts/model-catalog/build-published-catalog.py`、其 Python 测试、`.github/workflows/model-catalog-publish.yml` 与 fixtures；不得修改既有刷新器和共享资产。按规范 v3 输出，不运行 Android 测试，不接触密钥或实际部署。
2. 元数据工作包：独占 `data/model/catalog/ModelMetadata*.kt`、新增 `PublishedModelCatalog.kt`、`ModelInfo.kt`、`ProviderManager.kt`、协议层列表解析与对应 JVM 测试；保留旧 listModels API，新增 descriptor API。不修改 `BundledModelCatalog.kt`、Application、SettingsViewModel、页面或资源。主控集成列表调用与 runtime 切换。
3. 主控：新增目录验证/更新/缓存组件与测试，修改 runtime、Application、SettingsViewModel、ProviderModelsScreen 与资源；管理内置快照、PDEC、部署与文档。
4. 独立审阅：所有施工停止后审阅最终 diff 和验证证据；只读返回可复现缺陷，由主控修复。

## 执行步骤

- [x] 用离线 fixtures 写四源归一化与发布测试，验证失败再实现；产物结构与真实源交叉核对。
- [x] 用 JVM 测试固定原始 ID、作用域、冲突、三态和用户覆盖；实现 richer descriptor 与持久化。
- [x] 写传输信封、大小/哈希/签名/回放/失败保留测试；实现 IO 下载和原子缓存、runtime 状态与手动更新。
- [x] 将目录成功更新接入已有模型重解析与 UI，恢复完成后启动自动检查。
- [x] 更新 PDEC 中公开目录的 Actions 构建和 Pages 发布契约；生成独立密钥，公钥入 App，私钥经主机 CLI 直接注入 GitHub Secret。
- [x] 主控冻结 native 源码并串行执行针对性 JVM、全量 JVM、Lint、必要截图和构建；修复失败后仅重跑受影响检查。
- [x] 独立串行审阅；本机模拟器验证验签、离线启动、更新、用户覆盖和列表刷新。
- [x] 复核暂存并推送候选 SHA，启用 Pages，运行发布工作流；公网回读验签并通过 Android 真实 HTTPS 更新。
- [x] 同步 README、CHANGELOG、ADR-020、许可、注册表和运行说明；HLG 结构化追加；验证远端 SHA 后交付。

## 验收命令与审阅重点

Python：`python3 -m unittest discover -s scripts/tests -p '*model_catalog*.py'`。

JVM：在 `native-ui` 串行执行 `./gradlew :app:testDebugUnitTest --tests '*ModelCatalog*' --tests '*ModelMetadata*' --tests '*ProviderModelMetadata*' --tests '*GenericModelsEnvelope*'`，随后完整 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。设备构建按 `.pdec/contract.yaml` 的本机 operation 执行。

重点：签名校验先于信任字段、固定下载源、不回放旧版本、崩溃事务、不可变解析器、供应商额度不污染规范模型、wire ID 不被改写、用户编辑与供应商字段可持久化。任何假设失效、文件所有权冲突或新增不可逆边界须交回主控。

## 交付证据

实现与云发布源码为 `6f019e095697f2b93bf25edafe5d70108712ca2b`；逐项结果、APK 哈希、设备范围与未覆盖边界见[验收记录](../../release/2026-09-22-model-catalog-validation.md)。
