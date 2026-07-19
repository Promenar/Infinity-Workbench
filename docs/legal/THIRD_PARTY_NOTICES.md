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
- 任何离线更新与校验行为仅在项目仓库内进行，便于可复现与审计。
