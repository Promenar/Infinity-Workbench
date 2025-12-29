/**
 * Markdown 内容预处理工具
 * 用于在渲染前处理 LaTeX 公式和特殊的 SVG  
 */

export interface ContentSegment {
    type: 'text' | 'latex' | 'latexBlock' | 'svg';
    content: string;
    key: string;
}

/**
 * 解析 Markdown 内容，识别 LaTeX ($...$, $$...$$) 和 SVG
 */
export function parseMarkdownContent(markdown: string): ContentSegment[] {
    const segments: ContentSegment[] = [];
    let currentIndex = 0;
    let keyCounter = 0;

    // 正则模式
    const blockLatexRegex = /\$\$([\s\S]+?)\$\$/g;
    const inlineLatexRegex = /\$((?!\$)[^\$]+?)\$/g;
    const svgRegex = /```svg\n([\s\S]+?)```/g;

    // 收集所有匹配项并按位置排序
    const matches: Array<{ start: number; end: number; type: 'latexBlock' | 'latex' | 'svg'; content: string }> = [];

    // 块级 LaTeX
    let match;
    while ((match = blockLatexRegex.exec(markdown)) !== null) {
        matches.push({
            start: match.index,
            end: match.index + match[0].length,
            type: 'latexBlock',
            content: match[1].trim()
        });
    }

    // 行内 LaTeX
    blockLatexRegex.lastIndex = 0;
    while ((match = inlineLatexRegex.exec(markdown)) !== null) {
        // 确保不在块级 LaTeX 内部
        const isInsideBlock = matches.some(m =>
            m.type === 'latexBlock' && match!.index >= m.start && match!.index < m.end
        );
        if (!isInsideBlock) {
            matches.push({
                start: match.index,
                end: match.index + match[0].length,
                type: 'latex',
                content: match[1].trim()
            });
        }
    }

    // SVG 代码块
    while ((match = svgRegex.exec(markdown)) !== null) {
        matches.push({
            start: match.index,
            end: match.index + match[0].length,
            type: 'svg',
            content: match[1].trim()
        });
    }

    // 按起始位置排序
    matches.sort((a, b) => a.start - b.start);

    // 构建分段
    for (const m of matches) {
        //前面的普通文本
        if (currentIndex < m.start) {
            const textContent = markdown.substring(currentIndex, m.start);
            if (textContent.trim()) {
                segments.push({
                    type: 'text',
                    content: textContent,
                    key: `text-${keyCounter++}`
                });
            }
        }

        // 特殊内容
        segments.push({
            type: m.type,
            content: m.content,
            key: `${m.type}-${keyCounter++}`
        });

        currentIndex = m.end;
    }

    // 剩余的普通文本
    if (currentIndex < markdown.length) {
        const textContent = markdown.substring(currentIndex);
        if (textContent.trim()) {
            segments.push({
                type: 'text',
                content: textContent,
                key: `text-${keyCounter++}`
            });
        }
    }

    // 如果没有任何特殊内容，返回整个文本作为一个段落
    if (segments.length === 0 && markdown.trim()) {
        segments.push({
            type: 'text',
            content: markdown,
            key: 'text-0'
        });
    }

    return segments;
}
