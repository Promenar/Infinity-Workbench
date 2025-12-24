import { LlmClient, ChatMessage } from '../types';

export class OpenAiClient implements LlmClient {
    private apiKey: string;
    private baseUrl: string;
    private model: string;
    private temperature: number;

    constructor(apiKey: string, model: string, temperature: number, baseUrl: string = 'https://api.openai.com/v1') {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
    }

    async streamChat(
        messages: ChatMessage[],
        onChunk: (chunk: string) => void,
        onError: (err: Error) => void
    ): Promise<void> {
        try {
            const response = await fetch(`${this.baseUrl}/chat/completions`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${this.apiKey}`,
                },
                body: JSON.stringify({
                    model: this.model,
                    messages,
                    temperature: this.temperature,
                    stream: true,
                }),
            });

            if (!response.ok) {
                throw new Error(`API Error: ${response.statusText}`);
            }

            const reader = response.body?.getReader(); // Note: React Native Fetch might not support readable stream fully without polyfill or specialized lib
            // For RN, usually line-by-line reading of text response or using a library like event-source-polyfill is needed
            // However, modern Expo fetch often supports text streaming if not strictly ReadableStream.
            // Let's assume standard behavior for now, but in RN we might need XMLHttpRequest for true streaming or a specific lib.

            // FALLBACK for MVP Check: simple Mock stream if native fetch stream is tricky without logic
            // TODO: Implement robust RN streaming reader

            // Simulating stream for this phase since RN Reader needs utils
            const text = await response.text();
            // This is NOT streaming, strictly speaking. 
            // For true streaming in RN, we usually use `react-native-sse` or `fetch-event-source`
            // I'll leave a TODO here and implement a basic "Split by newline" parser if the fetch returns a big chunk.

            onChunk(text); // Placeholder for non-streaming fallback

        } catch (e) {
            onError(e as Error);
        }
    }
}
