/**
 * 模型图标映射数据库
 * 映射模型 ID 到对应的品牌图标 URL (CDN)
 */

export const MODEL_ICONS: Record<string, string> = {
    // OpenAI
    'openai': 'https://upload.wikimedia.org/wikipedia/commons/4/4d/OpenAI_Logo.svg',

    // Anthropic (Claude)
    'anthropic': 'https://upload.wikimedia.org/wikipedia/commons/7/78/Anthropic_logo.svg',

    // Google Gemini
    'google': 'https://upload.wikimedia.org/wikipedia/commons/8/8a/Google_Gemini_logo.svg',

    // DeepSeek
    'deepseek': 'https://images.deepseek.com/logo.svg', // Placeholder / Use official if available

    // Zhipu AI (GLM)
    'zhipu': 'https://avatars.githubusercontent.com/u/129188031?s=200&v=4',

    // 默认图标 (Fallback)
    'default': 'https://cdn-icons-png.flaticon.com/512/4712/4712109.png'
};

/**
 * 获取模型的品牌图标 URL
 * @param iconType model-specs.ts 中定义的 icon 字段值
 */
export function getModelIconUrl(iconType?: string): string {
    if (!iconType) return MODEL_ICONS['default'];
    return MODEL_ICONS[iconType] || MODEL_ICONS['default'];
}
