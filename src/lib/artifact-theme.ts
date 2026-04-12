/**
 * Artifact 渲染器语义化颜色 Token
 *
 * 统一管理所有 Artifact 渲染器中的颜色值，
 * 消除渲染器间的硬编码颜色重复。
 *
 * 使用方式：
 *   import { artifactColors } from '../../lib/artifact-theme';
 *   const ac = artifactColors(isDark, colors);
 *   // ac.card.background, ac.card.border, ac.text.primary ...
 */

import { ColorPalette } from './color-utils';

export interface ArtifactColorTokens {
    /** 卡片容器 */
    card: {
        background: string;
        border: string;
    };
    /** 图标容器 */
    icon: {
        background: string;
    };
    /** 徽章 */
    badge: {
        background: string;
        text: string;
    };
    /** 文本 */
    text: {
        primary: string;
        secondary: string;
        hint: string;
        errorMuted: string;
        retryCount: string;
        disabled: string;
    };
    /** 按钮 */
    button: {
        icon: string;
        background: string;
        closeIcon: string;
    };
    /** 加载/错误覆盖层 */
    overlay: {
        background: string;
        retryBackground: string;
    };
    /** WebView 背景色 (用于全屏) */
    webview: {
        background: string;
    };
    /** 强调色 */
    accent: string;
}

/**
 * 根据主题状态生成语义化颜色 token
 *
 * @param isDark 是否暗色模式
 * @param colors 主题色阶 (来自 useTheme().colors)
 */
export function artifactColors(isDark: boolean, colors: ColorPalette): ArtifactColorTokens {
    return {
        card: {
            background: isDark ? '#1c1c1e' : '#f9fafb',
            border: isDark ? '#2c2c2e' : '#e5e7eb',
        },
        icon: {
            background: isDark ? '#2c2c2e' : (colors.opacity20 || '#ede9fe'),
        },
        badge: {
            background: isDark ? '#334155' : (colors.opacity30 || '#e2e8f0'),
            text: isDark ? '#cbd5e1' : (colors[500] || '#475569'),
        },
        text: {
            primary: isDark ? '#f4f4f5' : '#111827',
            secondary: isDark ? '#71717a' : '#9ca3af',
            hint: isDark ? '#71717a' : '#9ca3af',
            errorMuted: isDark ? '#888' : '#666',
            retryCount: isDark ? '#aaa' : '#555',
            disabled: isDark ? '#666' : '#999',
        },
        button: {
            icon: isDark ? '#52525b' : '#9ca3af',
            background: isDark ? '#1c1c1e' : '#f3f4f6',
            closeIcon: isDark ? '#fff' : '#666',
        },
        overlay: {
            background: isDark ? 'rgba(0,0,0,0.8)' : 'rgba(255,255,255,0.9)',
            retryBackground: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.05)',
        },
        webview: {
            background: isDark ? '#000' : '#fff',
        },
        accent: colors[500] || (isDark ? '#a78bfa' : '#7c3aed'),
    };
}
