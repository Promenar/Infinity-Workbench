
const formatDeepSeekOutput = (content) => {
    if (!content) return '';

    let processed = content;

    // 1. Fix: "**【Title】**" pattern often lacks preceding newlines
    processed = processed.replace(/([^\n])(\*\*【)/g, '$1\n\n$2');

    // 2. Fix: "**Title**" at start of lines without proper spacing
    processed = processed.replace(/([。！？])\s*(\*\*)/g, '$1\n\n$2');

    // 3. Fix: Lists that are mashed together
    processed = processed.replace(/([^\n])\s+(\d{1,2}\.)\s/g, '$1\n$2 ');

    // 4. Fix: Bullet points mashed together
    processed = processed.replace(/([^\n])\s+(-|\*)\s/g, '$1\n$2 ');

    return processed;
};

const testInput = `...两种操作。**【活体实验：生命的禁区】**你从别墅...
**结论**：你的空间...
1. Item 1 2. Item 2
text - Item
**第一部分：【剧情推进】****【能力开发：空间的秘密】**`;

console.log('--- Original ---');
console.log(testInput);
console.log('\n--- Processed ---');
console.log(formatDeepSeekOutput(testInput));
