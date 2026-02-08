/**
 * Smart Model Output Formatter
 * 
 * fixes the issue where some models (e.g. non-Gemini) output "walls of text"
 * by failing to add newlines around headers or distinct sections.
 * 
 * Strategies:
 * 1. Split adjacent bold blocks (**** -> **\n\n**)
 * 2. Identify "Structural Headers" (bold blocks with brackets, colons, specific keywords)
 * 3. Enforce newlines around headers, while respecting list markers.
 */

export function smartFormatModelOutput(text: string): string {
    if (!text) return '';
    let process = text;

    // 1. Pre-process: split adjacent bold blocks
    // e.g. **Header A****Header B** -> **Header A**\n\n**Header B**
    process = process.replace(/\*\*\*\*/g, '**\n\n**');

    /**
     * 2. Core Logic: Header Detection & Formatting
     * We iterate through all bold blocks (**...**) and analyze their content and context.
     */
    process = process.replace(/(\*\*(.*?)\*\*)/g, (match, fullBlock, content, offset, fullString) => {
        // 2.1 Sanity checks
        if (!content || content.trim().length === 0) return match;
        // Headers are rarely super long. If it's > 100 chars, it's likely just emphasized text.
        if (content.length > 100) return match;

        // 2.2 Heuristic Analysis
        let isHeader = false;
        const trimmedContent = content.trim();

        // (A) Structural Brackets: 【...】, [...], <...>
        // e.g. **【剧情推进】**, **[System Notice]**
        if (/^[【\[<].+[】\]>]$/.test(trimmedContent) || /【.+?】/.test(trimmedContent)) isHeader = true;

        // (B) Keywords at start
        // e.g. **Step 1:**, **Chapter 5:**, **Note:**
        if (/^(第.+[章部分幕节]|Section|Chapter|Step|Phase|Case|Note|Tip|Warning|Error|TODO|Fix|Part)\s*[:：\d]|^#+\s/.test(trimmedContent)) isHeader = true;

        // (C) Ends with Colon (Key-Value style)
        // e.g. **Name:**, **Description:**
        if (/[:：]$/.test(trimmedContent)) isHeader = true;

        // (D) Numbered Header (if arguably not in a list)
        // e.g. **1. Introduction**
        if (/^\d+\.\s/.test(trimmedContent)) isHeader = true;

        // (E) Short Action/System Quotes (RPG style)
        // e.g. **“行动！”**, **System:**
        if (trimmedContent.length < 15 && /[！!。]/.test(trimmedContent)) isHeader = true;
        if (trimmedContent.includes('系统提示') || trimmedContent.includes('System Notification')) isHeader = true;

        if (!isHeader) return match;

        // 2.3 Context Handling (List Awareness)
        // We need to check if this header is part of a list (e.g. "1. **Header**")
        // If so, we SHOULD NOT add a newline *before* it, ensuring it stays on the list item line.

        // Scan backwards from current match offset to find the start of the line
        let lineStart = offset;
        while (lineStart > 0 && fullString[lineStart - 1] !== '\n') {
            lineStart--;
        }
        const prefix = fullString.slice(lineStart, offset);

        // Regex to detect if line starts with a list marker
        const isListLike = /^\s*(\d+\.|-|[*])\s+$/.test(prefix);

        if (isListLike) {
            // It IS a list item.
            // Strategy: Keep attached to marker, but ensure content *after* is on new line
            // e.g. "1. **Title** Content" -> "1. **Title**\n\nContent"
            return `${fullBlock}\n\n`;
        } else {
            // Standard Header (not in a list)
            // Strategy: Isolate completely with double newlines
            return `\n\n${fullBlock}\n\n`;
        }
    });

    // 3. Cleanup: Compress excessive newlines (3+ -> 2)
    process = process.replace(/\n{3,}/g, '\n\n');

    return process.trim();
}
