export class RecursiveCharacterTextSplitter {
    private chunkSize: number;
    private chunkOverlap: number;
    private separators: string[];

    constructor(options?: { chunkSize?: number; chunkOverlap?: number; separators?: string[] }) {
        this.chunkSize = options?.chunkSize || 1000;
        this.chunkOverlap = options?.chunkOverlap || 200;
        this.separators = options?.separators || ["\n\n", "\n", " ", ""];
    }

    splitText(text: string): string[] {
        const finalChunks: string[] = [];
        let goodCuts: string[] = [text];

        // Iteratively try to split by separators
        for (const separator of this.separators) {
            const newCuts: string[] = [];
            for (const chunk of goodCuts) {
                if (chunk.length > this.chunkSize) {
                    newCuts.push(...this.splitBySeparator(chunk, separator));
                } else {
                    newCuts.push(chunk);
                }
            }
            goodCuts = newCuts;
        }

        // Re-merge small chunks if possible (simple logic for now)
        // Ideally, we accumulate until chunkSize is reached
        let currentChunk = "";
        for (const cut of goodCuts) {
            if (currentChunk.length + cut.length + 1 > this.chunkSize) {
                if (currentChunk) finalChunks.push(currentChunk.trim());
                currentChunk = cut;
            } else {
                currentChunk = currentChunk ? currentChunk + " " + cut : cut;
            }
        }
        if (currentChunk) finalChunks.push(currentChunk.trim());

        return finalChunks.filter(c => c.length > 0);
    }

    private splitBySeparator(text: string, separator: string): string[] {
        if (separator === "") {
            return Array.from(text); // Split by char
        }
        return text.split(separator).filter(Boolean);
    }
}

// 导出 Trigram 分词器（中文友好）
export { TrigramTextSplitter } from './trigram-splitter';
