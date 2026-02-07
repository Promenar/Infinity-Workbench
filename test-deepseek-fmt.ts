
import { formatDeepSeekOutput } from './src/features/chat/utils/deepseek-formatter';

const testInput = `...两种操作。**【活体实验：生命的禁区】**你从别墅...
**结论**：你的空间...
1. Item 12. Item 2
text- Item
**第一部分：【剧情推进】****【能力开发：空间的秘密】**`;

console.log('--- Original ---');
console.log(testInput);
console.log('\n--- Processed ---');
console.log(formatDeepSeekOutput(testInput));
