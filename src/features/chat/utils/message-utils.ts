import { Message } from '../../../types/chat';

/**
 * Extracts the full semantic content of a message, including hidden "Streaming Cards".
 * 
 * Strategy:
 * 1. Main Content: Included.
 * 2. Thinking Steps: Included (Critical for reasoning context).
 * 3. Search/Memory Results: Summarized into text (Critical for facts).
 * 4. Tool Args/Code/Charts: Excluded (Noise).
 */
export function getFullMessageContent(message: Message): string {
    const sections: string[] = [];

    // 1. Core Content (Evaluation/Answer)
    if (message.content) {
        sections.push(message.content);
    }

    // 2. Execution Steps (The "Streaming Cards")
    if (message.executionSteps) {
        message.executionSteps.forEach(step => {
            switch (step.type) {
                case 'thinking':
                    // [MUST INCLUDE] Reasoning is core knowledge
                    if (step.content) {
                        sections.push(`[Thinking Process]: ${step.content}`);
                    }
                    break;

                case 'tool_result':
                    // [MUST INCLUDE] Search/Recall Snippets (Knowledge Snapshots)
                    if (step.toolName === 'search_internet' && step.data?.sources) {
                        // Convert structured search results to natural language text
                        const snippets = step.data.sources.map((s: any) => `- ${s.title}: ${s.snippet}`).join('\n');
                        sections.push(`[Search Results Reference]:\n${snippets}`);
                    } else if (step.toolName === 'query_vector_db' && step.data?.references) {
                        // Already in RAG, but good for context summary if needed
                        const refs = step.data.references.map((r: any) => `- ${r.content}`).join('\n');
                        sections.push(`[Memory Context]:\n${refs}`);
                    } else if (step.toolName === 'native_search' && step.data?.sources) {
                        // Native Search Results
                        const snippets = step.data.sources.map((s: any) => `- ${s.title}: ${s.snippet}`).join('\n');
                        sections.push(`[Native Search Results]:\n${snippets}`);
                    }
                    // [EXCLUDE] Raw JSON / Code / Charts
                    break;

                case 'tool_call':
                    // [EXCLUDE] Tool Args (Process Noise)
                    break;
            }
        });
    }

    return sections.join('\n\n');
}
