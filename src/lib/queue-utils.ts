/**
 * Utility for processing large batches of items without freezing the UI.
 * Uses small delays to yield control back to the JS event loop.
 */
export async function processBatchWithProgress<T>(
    items: T[],
    processor: (item: T) => Promise<void>,
    onProgress?: (completed: number, total: number) => void,
    batchSize: number = 5,
    delayMs: number = 10
): Promise<{ success: number; failed: number; errors: Error[] }> {
    let completed = 0;
    let failed = 0;
    const errors: Error[] = [];
    const total = items.length;

    // Helper to process a chunk
    const processChunk = async (chunk: T[]) => {
        const promises = chunk.map(async (item) => {
            try {
                await processor(item);
                completed++;
            } catch (e) {
                failed++;
                errors.push(e as Error);
                console.error('Batch processing error:', e);
            }
        });
        await Promise.all(promises);
    };

    // Split into batches
    for (let i = 0; i < total; i += batchSize) {
        const chunk = items.slice(i, i + batchSize);
        await processChunk(chunk);

        if (onProgress) {
            onProgress(completed + failed, total); // Update progress
        }

        // Yield to UI thread
        if (i + batchSize < total) {
            await new Promise(resolve => setTimeout(resolve, delayMs));
        }
    }

    return { success: completed, failed, errors };
}
