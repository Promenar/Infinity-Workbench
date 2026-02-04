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
    // 映射一些别名到 LobeHub 官方 Slug
    const slugMap: Record<string, string> = {
      claude: 'claude',
      gemini: 'gemini',
      glm: 'zhipu',
      chatglm: 'zhipu',
      kimi: 'moonshot',
      ernie: 'wenxin',
      'llama-3': 'meta',
      'llama-2': 'meta',
    };

    const normalizedSlug = slugMap[slug.toLowerCase()] || slug.toLowerCase();

    // 绝大多数 AI 品牌在 LobeHub 中都有 -color 变体
    // 排除已知只有基础版本的品牌 (OpenAI, Anthropic 厂商图标)
    // 注意：moonshot 官方包中没有 moonshot-color.svg，直接用 moonshot.svg 就是彩色的
    const baseOnlySlugs = ['openai', 'anthropic', 'moonshot'];

    // 特殊处理：有些品牌默认就是彩色的，有些需要添加 -color 后缀
    // 经调研，大部分主流品牌如 deepseek, qwen, baichuan, minimax, google, gemini, claude 等均有 -color
    const finalSlug = baseOnlySlugs.includes(normalizedSlug) ? normalizedSlug : `${normalizedSlug}-color`;

    // 使用 npmmirror 以确保在国内环境的稳定性
    const uri = `https://registry.npmmirror.com/@lobehub/icons-static-svg/latest/files/icons/${finalSlug}.svg`;

    return <CachedSvgUri width={size} height={size} uri={uri} />;
  },
};
