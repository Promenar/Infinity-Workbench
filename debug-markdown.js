
const preprocessMarkdown = (text) => {
    if (!text) return '';

    // 1. Protect Code Blocks
    const codeBlockRegex = /(```[\s\S]*?```|`[^`]*`)/g;
    const placeholders = [];
    const protectedContent = text.replace(codeBlockRegex, (match) => {
        placeholders.push(match);
        return `__CODE_BLOCK_${placeholders.length - 1}__`;
    });

    // 2. Enhance Newlines
    // Current Logic: /([^\n])\n([^\n])/g
    // Problem: Might miss lines ending with space? Or CR?
    let processed = protectedContent.replace(/([^\n])\n([^\n])/g, '$1\n\n$2');

    // 3. Restore Code Blocks
    processed = processed.replace(/__CODE_BLOCK_(\d+)__/g, (_, index) => {
        return placeholders[parseInt(index, 10)];
    });

    return processed;
};

// Real-world scenario simulation
const inputs = [
    // Standard case
    "Line 1.\nLine 2.",
    // With trailing space
    "Line 1. \nLine 2.",
    // With carriage return
    "Line 1.\r\nLine 2.",
    // Chinese text
    "第一行。\n第二行。",
    // Chinese text with space
    "第一行。 \n第二行。"
];

inputs.forEach((input, i) => {
    const output = preprocessMarkdown(input);
    const changed = input !== output;
    console.log(`\nCase ${i + 1}: [${JSON.stringify(input)}] -> [${JSON.stringify(output)}] (${changed ? 'CHANGED' : 'UNCHANGED'})`);
});
