import { sanitize } from './src/lib/sanitizer';

const testCases = [
  {
    name: 'LaTeX Normalization',
    input: 'Calculate \\[ x^2 \\] and \\( y \\).',
    expected: 'Calculate $$ x^2 $$ and $ y $.'
  },
  {
    name: 'Table Repair (MiniMax style)',
    input: '| Header 1 | Header 2 || Row 1-1 | Row 1-2 |',
    expected: '| Header 1 | Header 2 |\n| --- | --- |\n| Row 1-1 | Row 1-2 |'
  },
  {
    name: 'JSON Repair (ECharts)',
    input: '```echarts\n{ title: { text: "Test" }, series: [{ type: "bar", data: [1, 2, 3] }] }\n```',
    expectedContent: '"title":{"text":"Test"}' // check if keys are quoted
  },
  {
    name: 'Image Extraction',
    input: 'Text before ![Alt](https://example.com/img.png) text after.',
    options: { extractImages: true }
  }
];

async function runTests() {
  console.log('--- ContentSanitizer Verification ---\n');
  
  for (const tc of testCases) {
    const result = sanitize(tc.input, tc.options);
    console.log(`Test: ${tc.name}`);
    console.log(`Input: ${tc.input.replace(/\n/g, '\\n')}`);
    console.log(`Output: ${result.text.replace(/\n/g, '\\n')}`);
    if (result.images) console.log(`Images: ${JSON.stringify(result.images)}`);
    console.log('------------------------------------\n');
  }
}

runTests().catch(console.error);
