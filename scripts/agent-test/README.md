# Agent Test Framework

Nexara 智能测试框架 - 提供测试运行、诊断、自动修复、基准测试和视觉回归检测能力。

## 快速开始

### 安装依赖

```bash
# 确保已安装 tsx
npm install -D tsx

# 或使用 npx 直接运行
npx tsx scripts/agent-test/cli.ts
```

### 基本用法

```bash
# 运行所有测试
npm run test:agent

# 指定测试范围
npm run test:agent -- --scope=src/lib

# 启用覆盖率报告
npm run test:agent -- --coverage

# 详细输出
npm run test:agent -- --verbose

# 更新快照
npm run test:agent -- --updateSnapshot
```

## CLI 选项

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `--mode=<mode>` | 运行模式: `run`, `fix`, `diagnose`, `benchmark`, `visual` | `run` |
| `--scope=<pattern>` | 测试范围 (文件路径或 glob 模式) | - |
| `--testNamePattern=<p>` | 测试名称匹配模式 | - |
| `--coverage` | 生成覆盖率报告 | `false` |
| `--no-fix` | 禁用自动修复 (诊断模式) | `false` |
| `--updateSnapshot` | 更新快照 | `false` |
| `--verbose`, `-v` | 详细输出 | `false` |
| `--help`, `-h` | 显示帮助 | - |

## 模式说明

### run 模式 (默认)
运行测试并生成报告：
```bash
agent-test --mode=run --scope=src/components --coverage
```

### diagnose 模式
分析测试失败原因：
```bash
agent-test --mode=diagnose --scope=src/lib
```

### fix 模式
尝试自动修复测试问题：
```bash
agent-test --mode=fix --scope=src/lib
```

### benchmark 模式
性能基准测试 (待实现)：
```bash
agent-test --mode=benchmark
```

### visual 模式
视觉回归测试 (待实现)：
```bash
agent-test --mode=visual
```

## 项目集成

### 1. 添加 npm script

在项目根目录的 `package.json` 中添加：

```json
{
  "scripts": {
    "test:agent": "tsx scripts/agent-test/cli.ts"
  }
}
```

### 2. 配置文件 (可选)

在项目根目录创建 `agent-test.config.json`:

```json
{
  "jest": {
    "preset": "react-native",
    "coverageThreshold": {
      "lines": 80,
      "branches": 70,
      "functions": 80,
      "statements": 80
    }
  },
  "diagnosis": {
    "maxProcessingTimeMs": 5000,
    "confidenceThreshold": 0.7,
    "autoFixEnabled": false
  },
  "output": {
    "resultsDir": ".agent-test/results",
    "reportsDir": ".agent-test/reports"
  }
}
```

## 输出结构

```
.agent-test/
├── results/          # JSON 测试结果
│   └── <report-id>.json
├── reports/          # 生成的报告
├── baseline/         # 视觉测试基准
├── snapshots/        # 当前快照
└── diffs/            # 差异对比
```

## 退出码

| 退出码 | 说明 |
|--------|------|
| 0 | 所有测试通过 |
| 1 | 有测试失败 |
| 2 | 执行错误 |

## 类型定义

框架使用完整的 TypeScript 类型定义，主要类型包括：

- `TestResult` - 单个测试结果
- `TestRunReport` - 完整测试报告
- `DiagnosisResult` - 诊断结果
- `FixResult` - 修复结果
- `BenchmarkResult` - 基准测试结果

详见 `types/` 目录。
