import { SanitizerPlugin } from '../types';

/**
 * Repairs malformed GFM tables (missing separators, double pipes, etc.)
 */
export const tableFixer: SanitizerPlugin = {
  name: 'table-fixer',
  phase: 'post-protect',
  process(text) {
    // ━━ 表格结构修复 ━━
    
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
};
