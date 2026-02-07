/**
 * DeepSeek / Local LLM Output Formatter
 * 
 * fixes issues where models output dense text without proper markdown newlines,
 * especially for headers, lists, and bold sections.
 */
export const formatDeepSeekOutput = (content: string): string => {
    if (!content) return '';

    let processed = content;

    // 1. Fix: "**【Title】**" pattern often lacks preceding newlines
    // Replace "text**【" with "text\n\n**【"
    processed = processed.replace(/([^\n])(\*\*【)/g, '$1\n\n$2');

    // 2. Fix: "**Title**" at start of lines without proper spacing
    // If a bold segment follows a sentence ending (。！？) or newline, ensure it has a newline before it
    // Example: "...end。**Next Part**" -> "...end。\n\n**Next Part**"
    processed = processed.replace(/([。！？])\s*(\*\*)/g, '$1\n\n$2');

    // 3. Fix: Lists that are mashed together
    // "1. Item 1 2. Item 2" -> "1. Item 1\n2. Item 2"
    // Match any non-newline character followed by space + digit + dot + space
    // We rely on the fact that lists usually start with 1-2 digits. 
    // To safe guard against dates (May 12. 2024), we might match specific context or accept the risk for now.
    // The key is the \s before the number.
    processed = processed.replace(/([^\n])\s+(\d{1,2}\.)\s/g, '$1\n$2 ');

    // 4. Fix: Bullet points mashed together
    // "text - item" -> "text\n- item" or "text* item" -> "text\n* item"
    // Ensure we don't break hyphenated words (requires space before -)
    processed = processed.replace(/([^\n])\s+(-|\*)\s/g, '$1\n$2 ');

    // 5. Fix: "---" separators without newlines
    processed = processed.replace(/([^\n])(---)/g, '$1\n\n$2');
    processed = processed.replace(/(---)([^\n])/g, '$1\n\n$2');

    // 6. Fix: Bold Key-Value pairs like "**Key**: Value" often missing newlines if stuck together
    // "Value.**Key**:" -> "Value.\n\n**Key**:"
    processed = processed.replace(/([^\n])(\*\*.*?\*\*[:：])/g, '$1\n\n$2');

    return processed;
};
