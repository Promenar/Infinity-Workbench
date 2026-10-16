# 发行渠道构建与富内容渲染

`distribution` 为 `play` 与 `sideload`。两者共享代码与 `com.promenar.nexara.native`，Debug、DeviceTest、MinifiedTest 的后缀分别为 `.debug`、`.deviceTest`、`.minifiedTest`。同构建类型的两个渠道会覆盖彼此，不能并列安装。`DistributionCapabilities` 描述制品能力，不能代表系统已经授予权限。

只有 `src/sideload/AndroidManifest.xml` 声明 `MANAGE_EXTERNAL_STORAGE`。侧载渠道的共享存储真实路径后端需取得系统“所有文件访问”授权；Play 渠道仅使用 SAF。设置页“导出诊断”生成的 JSONL 首行 `app.build` 包含 `distribution`、`allFilesAccessAvailable` 与版本。

构建插件使用 Kotlin 2.4.10 编译器、Compose 与序列化插件，应用源码保留 2.2 语言级别；协程运行和测试库为 1.11.0。QuickJS 1.0.15 的 Android JNI 在两个渠道共用，JVM 单测仅在运行时替换为桌面 JNI。AAR 自带 JNI 消费端保留规则；项目不使用脚本对象反射绑定。隔离服务设备回归类为 `com.promenar.nexara.data.script.ScriptRuntimeDeviceTest`，设备测试默认 ABI 与 `nexara.deviceE2eAbi` 配置一致；ARM64 设备构建需显式传 `-Pnexara.deviceE2eAbi=arm64-v8a`。

在 `native-ui/` 串行执行：

```bash
./gradlew :app:testSideloadDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintSideloadDebug
./gradlew :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:assembleSideloadMinifiedTest :app:assemblePlayMinifiedTest
./gradlew -Pnexara.deviceE2e=true :app:assembleSideloadDeviceTest :app:assembleSideloadDeviceTestAndroidTest :mainactivity-e2e:assembleDeviceTest
./gradlew :app:validateSideloadDebugScreenshotTest :app:validatePlayDebugScreenshotTest
python3 ../scripts/ci/check-distribution-manifests.py
```

截图验证共用 `app/src/screenshotTestDebug/reference/`，不按渠道复制像素。设备构建只生成 APK；设备脚本会连接并修改设备，需在已授权的专用本机 AVD 上另行执行。`mainactivity-e2e` 以 `missingDimensionStrategy` 固定匹配侧载应用，其任务名保持 `assembleDeviceTest`。黑盒夹具为独立应用，保持 `:blackbox-fixture:assembleDebug`。

| 变体 | APK 相对 `native-ui/` 的路径 | mapping |
|---|---|---|
| sideloadDebug | `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk` | 无 |
| playDebug | `app/build/outputs/apk/play/debug/app-play-debug.apk` | 无 |
| sideloadDeviceTest | `app/build/outputs/apk/sideload/deviceTest/app-sideload-deviceTest.apk` | 无 |
| sideloadDeviceTestAndroidTest | `app/build/outputs/apk/androidTest/sideload/deviceTest/app-sideload-deviceTest-androidTest.apk` | 无 |
| mainactivity-e2e DeviceTest | `mainactivity-e2e/build/outputs/apk/deviceTest/mainactivity-e2e-deviceTest.apk` | 无 |
| sideloadMinifiedTest | `app/build/outputs/apk/sideload/minifiedTest/app-sideload-minifiedTest.apk` | `app/build/outputs/mapping/sideloadMinifiedTest/mapping.txt` |
| playMinifiedTest | `app/build/outputs/apk/play/minifiedTest/app-play-minifiedTest.apk` | `app/build/outputs/mapping/playMinifiedTest/mapping.txt` |
| sideloadRelease | `app/build/outputs/apk/sideload/release/app-sideload-release.apk` | `app/build/outputs/mapping/sideloadRelease/mapping.txt` |
| playRelease | `app/build/outputs/apk/play/release/app-play-release.apk` | `app/build/outputs/mapping/playRelease/mapping.txt` |

Release 构建需要安全注入既有四个 `NEXARA_*` 签名变量，在获得签名构建授权的主控会话执行 `:app:assembleSideloadRelease :app:assemblePlayRelease`，随后执行 `python3 ../scripts/ci/check-distribution-manifests.py --build-type release`。不把秘密写入命令、文档或报告。

发行工作流整理侧载主资产 `dist/nexara-v0.2.2-beta.apk` 和 Play 资产 `dist/nexara-v0.2.2-beta-play.apk`；二者各有 `.sha256`。R8 证据分别进入 `dist/r8/sideload/` 与 `dist/r8/play/`。冷安装冒烟覆盖每个渠道的 API 35/36，校验对应哈希、包名、版本与发行证书；v0.1 升级只使用侧载主资产。准备发行链路不构成公开发布，也不代表 Play 商店审核通过。

## 富内容 WebView

富内容池及所有派生客户端统一应用协议、地址与导航策略。打包的 KaTeX、Mermaid、ECharts、知识图谱模板通过 `https://appassets.androidplatform.net/assets/` 受控读取；其它应用资产、路径穿越和缺失文件直接拒绝。复用现有 OkHttp，无新增 AndroidX WebKit 依赖。

初始 HTML 作为内存快照注册到随机 HTTPS 文档 URL，仅该完整 URL 的主框架 GET 请求可读取；更换内容后旧 URL 失效。元数据与正文使用单个不可变快照发布到 WebView 请求线程，ECharts 在首次加载前安装客户端。内嵌图片和字体仅作子资源解析，限制 4 MiB；HTML、脚本和顶层 `data:` 请求拒绝。公网单资源限制 16 MiB，重定向最多六次请求。

公网 HTTPS 子资源经独立、无 Cookie/认证的 OkHttp 客户端读取；系统解析返回的全部地址均须为公网，实际连接使用该解析结果。自动代理和自动重定向关闭，每次跳转重新判定协议、地址与 DNS，拒绝 HTTPS 降级与转入本地资源。默认 WebView 网络直连关闭。阻止文件、内容提供器与非预期协议，关闭文件 URL 跨域访问、DOM 存储和地理定位；不注册原生 JS 桥接。

页面顶层导航与链接直接阻止，避免不可信模型内容主动打开外部应用；PNG 导出由 Android 原生绘制与 MediaStore 完成，不依赖页面导航或文件读取。CSP 禁止子框架、对象、Worker、表单提交、媒体与 WebSocket，仅允许渲染所需的内联脚本/样式、HTTPS 脚本/样式/图片/字体，以及内嵌图片/字体数据。PlantUML 保留 `https://kroki.io/plantuml/svg/...`。

主控在专用设备分别检查：离线公式与 Mermaid/ECharts、知识图谱模板、公共 CDN HTML 与 HTTPS 重定向、HTML PNG 导出、PlantUML；尝试访问环回、局域网、链路本地、混合 DNS 与公网转内网重定向应失败，点击链接不跳转。设备运行证据与 JVM 判定测试分别记录，编译通过不能代替运行验收。

历史发行账本与已完成计划中的旧任务、路径属于当时证据；当前可执行入口以本说明、工作流和 PDEC 为准。
