import { Platform } from 'react-native';

/**
 * Markdown 预处理器 — LaTeX 转换 + 通行幂等结构化间距修复
 *
 * 设计原则:
 *   - 通行: 不针对任何特定厂商，所有模型输出均适用
 *   - 幂等: 对已正确排版的内容运行后结果不变
 *   - 保守: 仅修复最明确的结构缺陷，不触碰歧义场景
 */
export function preprocessMarkdown(text: string): string {
    if (!text) return '';

    let processed = text;

    // ━━ 1. LaTeX 分隔符转换 ━━
    processed = processed.replaceAll('\\[', '$$').replaceAll('\\]', '$$');
    processed = processed.replaceAll('\\(', '$').replaceAll('\\)', '$');

    // ━━ 2. 保护代码块（避免结构修复影响代码内容）━━
    const codeBlocks: string[] = [];
    processed = processed.replace(/(```[\s\S]*?```)/g, (match) => {
        codeBlocks.push(match);
        return `\x00CB${codeBlocks.length - 1}\x00`;
    });

    // ━━ 3. 结构化间距修复（通行 + 幂等）━━
    // 守卫原理: ([^\n]) 确保仅在前方不是换行符时匹配，已有 \n\n 时不动作

    // 3a. 标题（有空格）前确保空行
    //     [^\n#] 排除 # 自身，防止 "###" 内部 # 触发匹配
    processed = processed.replace(/([^\n#])\n?(#{1,6}\s)/g, '$1\n\n$2');

    // 3a'. 标题（无空格，畸形）: "text###标题" → "text\n\n### 标题"
    //      [^\n#] 排除 # 自身，防止 "### " 被内部 # 拆碎
    processed = processed.replace(/([^\n#])\n?(#{1,6})([^\s#\n])/g, '$1\n\n$2 $3');
    // 行首标题无空格也修复: "###标题" → "### 标题"
    processed = processed.replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2');

    // 3b. 分隔符前后确保空行
    processed = processed.replace(/([^\n])\n?(---+)/g, '$1\n\n$2');
    processed = processed.replace(/(---+)\n?([^\n])/g, '$1\n\n$2');

    // 3c. 有序列表紧跟正文
    //     字面空格而非 \s，防止匹配换行符；排除 # 和空格，防止拆磎 "### 1. 标题"
    processed = processed.replace(/([^\n\d# ])\n?(\d{1,2}\. )/g, '$1\n$2');

    // 3d'. 修复粘连的 bullet + bold: "***text**:" → "\n* **text**:"
    //      三 * 开 + 二 * 闭 = 始终是畸形 Markdown，安全修复
    //      (?!\*) 防止误触合法的 bold+italic (***text***)
    processed = processed.replace(/\*\*\*([^*\n]+)\*\*(?!\*)/g, '\n* **$1**');

    // 3d. 无序列表紧跟正文
    //     [^\n*] 排除 * 自身；字面空格而非 \s，防止 "---\n" 中的 "-\n" 被误匹配
    processed = processed.replace(/([^\n*])\n?([-*] )/g, '$1\n$2');

    // ━━ 4. 恢复代码块 ━━
    codeBlocks.forEach((block, i) => {
        processed = processed.replace(`\x00CB${i}\x00`, block);
    });

    return processed;
}

/**
 * Common styles for Markdown rendering
 */
export const markdownStyles = {
    body: {
        fontSize: 16,
        lineHeight: 24, // 1.5 * 16 (Close to Cherry Studio's 1.6)
        color: '#3f3f46', // zinc-700
    },
    heading1: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 32,
        fontWeight: '700',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7', // zinc-200
    },
    heading2: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 28,
        fontWeight: '600',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7',
    },
    paragraph: {
        marginTop: 8,
        marginBottom: 8,
        lineHeight: 24,
    },
    list_item: {
        marginTop: 4,
        marginBottom: 4,
    },
};
