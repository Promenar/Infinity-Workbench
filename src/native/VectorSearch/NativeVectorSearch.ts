import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  search(
    queryEmbedding: Float32Array,
    candidateEmbeddings: Float32Array[],
    candidateIds: string[],
    threshold: number,
    limit: number
  ): Promise<ReadonlyArray<{
    id: string;
    similarity: number;
  }>>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('VectorSearch');
