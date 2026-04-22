/**
 * ArtifactParser 单元测试
 * 测试 ECharts 和 Mermaid 内容的解析功能
 */

import {
  parseEChartsContent,
  parseMermaidContent,
  extractEChartsMetadata,
  isEChartsContent,
  isMermaidContent,
} from '../../artifact-parser';

describe('parseEChartsContent', () => {
  describe('基本解析', () => {
    it('应解析标准 JSON 格式', () => {
      const result = parseEChartsContent('{"series": [{"type": "bar"}]}');
      expect(result.data).toBeDefined();
      expect(result.data.series[0].type).toBe('bar');
      expect(result.error).toBeNull();
    });

    it('应解析数组格式', () => {
      const result = parseEChartsContent('[{"x": 1}, {"x": 2}]');
      expect(result.data).toHaveLength(2);
    });

    it('应处理空字符串', () => {
      const result = parseEChartsContent('');
      expect(result.data).toBeNull();
      expect(result.error).toBeNull();
    });

    it('应处理纯空白字符串', () => {
      const result = parseEChartsContent('   \n\t  ');
      expect(result.data).toBeNull();
      expect(result.error).toBeNull();
    });
  });

  describe('代码块解析', () => {
    it('应解析 ```echarts 代码块', () => {
      const result = parseEChartsContent('```echarts\n{"title": "Test"}\n```');
      expect(result.data).toBeDefined();
      expect(result.data.title).toBe('Test');
    });

    it('应解析 ```json 代码块', () => {
      const result = parseEChartsContent('```json\n{"type": "line"}\n```');
      expect(result.data).toBeDefined();
      expect(result.data.type).toBe('line');
    });

    it('应处理代码块中的换行', () => {
      const content = '```echarts\n{\n  "series": [\n    {"type": "pie"}\n  ]\n}\n```';
      const result = parseEChartsContent(content);
      expect(result.data).toBeDefined();
      expect(result.data.series[0].type).toBe('pie');
    });
  });

  describe('BOM 处理', () => {
    it('应移除 UTF-8 BOM', () => {
      const content = '\uFEFF{"title": "BOM Test"}';
      const result = parseEChartsContent(content);
      expect(result.data?.title).toBe('BOM Test');
    });

    it('应处理带 BOM 的代码块', () => {
      const content = '```json\n\uFEFF{"title": "BOM"}```';
      const result = parseEChartsContent(content);
      expect(result.data?.title).toBe('BOM');
    });
  });

  describe('JSON 修复', () => {
    it('应修复尾部逗号', () => {
      const result = parseEChartsContent('{"x": 1, "y": 2,}');
      expect(result.data).toBeDefined();
    });

    it('应修复缺少引号的键', () => {
      const result = parseEChartsContent('{title: "Test"}');
      expect(result.data).toBeDefined();
    });

    it('应修复单引号为双引号', () => {
      const result = parseEChartsContent("{'title': 'Test'}");
      expect(result.data).toBeDefined();
    });

    it('应修复注释', () => {
      const result = parseEChartsContent('{"x": 1, // comment\n"y": 2}');
      expect(result.data).toBeDefined();
    });
  });

  describe('嵌套代码块处理', () => {
    it('应处理嵌套的代码块标记', () => {
      const result = parseEChartsContent('```json\n{"inner": "```code```"}\n```');
      expect(result.data).toBeDefined();
    });
  });

  describe('raw 属性', () => {
    it('应返回原始 JSON 字符串', () => {
      const result = parseEChartsContent('{"key": "value"}');
      expect(result.raw).toBe('{"key": "value"}');
    });

    it('应返回代码块内容作为 raw', () => {
      const result = parseEChartsContent('```echarts\n{"title": "Test"}\n```');
      expect(result.raw).toContain('{"title": "Test"}');
    });
  });
});

describe('extractEChartsMetadata', () => {
  it('应提取标题', () => {
    const option = { title: { text: 'My Chart' } };
    const meta = extractEChartsMetadata(option);
    expect(meta.title).toBe('My Chart');
  });

  it('应提取图表类型', () => {
    const option = { series: [{ type: 'line' }] };
    const meta = extractEChartsMetadata(option);
    expect(meta.chartType).toBe('line');
  });

  it('应处理多层嵌套的 series', () => {
    const option = { series: [{ type: 'pie' }, { type: 'bar' }, {}] };
    const meta = extractEChartsMetadata(option);
    expect(meta.chartType).toBe('pie');
  });

  it('应使用默认值当缺失时', () => {
    const meta = extractEChartsMetadata({});
    expect(meta.title).toBe('ECharts Visualization');
    expect(meta.chartType).toBe('bar');
  });

  it('应处理 null 输入', () => {
    const meta = extractEChartsMetadata(null);
    expect(meta.title).toBe('ECharts Visualization');
  });

  it('应处理 undefined 输入', () => {
    const meta = extractEChartsMetadata(undefined);
    expect(meta.title).toBe('ECharts Visualization');
  });
});

describe('parseMermaidContent', () => {
  describe('基本解析', () => {
    it('应解析简单流程图', () => {
      const result = parseMermaidContent('graph TD\nA[Start] --> B[End]');
      expect(result.data).toBe('graph TD\nA[Start] --> B[End]');
      expect(result.error).toBeNull();
    });

    it('应解析序列图', () => {
      const result = parseMermaidContent('sequenceDiagram\nA->>B: Hello');
      expect(result.data).toContain('sequenceDiagram');
      expect(result.error).toBeNull();
    });

    it('应解析类图', () => {
      const result = parseMermaidContent('classDiagram\nclass MyClass');
      expect(result.data).toContain('classDiagram');
    });
  });

  describe('代码块处理', () => {
    it('应移除 ```mermaid 标记', () => {
      const result = parseMermaidContent('```mermaid\ngraph TD\nA --> B\n```');
      expect(result.data).toBe('graph TD\nA --> B');
      expect(result.data).not.toContain('```mermaid');
    });

    it('应移除结尾的 ```', () => {
      const result = parseMermaidContent('graph TD\nA --> B\n```');
      expect(result.data).toBe('graph TD\nA --> B');
    });

    it('应处理多行标记', () => {
      const result = parseMermaidContent('```mermaid\ngraph LR\nA --> B\n```');
      expect(result.data).toBe('graph LR\nA --> B');
    });
  });

  describe('BOM 处理', () => {
    it('应移除 BOM', () => {
      const result = parseMermaidContent('\uFEFFgraph TD\nA --> B');
      expect(result.data).toBe('graph TD\nA --> B');
    });
  });

  describe('有效性验证', () => {
    it('应拒绝空内容', () => {
      const result = parseMermaidContent('');
      expect(result.data).toBeNull();
      // 空内容返回 { data: null, error: null }
      expect(result.error).toBeNull();
    });

    it('应拒绝纯空白内容', () => {
      const result = parseMermaidContent('   \n\t  ');
      expect(result.data).toBeNull();
      expect(result.error).toBeNull();
    });

    it('应拒绝无字母的内容', () => {
      const result = parseMermaidContent('12345\n-----\n[][][]');
      expect(result.data).toBeNull();
    });
  });

  describe('raw 属性', () => {
    it('应返回清洗后的内容作为 raw', () => {
      const result = parseMermaidContent('```mermaid\ngraph TD\nA --> B\n```');
      expect(result.raw).toBe('graph TD\nA --> B');
    });
  });
});

describe('isEChartsContent', () => {
  it('应识别 ```echarts 标记', () => {
    expect(isEChartsContent('```echarts\n{}```')).toBe(true);
  });

  it('应识别 ECHARTS 标记', () => {
    expect(isEChartsContent('```ECHARTS\n{}```')).toBe(true);
  });

  it('应识别 JSON 格式', () => {
    expect(isEChartsContent('{"series": []}')).toBe(true);
  });

  it('应识别带 series 关键字', () => {
    expect(isEChartsContent('{"series": [], "option": {}}')).toBe(true);
  });

  it('应识别带 chart 关键字', () => {
    expect(isEChartsContent('{"chart": {}}')).toBe(true);
  });

  it('应拒绝普通文本', () => {
    expect(isEChartsContent('Hello, world!')).toBe(false);
  });

  it('应拒绝空字符串', () => {
    expect(isEChartsContent('')).toBe(false);
  });

  it('应拒绝无关键字的 JSON', () => {
    expect(isEChartsContent('{"x": 1, "y": 2}')).toBe(false);
  });
});

describe('isMermaidContent', () => {
  it('应识别 ```mermaid 标记', () => {
    expect(isMermaidContent('```mermaid\ngraph TD\nA --> B\n```')).toBe(true);
  });

  it('应识别 graph 关键字', () => {
    expect(isMermaidContent('graph TD\nA --> B')).toBe(true);
  });

  it('应识别 flowchart 关键字', () => {
    expect(isMermaidContent('flowchart LR\nA --> B')).toBe(true);
  });

  it('应识别 sequenceDiagram', () => {
    expect(isMermaidContent('sequenceDiagram\nA->>B: Hello')).toBe(true);
  });

  it('应识别 classDiagram', () => {
    expect(isMermaidContent('classDiagram\nclass A')).toBe(true);
  });

  it('应识别 stateDiagram', () => {
    expect(isMermaidContent('stateDiagram-v2\n[*] --> State1')).toBe(true);
  });

  it('应识别 erDiagram', () => {
    expect(isMermaidContent('erDiagram\nCUSTOMER ||--o{ ORDER : places')).toBe(true);
  });

  it('应识别 gantt', () => {
    expect(isMermaidContent('gantt\ntitle A Gantt\nsection Section')).toBe(true);
  });

  it('应识别 pie', () => {
    expect(isMermaidContent('pie title Pets\n"Dogs" : 386')).toBe(true);
  });

  it('应识别 gitGraph', () => {
    expect(isMermaidContent('gitGraph\ncommit')).toBe(true);
  });

  it('应识别 journey', () => {
    expect(isMermaidContent('journey\ntitle My working day')).toBe(true);
  });

  it('应拒绝普通文本', () => {
    expect(isMermaidContent('Hello, this is not mermaid.')).toBe(false);
  });

  it('应拒绝空字符串', () => {
    expect(isMermaidContent('')).toBe(false);
  });

  it('应区分大小写', () => {
    expect(isMermaidContent('GRAPH TD')).toBe(false);
    expect(isMermaidContent('graph td')).toBe(true);
  });
});

describe('综合测试', () => {
  it('应正确区分 ECharts 和 Mermaid', () => {
    const echartsContent = '```echarts\n{"series": []}\n```';
    const mermaidContent = '```mermaid\ngraph TD\nA --> B\n```';

    expect(isEChartsContent(echartsContent)).toBe(true);
    expect(isMermaidContent(echartsContent)).toBe(false);

    expect(isMermaidContent(mermaidContent)).toBe(true);
    expect(isEChartsContent(mermaidContent)).toBe(false);
  });

  it('应处理混合内容', () => {
    const mixed = 'Some text before\n```mermaid\ngraph TD\nA --> B\n```\nAnd some text after';
    expect(isMermaidContent(mixed)).toBe(true);
  });

  it('应处理复杂 ECharts 配置', () => {
    const complex = `
\`\`\`echarts
{
  "title": {
    "text": "Sales Dashboard",
    "subtext": "2024 Q1"
  },
  "tooltip": {},
  "legend": {
    "data": ["Sales", "Profit"]
  },
  "xAxis": {
    "type": "category",
    "data": ["Jan", "Feb", "Mar"]
  },
  "yAxis": {},
  "series": [
    {
      "name": "Sales",
      "type": "bar",
      "data": [120, 200, 150]
    },
    {
      "name": "Profit",
      "type": "line",
      "data": [80, 140, 110]
    }
  ]
}
\`\`\`
`;
    const result = parseEChartsContent(complex);
    expect(result.data).toBeDefined();
    expect(result.data.title.text).toBe('Sales Dashboard');
    expect(result.data.series).toHaveLength(2);
  });

  it('应处理复杂 Mermaid 图表', () => {
    const complex = `
\`\`\`mermaid
sequenceDiagram
    participant A as Alice
    participant B as Bob
    participant C as Charlie

    A->>B: Hello Bob!
    B->>C: Hello Charlie!
    C-->>B: Hi Bob!
    B-->>A: Hi Alice!
\`\`\`
`;
    const result = parseMermaidContent(complex);
    expect(result.data).toBeDefined();
    expect(result.data).toContain('Alice');
    expect(result.data).toContain('Bob');
  });
});
