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
const LINE_BREAK_THRESHOLD = 100;

export function preprocessMarkdown(text: string): string {
    if (!text) return '';

    let processed = text;

    // ━━ 1. LaTeX 分隔符转换 ━━
    processed = processed.replaceAll('\\[', '$$').replaceAll('\\]', '$$');
    processed = processed.replaceAll('\\(', '$').replaceAll('\\)', '$');

    // ━━ 2. 保护代码块和行内代码（避免结构修复影响代码内容）━━
    const protectedBlocks: string[] = [];
    const PB_PREFIX = '\x00\x01PB_';
    const PB_SUFFIX = '_PB\x01\x00';
    processed = processed.replace(/(```[\s\S]*?```|`[^`]+`)/g, (match) => {
        protectedBlocks.push(match);
        return `${PB_PREFIX}${protectedBlocks.length - 1}${PB_SUFFIX}`;
    });

    // ━━ 3. 结构化间距修复（通行 + 幂等）━━
    // 守卫原理: ([^\n]) 确保仅在前方不是换行符时匹配，已有 \n\n 时不动作

    // 3a. 标题（有空格）前确保空行
    processed = processed.replace(/([^\n#])\n?(#{1,6}\s)/g, '$1\n\n$2');

    // 3a'. 标题（无空格，畸形）: "text###标题" → "text\n\n### 标题"
    processed = processed.replace(/([^\n#])\n?(#{1,6})([^\s#\n])/g, '$1\n\n$2 $3');
    processed = processed.replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2');

    // 3b. 分隔符前后确保空行（仅匹配独立成行的 HR，避免破坏 GFM 表格分隔行 `| --- |`）
    processed = processed.replace(/([^\n])\n(^---+\s*$)/gm, '$1\n\n$2');
    processed = processed.replace(/(^---+\s*$)\n([^\n])/gm, '$1\n\n$2');

    // 3c. 有序列表紧跟正文
    processed = processed.replace(/([^\n\d# ])\n?(\d{1,2}\. )/g, '$1\n$2');

    // 3d'. 修复粘连的 bullet + bold（仅行首场景，避免误匹配合法的 bold+italic）
    processed = processed.replace(/^(\*\*\*)((?=[^*])([^*\n]+))\*\*(?!\*)/gm, '\n* **$3**');

    // 3d. 无序列表紧跟正文
    processed = processed.replace(/([^\n*])\n?([-*] )/g, '$1\n$2');

    // ━━ 3e. 表格结构修复 ━━
    // 修复低参数模型（如 MiniMax）输出的畸形 GFM 表格
    processed = fixMalformedTables(processed);

    // ━━ 3.5. 中西文混排间距（pangu.js 规则）━━
    // 中文后紧跟拉丁字母/数字 → 插入空格
    processed = processed.replace(/([\u4e00-\u9fa5\u3400-\u4dbf])([A-Za-z0-9])/g, '$1 $2');
    // 拉丁字母/数字后紧跟中文 → 插入空格
    processed = processed.replace(/([A-Za-z0-9])([\u4e00-\u9fa5\u3400-\u4dbf])/g, '$1 $2');

    // ━━ 4. 中文智能换行 ━━
    // 针对低参数模型输出的无换行长文本，在句末标点后智能插入换行
    processed = addChineseLineBreaks(processed);

    // ━━ 5. 恢复保护块 ━━
    protectedBlocks.forEach((block, i) => {
        processed = processed.replace(`${PB_PREFIX}${i}${PB_SUFFIX}`, block);
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
            if (['\u201d', '\u300d', '\u300f', '\uff09', ')', '\u300b', '\u3011', ']'].includes(charAfter)) {
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

            // 如果距离超过阈值，插入双换行实现段落分割
            if (distanceToNext >= LINE_BREAK_THRESHOLD / 2) {
                processedLine += line.slice(lastInsertPos, afterPunctPos) + '\n\n';
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
 * 表格结构修复（状态机模式）
 * 
 * 处理低参数模型（如 MiniMax-M2.7）输出的畸形 GFM 表格。
 * 常见问题：
 *   1. 行之间使用 `||` 双管道符而非换行
 *   2. 缺少 GFM 必需的分隔行 `|---|---|---|`
 *   3. 行首/行尾多余的空管道符
 * 
 * 设计约束：
 *   - 幂等：已正确格式化的表格不受影响
 *   - 保守：仅修复高置信度的表格模式
 *   - 状态机：追踪 inTable 状态，只在表头后补一次分隔行
 */
function fixMalformedTables(text: string): string {
    // 步骤 1：拆分粘连的表格行 `||` → `|\n|`
    let processed = text.replace(/\|\|(?=\s*[^\|\n])/g, '|\n|');

    // 步骤 2：清理行首多余管道符 `|| 内容` → `| 内容`
    processed = processed.replace(/^\|\|(?!\|)/gm, '|');

    // 步骤 3：清理行尾多余管道符 `内容 ||` → `内容 |`
    processed = processed.replace(/\|\|$/gm, '|');

    // 步骤 3.5：清理独立的空管道行（`||` 拆分后的残留）
    processed = processed.replace(/^\|?\|?\s*$/gm, '').replace(/\n{3,}/g, '\n\n');

    // 步骤 4：状态机检测并修复缺少分隔行的表格
    const lines = processed.split('\n');
    const result: string[] = [];
    let inTable = false;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i]?.trim() ?? '';
        const isTableRow = line.startsWith('|') && line.endsWith('|') &&
            (line.match(/\|/g) || []).length >= 3;
        const isSepLine = /^\|[\s\-:]+([\s\-:]*\|)+/.test(line);

        // 非表格内容 → 重置状态
        if (!isTableRow && !isSepLine) {
            inTable = false;
            result.push(lines[i]);
            continue;
        }

        // 分隔行 → 标记已在表格中
        if (isSepLine) {
            inTable = true;
            result.push(lines[i]);
            continue;
        }

        // 数据行且已在表格内 → 直接输出
        if (inTable) {
            result.push(lines[i]);
            continue;
        }

        // 表格行且不在表格内 → 可能是表头
        result.push(lines[i]);
        const nextLine = lines[i + 1]?.trim() ?? '';
        const nextIsSep = /^\|[\s\-:]+([\s\-:]*\|)+/.test(nextLine);

        if (nextIsSep) {
            inTable = true; // 下一行已是分隔行，幂等
            continue;
        }

        // 下一行是表格行（缺分隔行）？
        const nextIsTableRow = nextLine.startsWith('|') && nextLine.endsWith('|') &&
            (nextLine.match(/\|/g) || []).length >= 3;
        // 或下一行是空行/空管道，再下一行是表格行？
        const nextIsEmpty = !nextLine || nextLine === '|' || nextLine === '||';
        const lineAfter = nextIsEmpty ? (lines[i + 2]?.trim() ?? '') : '';
        const lineAfterIsTable = nextIsEmpty &&
            lineAfter.startsWith('|') && lineAfter.endsWith('|') &&
            (lineAfter.match(/\|/g) || []).length >= 3;

        if (nextIsTableRow || lineAfterIsTable) {
            const pipeCount = (line.match(/\|/g) || []).length;
            const colCount = pipeCount - 1;
            result.push('|' + ' --- |'.repeat(colCount));
            inTable = true;
            if (nextIsEmpty && !nextIsTableRow) i++; // 跳过空行
        }
    }

    return result.join('\n');
}

/**
 * Common styles for Markdown rendering
 */
export const markdownStyles = {
    body: {
        fontSize: 16,
        lineHeight: 26,
        color: '#3f3f46',
        includeFontPadding: false,
        textAlignVertical: 'center' as const,
    },
    text: {
        includeFontPadding: false,
        textAlignVertical: 'center' as const,
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
        lineHeight: 26,
    },
    list_item: {
        marginTop: 4,
        marginBottom: 4,
    },
};
