/**
 * Artifact 自动提取工具
 * 从工具执行结果或消息内容中自动识别和提取 Artifact
 */

import { ArtifactType, CreateArtifactParams } from '../../../types/artifact';

// Markdown 代码块正则表达式
const CODE_BLOCK_REGEX = /```(\w+)\n([\s\S]*?)```/g;

// 支持的 Artifact 类型与代码块语言映射
const LANGUAGE_TYPE_MAP: Record<string, ArtifactType> = {
    'echarts': 'echarts',
    'mermaid': 'mermaid',
    'mmd': 'mermaid',
    'math': 'math',
    'latex': 'math',
    'html': 'html',
    'svg': 'svg',
};

// 类型中文名称映射
const TYPE_NAME_MAP: Record<ArtifactType, string> = {
    'echarts': 'ECharts图表',
    'mermaid': 'Mermaid图表',
    'math': '数学公式',
    'html': 'HTML代码',
    'svg': 'SVG图像',
};

/**
 * 根据内容和类型生成标题
 */
export function generateArtifactTitle(content: string, type: ArtifactType): string {
    // 从内容中提取简短标题
    const firstLine = content.split('\n')[0].trim();

    // 尝试从内容中提取有意义的标题
    let title = '';

    switch (type) {
        case 'echarts': {
            // 尝试提取 title 配置
            const titleMatch = content.match(/title\s*:\s*['"`]([^'"`]+)['"`]/);
            if (titleMatch) {
                title = titleMatch[1];
            }
            break;
        }
        case 'mermaid': {
            // 尝试提取图表标题
            const titleMatch = content.match(/title\s+(.+)/);
            if (titleMatch) {
                title = titleMatch[1].trim();
            } else {
                // 使用第一行（通常是图表类型声明）
                title = firstLine;
            }
            break;
        }
        case 'math': {
            // 数学公式使用前30个字符
            title = content.trim().substring(0, 30);
            if (content.trim().length > 30) {
                title += '...';
            }
            break;
        }
        case 'html': {
            // 尝试提取 title 标签
            const titleMatch = content.match(/<title>([^<]+)<\/title>/i);
            if (titleMatch) {
                title = titleMatch[1];
            } else {
                title = 'HTML片段';
            }
            break;
        }
        case 'svg': {
            // 尝试提取 desc 或 title 标签
            const descMatch = content.match(/<desc>([^<]+)<\/desc>/i);
            const titleMatch = content.match(/<title>([^<]+)<\/title>/i);
            if (titleMatch) {
                title = titleMatch[1];
            } else if (descMatch) {
                title = descMatch[1];
            } else {
                title = 'SVG图像';
            }
            break;
        }
    }

    // 如果没有提取到标题，使用类型默认名称
    if (!title) {
        // 使用第一行内容作为标题
        if (firstLine.length <= 30) {
            title = firstLine || TYPE_NAME_MAP[type];
        } else {
            title = firstLine.substring(0, 27) + '...';
        }
    }

    return title;
}

/**
 * 提取结果接口
 */
export interface ExtractedArtifact {
    type: ArtifactType;
    title: string;
    content: string;
}

/**
 * 从文本内容中提取 Artifacts
 */
export function extractArtifactsFromContent(content: string): ExtractedArtifact[] {
    const artifacts: ExtractedArtifact[] = [];

    // 重置正则表达式状态
    CODE_BLOCK_REGEX.lastIndex = 0;

    let match;
    while ((match = CODE_BLOCK_REGEX.exec(content)) !== null) {
        const language = match[1].toLowerCase();
        const codeContent = match[2].trim();

        const type = LANGUAGE_TYPE_MAP[language];
        if (type && codeContent) {
            const title = generateArtifactTitle(codeContent, type);
            artifacts.push({
                type,
                title,
                content: codeContent,
            });
        }
    }

    return artifacts;
}

/**
 * 工具执行结果接口（简化版）
 */
interface ToolResultLike {
    content: string;
    status: 'success' | 'error';
    data?: any;
}

/**
 * 从工具执行结果中提取 Artifacts
 * 支持识别 render_echarts、render_mermaid 等渲染工具的结果
 */
export function extractArtifactsFromToolResult(
    toolName: string,
    toolResult: ToolResultLike
): ExtractedArtifact[] {
    // 只处理成功的工具调用
    if (toolResult.status !== 'success' || !toolResult.content) {
        return [];
    }

    const artifacts: ExtractedArtifact[] = [];

    // 特殊处理渲染工具
    if (toolName === 'render_echarts' || toolName === 'render_mermaid') {
        const artifactType: ArtifactType = toolName === 'render_echarts' ? 'echarts' : 'mermaid';
        const regex = artifactType === 'echarts'
            ? /```echarts\n([\s\S]*?)```/
            : /```mermaid\n([\s\S]*?)```/;

        const match = toolResult.content.match(regex);
        if (match && match[1]) {
            const codeContent = match[1].trim();
            artifacts.push({
                type: artifactType,
                title: generateArtifactTitle(codeContent, artifactType),
                content: codeContent,
            });
        }
    }

    // 通用：从任意工具结果中扫描支持的代码块
    const genericArtifacts = extractArtifactsFromContent(toolResult.content);

    // 合并结果，避免重复
    for (const artifact of genericArtifacts) {
        const isDuplicate = artifacts.some(
            a => a.type === artifact.type && a.content === artifact.content
        );
        if (!isDuplicate) {
            artifacts.push(artifact);
        }
    }

    return artifacts;
}

/**
 * 创建 Artifact 参数（包含 session 关联信息）
 */
export function createArtifactParams(
    extracted: ExtractedArtifact,
    sessionId: string,
    messageId: string,
    workspacePath?: string
): CreateArtifactParams {
    return {
        type: extracted.type,
        title: extracted.title,
        content: extracted.content,
        sessionId,
        messageId,
        workspacePath,
    };
}

/**
 * 批量创建 Artifact 参数
 */
export function createArtifactParamsBatch(
    extractedList: ExtractedArtifact[],
    sessionId: string,
    messageId: string,
    workspacePath?: string
): CreateArtifactParams[] {
    return extractedList.map(extracted => createArtifactParams(extracted, sessionId, messageId, workspacePath));
}
