/**
 * ArtifactContentParser
 * 统一解析各类 Artifact 内容，增强健壮性
 *
 * 支持：
 * - echarts: 从含/不含代码块标记的内容中提取 JSON 配置
 * - mermaid: 清洗内容并验证有效性
 * - 通用: BOM 头移除、空白处理、嵌套代码块处理
 */

import { jsonrepair } from 'jsonrepair';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ParseResult<T> {
    data: T | null;
    error: string | null;
    raw: string;
}

export interface EChartsMetadata {
    title: string;
    chartType: string;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * 移除 BOM 头
 */
function stripBOM(input: string): string {
    if (input.charCodeAt(0) === 0xFEFF) {
        return input.slice(1);
    }
    return input;
}

/**
 * 尝试多种模式从内容中提取 echarts JSON 配置字符串
 */
function extractEChartsJson(content: string): string {
    const trimmed = content.trim();

    // 模式 1: ```echarts\n{...}\n```
    const codeBlockMatch = trimmed.match(/```echarts\s*\n([\s\S]*?)\n?\s*```/);
    if (codeBlockMatch) {
        return codeBlockMatch[1].trim();
    }

    // 模式 2: ```json\n{...}\n```（常见变体）
    const jsonBlockMatch = trimmed.match(/```json\s*\n([\s\S]*?)\n?\s*```/);
    if (jsonBlockMatch) {
        return jsonBlockMatch[1].trim();
    }

    // 模式 3: 纯 JSON（以 { 或 [ 开头）
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        return trimmed;
    }

    // 模式 4: 包含 JSON 但被其他文本包裹，尝试提取第一个 { 到最后一个 }
    const firstBrace = trimmed.indexOf('{');
    const lastBrace = trimmed.lastIndexOf('}');
    if (firstBrace !== -1 && lastBrace > firstBrace) {
        return trimmed.slice(firstBrace, lastBrace + 1);
    }

    return trimmed;
}

/**
 * 从 mermaid 内容中提取实际代码
 */
function extractMermaidContent(content: string): string {
    let cleaned = content;

    // 移除开头的 ```mermaid 标记
    cleaned = cleaned.replace(/^```mermaid\s*\n?/, '');
    // 移除结尾的 ``` 标记（注意不要误删内容中的 ``` ）
    // 只移除末尾的 ```
    cleaned = cleaned.replace(/\n?\s*```\s*$/, '');
    // 移除 BOM
    cleaned = stripBOM(cleaned);

    return cleaned.trim();
}

/**
 * 尝试解析 JSON，含自动修复
 */
function tryParseJson(raw: string): { data: any; error: string | null } {
    // 第一次尝试：直接解析
    try {
        return { data: JSON.parse(raw), error: null };
    } catch {
        // 继续尝试修复
    }

    // 第二次尝试：使用 jsonrepair 修复
    try {
        const repaired = jsonrepair(raw);
        return { data: JSON.parse(repaired), error: null };
    } catch {
        // 继续尝试
    }

    // 第三次尝试：移除 JS 注释后再修复
    try {
        const noComments = raw
            .replace(/\/\/.*$/gm, '')     // 单行注释
            .replace(/\/\*[\s\S]*?\*\//g, '') // 多行注释
            .replace(/,\s*([}\]])/g, '$1');   // 尾部逗号
        const repaired = jsonrepair(noComments);
        return { data: JSON.parse(repaired), error: null };
    } catch (e: any) {
        return { data: null, error: e?.message || 'JSON 解析失败' };
    }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * 解析 ECharts 内容
 *
 * @param content 原始内容（可能含 ```echarts``` 代码块标记，或纯 JSON）
 * @returns ParseResult 包含解析后的 chartOption 对象
 */
export function parseEChartsContent(content: string): ParseResult<any> {
    const cleaned = stripBOM(content).trim();

    if (!cleaned) {
        return { data: null, error: null, raw: cleaned };
    }

    const jsonStr = extractEChartsJson(cleaned);
    const { data, error } = tryParseJson(jsonStr);

    return {
        data,
        error: data ? null : (error || '图表配置解析失败'),
        raw: jsonStr,
    };
}

/**
 * 从解析后的 echarts option 中提取元数据
 */
export function extractEChartsMetadata(option: any): EChartsMetadata {
    let title = 'ECharts Visualization';
    let chartType = 'bar';

    if (!option) return { title, chartType };

    try {
        if (option.title?.text) {
            title = String(option.title.text);
        }

        if (option.series && Array.isArray(option.series) && option.series.length > 0) {
            chartType = option.series[0]?.type || 'bar';
        }
    } catch {
        // 保持默认值
    }

    return { title, chartType };
}

/**
 * 解析 Mermaid 内容
 *
 * @param content 原始内容（可能含 ```mermaid``` 代码块标记）
 * @returns ParseResult 包含清洗后的 mermaid 代码字符串
 */
export function parseMermaidContent(content: string): ParseResult<string> {
    const cleaned = stripBOM(content).trim();

    if (!cleaned) {
        return { data: null, error: null, raw: '' };
    }

    const mermaidCode = extractMermaidContent(cleaned);

    // 基本有效性检查：至少需要包含一些字母字符
    if (mermaidCode.length === 0 || !/[a-zA-Z]/.test(mermaidCode)) {
        return {
            data: null,
            error: '流程图内容无效或为空',
            raw: mermaidCode,
        };
    }

    return {
        data: mermaidCode,
        error: null,
        raw: mermaidCode,
    };
}

/**
 * 检查内容是否可能是 echarts 类型
 */
export function isEChartsContent(content: string): boolean {
    const cleaned = stripBOM(content).trim();
    if (!cleaned) return false;

    // 包含 ```echarts 标记
    if (/```echarts/i.test(cleaned)) return true;

    // 以 { 或 [ 开头且包含 series 或 option 关键字
    if (/^[\{\[]/.test(cleaned) && /series|option|chart/i.test(cleaned)) return true;

    return false;
}

/**
 * 检查内容是否可能是 mermaid 类型
 */
export function isMermaidContent(content: string): boolean {
    const cleaned = stripBOM(content).trim();
    if (!cleaned) return false;

    // 包含 ```mermaid 标记
    if (/```mermaid/i.test(cleaned)) return true;

    // 常见 mermaid 关键字模式
    const mermaidKeywords = /^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|erDiagram|gantt|pie|gitGraph|journey)/;
    if (mermaidKeywords.test(cleaned)) return true;

    return false;
}
