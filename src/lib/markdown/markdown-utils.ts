/**
 * Markdown 预处理器 — LaTeX 转换 + 通行幂等结构化间距修复 + 中文智能换行
 *
 * 设计原则:
 *   - 通行: 不针对任何特定厂商，所有模型输出均适用
 *   - 幂等: 对已正确排版的内容运行后结果不变
 *   - 保守: 仅修复最明确的结构缺陷，不触碰歧义场景
 */

const CHINESE_SENTENCE_END = /[。！？；]/;
const CHINESE_SENTENCE_END_GLOBAL = /[。！？；]/g;
const LINE_BREAK_THRESHOLD = 60;

export function preprocessMarkdown(text: string): string {
    if (!text) return '';

    let processed = text;

    // ━━ 1. LaTeX 分隔符转换 ━━
    processed = processed.replaceAll('\\[', '$$').replaceAll('\\]', '$$');
    processed = processed.replaceAll('\\(', '$').replaceAll('\\)', '$');

    // ━━ 2. 保护代码块和行内代码（避免结构修复影响代码内容）━━
    const protectedBlocks: string[] = [];
    processed = processed.replace(/(```[\s\S]*?```|`[^`]+`)/g, (match) => {
        protectedBlocks.push(match);
        return `\x00PB${protectedBlocks.length - 1}\x00`;
    });

    // ━━ 3. 结构化间距修复（通行 + 幂等）━━
    // 守卫原理: ([^\n]) 确保仅在前方不是换行符时匹配，已有 \n\n 时不动作

    // 3a. 标题（有空格）前确保空行
    processed = processed.replace(/([^\n#])\n?(#{1,6}\s)/g, '$1\n\n$2');

    // 3a'. 标题（无空格，畸形）: "text###标题" → "text\n\n### 标题"
    processed = processed.replace(/([^\n#])\n?(#{1,6})([^\s#\n])/g, '$1\n\n$2 $3');
    processed = processed.replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2');

    // 3b. 分隔符前后确保空行
    processed = processed.replace(/([^\n])\n?(---+)/g, '$1\n\n$2');
    processed = processed.replace(/(---+)\n?([^\n])/g, '$1\n\n$2');

    // 3c. 有序列表紧跟正文
    processed = processed.replace(/([^\n\d# ])\n?(\d{1,2}\. )/g, '$1\n$2');

    // 3d'. 修复粘连的 bullet + bold
    processed = processed.replace(/\*\*\*([^*\n]+)\*\*(?!\*)/g, '\n* **$1**');

    // 3d. 无序列表紧跟正文
    processed = processed.replace(/([^\n*])\n?([-*] )/g, '$1\n$2');

    // ━━ 4. 中文智能换行 ━━
    // 针对低参数模型输出的无换行长文本，在句末标点后智能插入换行
    processed = addChineseLineBreaks(processed);

    // ━━ 5. 恢复保护块 ━━
    protectedBlocks.forEach((block, i) => {
        processed = processed.replace(`\x00PB${i}\x00`, block);
    });

    return processed;
}

/**
 * 中文智能换行
 * 
 * 规则：检测连续长文本（超过阈值的中文段落），在句末标点后插入换行
 * 条件：
 *   - 当前位置是句末标点（。！？；）
 *   - 标点后紧跟非换行字符
 *   - 从当前位置到下个句末标点/换行的距离超过阈值
 */
function addChineseLineBreaks(text: string): string {
    const lines = text.split('\n');
    const result: string[] = [];

    for (const line of lines) {
        // 跳过空行、Markdown 结构行、已包含换行的短行
        if (
            !line.trim() ||
            line.startsWith('#') ||
            line.startsWith('-') ||
            line.startsWith('*') ||
            line.startsWith('>') ||
            line.startsWith('|') ||
            /^\d+\.\s/.test(line) ||
            line.length < LINE_BREAK_THRESHOLD
        ) {
            result.push(line);
            continue;
        }

        // 检测是否为连续长文本（句末标点密度低）
        const sentenceEnds = line.match(CHINESE_SENTENCE_END_GLOBAL);
        if (!sentenceEnds || sentenceEnds.length < 2) {
            result.push(line);
            continue;
        }

        // 计算平均句子长度
        const avgSentenceLength = line.length / sentenceEnds.length;
        if (avgSentenceLength < LINE_BREAK_THRESHOLD / 2) {
            // 句子较短，说明已有合理分段，跳过
            result.push(line);
            continue;
        }

        // 执行智能换行：在句末标点后插入换行
        let processedLine = '';
        let lastInsertPos = 0;

        let match: RegExpExecArray | null;
        const regex = new RegExp(CHINESE_SENTENCE_END_GLOBAL.source, 'g');

        while ((match = regex.exec(line)) !== null) {
            const punctPos = match.index;
            const afterPunctPos = punctPos + 1;

            // 检查标点后是否已有换行或空格
            if (afterPunctPos >= line.length) {
                processedLine += line.slice(lastInsertPos);
                break;
            }

            const charAfter = line[afterPunctPos];

            // 如果后面是引号或括号，跳过
            if (['"', '"', '」', '』', '）', ')', '》', '】', ']'].includes(charAfter)) {
                continue;
            }

            // 如果后面已经是空格或换行，跳过
            if (charAfter === ' ' || charAfter === '\n') {
                continue;
            }

            // 检查到下一个句末标点的距离
            const remaining = line.slice(afterPunctPos);
            const nextPunctMatch = remaining.match(CHINESE_SENTENCE_END);
            const distanceToNext = nextPunctMatch ? nextPunctMatch.index! + 1 : remaining.length;

            // 如果距离超过阈值，插入换行
            if (distanceToNext >= LINE_BREAK_THRESHOLD / 2) {
                processedLine += line.slice(lastInsertPos, afterPunctPos) + '\n';
                lastInsertPos = afterPunctPos;
            }
        }

        // 添加剩余部分
        if (lastInsertPos < line.length) {
            processedLine += line.slice(lastInsertPos);
        }

        result.push(processedLine);
    }

    return result.join('\n');
}

/**
 * Common styles for Markdown rendering
 */
export const markdownStyles = {
    body: {
        fontSize: 16,
        lineHeight: 24,
        color: '#3f3f46',
    },
    heading1: {
        marginTop: 24,
        marginBottom: 16,
        lineHeight: 32,
        fontWeight: '700',
        borderBottomWidth: 0.5,
        borderBottomColor: '#e4e4e7',
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
