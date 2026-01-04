/**
 * 超级助手配置类型定义
 */

export type FABIconType =
  | 'Sparkles'
  | 'Aperture'
  | 'Orbit'
  | 'Atom'
  | 'Hexagon'
  | 'Snowflake'
  | 'Target'
  | 'Sun'
  | 'Crosshair'
  | 'Component'
  | 'Dna'
  | 'Disc'
  | 'Cpu'
  | 'Blocks'
  | 'CircleDashed'
  | 'Settings'
  | 'custom';

// ... (SuperAssistantPreferences interface remains same) ...

// 预设图标选项
export const PRESET_FAB_ICONS: { type: FABIconType; labelKey: string }[] = [
  { type: 'Aperture', labelKey: 'iconAperture' },
  { type: 'Orbit', labelKey: 'iconOrbit' },
  { type: 'Atom', labelKey: 'iconAtom' },
  { type: 'Sparkles', labelKey: 'iconSparkles' },
  { type: 'Hexagon', labelKey: 'iconHexagon' },
  { type: 'Snowflake', labelKey: 'iconSnowflake' },
  { type: 'Target', labelKey: 'iconTarget' },
  { type: 'Sun', labelKey: 'iconSun' },
  { type: 'Crosshair', labelKey: 'iconCrosshair' },
  { type: 'Component', labelKey: 'iconComponent' },
  { type: 'Dna', labelKey: 'iconDna' },
  { type: 'Disc', labelKey: 'iconDisc' },
  { type: 'Cpu', labelKey: 'iconCpu' },
  { type: 'Blocks', labelKey: 'iconBlocks' },
  { type: 'CircleDashed', labelKey: 'iconCircleDashed' },
  { type: 'Settings', labelKey: 'iconSettings' },
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
  { nameKey: 'colorOrange', value: '#f97316' },
  { nameKey: 'colorRed', value: '#ef4444' },
  { nameKey: 'colorIndigo', value: '#6366f1' },
  { nameKey: 'colorTeal', value: '#14b8a6' },
  { nameKey: 'colorLime', value: '#84cc16' },
  { nameKey: 'colorFuchsia', value: '#d946ef' },
  { nameKey: 'colorBlue', value: '#2563eb' },
  { nameKey: 'colorSlate', value: '#475569' },
];

export const ANIMATION_MODES = [
  { mode: 'pulse', label: 'Pulse (Default)', icon: 'Activity' },
  { mode: 'nebula', label: 'Nebula (Starfield)', icon: 'Stars' },
  { mode: 'quantum', label: 'Quantum (Orbit)', icon: 'Atom' },
  { mode: 'glitch', label: 'Glitch (Cyber)', icon: 'ZapOff' },
  { mode: 'liquid', label: 'Liquid (Morph)', icon: 'Droplets' },
] as const;
