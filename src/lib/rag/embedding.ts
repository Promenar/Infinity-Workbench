import { ProviderConfig } from '../../store/api-store';

export class EmbeddingClient {
    private provider: ProviderConfig;
    private model: string;

    constructor(provider: ProviderConfig, model?: string) {
        this.provider = provider;
        this.model = model || 'text-embedding-3-small'; // Default fallback
    }

    async embedDocuments(texts: string[]): Promise<number[][]> {
        // Handle based on provider type
        // OpenAI-compatible providers
        if (
            this.provider.type === 'openai' ||
            this.provider.type === 'siliconflow' ||
            this.provider.type === 'deepseek' ||
            this.provider.type === 'moonshot' ||
            this.provider.type === 'zhipu' ||
            this.provider.type === 'local'
        ) {
            return this.embedOpenAI(texts);
        }

        if (this.provider.type === 'gemini' || this.provider.type === 'google') {
            // Google/Vertex models usually named "text-embedding-004"
            return this.embedGoogle(texts);
        }

        throw new Error(`Embedding not supported for provider: ${this.provider.type}`);
    }

    async embedQuery(text: string): Promise<number[]> {
        const embeddings = await this.embedDocuments([text]);
        return embeddings[0];
    }

    private async embedOpenAI(texts: string[]): Promise<number[][]> {
        const res = await fetch(`${this.provider.baseUrl}/embeddings`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${this.provider.apiKey}`
            },
            body: JSON.stringify({
                model: this.model,
                input: texts
            })
        });

        if (!res.ok) throw new Error(`OpenAI Embedding Error: ${res.status} ${await res.text()}`);
        const data = await res.json();
        return data.data.map((item: any) => item.embedding);
    }

    private async embedGoogle(texts: string[]): Promise<number[][]> {
        // Google usually expects 1 input per request or batch endpoint logic which varies
        // For simplicity/compatibility, loop (or adapt if batch endpoint known)
        // Vertex/Gemini `embedContent` or `batchEmbedContents`

        // TODO: Implement specific Google embedding logic details
        // Note: Google's API path is specifically complex for embeddings (/models/embedding-001:embedContent)
        // Placeholder for now
        throw new Error("Google Embedding not yet fully implemented");
    }
}
