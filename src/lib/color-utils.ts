/**
 * 动态色彩系统工具库
 * 用于从单一主色生成完整的 UI 色阶
 */

/**
 * 将 Hex 转换为 RGB
 */
function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    return result
        ? {
            r: parseInt(result[1], 16),
            g: parseInt(result[2], 16),
            b: parseInt(result[3], 16),
        }
        : null;
}

/**
 * 将 RGB 转换为 Hex
 */
function rgbToHex(r: number, g: number, b: number): string {
    return '#' + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
}

/**
 * 混合颜色（用于生成色阶）
 */
function mix(color1: { r: number; g: number; b: number }, color2: { r: number; g: number; b: number }, weight: number) {
    const w1 = weight;
    const w2 = 1 - weight;
    return {
        r: Math.round(color1.r * w1 + color2.r * w2),
        g: Math.round(color1.g * w1 + color2.g * w2),
        b: Math.round(color1.b * w1 + color2.b * w2),
    };
}

export interface ColorPalette {
    50: string;
    100: string;
    200: string;
    300: string;
    400: string;
    500: string;
    600: string;
    700: string;
    800: string;
    900: string;
    // 额外辅助色
    opacity10: string;
    opacity20: string;
    opacity30: string;
}

/**
 * 基于主色生成完整的 Tailwind 风格色阶
 * 算法逻辑：
 * - 500 是基准色
 * - < 500 与白色混合
 * - > 500 与黑色混合
 */
export function generatePalette(baseHex: string): ColorPalette {
    const base = hexToRgb(baseHex) || { r: 99, g: 102, b: 241 }; // Default to indigo
    const white = { r: 255, g: 255, b: 255 };
    const black = { r: 0, g: 0, b: 0 };

    const getHex = (mixColor: { r: number; g: number; b: number }, weight: number) => {
        const mixed = mix(base, mixColor, weight);
        return rgbToHex(mixed.r, mixed.g, mixed.b);
    };

    return {
        50: getHex(white, 0.1),
        100: getHex(white, 0.2),
        200: getHex(white, 0.4),
        300: getHex(white, 0.6),
        400: getHex(white, 0.8),
        500: baseHex,
        600: getHex(black, 0.9),
        700: getHex(black, 0.8),
        800: getHex(black, 0.7),
        900: getHex(black, 0.6),
        opacity10: `${baseHex}1a`, // 10%
        opacity20: `${baseHex}33`, // 20%
        opacity30: `${baseHex}4d`, // 30%
    };
}
