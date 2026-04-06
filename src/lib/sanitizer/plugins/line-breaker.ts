import { SanitizerPlugin } from '../types';

const CHINESE_SENTENCE_END = /[。！？；]/;
const CHINESE_SENTENCE_END_GLOBAL = /[。！？；]/g;
const LINE_BREAK_THRESHOLD = 100;

/**
 * Inserts line breaks in long Chinese text at sentence endings
 */
export const lineBreaker: SanitizerPlugin = {
  name: 'line-breaker',
  phase: 'post-restore', // Run after restoring pointers to count length accurately
  process(text, context) {
    if (context.options.chineseLineBreaks === false) return text;

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
};
