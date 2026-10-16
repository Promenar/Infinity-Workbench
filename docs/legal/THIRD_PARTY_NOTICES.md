## QuickJS 脚本引擎

- Android 依赖：`io.github.dokar3:quickjs-kt-android:1.0.15`；JVM 测试使用同版本 `quickjs-kt-jvm`，不打入 APK。
- 绑定库来源：[dokar3/quickjs-kt v1.0.15](https://github.com/dokar3/quickjs-kt/tree/v1.0.15)，Apache-2.0；[完整许可](licenses/quickjs-kt-1.0.15-Apache-2.0.txt)。
- 上游内嵌引擎：[bellard/quickjs，提交 04be246001599f5995fa2f2d8c91a0f198d3f34c](https://github.com/bellard/quickjs/tree/04be246001599f5995fa2f2d8c91a0f198d3f34c)，MIT，版权 Fabrice Bellard、Charlie Gordon；[完整许可](licenses/quickjs-MIT.txt)。
- 上游内嵌容器：[eteran/c-vector，提交 774773d4cb1e66dd736e90e7f482d4f22af464c6](https://github.com/eteran/c-vector/tree/774773d4cb1e66dd736e90e7f482d4f22af464c6)，MIT，版权 Evan Teran；[完整许可](licenses/c-vector-MIT.txt)。子模块身份从绑定库的固定版本树核验。
- 保留各库原许可、版权及免责条款。Apache-2.0 可纳入 GPL-3.0 项目，参见 [Apache 官方兼容性说明](https://www.apache.org/licenses/GPL-compatibility.html)。Chaquopy 未纳入产品分发。

## models.dev 第三方归属与许可

### 上游项目

- 名称：models.dev
- 来源：`https://models.dev/models.json`
- 源码仓库：`https://github.com/anomalyco/models.dev`
- 许可：MIT

### 引用与快照凭据

- 真实抓取与快照元数据以离线清单为准：`native-ui/app/src/main/assets/model-catalog/manifest.json`
- 该文件字段 `fetchedAt`、`sourceSha256`、`recordCount`、`catalogSha256`、`sourceBytes` 为本次冻结来源与校验锚点。
- 快照文件：`native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json`
- 目录用途：运行时离线加载与模型能力覆盖基线，不对外提供签名后 API 语义保证。

### Copyright Notice

`models.dev` 上游文件受 MIT 协议约束，版权声明如下：

```
MIT License

Copyright (c) 2025 models.dev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 交付边界（Nexara 责任说明）

- Nexara 会基于 `models.dev` 上游快照叠加厂商元数据与本地修正。
- Nexara 不保证该快照目录单独构成完整真实权威模型库。
- 规范数据与供应商条目经归一化、签名后通过 GitHub Pages 分发；来源与许可随目录保存，客户端保持验签与离线回退。

---

## Bettbox Material 3 界面参考与转译

### 上游项目

- 名称：Bettbox
- 上游仓库：`https://github.com/appshubcc/Bettbox`
- 本地固定来源提交：`714130a3d746d093f6278c0503dac9e2e43fd3e3`
- 许可：GNU General Public License v3.0
- Nexara 许可：GNU General Public License v3.0，与上游许可兼容

### 转译范围

Nexara 没有直接引入 Bettbox 的 Flutter/Dart 构建产物，而是将以下界面结构和交互语言按 Jetpack Compose 重新实现：

- `lib/widgets/list.dart`：标准 ListTile 节奏、20dp 圆角填充分组、弱化的组内分隔；
- `lib/widgets/google_bottom_nav_bar.dart`：36dp 浮动导航座、弱描边与阴影、选中容器、250ms 状态过渡；
- `lib/views/theme.dart`：三模式选择、种子色盘、纯黑模式与全局文字缩放；
- `lib/common/color.dart` 与 `lib/providers/state.dart`：种子色和纯黑表面的状态映射思路。

### 交付边界

- 业务模型、数据层、导航目的地、无障碍语义与 Android 触感仍由 Nexara 自身实现负责；Flutter 特有依赖没有进入 Android 运行时。
- 固定提交只用于复现本次视觉基线；后续上游变化不会自动改变 Nexara 的界面行为。
- GPLv3 完整条款见仓库根目录 `LICENSE`。


## 独立模型目录新增来源

- LiteLLM 根目录模型价格与上下文文件：MIT，Copyright (c) 2023 Berri AI；不包含 enterprise/ 内容。[上游许可](https://github.com/BerriAI/litellm/blob/main/LICENSE)。
- OpenRouter 公共模型 API：按[OpenRouter 服务条款](https://openrouter.ai/terms)使用，不能将其数据声明为 MIT。公开事实经过结构化筛选，不含账号凭据或用户请求。
- Google Gemini 官方模型文档：CC BY 4.0；补充文件记录来源与核验日期，Nexara 将公开规格转换为结构化字段。[Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)。
- SenseNova 官方 API 文档：仅整理模型 ID、公开模态与推理事实并保留来源，不复制文档长段原文；仓库未声明统一再发布许可。[官方文档](https://github.com/OpenSenseNova/SenseNova6.8/blob/main/API_CN.md)。

发布产物附带 `third-party-notices.txt`，保留 models.dev 与 LiteLLM 的完整 MIT 许可及上述出处。抓取时点和摘要以该次签名目录的 sources 为准；人工补充在规范源接管后自动停用。

## 原生 SSH 与终端

- [mwiede/jsch 2.28.8](https://github.com/mwiede/jsch/tree/jsch-2.28.8)：SSH、SFTP、PTY 与本地转发。主库为三条款 BSD；内嵌 JZlib 为 BSD、jBCrypt 为 ISC。完整许可与版权归档在 [ssh/jsch-LICENSE.txt](ssh/jsch-LICENSE.txt)、[JZlib](ssh/jsch-LICENSE.JZlib.txt)、[jBCrypt](ssh/jsch-LICENSE.jBCrypt.txt)。
- [Bouncy Castle bcprov-jdk15to18 1.86](https://www.bouncycastle.org/download/bouncy-castle-java/)：SSH 仅使用 lightweight 密码学路径补足 Android API 31 的 Ed25519/X25519，不注册或替换系统 Provider；既有 PDFBox 的 bcpkix-jdk15to18、bcutil-jdk15to18 同步统一到 1.86，避免重复类及跨版本 API 混用。许可采用 Bouncy Castle/MIT 条款，完整声明见 [ssh/bouncycastle-LICENSE.txt](ssh/bouncycastle-LICENSE.txt)。
- [Termux terminal-emulator / terminal-view 0.118.0](https://github.com/termux/termux-app/tree/v0.118.0)：使用终端仿真器和渲染器连接远端 SSH PTY，不构造本地 TerminalSession，不启动本地进程。上游为 GPLv3-only，包含 Apache-2.0 来源例外，见 [ssh/termux-LICENSE.md](ssh/termux-LICENSE.md) 与[上游源码](https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator)。项目 GPL-3.0 许可允许该组合。
- [Apache MINA SSHD 2.16.0](https://github.com/apache/mina-sshd/tree/sshd-2.16.0)：Apache-2.0，仅用于 JVM 测试中的内嵌 SSH/SFTP 服务端，不进入应用运行时。

SSH 与终端依赖保持版本固定；引入的终端原生进程桥接库不属于远端 PTY 的执行路径。许可原文按上游保留，中文说明不替代许可条款。
