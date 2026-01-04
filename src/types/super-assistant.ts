/**
 * 超级助手配置类型定义
 */

export type FABIconType =
  | 'Sparkles' // 默认：星星
  | 'Brain' // 大脑
  | 'Zap' // 闪电
  | 'Star' // 实心星
  | 'Flame' // 火焰
  | 'Crown' // 皇冠
  | 'custom'; // 自定义图片

export interface SuperAssistantPreferences {
  // FAB 外观设置
  fab: {
    iconType: FABIconType;
    customIconUri?: string; // 自定义图标的本地 URI
    iconColor: string; // 图标颜色（hex）
    backgroundColor: string; // 背景色（hex）
    enableRotation: boolean; // 是否启用旋转动画
    enableGlow: boolean; // 是否启用发光效果
    glowColor: string; // 发光颜色（hex）
    glowIntensity: number; // 发光强度 (0-1)
    animationMode?: 'pulse' | 'nebula' | 'quantum' | 'glitch' | 'liquid'; // 动画模式
  };

  // 全局 RAG 统计（只读，从实际数据计算）
  ragStats?: {
    totalDocuments: number;
    totalSessions: number;
    totalVectors: number;
    lastUpdated: number;
  };
}

// 默认配置
export const DEFAULT_SPA_PREFERENCES: SuperAssistantPreferences = {
  fab: {
    iconType: 'Sparkles',
    iconColor: '#8b5cf6', // 紫色
    backgroundColor: '#8b5cf6',
    enableRotation: true,
    enableGlow: true,
    glowColor: '#8b5cf6',
    glowIntensity: 0.5,
    animationMode: 'pulse',
  },
};

// 预设图标选项
export const PRESET_FAB_ICONS: { type: FABIconType; labelKey: string; color: string }[] = [
  { type: 'Sparkles', labelKey: 'iconSparkles', color: '#8b5cf6' },
  { type: 'Brain', labelKey: 'iconBrain', color: '#ec4899' },
  { type: 'Zap', labelKey: 'iconZap', color: '#f59e0b' },
  { type: 'Star', labelKey: 'iconStar', color: '#eab308' },
  { type: 'Flame', labelKey: 'iconFlame', color: '#ef4444' },
  { type: 'Crown', labelKey: 'iconCrown', color: '#a855f7' },
];

// 预设颜色方案
export const PRESET_COLORS = [
  { nameKey: 'colorViolet', value: '#8b5cf6' },
  { nameKey: 'colorPink', value: '#ec4899' },
  { nameKey: 'colorAmber', value: '#f59e0b' },
  { nameKey: 'colorEmerald', value: '#10b981' },
  { nameKey: 'colorSky', value: '#3b82f6' },
  { nameKey: 'colorRose', value: '#f43f5e' },
  { nameKey: 'colorYellow', value: '#eab308' },
  { nameKey: 'colorCyan', value: '#06b6d4' },
];

export const ANIMATION_MODES = [
  { mode: 'pulse', label: 'Pulse (Default)', icon: 'Activity' },
  { mode: 'nebula', label: 'Nebula (Starfield)', icon: 'Stars' },
  { mode: 'quantum', label: 'Quantum (Orbit)', icon: 'Atom' },
  { mode: 'glitch', label: 'Glitch (Cyber)', icon: 'ZapOff' },
  { mode: 'liquid', label: 'Liquid (Morph)', icon: 'Droplets' },
] as const;
