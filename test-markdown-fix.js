
const preprocess = (content) => {
    // 1. Protect Code Blocks
    const codeBlockRegex = /(```[\s\S]*?```|`[^`]*`)/g;
    const placeholders = [];
    const protectedContent = content.replace(codeBlockRegex, (match) => {
        placeholders.push(match);
        return `__CODE_BLOCK_${placeholders.length - 1}__`;
    });

    // 2. Enhance Newlines (The Logic to Test)
    // Replace single newline with double newline, but NOT if it's already a double newline
    const formatted = protectedContent.replace(/([^\n])\n([^\n])/g, '$1\n\n$2');

    // 3. Restore
    return formatted.replace(/__CODE_BLOCK_(\d+)__/g, (_, index) => {
        return placeholders[parseInt(index)];
    });
};

const runTest = (name, input) => {
    const output = preprocess(input);
    const unchanged = output === input;
    console.log(`\n--- Test: ${name} ---`);
    console.log(`Input:\n${JSON.stringify(input)}`);
    console.log(`Output:\n${JSON.stringify(output)}`);
    console.log(`Result: ${unchanged ? 'UNCHANGED (Safe)' : 'MODIFIED'}`);
    return output;
};

// Case 1: Gemini Standard (Double Newline) - Should be safe
const geminiText = "这是第一段。\n\n这是第二段。";
runTest("Gemini Paragraphs", geminiText);

// Case 2: Lists (Double Newline) - Should be safe
const geminiList = "列表：\n\n- 项目1\n- 项目2";
// Note: Gemini tight lists uses single newline? 
// If Gemini outputs: "Lists:\n- Item 1\n- Item 2", the logic WILL modify it.
// Let's test standard tight list.
const tightList = "列表：\n- 项目1\n- 项目2";
runTest("Tight List", tightList);

// Case 3: DeepSeek (Single Newline) - Should be fixed
const deepSeekText = "第一段文字。\n第二段文字挤在一起。";
runTest("DeepSeek Paragraphs", deepSeekText);

// Case 4: Code Block - Should be safe
const codeText = "代码前\n```javascript\nconsole.log('line1');\nconsole.log('line2');\n```\n代码后";
runTest("Code Block", codeText);
