export interface DocumentChunk {
    id: string;
    content: string;
    metadata?: any;
}

export const processDocument = async (content: string): Promise<DocumentChunk[]> => {
    // Simple text splitter by paragraph
    const paragraphs = content.split('\n\n').filter(p => p.trim().length > 0);

    return paragraphs.map((p, index) => ({
        id: `chunk-${Date.now()}-${index}`,
        content: p.trim(),
        metadata: { source: 'user-upload' }
    }));
};

export const retrieveContext = async (query: string, chunks: DocumentChunk[]): Promise<DocumentChunk[]> => {
    // Mock semantic search: just find chunks with matching keywords
    const keywords = query.toLowerCase().split(' ').filter(w => w.length > 3);

    if (keywords.length === 0) return [];

    return chunks.filter(chunk => {
        const text = chunk.content.toLowerCase();
        return keywords.some(k => text.includes(k));
    }).slice(0, 3); // Top 3
};
