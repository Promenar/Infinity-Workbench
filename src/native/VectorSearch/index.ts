import { NativeModules, Platform } from 'react-native';

export interface VectorSearchResult {
  id: string;
  similarity: number;
}

export interface VectorSearchCandidate {
  id: string;
  embedding: Float32Array;
}

const { VectorSearch } = NativeModules;

export async function searchVectors(
  queryEmbedding: Float32Array,
  candidates: VectorSearchCandidate[],
  threshold: number = 0.7,
  limit: number = 5
): Promise<VectorSearchResult[]> {
  if (!VectorSearch) {
    throw new Error('VectorSearch native module not available');
  }

  if (candidates.length === 0) {
    return [];
  }

  const candidateEmbeddings = candidates.map(c => Array.from(c.embedding));
  const candidateIds = candidates.map(c => c.id);

  try {
    const results = await VectorSearch.search(
      Array.from(queryEmbedding),
      candidateEmbeddings,
      candidateIds,
      threshold,
      limit
    );

    return results.map((r: any) => ({
      id: r.id,
      similarity: r.similarity,
    }));
  } catch (e) {
    throw new Error(`VectorSearch native error: ${e}`);
  }
}

export function isNativeModuleAvailable(): boolean {
  return VectorSearch !== null && VectorSearch !== undefined;
}
