import React from 'react';
import Svg, { Path, Circle, Defs, LinearGradient, Stop, G } from 'react-native-svg';
import { SvgUri } from 'react-native-svg';
import { CachedSvgUri } from '../ui/CachedSvgUri';

interface IconProps {
  size?: number;
  color?: string;
}

/**
 * 精确品牌 SVG 图标库
 */
export const BrandIcon = {
  // OpenAI Logo (Snowflake/Spiral)
  OpenAI: ({ size = 24, color = '#10A37F' }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.2502-9.9731ZM18.3066 5.186a4.4491 4.4491 0 0 1 1.8811 4.0558L10.821 14.6862V5.0592a4.4292 4.4292 0 0 1 7.4856 0.1268ZM1.498 7.8223a4.4292 4.4292 0 0 1 3.0108-4.831 4.4374 4.4374 0 0 1 3.0274 0.149l-1.0163 1.7602a4.4491 4.4491 0 0 1-5.0219 2.9218Zm1.8811 11.1219a4.4291 4.4291 0 0 1-1.8811-4.0558L10.8667 9.4441V19.071a4.4292 4.4292 0 0 1-7.4876-0.1268Zm18.3475 0a4.4291 4.4291 0 0 1-3.0108 4.831 4.4374 4.4374 0 0 1-3.0274-0.149l1.0163-1.7602a4.4491 4.4491 0 0 1 5.0219-2.9218Z"
        fill={color}
      />
    </Svg>
  ),

  // Google Gemini (Sparkle Star)
  Google: ({ size = 24 }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M12 2C12 2 11 11 2 12C11 13 12 22 12 22C12 22 13 13 22 12C13 11 12 2 12 2Z"
        fill="#4285F4"
      />
      <Path
        d="M12 6C12 6 11.5 11.5 6 12C11.5 12.5 12 18 12 18C12 18 12.5 12.5 18 12C12.5 11.5 12 6 12 6Z"
        fill="#8AB4F8"
      />
    </Svg>
  ),

  // Attention/Reasoning (Sparkle/Brain)
  Attention: ({ size = 24, color = '#7c3aed' }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path d="M12 4L14 9H19L15 13L16.5 18.5L12 15.5L7.5 18.5L9 13L5 9H10L12 4Z" fill={color} />
    </Svg>
  ),

  // Anthropic (Stylized 'A')
  Anthropic: ({ size = 24, color = '#D97757' }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M12 2L4.5 20.5H6.8L8.6 15.5H15.4L17.2 20.5H19.5L12 2ZM9.3 13.5L12 6.5L14.7 13.5H9.3Z"
        fill={color}
      />
    </Svg>
  ),

  // DeepSeek - Fallback
  DeepSeek: ({ size = 24 }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path d="M12 2L2 12L12 22L22 12L12 2Z" fill="#4B68FF" />
    </Svg>
  ),

  // Zhipu - Fallback
  Zhipu: ({ size = 24 }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path d="M12 2L4 7V17L12 22L20 17V7L12 2Z" fill="#3D45E4" />
    </Svg>
  ),

  // Moonshot - Fallback
  Moonshot: ({ size = 24 }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Circle cx="12" cy="12" r="10" fill="#F43F5E" />
    </Svg>
  ),

  // Ali Qwen - Fallback
  Qwen: ({ size = 24 }: IconProps) => (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path d="M12 2L2 12L12 22L22 12L12 2Z" fill="#6A1B9A" />
    </Svg>
  ),

  /**
   * 动态加载 LobeHub 图标
   * @param slug 图标标识符 (如 'openai', 'deepseek')
   */
  ModelLogo: ({ slug, size = 24 }: IconProps & { slug: string }) => {
    // 引入 Theme Hook (需确保组件在 ThemeProvider 内)
    // Dynamic require/import to avoid circular dependency issues at top level if any, 
    // but better to use the hook if available. 
    // Assuming we can import hooks here. If this is a plain object, we need to make ModelLogo a proper component or use hooks inside it.
    // Since it's used as <BrandIcon.ModelLogo />, it is a functional component.
    const { useTheme } = require('../../theme/ThemeProvider');
    const { isDark } = useTheme();
    const { View } = require('react-native'); // Late import if needed, or add to top

    // 映射一些别名到 LobeHub 官方 Slug
    const slugMap: Record<string, string> = {
      claude: 'claude',
      gemini: 'google',
      vertex: 'google',
      google: 'google',
      glm: 'zhipu',
      chatglm: 'zhipu',
      kimi: 'moonshot',
      ernie: 'wenxin',
      wenxin: 'wenxin',
      'llama-3': 'meta',
      'llama-2': 'meta',
      llama: 'meta',
      github: 'github',
      groq: 'groq',
      minimax: 'minimax',
      mistral: 'mistral',
      ollama: 'ollama',
      xai: 'xai',
      grok: 'xai',
      siliconflow: 'openai',
      local: 'openai',
      rwkv: 'openai',
      // Fix: openai-compatible 映射到有效的 openai 图标
      'openai-compatible': 'openai',
    };

    const normalizedSlug = slugMap[slug.toLowerCase()] || slug.toLowerCase();

    // 只有基础版本的品牌 (往往是单色/黑色)
    // 只有基础版本的品牌 (往往是单色/黑色)
    const baseOnlySlugs = ['openai', 'anthropic', 'moonshot', 'github', 'vercel', 'groq', 'ollama', 'xai', 'mistral'];

    // 以前的白名单已废弃，现在改为通用适配
    // const needWhiteBackgroundInDark = ['github', 'ollama', 'xai', 'mistral', 'groq', 'vercel'];

    const finalSlug = baseOnlySlugs.includes(normalizedSlug) ? normalizedSlug : `${normalizedSlug}-color`;

    const uri = `https://registry.npmmirror.com/@lobehub/icons-static-svg/latest/files/icons/${finalSlug}.svg`;

    const image = <CachedSvgUri width={size} height={size} uri={uri} />;

    // Dark Mode 通用适配：
    // 用户反馈白名单太笨，直接给所有图标在暗黑模式下加一个亮色背景，简单粗暴且有效。
    // 这能同时解决黑色图标看不清和彩色图标在深色背景下不够突出的问题。
    if (isDark) {
      return (
        <View
          style={{
            backgroundColor: '#e4e4e7', // zinc-200
            borderRadius: size * 0.25, // 稍微大一点的圆角
            padding: 2, // 内边距
            width: size,
            height: size,
            justifyContent: 'center',
            alignItems: 'center'
          }}
        >
          {/* 稍微缩小一点图标以适应容器 */}
          <CachedSvgUri width={size * 0.85} height={size * 0.85} uri={uri} />
        </View>
      );
    }

    return image;
  },
};

