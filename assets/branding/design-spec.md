# Nexara 折叠 N 品牌与应用图标

## 采用状态和来源

用户于 2026-10-16 采用 `approved-fold-concept.png` 中的折叠 N：白底黑标为主版本，黑底白标为深色版本。形状由三个独立折面组成，保留书页展开与空间入口的读法。`provenance.json` 记录生成根、直接输入、生成式编辑序号、摘要与采用状态。

`nexara-fold-master.svg` 是应用图标形状的唯一来源。母版由三条具备稳定 ID 的闭合贝塞尔路径人工重建，分组为 `brand-logo`；不含位图、文字、字体、渐变、外链、裁切或变换。重建是确定性矢量制作，图像生成谱系不因此重置。

## 交付规格

- 模式：`digital`。母版为 108 × 108 像素，透明底纯黑；三个折面处于中心 66dp 安全区内。
- `nexara-fold-light.svg`：白底黑标，512 × 512 像素；`nexara-fold-dark.svg`：黑底白标，相同形状与尺寸。两张展示稿使用中心 72dp 视口，模拟 Android 桌面图标的可见区域。
- 品牌色为纯黑 `#000000`、纯白 `#FFFFFF`。图标不携带圆角外框或投影；圆形、圆角矩形及其它遮罩由 Android 桌面提供。
- `production.json` 可供 SVG Production 打包器生成带结构校验、SHA-256 和 ZIP 的独立矢量交付包，派生目录为 `build/vector-delivery/`。

## Android 构建契约

Android 图层尺寸、安全区与单色规则依据[Android 官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

项目通过 `native-ui/buildSrc` 的 `GenerateLauncherIcons` 和 `androidComponents.onVariants`，把每个变体的生成任务接入 `variant.sources.res.addGeneratedSourceDirectory`。资源合并自动依赖生成任务；`:app:generateNexaraLauncherIcons` 可单独生成并验证所有变体。`assembleSideloadRelease`、`assemblePlayRelease`、对应 `bundleSideloadRelease` / `bundlePlayRelease`、Android Studio、其它 IDE/ADE、CI 和命令行均使用这条 Gradle 链路，无需额外图像工具、插件、Python 或 Node。

每个变体拥有独立生成目录，由 Android Gradle Plugin 分配。自动生成：

- `mipmap-anydpi`：普通和圆形入口的矢量回退。
- `mipmap-anydpi-v26`：108dp 的自适应背景与前景；普通及圆形入口使用同一标志。
- `mipmap-anydpi-v33`：附加独立 `monochrome` 图层，与品牌标志具有相同透明轮廓，由支持主题图标的桌面着色。
- `values` / `values-night`：白底黑标 / 黑底白标。夜间版本按 Android 资源配置解析；应用内主题选择不会强制刷新桌面图标，具体显示及缓存刷新取决于桌面实现。

Manifest 统一引用 `@mipmap/ic_launcher` 与 `@mipmap/ic_launcher_round`。禁止在来源目录或任一变体中放置 `ic_launcher*` 文件、覆盖保留颜色资源或另指旧图标；生成任务会报错，避免悄悄回退。SVG 只接受根级说明、唯一 `brand-logo` 分组与三个直接路径，拒绝嵌套 SVG、变换或位图。

修改形状时编辑母版，核对展示 SVG 的相同路径并回渲，然后构建。不要编辑 `app/build/` 中的派生资源。

## 验证入口

`python3 scripts/tests/test_launcher_icons.py` 在隔离 Gradle 项目中运行真实生成任务，覆盖正反色、自适应/单色输出、母版更新与覆盖防护。

`LauncherIconDeviceTest` 检查 Android 实际解析出的前景、背景、分离缝、夜间版本、单色透明轮廓和安全区。可通过 instrumentation 参数 `launcherIconTargetPackage` 指向已安装的正式签名 APK，不以测试变体结果替代发行制品结果。

真机/OEM 桌面的形状、主题着色与缓存行为仍以对应设备显示为准。
