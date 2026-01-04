/**
 * 视觉一致性色值常量 (Source of Truth)
 * 优先提供 JS 常量以支持 React Native 的稳定性
 */

export const Colors = {
  // 品牌色 (Indigo)
  primary: '#6366f1',
  primaryDark: '#4f46e5',
  primaryLight: '#eef2ff',

  // 状态色
  success: '#10b981',
  error: '#ef4444',
  warning: '#f59e0b',
  info: '#3b82f6',

  // 灰阶/语义色 (Zinc 风格)
  light: {
    background: '#ffffff',
    surfaceSecondary: '#f4f4f5', // Zinc-50
    surfaceTertiary: '#e4e4e7', // Zinc-200
    textPrimary: '#09090b', // Zinc-950
    textSecondary: '#71717a', // Zinc-500
    textTertiary: '#a1a1aa', // Zinc-400
    borderDefault: '#e4e4e7', // Zinc-200
    borderSubtle: '#f4f4f5', // Zinc-100
  },
  dark: {
    background: '#09090b', // Zinc-950
    surfaceSecondary: '#18181b', // Zinc-900
    surfaceTertiary: '#27272a', // Zinc-800
    textPrimary: '#f4f4f5', // Zinc-100
    textSecondary: '#a1a1aa', // Zinc-400
    textTertiary: '#71717a', // Zinc-500
    borderDefault: '#27272a', // Zinc-800
    borderSubtle: '#18181b', // Zinc-900
  },
};

export type ThemeColors = typeof Colors.light;
