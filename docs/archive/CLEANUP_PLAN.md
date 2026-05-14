# 项目目录清理方案

> **目标**: 将 `native-kotlin-refactor` 分支变为纯粹的原生 Kotlin 项目，移除所有 React Native (Expo) 时代残余
> **制定日期**: 2026-05-13
> **预计耗时**: ~15 分钟（主要是 `web-client/`、`src/` 中的 node_modules 缓存体积大）

---

## 1. 项目现状

当前分支根目录混合了：
- **RN 时代残余**（~18 个目录/文件，含 web-client 的 25,000+ 文件）
- **原生 Kotlin 代码**（`native-ui/`，235 个 Kotlin 源文件）
- **原生时代文档**（`docs/`，6 个文件）
- **共享配置**（`.gitignore`、`LICENSE`、`secure_env/`、IDE 配置等）

清理后的理想状态：
```
Nexara/
├── native-ui/          ← 唯一源码目录（Kotlin/Compose 原生）
├── docs/               ← 项目文档（PRD/架构/进度/清理方案）
├── CHANGELOG.md        ← 版本变更记录
├── README.md           ← 项目概览（待更新为原生版）
├── LICENSE             ← GPL-3.0
├── .gitignore          ← 精简（移除 RN 相关忽略规则）
├── secure_env/         ← Android 签名密钥（native-ui 构建需要）
├── .agent/             ← 根级 agent 工作区（含历史文档归档）
└── IDE 配置            ← .codebuddy/ / .qoder/ / .roo/ / .ai/ / .air/
```

---

## 2. 清理清单

### 2.1 安全删除（纯 RN 时代产物）

| # | 路径 | 体积 | 说明 |
|---|------|------|------|
| 1 | `app/` | 34 文件 | Expo Router 文件路由（TSX 页面） |
| 2 | `src/` | 168 文件 | RN 核心源码（129 TSX + 39 TS） |
| 3 | `assets/` | 7 文件 | App 图标 + web-libs |
| 4 | `scripts/` | 72 文件 | RN 构建脚本（52 TS + 12 JS） |
| 5 | `plugins/` | 7 文件 | Expo 配置插件（withAndroidSigning 等） |
| 6 | `web-client/` | **25,751 文件** | RN 时代的 Web 管理面板（Vite + React） |
| 7 | `.expo/` | 少量 | Expo CLI 缓存 |
| 8 | `.stitch/` | 少量 | RN 设计规范文件 |
| 9 | `dist/` | 少量 | 构建输出目录 |
| 10 | `screenshots/` | 4 文件 | RN 时代截图 |
| 11 | `android/` | 85 文件 | Expo prebuild 输出（Groovy gradle，与 native-ui 独立） |
| 12 | `worktree/` | **182,335 文件** | Git worktree 镜像（已在 .gitignore 中，删除不影响代码） |
| 13 | `nexara_logs.txt` | ~50 KB | 运行时调试日志 |

**根目录单文件（RN 构建配置）**：

| # | 文件 | 说明 |
|---|------|------|
| 14 | `package.json` | NPM 包配置（RN 依赖） |
| 15 | `package-lock.json` | NPM 锁文件（882 KB） |
| 16 | `app.json` | Expo 配置 |
| 17 | `babel.config.js` | Babel 转译配置 |
| 18 | `global.css` | NativeWind 全局样式 |
| 19 | `jest.config.js` | Jest 测试配置 |
| 20 | `metro.config.js` | Metro 打包器配置 |
| 21 | `nativewind-env.d.ts` | NativeWind 类型声明 |
| 22 | `tailwind.config.js` | TailwindCSS 配置 |
| 23 | `tsconfig.json` | TypeScript 配置 |
| 24 | `fetch_stitch_specs.py` | RN 设计规范抓取脚本 |
| 25 | `build-release.sh` | RN 发布构建脚本 |

### 2.2 保留项目

| # | 路径 | 原因 |
|---|------|------|
| 1 | `native-ui/` | **核心**：完整的 Kotlin/Compose 原生项目 |
| 2 | `docs/` | 原生时代全部文档（PRD/架构/进度/清理方案） |
| 3 | `CHANGELOG.md` | 跨时代变更记录（保留完整历史） |
| 4 | `README.md` | 项目门面（**需更新**为原生版内容） |
| 5 | `LICENSE` | GPL-3.0 许可证 |
| 6 | `.gitignore` | 版本控制规则（**需精简**，移除 RN 忽略项） |
| 7 | `secure_env/` | Android 签名密钥 + 安全属性文件（native-ui 构建依赖） |
| 8 | `.agent/` | 根级 agent 工作区（含项目记忆、历史文档归档） |
| 9 | `.codebuddy/` | IDE 配置 |
| 10 | `.qoder/` | IDE 配置 |
| 11 | `.roo/` | IDE 配置 |
| 12 | `.ai/` | AI 工具配置 |
| 13 | `.air/` | AI 工具配置 |
| 14 | `.mcp.json` | MCP 服务器配置 |
| 15 | `.prettierrc` | 代码格式化（JSON 等仍可用） |
| 16 | `.agent-test/` | Agent 测试结果 |

### 2.3 需要更新的文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `README.md` | **重写** | 当前描述的是 RN/Expo 技术栈，需更新为 Kotlin/Compose |
| `.gitignore` | **精简** | 移除 `node_modules/`、`.expo/`、`web-build/` 等 RN 专属忽略规则；保留 `worktree/`、`secure_env/`、`*.keystore` 等 |

---

## 3. 执行命令（复制即用）

```bash
cd /Users/promenar/Codex/Nexara

# ===== 第一步：删除大体积目录（先处理最大的） =====
rm -rf worktree/          # Git worktree 镜像（~182K 文件）
rm -rf web-client/        # Web 管理面板（~25K 文件）
rm -rf src/               # RN 核心源码
rm -rf scripts/           # RN 构建脚本

# ===== 第二步：删除 RN 应用目录 =====
rm -rf app/               # Expo Router 页面
rm -rf android/           # Expo prebuild 输出
rm -rf assets/            # App 图标等
rm -rf plugins/           # Expo 插件
rm -rf dist/              # 构建输出
rm -rf screenshots/       # 旧截图
rm -rf .expo/             # Expo 缓存
rm -rf .stitch/           # 设计规范

# ===== 第三步：删除 RN 配置文件 =====
rm -f package.json
rm -f package-lock.json
rm -f app.json
rm -f babel.config.js
rm -f global.css
rm -f jest.config.js
rm -f metro.config.js
rm -f nativewind-env.d.ts
rm -f tailwind.config.js
rm -f tsconfig.json
rm -f fetch_stitch_specs.py
rm -f build-release.sh
rm -f nexara_logs.txt
```

---

## 4. 清理后的 `.gitignore`

```gitignore
# === IDE ===
.idea/
*.iml
.vscode/

# === Android (native-ui) ===
native-ui/.gradle/
native-ui/build/
native-ui/app/build/
native-ui/app/release/
native-ui/local.properties
native-ui/.kotlin/
*.apk
*.aab

# === 签名密钥（仅保留在 secure_env 且不过滤 keystore） ===
# secure_env/ 保留（含签名密钥）

# === Git Worktree ===
worktree/
worktrees/

# === OS ===
.DS_Store
Thumbs.db

# === Agent 工作区（不提交运行时状态） ===
.agent/temp/
```

---

## 5. 风险评估

| 风险 | 等级 | 缓解 |
|------|------|------|
| `secure_env/` 被误删导致无法签名 | 🟢 低 | 明确标记为"保留"，不在删除清单中 |
| `android/` 删除后 Expo prebuild 无法恢复 RN 版 | 🟢 低 | Git 历史可恢复；RN 版已归档不再需要 |
| `.agent/` 中有 RN 时代的参考文档 | 🟡 中 | 保留根 `.agent/`，其内容为历史归档，不阻塞原生开发。如需彻底清理可后续单独处理 |
| `CHANGELOG.md` 中 RN 时代的条目 | 🟢 低 | 保留全部历史，不删除 |

---

## 6. 清理后验证清单

- [ ] `native-ui/` 完整存在，`./gradlew :app:assembleDebug` 编译通过
- [ ] `docs/` 下 6 个文件完整
- [ ] `secure_env/` 签名密钥存在
- [ ] `README.md` 已更新为原生版内容
- [ ] `.gitignore` 已精简
- [ ] `git status` 确认清理结果
- [ ] 清理后执行 `git add -A && git commit -m "chore: 清理 RN 时代残余，聚焦原生 Kotlin 项目"`

---

**文档维护者**: AI Assistant
**最后更新**: 2026-05-13
