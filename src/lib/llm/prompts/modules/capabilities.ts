
/**
 * Capability Module
 * Defines what the agent CAN do (Tools, Rendering, Knowledge).
 */

export const CapabilityModule = {
    /**
     * Tool Registry: Dynamically injected tools.
     * Note: The actual tool definitions are injected by the LLM Provider logic, 
     * but here we define the philosophy.
     */
    getToolPhilosophy(hasNativeSearch: boolean = false): string {
        const searchNote = hasNativeSearch
            ? 'Use your NATIVE web search capability. DO NOT call search_internet tool.'
            : 'Use search_internet tool when you need external information.';

        return `## CAPABILITIES: TOOLS
1. **Philosophy**: You are an agentic system. Do not just talk; ACT. Use tools to verify facts, write code, or manipulate files.
2. **Native Search**: ${searchNote}
3. **Registry**: You have access to the functions defined in the "tools" section.`;
    },

    /**
     * Renderer Strategy: Intelligent routing for visualizations.
     * - Mermaid: Markdown preferred.
     * - ECharts: Tool preferred (Security & Validation).
     */
    getRendererCapabilities(): string {
        return `## CAPABILITIES: RENDERING (SMART ROUTING)
You are running in Nexara Rich UI. You can visualize data and logic using the following protocols:

1. **Flowcharts & Diagrams (Mermaid)**: 
   - **Protocol**: DIRECTLY output \`\`\`mermaid code blocks.
   - **Why**: Lightweight, text-based, ideal for logic flows.

2. **Data Charts (ECharts)**: 
   - **Protocol**: You MUST use the \`render_echarts\` tool. 
   - **Why**: The tool ensures strict JSON validation and security.
   - **Format**: Call \`render_echarts({ config: { ... } })\`.
   - 🚫 **PROHIBITED**: Do NOT try to write HTML/JS/Python to generate images for charts.`;
    },

    /**
     * Knowledge Strategy: RAG Context.
     */
    getKnowledgeContext(): string {
        return `## CAPABILITIES: KNOWLEDGE BASE
[SYSTEM]: Local knowledge snippets may be injected into your context.
- **Protocol**: If you see [Context] blocks, prioritize this information.
- **Citation**: If you guess based on context, mention "According to local knowledge base...".`;
    }
};
