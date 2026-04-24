/**
 * CSS 变量主题管理
 *
 * 将 RN 侧的主题数据（isDark + accentColor + ColorPalette）映射为
 * CSS custom properties，实现 WebView 内的主题切换。
 *
 * 色值来源：
 * - 静态色值 → src/theme/colors.ts Colors.light / Colors.dark
 * - 动态色阶 → src/lib/color-utils.ts generatePalette()
 * - Artifact Token → src/lib/artifact-theme.ts artifactColors()
 */

import type { WebViewThemePayload } from '../types/bridge';

/**
 * 将主题数据应用到 CSS 变量
 *
 * 仅触发 repaint 不 reflow，性能开销极低。
 */
export function applyTheme(theme: WebViewThemePayload): void {
  const root = document.documentElement;
  const { isDark, accentColor, palette } = theme;

  // --- 背景与表面 ---
  root.style.setProperty('--bg-primary', isDark ? '#0a0a0c' : '#ffffff');
  root.style.setProperty('--bg-secondary', isDark ? '#161618' : '#f4f4f5');
  root.style.setProperty('--bg-tertiary', isDark ? '#222224' : '#e4e4e7');

  // --- 文本 ---
  root.style.setProperty('--text-primary', isDark ? '#ffffff' : '#09090b');
  root.style.setProperty('--text-secondary', isDark ? '#a1a1aa' : '#71717a');
  root.style.setProperty('--text-tertiary', isDark ? '#71717a' : '#a1a1aa');

  // --- 边框 ---
  root.style.setProperty('--border-default', isDark ? '#272729' : '#e4e4e7');
  root.style.setProperty('--border-subtle', isDark ? '#161618' : '#f4f4f5');

  // --- 气泡 ---
  root.style.setProperty('--bubble-user-bg', isDark ? '#1c1c1e' : '#f4f4f5');
  root.style.setProperty('--bubble-user-border', isDark ? '#2c2c2e' : '#e5e7eb');
  root.style.setProperty('--bubble-assistant-bg', isDark ? 'transparent' : 'transparent');
  root.style.setProperty('--bubble-assistant-border', 'transparent');

  // --- 代码块 ---
  root.style.setProperty('--code-bg', isDark ? '#1a1a2e' : '#f8f9fa');
  root.style.setProperty('--code-border', isDark ? '#2d2d44' : '#e2e8f0');

  // --- 强调色（动态色阶） ---
  root.style.setProperty('--accent', palette[500] || accentColor);
  root.style.setProperty('--accent-50', palette[50]);
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
  root.style.setProperty('--color-error', '#ef4444');
  root.style.setProperty('--color-warning', '#f59e0b');

  // --- 滚动条 ---
  root.style.setProperty('--scrollbar-thumb', isDark ? '#333' : '#ccc');
  root.style.setProperty('--scrollbar-track', isDark ? '#111' : '#f0f0f0');

  // --- 代码高亮主题类名 ---
  root.setAttribute('data-theme', isDark ? 'dark' : 'light');
}
