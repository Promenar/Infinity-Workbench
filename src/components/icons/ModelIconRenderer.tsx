import React from 'react';
import { View, ViewStyle } from 'react-native';
import { BrandIcon } from './BrandIcons';
import { Cpu, Sparkles } from 'lucide-react-native';

interface ModelIconRendererProps {
  icon?: string;
  size?: number;
  color?: string; // fallback color for generic icons
  style?: ViewStyle;
}

export const ModelIconRenderer: React.FC<ModelIconRendererProps> = ({
  icon,
  size = 24,
  color = '#6366f1',
  style,
}) => {
  // Normalize icon key
  const iconKey = icon?.toLowerCase();

  // 如果定义了 icon，优先处理
  let content;

  if (iconKey === 'reasoning') {
    content = <BrandIcon.Attention size={size} color={color} />;
  } else if (iconKey) {
    // 强制使用动态 CDN 图标库（LobeHub 高保真官方 Logo）
    content = <BrandIcon.ModelLogo slug={iconKey} size={size} />;
  } else {
    // 兜底逻辑：使用 OpenAI Logo
    content = <BrandIcon.OpenAI size={size} color={color} />;
  }

  return <View style={style}>{content}</View>;
};
