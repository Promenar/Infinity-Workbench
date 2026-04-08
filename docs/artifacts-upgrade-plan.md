# Artifacts 模块升级实施计划

| 版本 | 日期 | 作者 | 状态 |
|------|------|------|------|
| 1.0.0 | 2026-04-07 | Code Audit System | 草案 |

---

## 目录

1. [项目概述](#1-项目概述)
2. [当前架构分析](#2-当前架构分析)
3. [Phase 1: 基础优化阶段](#3-phase-1-基础优化阶段1-2周)
4. [Phase 2: 架构重构阶段](#4-phase-2-架构重构阶段1-2月)
5. [Phase 3: 功能扩展阶段](#5-phase-3-功能扩展阶段3-4月)
6. [Phase 4: 高级特性阶段](#6-phase-4-高级特性阶段5-6月)
7. [风险评估与缓解策略](#7-风险评估与缓解策略)
8. [资源需求评估](#8-资源需求评估)
9. [附录](#9-附录)

---

## 1. 项目概述

### 1.1 背景

Artifacts 模块是 Nexara 项目中负责渲染和管理 AI 生成结构化内容的核心模块。经过全面审计，发现当前实现在功能完整性、代码质量、交互体验等方面存在显著差距，亟需系统性升级。

### 1.2 升级目标

| 目标维度 | 当前状态 | 目标状态 |
|----------|----------|----------|
| 类型支持 | 2种 (echarts, mermaid) | 6+种 |
| 代码质量 | 5/10 | 8/10 |
| 交互体验 | 6/10 | 9/10 |
| 无障碍支持 | 3/10 | 8/10 |
| 可扩展性 | 4/10 | 8/10 |

### 1.3 实施原则

1. **渐进式升级**：分阶段实施，每个阶段可独立交付
2. **向后兼容**：确保现有功能不受影响
3. **测试驱动**：每个功能点需有对应测试用例
4. **文档同步**：代码与文档同步更新

---

## 2. 当前架构分析

### 2.1 核心文件清单

```
src/
├── types/chat.ts                          # ToolResultArtifact 类型定义
├── features/chat/components/
│   ├── ToolArtifacts.tsx                  # 产物容器组件 (95行)
│   └── message/blocks/ToolCallBlock.tsx   # 工具调用块 (63行)
├── components/chat/
│   ├── EChartsRenderer.tsx                # ECharts 渲染器 (399行)
│   └── MermaidRenderer.tsx                # Mermaid 渲染器 (351行)
├── lib/skills/core/rendering.ts           # 渲染技能定义 (68行)
├── store/chat/tool-execution.ts           # 工具执行逻辑 (338行)
└── lib/webview-assets.ts                  # WebView 资源管理 (71行)
```

### 2.2 数据流现状

```
LLM Response → Tool Call Parse → Skill Execution
      ↓                                    ↓
tool-execution.ts → ToolResult → Message.toolResults[]
      ↓                                    ↓
SQLite 持久化      → ToolArtifacts → Renderer Components
```

### 2.3 已识别问题清单

| 编号 | 类别 | 问题描述 | 严重程度 |
|------|------|----------|----------|
| A1 | 架构 | Artifact 类型系统过于简单 | 中 |
| A2 | 架构 | 无全局 Artifact 索引/搜索 | 高 |
| A3 | 架构 | 渲染器与业务逻辑耦合 | 中 |
| V1 | 视觉 | 硬编码颜色值 | 低 |
| V2 | 视觉 | 内联 HTML 模板 | 中 |
| V3 | 视觉 | 无骨架屏加载 | 中 |
| V4 | 视觉 | 错误状态无重试 | 高 |
| I1 | 交互 | 预览卡片整体可点击 | 中 |
| I2 | 交互 | 缺少图表工具箱 | 高 |
| I3 | 交互 | 无导出/下载功能 | 高 |
| I4 | 交互 | 无障碍支持缺失 | 高 |
| C1 | 代码 | 正则解析脆弱 | 中 |
| C2 | 代码 | WebView key 不稳定 | 低 |
| C3 | 代码 | 重复代码 | 中 |
| C4 | 代码 | 无单元测试 | 高 |

---

## 3. Phase 1: 基础优化阶段（1-2周）

### 3.1 阶段目标

- 修复关键缺陷
- 提升用户体验
- 增加基础功能

### 3.2 任务清单

#### 3.2.1 P0: 添加错误重试机制

**预估工时**: 4小时

**实施步骤**:

1. 创建 `ArtifactError` 组件

```typescript
// src/components/artifacts/ArtifactError.tsx
import React from 'react';
import { View, TouchableOpacity, Text, StyleSheet } from 'react-native';
import { AlertCircle, RefreshCw, Flag } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { Typography } from '../ui/Typography';

interface ArtifactErrorProps {
  error: Error;
  onRetry: () => void;
  onReport?: () => void;
}

export const ArtifactError: React.FC<ArtifactErrorProps> = ({
  error,
  onRetry,
  onReport
}) => {
  const { isDark, colors } = useTheme();

  return (
    <View style={[
      styles.container,
      {
        backgroundColor: isDark 
          ? 'rgba(239, 68, 68, 0.1)' 
          : 'rgba(239, 68, 68, 0.05)',
        borderColor: isDark 
          ? 'rgba(239, 68, 68, 0.3)' 
          : 'rgba(239, 68, 68, 0.2)'
      }
    ]}>
      <View style={styles.header}>
        <AlertCircle size={24} color="#ef4444" />
        <Typography style={styles.title}>渲染失败</Typography>
      </View>
      
      <Typography variant="caption" style={styles.message}>
        {error.message || '无法解析图表配置'}
      </Typography>
      
      <View style={styles.actions}>
        <TouchableOpacity 
          onPress={onRetry} 
          style={[styles.button, { backgroundColor: colors?.[500] || '#6366f1' }]}
        >
          <RefreshCw size={16} color="#fff" />
          <Typography style={styles.buttonText}>重试</Typography>
        </TouchableOpacity>
        
        {onReport && (
          <TouchableOpacity onPress={onReport} style={styles.secondaryButton}>
            <Flag size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
            <Typography style={[styles.secondaryText, { color: isDark ? '#a1a1aa' : '#71717a' }]}>
              报告问题
            </Typography>
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    padding: 16,
    borderRadius: 12,
    borderWidth: 1,
    marginVertical: 8,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  title: {
    fontSize: 14,
    fontWeight: '700',
    marginLeft: 8,
    color: '#ef4444',
  },
  message: {
    fontSize: 12,
    opacity: 0.7,
    marginBottom: 12,
  },
  actions: {
    flexDirection: 'row',
    gap: 12,
  },
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    gap: 6,
  },
  buttonText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  secondaryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    gap: 6,
  },
  secondaryText: {
    fontSize: 13,
  },
});
```

2. 集成到渲染器组件

```typescript
// 修改 EChartsRenderer.tsx
// 在 parseError 分支使用 ArtifactError

if (!chartOption || parseError) {
  return (
    <ArtifactError 
      error={new Error(parseError ? "图表配置解析失败" : "等待图表数据...")}
      onRetry={() => {
        // 触发重新解析
        setContentVersion(v => v + 1);
      }}
    />
  );
}
```

**验收标准**:
- [ ] 错误状态显示友好提示
- [ ] 重试按钮可点击并重新渲染
- [ ] 支持暗色模式
- [ ] 有无障碍标签

---

#### 3.2.2 P0: 添加导出/分享功能

**预估工时**: 6小时

**实施步骤**:

1. 创建导出工具模块

```typescript
// src/lib/artifacts/export-utils.ts
import * as Sharing from 'expo-sharing';
import * as FileSystem from 'expo-file-system';
import { Platform } from 'react-native';

export type ExportFormat = 'png' | 'svg' | 'json';

export interface ExportOptions {
  format: ExportFormat;
  filename?: string;
  quality?: 'low' | 'medium' | 'high';
}

export class ArtifactExporter {
  /**
   * 导出 ECharts 图表为图片
   */
  static async exportECharts(
    option: any,
    options: ExportOptions = { format: 'png' }
  ): Promise<string | null> {
    const { format, filename, quality = 'medium' } = options;
    
    // 通过 WebView 截图实现
    // 需要在 WebView 中注入导出脚本
    const pixelRatio = quality === 'high' ? 3 : quality === 'medium' ? 2 : 1;
    
    // 返回文件 URI
    return null; // TODO: 实现
  }

  /**
   * 导出 Mermaid 图表
   */
  static async exportMermaid(
    svgContent: string,
    options: ExportOptions = { format: 'svg' }
  ): Promise<string | null> {
    const { format, filename } = options;
    
    if (format === 'svg') {
      // 直接保存 SVG
      const path = `${FileSystem.cacheDirectory}${filename || 'diagram'}.svg`;
      await FileSystem.writeAsStringAsync(path, svgContent);
      return path;
    }
    
    // PNG 需要通过 WebView 渲染后截图
    return null; // TODO: 实现
  }

  /**
   * 分享文件
   */
  static async share(uri: string, mimeType: string): Promise<boolean> {
    if (!await Sharing.isAvailableAsync()) {
      console.warn('Sharing is not available on this platform');
      return false;
    }
    
    await Sharing.shareAsync(uri, {
      mimeType,
      dialogTitle: '分享图表',
    });
    
    return true;
  }

  /**
   * 复制配置到剪贴板
   */
  static async copyConfig(config: object): Promise<void> {
    const { Clipboard } = require('expo-clipboard');
    await Clipboard.setStringAsync(JSON.stringify(config, null, 2));
  }
}
```

2. 创建工具栏组件

```typescript
// src/components/artifacts/ArtifactToolbar.tsx
import React, { useState } from 'react';
import { View, TouchableOpacity, StyleSheet, Modal, Text } from 'react-native';
import { Download, Share2, Copy, X, FileImage, FileCode, FileText } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { Typography } from '../ui/Typography';
import { ArtifactExporter, ExportFormat } from '../../lib/artifacts/export-utils';

interface ArtifactToolbarProps {
  type: 'echarts' | 'mermaid';
  config: any;
  onExport?: (format: ExportFormat) => void;
  onShare?: () => void;
  onCopy?: () => void;
}

export const ArtifactToolbar: React.FC<ArtifactToolbarProps> = ({
  type,
  config,
  onExport,
  onShare,
  onCopy
}) => {
  const { isDark, colors } = useTheme();
  const [showExportMenu, setShowExportMenu] = useState(false);

  const handleExport = async (format: ExportFormat) => {
    setShowExportMenu(false);
    if (onExport) {
      onExport(format);
    } else {
      // 默认导出逻辑
      const uri = type === 'echarts'
        ? await ArtifactExporter.exportECharts(config, { format })
        : await ArtifactExporter.exportMermaid(config, { format });
      
      if (uri) {
        await ArtifactExporter.share(uri, format === 'svg' ? 'image/svg+xml' : 'image/png');
      }
    }
  };

  const handleCopy = async () => {
    await ArtifactExporter.copyConfig(config);
    if (onCopy) onCopy();
  };

  return (
    <View style={[styles.container, {
      backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
      borderTopColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
    }]}>
      <TouchableOpacity 
        style={styles.button} 
        onPress={() => setShowExportMenu(true)}
      >
        <Download size={18} color={colors?.[500] || '#6366f1'} />
        <Typography style={[styles.buttonText, { color: colors?.[500] || '#6366f1' }]}>
          导出
        </Typography>
      </TouchableOpacity>

      <TouchableOpacity style={styles.button} onPress={handleCopy}>
        <Copy size={18} color={isDark ? '#a1a1aa' : '#71717a'} />
        <Typography style={[styles.buttonText, { color: isDark ? '#a1a1aa' : '#71717a' }]}>
          复制
        </Typography>
      </TouchableOpacity>

      {/* 导出格式菜单 */}
      <Modal
        visible={showExportMenu}
        transparent
        animationType="fade"
        onRequestClose={() => setShowExportMenu(false)}
      >
        <TouchableOpacity 
          style={styles.modalOverlay} 
          activeOpacity={1}
          onPress={() => setShowExportMenu(false)}
        >
          <View style={[styles.menu, {
            backgroundColor: isDark ? '#27272a' : '#ffffff',
          }]}>
            <View style={styles.menuHeader}>
              <Typography style={styles.menuTitle}>选择导出格式</Typography>
              <TouchableOpacity onPress={() => setShowExportMenu(false)}>
                <X size={20} color={isDark ? '#a1a1aa' : '#71717a'} />
              </TouchableOpacity>
            </View>
            
            <TouchableOpacity 
              style={styles.menuItem}
              onPress={() => handleExport('png')}
            >
              <FileImage size={20} color={isDark ? '#a1a1aa' : '#71717a'} />
              <View style={styles.menuItemContent}>
                <Typography style={styles.menuItemText}>PNG 图片</Typography>
                <Typography variant="caption" style={styles.menuItemHint}>
                  适合分享和嵌入文档
                </Typography>
              </View>
            </TouchableOpacity>

            <TouchableOpacity 
              style={styles.menuItem}
              onPress={() => handleExport('svg')}
            >
              <FileCode size={20} color={isDark ? '#a1a1aa' : '#71717a'} />
              <View style={styles.menuItemContent}>
                <Typography style={styles.menuItemText}>SVG 矢量图</Typography>
                <Typography variant="caption" style={styles.menuItemHint}>
                  无损缩放，适合打印
                </Typography>
              </View>
            </TouchableOpacity>

            <TouchableOpacity 
              style={styles.menuItem}
              onPress={() => handleExport('json')}
            >
              <FileText size={20} color={isDark ? '#a1a1aa' : '#71717a'} />
              <View style={styles.menuItemContent}>
                <Typography style={styles.menuItemText}>JSON 配置</Typography>
                <Typography variant="caption" style={styles.menuItemHint}>
                  可用于重新生成图表
                </Typography>
              </View>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 24,
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderTopWidth: 1,
  },
  button: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  buttonText: {
    fontSize: 13,
    fontWeight: '600',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  menu: {
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingBottom: 34,
  },
  menuHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(0,0,0,0.1)',
  },
  menuTitle: {
    fontSize: 16,
    fontWeight: '700',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    gap: 12,
  },
  menuItemContent: {
    flex: 1,
  },
  menuItemText: {
    fontSize: 15,
    fontWeight: '500',
  },
  menuItemHint: {
    fontSize: 12,
    opacity: 0.6,
    marginTop: 2,
  },
});
```

**验收标准**:
- [ ] 支持导出 PNG/SVG/JSON 格式
- [ ] 支持系统分享功能
- [ ] 支持复制配置到剪贴板
- [ ] 导出菜单 UI 符合设计规范

---

#### 3.2.3 P1: 实现骨架屏加载

**预估工时**: 4小时

**实施步骤**:

```typescript
// src/components/artifacts/ArtifactSkeleton.tsx
import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
  useSharedValue,
  withRepeat,
  withTiming,
  useAnimatedStyle,
  interpolate,
} from 'react-native-reanimated';
import { useTheme } from '../../theme/ThemeProvider';

interface ArtifactSkeletonProps {
  type?: 'chart' | 'diagram';
}

export const ArtifactSkeleton: React.FC<ArtifactSkeletonProps> = ({ 
  type = 'chart' 
}) => {
  const { isDark } = useTheme();
  const shimmer = useSharedValue(0);

  useEffect(() => {
    shimmer.value = withRepeat(
      withTiming(1, { duration: 1200 }),
      -1,
      true
    );
  }, []);

  const animatedStyle = useAnimatedStyle(() => ({
    opacity: interpolate(shimmer.value, [0, 1], [0.3, 0.7]),
  }));

  const baseColor = isDark ? '#27272a' : '#e4e4e7';
  const highlightColor = isDark ? '#3f3f46' : '#d4d4d8';

  return (
    <View style={[styles.container, { backgroundColor: baseColor }]}>
      {/* 头部骨架 */}
      <View style={styles.header}>
        <View style={[styles.iconPlaceholder, { backgroundColor: highlightColor }]} />
        <View style={styles.titleContainer}>
          <View style={[styles.titlePlaceholder, { backgroundColor: highlightColor }]} />
          <View style={[styles.badgePlaceholder, { backgroundColor: highlightColor }]} />
        </View>
      </View>
      
      {/* 内容骨架 */}
      <Animated.View style={[styles.content, animatedStyle, { backgroundColor: highlightColor }]}>
        {type === 'chart' ? (
          // 图表骨架
          <View style={styles.chartSkeleton}>
            <View style={styles.chartBars}>
              {[1, 2, 3, 4, 5].map(i => (
                <View 
                  key={i} 
                  style={[
                    styles.bar, 
                    { height: 40 + Math.random() * 40, backgroundColor: highlightColor }
                  ]} 
                />
              ))}
            </View>
          </View>
        ) : (
          // 流程图骨架
          <View style={styles.diagramSkeleton}>
            <View style={[styles.node, { backgroundColor: highlightColor }]} />
            <View style={[styles.connector, { backgroundColor: highlightColor }]} />
            <View style={[styles.node, { backgroundColor: highlightColor }]} />
          </View>
        )}
      </Animated.View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    borderRadius: 16,
    padding: 12,
    marginVertical: 4,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  iconPlaceholder: {
    width: 44,
    height: 44,
    borderRadius: 12,
    marginRight: 12,
  },
  titleContainer: {
    flex: 1,
  },
  titlePlaceholder: {
    height: 16,
    width: '60%',
    borderRadius: 4,
    marginBottom: 8,
  },
  badgePlaceholder: {
    height: 12,
    width: 60,
    borderRadius: 4,
  },
  content: {
    height: 120,
    borderRadius: 8,
    overflow: 'hidden',
  },
  chartSkeleton: {
    flex: 1,
    justifyContent: 'flex-end',
    padding: 16,
  },
  chartBars: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'flex-end',
  },
  bar: {
    width: 30,
    borderRadius: 4,
  },
  diagramSkeleton: {
    flex: 1,
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 20,
  },
  node: {
    width: 60,
    height: 40,
    borderRadius: 8,
  },
  connector: {
    width: 40,
    height: 2,
  },
});
```

**验收标准**:
- [ ] 加载时显示骨架屏
- [ ] 动画流畅 (60fps)
- [ ] 支持图表和流程图两种样式
- [ ] 暗色模式适配

---

#### 3.2.4 P1: 修复 Mermaid 类型显示

**预估工时**: 2小时

**实施步骤**:

```typescript
// src/lib/artifacts/mermaid-utils.ts

/**
 * 从 Mermaid 代码推断图表类型
 */
export function inferMermaidType(code: string): {
  type: string;
  label: string;
  icon: string;
} {
  const firstLine = code.trim().split('\n')[0].toLowerCase();
  
  const typeMap: Record<string, { label: string; icon: string }> = {
    'graph': { label: '流程图', icon: 'Network' },
    'flowchart': { label: '流程图', icon: 'Network' },
    'sequence': { label: '时序图', icon: 'GitBranch' },
    'class': { label: '类图', icon: 'Box' },
    'state': { label: '状态图', icon: 'Circle' },
    'er': { label: 'ER图', icon: 'Database' },
    'gantt': { label: '甘特图', icon: 'Calendar' },
    'pie': { label: '饼图', icon: 'PieChart' },
    'mindmap': { label: '思维导图', icon: 'Brain' },
    'timeline': { label: '时间线', icon: 'Clock' },
  };

  for (const [key, value] of Object.entries(typeMap)) {
    if (firstLine.startsWith(key)) {
      return { type: key, ...value };
    }
  }

  return { type: 'diagram', label: '图表', icon: 'Network' };
}
```

**验收标准**:
- [ ] 正确识别常见 Mermaid 图表类型
- [ ] 卡片标题显示正确的图表类型
- [ ] 图标与类型匹配

---

#### 3.2.5 P2: 添加无障碍支持

**预估工时**: 4小时

**实施步骤**:

```typescript
// 为所有交互元素添加无障碍属性

// EChartsRenderer.tsx 修改示例
<TouchableOpacity
  activeOpacity={0.85}
  onPress={() => setIsFullscreen(true)}
  accessibilityLabel={`${title} 图表预览`}
  accessibilityHint="双击展开全屏查看"
  accessibilityRole="button"
>
  {/* ... */}
</TouchableOpacity>

// 关闭按钮
<TouchableOpacity
  onPress={handleClose}
  accessibilityLabel="关闭全屏"
  accessibilityHint="返回消息列表"
  accessibilityRole="button"
>
  <X size={20} color={isDark ? '#fff' : '#666'} />
</TouchableOpacity>

// 横屏切换按钮
<TouchableOpacity
  onPress={toggleOrientation}
  accessibilityLabel={isLandscape ? "切换到竖屏" : "切换到横屏"}
  accessibilityHint="旋转屏幕方向"
  accessibilityRole="button"
>
  <PhoneRotateIcon size={28} color="#fff" />
</TouchableOpacity>
```

**验收标准**:
- [ ] 所有按钮有无障碍标签
- [ ] 支持屏幕阅读器
- [ ] 支持动态字体大小
- [ ] 焦点顺序合理

---

### 3.3 Phase 1 验收清单

| 任务 | 状态 | 验收人 | 日期 |
|------|------|--------|------|
| 错误重试机制 | [ ] | | |
| 导出/分享功能 | [ ] | | |
| 骨架屏加载 | [ ] | | |
| Mermaid 类型修复 | [ ] | | |
| 无障碍支持 | [ ] | | |

---

## 4. Phase 2: 架构重构阶段（1-2月）

### 4.1 阶段目标

- 重构类型系统
- 建立统一渲染器接口
- 实现 Artifact Store
- 解耦渲染器与业务逻辑

### 4.2 任务清单

#### 4.2.1 P0: 重构类型系统

**预估工时**: 8小时

**新类型定义**:

```typescript
// src/types/artifacts.ts

import { z } from 'zod';

/**
 * Artifact 类型枚举
 */
export type ArtifactType = 
  | 'echarts'      // ECharts 图表
  | 'mermaid'      // Mermaid 流程图
  | 'math'         // 数学公式
  | 'image'        // 图片
  | 'text'         // 纯文本
  | 'code'         // 代码块 (新增)
  | 'svg'          // SVG 图形 (新增)
  | 'html'         // HTML 片段 (新增)
  | 'react'        // React 组件 (新增)
  | 'table'        // 数据表格 (新增)
  | 'markdown';    // Markdown 文档 (新增)

/**
 * Artifact 状态
 */
export type ArtifactStatus = 
  | 'pending'      // 等待渲染
  | 'rendering'    // 渲染中
  | 'success'      // 渲染成功
  | 'error';       // 渲染失败

/**
 * Artifact 元数据
 */
export interface ArtifactMetadata {
  /** 标题 */
  title?: string;
  /** 描述 */
  description?: string;
  /** 标签 */
  tags?: string[];
  /** 来源工具名称 */
  toolName: string;
  /** 来源会话 ID */
  sessionId: string;
  /** 来源消息 ID */
  messageId: string;
  /** 作者 */
  author?: string;
  /** 版本号 */
  version?: number;
  /** 自定义数据 */
  custom?: Record<string, any>;
}

/**
 * Artifact 主接口
 */
export interface Artifact {
  /** 唯一标识符 */
  id: string;
  /** 类型 */
  type: ArtifactType;
  /** 原始内容 */
  content: string;
  /** 渲染后内容 (缓存) */
  renderedContent?: string;
  /** 元数据 */
  metadata: ArtifactMetadata;
  /** 状态 */
  status: ArtifactStatus;
  /** 错误信息 */
  error?: string;
  /** 创建时间 */
  createdAt: number;
  /** 更新时间 */
  updatedAt?: number;
  /** 缩略图 URI */
  thumbnailUri?: string;
  /** 是否收藏 */
  isFavorite?: boolean;
}

/**
 * Zod Schema for runtime validation
 */
export const ArtifactSchema = z.object({
  id: z.string().uuid(),
  type: z.enum([
    'echarts', 'mermaid', 'math', 'image', 'text',
    'code', 'svg', 'html', 'react', 'table', 'markdown'
  ]),
  content: z.string(),
  renderedContent: z.string().optional(),
  metadata: z.object({
    title: z.string().optional(),
    description: z.string().optional(),
    tags: z.array(z.string()).optional(),
    toolName: z.string(),
    sessionId: z.string(),
    messageId: z.string(),
    author: z.string().optional(),
    version: z.number().optional(),
    custom: z.record(z.any()).optional(),
  }),
  status: z.enum(['pending', 'rendering', 'success', 'error']),
  error: z.string().optional(),
  createdAt: z.number(),
  updatedAt: z.number().optional(),
  thumbnailUri: z.string().optional(),
  isFavorite: z.boolean().optional(),
});

/**
 * 渲染选项
 */
export interface RenderOptions {
  /** 是否暗色模式 */
  isDark: boolean;
  /** 主题色 */
  accentColor?: string;
  /** 是否显示标题 */
  showTitle?: boolean;
  /** 是否全屏 */
  fullscreen?: boolean;
  /** 预览高度 */
  previewHeight?: number;
}

/**
 * 导出格式
 */
export type ExportFormat = 'png' | 'svg' | 'json' | 'pdf' | 'markdown';

/**
 * 导出选项
 */
export interface ExportOptions {
  format: ExportFormat;
  filename?: string;
  quality?: 'low' | 'medium' | 'high';
  includeMetadata?: boolean;
}
```

**验收标准**:
- [ ] 类型定义完整
- [ ] Zod schema 校验通过
- [ ] 与现有代码兼容
- [ ] 文档更新

---

#### 4.2.2 P0: 创建 Artifact Store

**预估工时**: 12小时

**Store 实现**:

```typescript
// src/store/artifacts-store.ts
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { Artifact, ArtifactType, ArtifactStatus } from '../types/artifacts';

interface ArtifactState {
  // 数据
  artifacts: Map<string, Artifact>;
  
  // 索引
  sessionIndex: Map<string, Set<string>>;
  typeIndex: Map<ArtifactType, Set<string>>;
  
  // Actions
  addArtifact: (artifact: Artifact) => void;
  updateArtifact: (id: string, updates: Partial<Artifact>) => void;
  deleteArtifact: (id: string) => void;
  getArtifact: (id: string) => Artifact | undefined;
  getSessionArtifacts: (sessionId: string) => Artifact[];
  getTypeArtifacts: (type: ArtifactType) => Artifact[];
  searchArtifacts: (query: string) => Artifact[];
  toggleFavorite: (id: string) => void;
  getFavorites: () => Artifact[];
  
  // 批量操作
  deleteSessionArtifacts: (sessionId: string) => void;
  importArtifacts: (artifacts: Artifact[]) => void;
  exportArtifacts: (ids: string[]) => Artifact[];
}

export const useArtifactsStore = create<ArtifactState>()(
  persist(
    (set, get) => ({
      artifacts: new Map(),
      sessionIndex: new Map(),
      typeIndex: new Map(),

      addArtifact: (artifact) => {
        set((state) => {
          const newArtifacts = new Map(state.artifacts);
          newArtifacts.set(artifact.id, artifact);

          // 更新会话索引
          const newSessionIndex = new Map(state.sessionIndex);
          const sessionSet = newSessionIndex.get(artifact.metadata.sessionId) || new Set();
          sessionSet.add(artifact.id);
          newSessionIndex.set(artifact.metadata.sessionId, sessionSet);

          // 更新类型索引
          const newTypeIndex = new Map(state.typeIndex);
          const typeSet = newTypeIndex.get(artifact.type) || new Set();
          typeSet.add(artifact.id);
          newTypeIndex.set(artifact.type, typeSet);

          return {
            artifacts: newArtifacts,
            sessionIndex: newSessionIndex,
            typeIndex: newTypeIndex,
          };
        });
      },

      updateArtifact: (id, updates) => {
        set((state) => {
          const artifact = state.artifacts.get(id);
          if (!artifact) return state;

          const newArtifacts = new Map(state.artifacts);
          newArtifacts.set(id, {
            ...artifact,
            ...updates,
            updatedAt: Date.now(),
          });

          return { artifacts: newArtifacts };
        });
      },

      deleteArtifact: (id) => {
        set((state) => {
          const artifact = state.artifacts.get(id);
          if (!artifact) return state;

          const newArtifacts = new Map(state.artifacts);
          newArtifacts.delete(id);

          // 更新索引
          const newSessionIndex = new Map(state.sessionIndex);
          const sessionSet = newSessionIndex.get(artifact.metadata.sessionId);
          if (sessionSet) {
            sessionSet.delete(id);
            if (sessionSet.size === 0) {
              newSessionIndex.delete(artifact.metadata.sessionId);
            }
          }

          const newTypeIndex = new Map(state.typeIndex);
          const typeSet = newTypeIndex.get(artifact.type);
          if (typeSet) {
            typeSet.delete(id);
            if (typeSet.size === 0) {
              newTypeIndex.delete(artifact.type);
            }
          }

          return {
            artifacts: newArtifacts,
            sessionIndex: newSessionIndex,
            typeIndex: newTypeIndex,
          };
        });
      },

      getArtifact: (id) => get().artifacts.get(id),

      getSessionArtifacts: (sessionId) => {
        const state = get();
        const ids = state.sessionIndex.get(sessionId);
        if (!ids) return [];
        return Array.from(ids)
          .map(id => state.artifacts.get(id))
          .filter(Boolean) as Artifact[];
      },

      getTypeArtifacts: (type) => {
        const state = get();
        const ids = state.typeIndex.get(type);
        if (!ids) return [];
        return Array.from(ids)
          .map(id => state.artifacts.get(id))
          .filter(Boolean) as Artifact[];
      },

      searchArtifacts: (query) => {
        const state = get();
        const lowerQuery = query.toLowerCase();
        const results: Artifact[] = [];

        state.artifacts.forEach((artifact) => {
          const title = artifact.metadata.title?.toLowerCase() || '';
          const content = artifact.content.toLowerCase();
          const tags = artifact.metadata.tags?.join(' ').toLowerCase() || '';

          if (
            title.includes(lowerQuery) ||
            content.includes(lowerQuery) ||
            tags.includes(lowerQuery)
          ) {
            results.push(artifact);
          }
        });

        return results;
      },

      toggleFavorite: (id) => {
        set((state) => {
          const artifact = state.artifacts.get(id);
          if (!artifact) return state;

          const newArtifacts = new Map(state.artifacts);
          newArtifacts.set(id, {
            ...artifact,
            isFavorite: !artifact.isFavorite,
            updatedAt: Date.now(),
          });

          return { artifacts: newArtifacts };
        });
      },

      getFavorites: () => {
        const state = get();
        const favorites: Artifact[] = [];
        state.artifacts.forEach((artifact) => {
          if (artifact.isFavorite) {
            favorites.push(artifact);
          }
        });
        return favorites.sort((a, b) => b.createdAt - a.createdAt);
      },

      deleteSessionArtifacts: (sessionId) => {
        const state = get();
        const ids = state.sessionIndex.get(sessionId);
        if (!ids) return;

        ids.forEach(id => {
          get().deleteArtifact(id);
        });
      },

      importArtifacts: (artifacts) => {
        artifacts.forEach(artifact => {
          get().addArtifact(artifact);
        });
      },

      exportArtifacts: (ids) => {
        const state = get();
        return ids
          .map(id => state.artifacts.get(id))
          .filter(Boolean) as Artifact[];
      },
    }),
    {
      name: 'nexara-artifacts',
      storage: createJSONStorage(() => ({
        getItem: async (name: string) => {
          // 使用 SQLite 存储
          const { SessionRepository } = await import('../lib/db/session-repository');
          return SessionRepository.getSetting(name);
        },
        setItem: async (name: string, value: string) => {
          const { SessionRepository } = await import('../lib/db/session-repository');
          await SessionRepository.setSetting(name, value);
        },
        removeItem: async (name: string) => {
          const { SessionRepository } = await import('../lib/db/session-repository');
          await SessionRepository.deleteSetting(name);
        },
      })),
    }
  )
);
```

**验收标准**:
- [ ] Store 创建成功
- [ ] CRUD 操作正常
- [ ] 索引功能正常
- [ ] 搜索功能正常
- [ ] 持久化正常

---

#### 4.2.3 P1: 统一渲染器接口

**预估工时**: 16小时

**渲染器接口定义**:

```typescript
// src/lib/artifacts/renderer-interface.ts
import { Artifact, RenderOptions, ExportFormat } from '../../types/artifacts';

/**
 * 渲染器接口
 */
export interface ArtifactRenderer<T = any> {
  /** 支持的类型 */
  readonly type: string;
  
  /** 显示名称 */
  readonly displayName: string;
  
  /** 图标名称 (Lucide) */
  readonly iconName: string;

  /**
   * 解析内容
   */
  parse(content: string): Promise<T>;

  /**
   * 校验数据
   */
  validate(data: T): boolean;

  /**
   * 渲染组件
   */
  render(data: T, options: RenderOptions): React.ReactElement;

  /**
   * 导出
   */
  export?(data: T, format: ExportFormat): Promise<Blob>;

  /**
   * 生成缩略图
   */
  generateThumbnail?(data: T): Promise<string>;

  /**
   * 推断标题
   */
  inferTitle?(data: T): string;
}

/**
 * 渲染器注册表
 */
class RendererRegistry {
  private renderers: Map<string, ArtifactRenderer> = new Map();

  register(renderer: ArtifactRenderer): void {
    if (this.renderers.has(renderer.type)) {
      console.warn(`Renderer for type "${renderer.type}" already exists. Overwriting.`);
    }
    this.renderers.set(renderer.type, renderer);
  }

  get(type: string): ArtifactRenderer | undefined {
    return this.renderers.get(type);
  }

  getAll(): ArtifactRenderer[] {
    return Array.from(this.renderers.values());
  }

  getSupportedTypes(): string[] {
    return Array.from(this.renderers.keys());
  }
}

export const rendererRegistry = new RendererRegistry();
```

**ECharts 渲染器实现**:

```typescript
// src/lib/artifacts/renderers/echarts-renderer.ts
import { ArtifactRenderer, rendererRegistry } from '../renderer-interface';
import { RenderOptions, ExportFormat } from '../../../types/artifacts';
import React from 'react';
import { EChartsRendererComponent } from '../../../components/artifacts/EChartsRendererComponent';

interface EChartsOption {
  title?: { text?: string };
  series?: Array<{ type?: string }>;
  [key: string]: any;
}

export class EChartsRenderer implements ArtifactRenderer<EChartsOption> {
  readonly type = 'echarts';
  readonly displayName = 'ECharts 图表';
  readonly iconName = 'BarChart3';

  async parse(content: string): Promise<EChartsOption> {
    // 清理 markdown 代码块标记
    let cleanContent = content
      .replace(/^```echarts\s*\n?/, '')
      .replace(/\n?```$/, '')
      .trim();

    // 尝试 JSON 解析
    try {
      return JSON.parse(cleanContent);
    } catch (e) {
      // 尝试修复常见问题
      try {
        const { repair } = await import('jsonrepair');
        return JSON.parse(repair(cleanContent));
      } catch (repairError) {
        throw new Error('无法解析 ECharts 配置: ' + (e as Error).message);
      }
    }
  }

  validate(option: EChartsOption): boolean {
    // 基本校验：必须有 series
    return option && typeof option === 'object' && 'series' in option;
  }

  render(option: EChartsOption, options: RenderOptions): React.ReactElement {
    return React.createElement(EChartsRendererComponent, {
      option,
      isDark: options.isDark,
      accentColor: options.accentColor,
      showTitle: options.showTitle,
      fullscreen: options.fullscreen,
      previewHeight: options.previewHeight,
    });
  }

  async export(option: EChartsOption, format: ExportFormat): Promise<Blob> {
    switch (format) {
      case 'json':
        return new Blob([JSON.stringify(option, null, 2)], {
          type: 'application/json',
        });
      case 'png':
      case 'svg':
        // 需要 WebView 渲染后导出
        throw new Error('PNG/SVG 导出需要 WebView 支持');
      default:
        throw new Error(`不支持的导出格式: ${format}`);
    }
  }

  inferTitle(option: EChartsOption): string {
    return option.title?.text || 'ECharts 图表';
  }
}

// 注册渲染器
rendererRegistry.register(new EChartsRenderer());
```

**验收标准**:
- [ ] 接口定义完整
- [ ] ECharts 渲染器实现
- [ ] Mermaid 渲染器实现
- [ ] 注册表功能正常
- [ ] 单元测试覆盖

---

### 4.3 Phase 2 验收清单

| 任务 | 状态 | 验收人 | 日期 |
|------|------|--------|------|
| 类型系统重构 | [ ] | | |
| Artifact Store | [ ] | | |
| 渲染器接口 | [ ] | | |
| ECharts 渲染器迁移 | [ ] | | |
| Mermaid 渲染器迁移 | [ ] | | |
| 单元测试 | [ ] | | |

---

## 5. Phase 3: 功能扩展阶段（3-4月）

### 5.1 阶段目标

- 支持更多 Artifact 类型
- 实现全局 Artifact 库
- 添加版本历史功能
- 支持跨会话引用

### 5.2 任务清单

#### 5.2.1 新增 Artifact 类型支持

**预估工时**: 24小时

| 类型 | 优先级 | 预估工时 | 说明 |
|------|--------|----------|------|
| code | P0 | 6h | 代码块，支持语法高亮 |
| table | P0 | 4h | 数据表格，支持排序筛选 |
| markdown | P1 | 4h | Markdown 文档预览 |
| svg | P1 | 4h | SVG 图形渲染 |
| html | P2 | 6h | HTML 片段沙箱渲染 |

#### 5.2.2 全局 Artifact 库

**预估工时**: 16小时

**功能设计**:
- 独立的 Artifact 浏览页面
- 按类型/会话/时间筛选
- 搜索功能
- 收藏功能
- 批量操作

#### 5.2.3 版本历史

**预估工时**: 12小时

**功能设计**:
- 记录每次修改
- 差异对比
- 回滚功能

---

## 6. Phase 4: 高级特性阶段（5-6月）

### 6.1 阶段目标

- Artifact 编辑器
- AI 辅助优化
- 协作功能

### 6.2 任务清单

#### 6.2.1 Artifact 编辑器

**预估工时**: 40小时

**功能设计**:
- 代码编辑器集成
- 实时预览
- 语法校验
- AI 辅助补全

#### 6.2.2 AI 辅助优化

**预估工时**: 24小时

**功能设计**:
- 智能配色建议
- 布局优化建议
- 数据可视化推荐

---

## 7. 风险评估与缓解策略

### 7.1 技术风险

| 风险 | 概率 | 影响 | 缓解策略 |
|------|------|------|----------|
| WebView 性能问题 | 中 | 高 | 实现懒加载、虚拟化 |
| 类型兼容性问题 | 低 | 中 | 渐进式迁移、版本控制 |
| 存储空间不足 | 低 | 中 | 实现清理策略、压缩 |
| 第三方库更新 | 中 | 低 | 锁定版本、定期更新 |

### 7.2 进度风险

| 风险 | 概率 | 影响 | 缓解策略 |
|------|------|------|----------|
| 需求变更 | 高 | 中 | 敏捷开发、迭代调整 |
| 资源不足 | 中 | 高 | 优先级排序、MVP 策略 |
| 技术债务 | 中 | 中 | 代码审查、重构预留 |

---

## 8. 资源需求评估

### 8.1 人力需求

| 阶段 | 角色 | 人数 | 周期 |
|------|------|------|------|
| Phase 1 | 前端开发 | 1 | 2周 |
| Phase 2 | 前端开发 | 1 | 4周 |
| Phase 2 | 后端开发 | 0.5 | 2周 |
| Phase 3 | 前端开发 | 1 | 6周 |
| Phase 4 | 全栈开发 | 1 | 8周 |

### 8.2 技术栈需求

| 技术 | 用途 | 状态 |
|------|------|------|
| React Native | 移动端框架 | 已有 |
| Reanimated | 动画库 | 已有 |
| WebView | 图表渲染 | 已有 |
| ECharts | 图表库 | 已有 |
| Mermaid | 流程图库 | 已有 |
| jsonrepair | JSON 修复 | 需引入 |
| Zod | 运行时校验 | 已有 |

---

## 9. 附录

### 9.1 文件结构规划

```
src/
├── types/
│   └── artifacts.ts                    # Artifact 类型定义
├── store/
│   └── artifacts-store.ts              # Artifact Store
├── lib/artifacts/
│   ├── renderer-interface.ts           # 渲染器接口
│   ├── export-utils.ts                 # 导出工具
│   ├── mermaid-utils.ts                # Mermaid 工具
│   └── renderers/
│       ├── echarts-renderer.ts         # ECharts 渲染器
│       ├── mermaid-renderer.ts         # Mermaid 渲染器
│       ├── code-renderer.ts            # 代码渲染器
│       └── table-renderer.ts           # 表格渲染器
├── components/artifacts/
│   ├── ArtifactCard.tsx                # 基础卡片组件
│   ├── ArtifactError.tsx               # 错误状态组件
│   ├── ArtifactSkeleton.tsx            # 骨架屏组件
│   ├── ArtifactToolbar.tsx             # 工具栏组件
│   ├── EChartsRendererComponent.tsx    # ECharts 组件
│   ├── MermaidRendererComponent.tsx    # Mermaid 组件
│   └── ArtifactLibrary.tsx             # 全局库页面
└── features/chat/components/
    └── ToolArtifacts.tsx               # 容器组件 (保留)
```

### 9.2 数据库 Schema 扩展

```sql
-- artifacts 表
CREATE TABLE IF NOT EXISTS artifacts (
  id TEXT PRIMARY KEY NOT NULL,
  type TEXT NOT NULL,
  content TEXT NOT NULL,
  rendered_content TEXT,
  metadata TEXT, -- JSON
  status TEXT DEFAULT 'pending',
  error TEXT,
  thumbnail_uri TEXT,
  is_favorite INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER,
  
  -- 索引字段
  session_id TEXT,
  message_id TEXT,
  tool_name TEXT
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts(session_id);
CREATE INDEX IF NOT EXISTS idx_artifacts_type ON artifacts(type);
CREATE INDEX IF NOT EXISTS idx_artifacts_created ON artifacts(created_at);
CREATE INDEX IF NOT EXISTS idx_artifacts_favorite ON artifacts(is_favorite);

-- artifact_versions 表 (版本历史)
CREATE TABLE IF NOT EXISTS artifact_versions (
  id TEXT PRIMARY KEY NOT NULL,
  artifact_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  content TEXT NOT NULL,
  change_summary TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (artifact_id) REFERENCES artifacts(id) ON DELETE CASCADE
);
```

### 9.3 API 接口设计

```typescript
// 内部 API (Zustand Store)
interface ArtifactAPI {
  // CRUD
  create(artifact: Omit<Artifact, 'id' | 'createdAt'>): Promise<Artifact>;
  read(id: string): Promise<Artifact | null>;
  update(id: string, updates: Partial<Artifact>): Promise<Artifact>;
  delete(id: string): Promise<void>;
  
  // 查询
  list(options: { sessionId?: string; type?: ArtifactType }): Promise<Artifact[]>;
  search(query: string): Promise<Artifact[]>;
  
  // 导出
  export(id: string, format: ExportFormat): Promise<Blob>;
  
  // 版本
  getVersions(id: string): Promise<ArtifactVersion[]>;
  rollback(id: string, version: number): Promise<Artifact>;
}
```

---

## 变更记录

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| 1.0.0 | 2026-04-07 | 初始版本 |

