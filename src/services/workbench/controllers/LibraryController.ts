import { useRagStore } from '../../../store/rag-store';
import { graphStore } from '../../../lib/rag/graph-store';

export const LibraryController = {
    getLibrary: async () => {
        const store = useRagStore.getState();

        // Ensure data is loaded (this might be heavy if called often, but for now okay)
        // If we want to return current state without force-reloading, we can just return state.
        // But usually web client expects fresh data.
        await Promise.all([store.loadDocuments(), store.loadFolders()]);

        const state = useRagStore.getState();
        return {
            documents: state.documents,
            folders: state.folders,
            stats: state.getVectorStats()
        };
    },

    uploadFile: async (payload: { title: string; content: string; size: number; type: 'text' | 'note' | 'image'; folderId?: string }) => {
        const store = useRagStore.getState();
        const { title, content, size, type, folderId } = payload;

        await store.addDocument(title, content, size, type, folderId);

        // Return updated library to save a round trip? Or just success.
        // Let's return success and let client refresh.
        return { success: true };
    },

    deleteFile: async (payload: { id: string }) => {
        const store = useRagStore.getState();
        await store.deleteDocument(payload.id);
        return { success: true };
    },

    createFolder: async (payload: { name: string; parentId?: string }) => {
        const store = useRagStore.getState();
        await store.addFolder(payload.name, payload.parentId);
        return { success: true };
    },

    deleteFolder: async (payload: { id: string }) => {
        const store = useRagStore.getState();
        await store.deleteFolder(payload.id);
        return { success: true };
    },

    getGraphData: async (payload: { docIds?: string[]; sessionId?: string; agentId?: string }) => {
        return await graphStore.getGraphData(payload.docIds, payload.sessionId, payload.agentId);
    }
};
