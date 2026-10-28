# 产品更名为 Infinity Workbench：实施方案

> 用户于 2026-10-29 决定：产品名 Nexara 更名为 Infinity Workbench；应用 ID 与代码包名改为 `com.promenar.infinity`；代码中以 Nexara 开头的类名改为 Infinity 前缀；GitHub 仓库已改名为 `Promenar/Infinity-Workbench`。Play 渠道尚未上传过商店，改 ID 没有商店侧影响。
> 新应用 ID 安装后与旧应用并存，不能覆盖升级。用户自己的数据通过“旧应用导出备份 → 新应用导入”迁移，因此备份导入必须继续接受旧格式。

## 目标与非目标

目标：
- 应用 ID、命名空间、Kotlin/Java 包名、类名前缀、Gradle 工程名与任务名、用户可见文案、当前态文档统一改为新名称。
- 新应用能导入旧应用导出的备份文件。
- 改完后编译、单测与现有校验脚本全部按新名称工作。

非目标：
- 不改启动图标与品牌素材（新图标另行实施；现有图标生成链路原样保留，只随包名、任务名改名）。
- 不升版本号、不打签名包、不上真机（主控负责）。
- 不改写历史记录：`CHANGELOG.md` 中已有条目、`.agent/handover.md`、`docs/release/` 下的历史版本说明与验证记录、已完成的旧方案与计划保持原文。

## 映射表

| 旧 | 新 |
| --- | --- |
| `applicationId = "com.promenar.nexara.native"` | `com.promenar.infinity`（debug、deviceTest、minifiedTest 后缀照旧） |
| `namespace` 与包名 `com.promenar.nexara` | `com.promenar.infinity` |
| buildSrc 包 `com.promenar.nexara.buildlogic` | `com.promenar.infinity.buildlogic` |
| 类名、对象名、扩展名中的 `Nexara` / `nexara` 前缀（如 `NexaraTheme`、`NexaraDatabase`、`NexaraApplication`、`MaterialTheme.nexaraDomainColors`） | `Infinity` / `infinity` 前缀 |
| `rootProject.name = "NexaraNative"` | `InfinityWorkbench` |
| Gradle 任务名中的 `Nexara`（如 `generateNexaraLauncherIcons`） | `Infinity` |
| 品牌实体 `<!ENTITY brand "Nexara">`（各字符串文件各一份）与 `app_name` 的 `resValue` | `Infinity Workbench`；`app_name` 的开发、设备测试、压缩测试变体保留各自后缀说明 |
| 通知深链 `nexara://` | `infinity-workbench://` |
| 仓库地址 `https://github.com/Promenar/Nexara` | `https://github.com/Promenar/Infinity-Workbench` |
| Room schema 目录 `schemas/com.promenar.nexara.data.local.db.NexaraDatabase/` | 按新类全名移动目录，历史版本 JSON 原样保留 |

## 保留与兼容清单

新应用是全新安装，应用沙箱内的名称没有旧数据需要迁移；下列标识涉及应用外部或旧应用产出的数据，按规则处理：

1. **备份文件**：新应用导出的文件名与扩展名改为新名称（例如 `infinity_backup_<时间戳>.iwb`）。导入、列表与识别逻辑同时接受旧的 `nexara_backup_<13 位时间戳>.nexara` 与旧格式内容（包括格式标记、版本号字段、内部键名中含 nexara 的部分）。补一条测试：用旧格式样例导入成功。
2. **备份加密**：若备份密钥或暂存标识（如 `nexara.backup.secret.staging.v1`、`nexara_backup_secret_*`）绑定在应用 Keystore 里，跨应用导入旧备份时无法取到旧密钥；先核实旧备份是否由用户密码派生密钥。若依赖旧应用 Keystore，停下报告主控，不自行设计迁移。
3. **用户工作区里的落盘文件**：工作区、挂载目录等用户自有存储中的 `.nexara` 标记目录与 `.nexara-*` 临时文件，写入改用新前缀（例如 `.infinity`、`.infinity-*`）；读取与清理同时识别旧前缀，旧应用留下的临时文件也能被清理。
4. **应用沙箱内名称**（数据库文件 `nexara_v2.db`、SharedPreferences 名 `nexara_*`、诊断日志文件名、沙箱内临时目录）：直接改为新名称。系统提示词里描述这些路径的文字同步更新。
5. **旧应用 ID 字面量**：`com.promenar.nexara.native` 出现在 Linux 运行时路径、`/data/data/...` 路径、CI 脚本、`blackbox-fixture`、`mainactivity-e2e`、`scripts/ci/*`、`.github/workflows/*` 等处，全部改为新 ID；运行时路径优先改为从 `packageName` / `applicationId` 推导，不写死。
6. **升级冒烟**：`scripts/ci/android-release-upgrade-smoke.sh` 依赖上一版正式包覆盖升级。新 ID 没有上一版，改为：没有同 ID 的上一版基线时跳过并明确输出原因，脚本逻辑保留给下一个版本使用。
7. **第三方与网络标识**：Tailscale 设备名、User-Agent、MCP 客户端名等对外标识中的 Nexara 改为新名称；改动前列出清单写进报告。

## 执行方式

- 在独立工作树分支完成，期间没有其它分支施工；完成后由主控复核合并。
- 主体用确定性脚本执行：先 `git mv` 包目录与 schema 目录，再按映射表批量替换，脚本放在 `.agent/tmp-agent-reports/rename/` 并随报告提交，便于复查。替换必须区分大小写与词边界，避免误伤（例如第三方库名、模型品牌资源 `assets/model_brands/`、URL 中的其它项目）。
- 脚本跑完后再按编译报错逐项修正；修正属于语义判断的（保留清单相关）在报告里逐条说明。
- 当前态文档同步：`README.md`、`docs/distribution-builds.md`、`docs/` 下仍在生效的架构与说明文档、`design-qa.md` 中的当前描述。历史记录不改（见非目标）。`AGENTS.md` 与 `.agent/` 下的文件由主控改。

## 验收

在 `native-ui/` 运行：
- `./gradlew clean`
- `./gradlew :app:compileSideloadDebugKotlin :app:compilePlayDebugKotlin :app:compileSideloadDebugAndroidTestKotlin`
- `./gradlew :app:testSideloadDebugUnitTest`（改名波及全部代码，这里跑完整单测）
- `./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug`，并用 `aapt2 dump badging` 或等价方式确认包名为 `com.promenar.infinity.debug`、桌面名称为新名称。

在仓库根运行：
- `python3 scripts/ci/check-distribution-manifests.py`
- `python3 scripts/ci/check-native-page-alignment.py`
- `python3 scripts/tests/test_launcher_icons.py`
- `python3 -m unittest scripts/tests/test_verify_release_apk.py`

残留检查：`git grep -n -i nexara` 的剩余结果只能是历史记录、旧备份兼容代码与其测试、旧前缀兼容读取，报告中按类别列出剩余条数。
