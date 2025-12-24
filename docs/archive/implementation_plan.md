# Project NeuralFlow - 技术实施方案 (Implementation Plan)

## 目标描述
构建一个基于 Android 原生体验的高性能、双核驱动（聊天+写作）AI 客户端。本项目强调代码的可维护性、性能优化的极致追求（FlashList, op-sqlite）以及商业级的交互细节。

## 用户需知 (User Review Required)
> [!IMPORTANT]
> **本地原生模块依赖**: 本项目将大量使用原生模块 (`op-sqlite`, `react-native-reanimated`, `expo-file-system`)。
> 必须使用 `npx expo run:android` 进行开发预览 (Prebuild 模式)，**无法**仅仅使用 Expo Go App 运行全部功能（Expo Go 不支持自定义原生代码）。

## 拟定变更 (Proposed Changes)

### 1. 项目初始化与架构
使用 Expo SDK 50+ (或最新稳定版) 初始化项目。
采用 **Feature-based** 文件夹结构，确保可扩展性。

#### [NEW] 目录结构规划
```text
/
├── app/                    # Expo Router 路由目录
│   ├── (tabs)/             # 底部 Tab 导航
│   ├── chat/               # 聊天相关页面
│   ├── settings/           # 设置相关页面
│   └── _layout.tsx         # 全局布局与 Provider
├── src/
│   ├── components/         # 通用 UI 组件 (Button, Card, Input)
│   ├── features/           # 业务功能模块
│   │   ├── chat/           # 聊天核心 (Hooks, Components, Utils)
│   │   ├── settings/       # 设置逻辑
│   │   └── rag/            # RAG 与 知识库逻辑
│   ├── lib/                # 核心库封装 (API Client, Database, Storage)
│   ├── store/              # 全局状态管理 (Zustand: Settings, API Configs)
│   ├── theme/              # 设计系统实现 (Tokens, Tailwind Config)
│   ├── types/              # TypeScript 类型定义
│   └── utils/              # 通用工具函数
├── assets/                 # 静态资源
├── drizzle/                # (可选) Drizzle ORM Schema 若使用
└── ...配置文件
```

### 2. 关键技术栈选型

#### UI 与 交互
- **Styling**: `NativeWind v4` (若稳定) 或 `v2`。结合 `clsx` / `tailwind-merge`。
- **Animations**: `react-native-reanimated` + `react-native-gesture-handler`。
- **Icons**: `lucide-react-native` (风格统一，适配 Tailwind)。
- **Safe Area**: `react-native-safe-area-context`。

#### 核心逻辑
- **Database**: `op-sqlite` (高性能 SQLite 绑定)。
- **State Management**: `zustand` + `mmkv` (用于持久化轻量配置)。
- **Navigation**: `expo-router` v3/v4.
- **List Rendering**: `@shopify/flash-list` (处理长列表)。
- **Markdown**: `react-native-markdown-display` 或 自研基于 Text 的渲染器 (视性能需求而定)。

#### AI 与 RAG
- **Vector Store**: 基于 `op-sqlite` 的向量扩展 (若支持) 或纯 JS 余弦相似度计算 (若数据量允许)。
- **LLM Client**: 自行封装 Fetch Adapter，支持流式解析。

### 3. 组件开发计划 (Component Plan)
#### [NEW] `src/components/ui/`
- `Card.tsx`: 基础卡片容器，支持按压反馈。
- `Button.tsx`: 多变体按钮。
- `Typography.tsx`: 统一字体组件 (Title, Body, Caption)。
- `Toast.tsx`: 全局通知组件 (基于 Context 或 Zustand)。

#### [NEW] `src/lib/db/`
- `index.ts`: 数据库初始化。
- `schema.ts`: 定义 Tables (Sessions, Messages, Prompts)。

## 验证计划 (Verification Plan)

### 自动化测试
- 暂不配置 Jest/Detox，优先保证快速迭代。
- 使用 TypeScript 严格模式保证类型安全。

### 手动验证
1.  **启动流程**: 确保 Android 模拟器/真机冷启动无红屏。
2.  **UI 还原**: 逐一核对 `design_system.md` 中的视觉规范（圆角、阴影、字体）。
3.  **性能测试**:
    - [/] 插入 1000 条虚构消息，测试 FlashList 滚动帧率。
    - [/] 快速切换 API 分组，验证状态响应速度。
4.  **原生功能**:
    - 验证文件导出是否出现在系统“下载”文件夹。
    - 验证图片保存是否成功获得权限并写入相册。
