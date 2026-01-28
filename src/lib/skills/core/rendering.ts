import { z } from 'zod';
import { Skill, SkillContext, ToolResult } from '../../../types/skills';

export const RenderChartSkill: Skill = {
    id: 'render_echarts',
    name: 'ECharts Renderer',
    description: `Render an interactive ECharts visualization.
Use this tool whenever you need to visualize data (bar charts, line charts, pie charts, etc.).
DO NOT use code interpreters or write HTML files for charts.
`,
    schema: z.object({
        config: z.any().describe('The ECharts JSON configuration object. Can be a JSON string or an object.')
    }),
    execute: async (args: any, context: SkillContext): Promise<ToolResult> => {
        let chartOptionStr = '';

        try {
            if (typeof args.config === 'string') {
                // Remove potential markdown fences if model hallucinated them
                const clean = args.config.trim().replace(/^```(json|echarts)?\s*|\s*```$/g, '');
                // Validate JSON
                JSON.parse(clean); // Check for validity (optional, but good for feedback)
                chartOptionStr = clean;
            } else {
                chartOptionStr = JSON.stringify(args.config, null, 2);
            }

            return {
                id: 'success',
                content: `✅ Chart rendered to UI successfully.\n\n\`\`\`echarts\n${chartOptionStr}\n\`\`\``,
                status: 'success'
            };
        } catch (e: any) {
            return {
                id: 'error',
                content: `Invalid ECharts Configuration: ${e.message}`,
                status: 'error'
            };
        }
    }
};

export const RenderMermaidSkill: Skill = {
    id: 'render_mermaid',
    name: 'Mermaid Renderer',
    description: `Render a flowchart, sequence diagram, or other Mermaid visualization.
Use this tool whenever you need to visualize logic flows, data structures, or processes.
DO NOT use code interpreters for diagrams.
`,
    schema: z.object({
        code: z.string().describe('The Mermaid diagram source code.')
    }),
    execute: async (args: any, context: SkillContext): Promise<ToolResult> => {
        const code = args.code || '';
        return {
            id: 'success',
            content: `✅ Diagram rendered to UI successfully.\n\n\`\`\`mermaid\n${code}\n\`\`\``,
            status: 'success'
        };
    }
};
