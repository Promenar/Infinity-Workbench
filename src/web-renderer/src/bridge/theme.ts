/**
 * CSS 变量主题管理 — Stitch Material Design 3 动态颜色体系
 *
 * 将 RN 侧的主题数据映射为 CSS custom properties。
 * 设计参考：Stitch "Modern Session UI Redesign" 项目
 * 核心风格：Glassmorphism + Material Design 3 动态颜色
 *
 * 色值来源：
 * - 静态色值 → src/theme/colors.ts Colors.light / Colors.dark
 * - 动态色阶 → src/lib/color-utils.ts generatePalette()
 */

import type { WebViewThemePayload } from '../types/bridge';

/**
 * 将主题数据应用到 CSS 变量
 *
 * 基于 Stitch 设计系统的 Material Design 3 动态颜色映射。
 * 暗色模式使用 Zinc-950 基底 + Indigo 主色调。
 */
export function applyTheme(theme: WebViewThemePayload): void {
  const root = document.documentElement;
  const { isDark, accentColor, palette } = theme;

  if (isDark) {
    // ═══════════════════════════════════════
    // 暗色模式 — Stitch MD3 动态颜色体系
    // ═══════════════════════════════════════

    // --- 背景与表面 (Surface 层级) ---
    root.style.setProperty('--bg-primary',         '#131315');  // surface
    root.style.setProperty('--bg-secondary',       '#201f22');  // surface-container
    root.style.setProperty('--bg-tertiary',        '#2a2a2c');  // surface-container-high
    root.style.setProperty('--bg-surface-lowest',  '#0e0e10');  // surface-container-lowest
    root.style.setProperty('--bg-surface-low',     '#1c1b1d');  // surface-container-low
    root.style.setProperty('--bg-surface-high',    '#2a2a2c');  // surface-container-high
    root.style.setProperty('--bg-surface-highest', '#353437');  // surface-container-highest
    root.style.setProperty('--bg-surface-bright',  '#39393b');  // surface-bright

    // --- 文本 ---
    root.style.setProperty('--text-primary',   '#e5e1e4');  // on-surface
    root.style.setProperty('--text-secondary', '#c7c4d7');  // on-surface-variant
    root.style.setProperty('--text-tertiary',  '#908fa0');  // outline

    // --- 边框 ---
    root.style.setProperty('--border-default', '#464554');  // outline-variant
    root.style.setProperty('--border-subtle',  '#201f22');  // surface-container
    root.style.setProperty('--border-glass',   'rgba(255,255,255,0.1)');

    // --- 气泡 — Glassmorphism 风格 ---
    root.style.setProperty('--bubble-user-bg',    '#27272a');  // zinc-800
    root.style.setProperty('--bubble-user-border', '#3f3f46'); // zinc-700

    // --- 代码块 — Dark surface ---
    root.style.setProperty('--code-bg',        '#0e0e10');  // surface-container-lowest
    root.style.setProperty('--code-border',    'rgba(255,255,255,0.1)');
    root.style.setProperty('--code-header-bg', '#1c1b1d');  // surface-container-low
    root.style.setProperty('--code-inline-bg', 'rgba(255,255,255,0.05)');

    // --- 滚动条 ---
    root.style.setProperty('--scrollbar-thumb', '#464554');
    root.style.setProperty('--scrollbar-track', '#131315');

    // --- 卡片玻璃效果 ---
    root.style.setProperty('--glass-bg',        'rgba(255,255,255,0.03)');
    root.style.setProperty('--glass-border',    'rgba(255,255,255,0.1)');
    root.style.setProperty('--glass-blur',      '20px');

  } else {
    // ═══════════════════════════════════════
    // 浅色模式 — 配套映射
    // ═══════════════════════════════════════

    // --- 背景与表面 ---
    root.style.setProperty('--bg-primary',         '#ffffff');
    root.style.setProperty('--bg-secondary',       '#f4f4f5');
    root.style.setProperty('--bg-tertiary',        '#e4e4e7');
    root.style.setProperty('--bg-surface-lowest',  '#fafafa');
    root.style.setProperty('--bg-surface-low',     '#f4f4f5');
    root.style.setProperty('--bg-surface-high',    '#e4e4e7');
    root.style.setProperty('--bg-surface-highest', '#d4d4d8');
    root.style.setProperty('--bg-surface-bright',  '#ffffff');

    // --- 文本 ---
    root.style.setProperty('--text-primary',   '#09090b');
    root.style.setProperty('--text-secondary', '#52525b');
    root.style.setProperty('--text-tertiary',  '#a1a1aa');

    // --- 边框 ---
    root.style.setProperty('--border-default', '#e4e4e7');
    root.style.setProperty('--border-subtle',  '#f4f4f5');
    root.style.setProperty('--border-glass',   'rgba(0,0,0,0.08)');

    // --- 气泡 ---
    root.style.setProperty('--bubble-user-bg',     'rgba(244,244,245,0.6)');
    root.style.setProperty('--bubble-user-border', '#e5e7eb');

    // --- 代码块 ---
    root.style.setProperty('--code-bg',        'rgba(0,0,0,0.02)');
    root.style.setProperty('--code-border',    'rgba(0,0,0,0.08)');
    root.style.setProperty('--code-header-bg', 'rgba(0,0,0,0.02)');
    root.style.setProperty('--code-inline-bg', 'rgba(0,0,0,0.05)');

    // --- 滚动条 ---
    root.style.setProperty('--scrollbar-thumb', '#ccc');
    root.style.setProperty('--scrollbar-track', '#f0f0f0');

    // --- 卡片玻璃效果 ---
    root.style.setProperty('--glass-bg',        'rgba(255,255,255,0.7)');
    root.style.setProperty('--glass-border',    'rgba(0,0,0,0.08)');
    root.style.setProperty('--glass-blur',      '20px');
  }

  // --- 强调色（动态色阶） ---
  root.style.setProperty('--accent', palette[500] || accentColor);
  root.style.setProperty('--accent-50',  palette[50]);
  root.style.setProperty('--accent-100', palette[100]);
  root.style.setProperty('--accent-200', palette[200]);
  root.style.setProperty('--accent-300', palette[300]);
  root.style.setProperty('--accent-400', palette[400]);
  root.style.setProperty('--accent-500', palette[500]);
  root.style.setProperty('--accent-600', palette[600]);
  root.style.setProperty('--accent-700', palette[700]);
  root.style.setProperty('--accent-800', palette[800]);
  root.style.setProperty('--accent-900', palette[900]);
  root.style.setProperty('--accent-opacity-10', palette.opacity10);
  root.style.setProperty('--accent-opacity-20', palette.opacity20);
  root.style.setProperty('--accent-opacity-30', palette.opacity30);

  // --- 状态色 ---
  root.style.setProperty('--color-success', '#10b981');
  root.style.setProperty('--color-error',   '#ef4444');
  root.style.setProperty('--color-warning', '#f59e0b');

  // --- 代码高亮主题类名 ---
  root.setAttribute('data-theme', isDark ? 'dark' : 'light');
}
