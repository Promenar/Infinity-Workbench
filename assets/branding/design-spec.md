# Infinity Workbench 点阵无穷应用图标

## 采用状态和来源

用户于 2026-10-29 采用点阵无穷方向：深蓝夜空背景上，一条扭转的丝带由多条平行点线组成无穷符号，左下进入交汇处的一段最亮、偏青白，右环偏紫并逐渐变暗。`references/infinity-dotmatrix-concept.png` 是用户提供的概念图，`provenance.json` 记录谱系与采用状态。

矢量母版不是从概念图描摹或取点，而是由 `scripts/branding/infinity_icon.py` 按参数重建：

- 中心线是一条不对称的 Bernoulli 双纽线，右环更大，左环像透视里远去的一端，整体倾斜 -11°。
- 丝带每圈翻转一次，在左下交汇段收窄成扭结；7 条平行点线沿丝带按弧长等距取点。
- 亮度按位置计算，点的半径、颜色与不透明度随亮度变化；亮度 ≥ 0.74 的点各带一个径向渐变光晕，另有 3 团大范围环境光和左下一撮星尘。
- 所有点缩放进中心 66dp 安全圆（半径 32.5dp）。

重建是确定性矢量制作，不增加生成式编辑次数。修改形状或光效时改生成器参数，再运行 `python3 scripts/branding/infinity_icon.py` 重写下列产物，不要手改 SVG。

## 产物

| 文件 | 画布 | 用途 |
| --- | --- | --- |
| `infinity-dots-master.svg` | 108 × 108 | 自适应图标母版：`background` 分组是一条径向渐变的满幅路径，`foreground` 分组是环境光、光晕、按亮度和色相分档合并的点与星尘 |
| `infinity-dots-monochrome.svg` | 108 × 108 | Android 13+ 主题图标：4 条点线、点更大，单条纯黑路径，由桌面着色 |
| `infinity-notification.svg` | 24 × 24 | 通知栏小图标：沿同一中心线的 2dp 实线描边，四周留 2dp；小尺寸下点阵会糊成一团，所以不用点阵 |
| `infinity-dots-dark.svg` / `infinity-dots-light.svg` | 512 × 512 | README 与对外展示稿，取中心 72dp 可见区；浅色稿为浅灰底、深蓝点阵、无光晕 |

母版只用 SVG 的一个受限子集：根级 `title`/`desc`、`defs` 中的 `radialGradient`（画布坐标、带不透明度的色标）、`background`/`foreground` 分组与其中的直接 `path`。路径填充为 `#RRGGBB` 或径向渐变，可带 `fill-opacity`；主题图标与通知图标只允许纯色不透明剪影，通知图标使用描边。不含位图、文字、字体、外链、裁切、变换或嵌套视口。

彩色图标日夜一致；光效依赖深色底，不提供浅底版本作为桌面图标。

## Android 构建契约

Android 图层尺寸、安全区与单色规则依据[Android 官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。VectorDrawable 不支持模糊，因此发光用径向渐变路径模拟：每个渐变在资源中是一条带 `aapt:attr` 内联 `<gradient android:type="radial">` 的路径。

项目通过 `native-ui/buildSrc` 的 `GenerateLauncherIcons` 和 `androidComponents.onVariants`，把每个变体的生成任务接入 `variant.sources.res.addGeneratedSourceDirectory`。资源合并自动依赖生成任务；`:app:generateInfinityLauncherIcons` 可单独生成并验证所有变体。Android Studio、其它 IDE/ADE、CI 和命令行均使用这条 Gradle 链路，构建时不需要 Python、Node 或图像工具。

每个变体拥有独立生成目录，由 Android Gradle Plugin 分配。自动生成：

- `drawable/ic_launcher_background.xml`、`drawable/ic_launcher_foreground.xml`：自适应图标的背景与前景，来自彩色母版的两个分组。
- `drawable/ic_launcher_monochrome.xml`：主题图标单色层。
- `drawable/ic_notification.xml`：所有通知的 `setSmallIcon`。
- `mipmap-anydpi-v26`：普通与圆形入口的自适应图标；`mipmap-anydpi-v33` 另附 `monochrome` 图层。应用 minSdk 为 31，不再生成非自适应回退。

Manifest 统一引用 `@mipmap/ic_launcher` 与 `@mipmap/ic_launcher_round`。禁止在来源目录或任一变体中放置 `ic_launcher*`、`ic_notification` 文件、旧的 `launcher_icon_colors.xml` 或同名 values 资源，也禁止另指旧图标；生成任务会报错，避免悄悄回退。

## 验证入口

- `python3 scripts/tests/test_launcher_icons.py`：在隔离 Gradle 项目中运行真实生成任务，覆盖图层与渐变转换、不透明度、单色与通知剪影、母版更新、覆盖防护与非法母版；并以 `--check` 确认已提交的 SVG 与生成器当前参数一致（数字允许 0.01 的舍入差）。
- `LauncherIconDeviceTest`：在设备上解析实际资源，检查背景为不透明深色、前景四角透明、点都在安全圆内、左下高光段有接近白色的点、单色层在安全圆内、通知图标为实线剪影。可通过 instrumentation 参数 `launcherIconTargetPackage` 指向已安装的正式签名 APK。

真机/OEM 桌面的遮罩形状、主题着色与缓存行为仍以对应设备显示为准。
