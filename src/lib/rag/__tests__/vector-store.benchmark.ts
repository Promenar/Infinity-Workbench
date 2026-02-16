import { vectorStore } from '../vector-store';

/**
 * Benchmark for Vector Search performance.
 * 
 * Usage:
 * Import this function in your App.tsx or a test screen inside the React Native environment.
 * Do not run with `ts-node` unless `op-sqlite` and `react-native-worklets-core` are mocked.
 */
export async function runVectorStoreBenchmark() {
    console.log('🚀 Starting Vector Store Benchmark (Phase 1 Optimization)...');

    // Configuration
    const sizes = [100, 500, 1000];
    const DIMENSION = 1536; // OpenAI embedding size
    const LOOPS = 5; // Average over 5 runs per size

    // Helper to generate random embedding
    const randomEmbedding = () => {
        const vec = new Array(DIMENSION);
        for (let i = 0; i < DIMENSION; i++) vec[i] = Math.random();
        return vec;
    };

    try {
        for (const size of sizes) {
            console.log(`\n📊 Benchmarking size: ${size} vectors`);

            // 1. Clear Data
            console.log('  Cleaning DB...');
            await vectorStore.clearAllVectors();

            // 2. Insert Data
            console.log('  Generating vectors...');
            const vectors = [];
            for (let i = 0; i < size; i++) {
                vectors.push({
                    content: `Benchmark content ${i}`,
                    embedding: randomEmbedding(),
                    metadata: { type: 'benchmark', index: i }
                });
            }

            console.log('  Inserting vectors...');
            const startInsert = Date.now();
            await vectorStore.addVectors(vectors);
            const insertTime = Date.now() - startInsert;
            console.log(`  Insert time: ${insertTime}ms (${(insertTime / size).toFixed(2)}ms/vec)`);

            // 3. Search Benchmark
            console.log(`  Running search benchmark (${LOOPS} iterations)...`);
            let totalTime = 0;

            for (let i = 0; i < LOOPS; i++) {
                const query = randomEmbedding();
                const start = Date.now();
                await vectorStore.search(query, { limit: 10 });
                const end = Date.now();
                totalTime += (end - start);
            }

            const avgTime = totalTime / LOOPS;
            console.log(`  Average Search Time: ${avgTime.toFixed(2)}ms`);

            if (avgTime > 100) {
                console.warn('  ⚠️ Performance Warning: Search took > 100ms');
            } else {
                console.log('  ✅ Performance OK');
            }
        }

        console.log('\n✅ Benchmark Complete. Verify UI responsiveness during "Search Benchmark".');

    } catch (e) {
        console.error('❌ Benchmark Failed:', e);
    }
}
