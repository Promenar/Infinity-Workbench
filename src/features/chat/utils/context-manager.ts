import { Message } from '../hooks/useChat';

export interface ContextConfig {
    maxMessages: number; // e.g., 20 recently active messages
    summarizeThreshold: number; // e.g., if token count > 4k (mocked by char count roughly)
}

export const trimContext = (messages: Message[], config: ContextConfig = { maxMessages: 20, summarizeThreshold: 4000 }): Message[] => {
    // Simple sliding window: keep system prompt + last N messages
    const systemMessage = messages.find(m => m.role === 'system');
    const otherMessages = messages.filter(m => m.role !== 'system');

    const recentMessages = otherMessages.slice(-config.maxMessages);

    if (systemMessage) {
        return [systemMessage, ...recentMessages];
    }
    return recentMessages;
};

export const generateContextSummary = (messages: Message[]): string => {
    // Placeholder for actual LLM summarization call
    // In real app, this would call the API to summarize older messages
    return "Summary of previous conversation...";
};
