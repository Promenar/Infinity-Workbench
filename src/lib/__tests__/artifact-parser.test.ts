/**
 * artifact-parser 单元测试
 *
 * 覆盖：正常 JSON 解析、代码块标记提取、边界情况、错误恢复
 */

import {
    parseEChartsContent,
    parseMermaidContent,
    extractEChartsMetadata,
    isEChartsContent,
    isMermaidContent,
} from '../artifact-parser';

// ---------------------------------------------------------------------------
// parseEChartsContent
// ---------------------------------------------------------------------------

describe('parseEChartsContent', () => {
    it('应解析纯 JSON 内容', () => {
        const input = JSON.stringify({
            title: { text: 'Sales Chart' },
            series: [{ type: 'bar', data: [10, 20, 30] }],
        });

        const result = parseEChartsContent(input);
        expect(result.data).toBeTruthy();
        expect(result.data.title.text).toBe('Sales Chart');
        expect(result.data.series[0].type).toBe('bar');
        expect(result.error).toBeNull();
    });

    it('应解析含 ```echarts 代码块标记的内容', () => {
        const input = '```echarts\n{"title":{"text":"Test"},"series":[{"type":"line"}]}\n```';

        const result = parseEChartsContent(input);
        expect(result.data).toBeTruthy();
        expect(result.data.title.text).toBe('Test');
        expect(result.error).toBeNull();
    });

    it('应解析含 ```json 代码块标记的内容', () => {
        const input = '```json\n{"title":{"text":"JSON Chart"},"series":[{"type":"pie"}]}\n```';

        const result = parseEChartsContent(input);
        expect(result.data).toBeTruthy();
        expect(result.data.title.text).toBe('JSON Chart');
    });

    it('应从混合文本中提取 JSON', () => {
        const input = 'Here is the chart config: {"title":{"text":"Extracted"},"series":[{"type":"bar"}]} end';

        const result = parseEChartsContent(input);
        expect(result.data).toBeTruthy();
        expect(result.data.title.text).toBe('Extracted');
    });

    it('应返回空内容的结果', () => {
        const result = parseEChartsContent('');
        expect(result.data).toBeNull();
        expect(result.error).toBeNull();
    });

    it('应返回仅空白的结果', () => {
        const result = parseEChartsContent('   \n\t  ');
        expect(result.data).toBeNull();
        expect(result.error).toBeNull();
    });

    it('应对完全无效内容返回 data 为字符串（jsonrepair 将纯文本包装为 JSON 字符串）', () => {
        const result = parseEChartsContent('this is not json at all no braces');
        // jsonrepair 会将纯文本包装为合法的 JSON 字符串，因此不产生 error
        expect(typeof result.data).toBe('string');
        expect(result.error).toBeNull();
    });

    it('应处理含 BOM 头的内容', () => {
        const bom = '\uFEFF';
        const input = bom + JSON.stringify({ title: { text: 'BOM Test' } });

        const result = parseEChartsContent(input);
        expect(result.data).toBeTruthy();
        expect(result.data.title.text).toBe('BOM Test');
    });

    it('应处理含 JS 注释的 JSON', () => {
        const input = '{\n  // This is a comment\n  "title": {"text": "Commented"}\n}';

        const result = parseEChartsContent(input);
        // jsonrepair should handle this
        expect(result.data || result.raw).toBeTruthy();
    });
});

// ---------------------------------------------------------------------------
// parseMermaidContent
// ---------------------------------------------------------------------------

describe('parseMermaidContent', () => {
    it('应解析纯 mermaid 代码', () => {
        const input = 'graph TD\n    A-->B\n    B-->C';

        const result = parseMermaidContent(input);
        expect(result.data).toBe('graph TD\n    A-->B\n    B-->C');
        expect(result.error).toBeNull();
    });

    it('应移除 ```mermaid 代码块标记', () => {
        const input = '```mermaid\ngraph LR\n    A-->B\n```';

        const result = parseMermaidContent(input);
        expect(result.data).toContain('graph LR');
        expect(result.data).not.toContain('```mermaid');
        expect(result.data).not.toContain('```');
    });

    it('应返回空内容的空结果', () => {
        const result = parseMermaidContent('');
        expect(result.data).toBeNull();
        expect(result.error).toBeNull();
    });

    it('应拒绝不含字母字符的内容', () => {
        const result = parseMermaidContent('12345 !!! @@@');
        expect(result.data).toBeNull();
        expect(result.error).toBeTruthy();
    });

    it('应处理含 BOM 头的内容', () => {
        const bom = '\uFEFF';
        const input = bom + 'graph TD\n    A-->B';

        const result = parseMermaidContent(input);
        expect(result.data).toContain('graph TD');
    });

    it('应处理 sequenceDiagram 类型', () => {
        const input = 'sequenceDiagram\n    participant A\n    participant B\n    A->>B: Hello';

        const result = parseMermaidContent(input);
        expect(result.data).toContain('sequenceDiagram');
        expect(result.error).toBeNull();
    });
});

// ---------------------------------------------------------------------------
// extractEChartsMetadata
// ---------------------------------------------------------------------------

describe('extractEChartsMetadata', () => {
    it('应从 option 中提取标题和图表类型', () => {
        const option = {
            title: { text: 'Revenue Report' },
            series: [{ type: 'pie', data: [10, 20] }],
        };

        const meta = extractEChartsMetadata(option);
        expect(meta.title).toBe('Revenue Report');
        expect(meta.chartType).toBe('pie');
    });

    it('应返回默认值对于 null option', () => {
        const meta = extractEChartsMetadata(null);
        expect(meta.title).toBe('ECharts Visualization');
        expect(meta.chartType).toBe('bar');
    });

    it('应返回默认值对于空 option', () => {
        const meta = extractEChartsMetadata({});
        expect(meta.title).toBe('ECharts Visualization');
        expect(meta.chartType).toBe('bar');
    });
});

// ---------------------------------------------------------------------------
// isEChartsContent / isMermaidContent
// ---------------------------------------------------------------------------

describe('isEChartsContent', () => {
    it('应识别 ```echarts 标记', () => {
        expect(isEChartsContent('```echarts\n{}\n```')).toBe(true);
    });

    it('应识别含 series 关键字的 JSON', () => {
        expect(isEChartsContent('{"series":[]}')).toBe(true);
    });

    it('应拒绝普通文本', () => {
        expect(isEChartsContent('Hello World')).toBe(false);
    });

    it('应拒绝空字符串', () => {
        expect(isEChartsContent('')).toBe(false);
    });
});

describe('isMermaidContent', () => {
    it('应识别 ```mermaid 标记', () => {
        expect(isMermaidContent('```mermaid\ngraph TD\n```')).toBe(true);
    });

    it('应识别 graph 关键字', () => {
        expect(isMermaidContent('graph TD\n    A-->B')).toBe(true);
    });

    it('应识别 flowchart 关键字', () => {
        expect(isMermaidContent('flowchart LR\n    A-->B')).toBe(true);
    });

    it('应拒绝普通文本', () => {
        expect(isMermaidContent('Just some text')).toBe(false);
    });
});
