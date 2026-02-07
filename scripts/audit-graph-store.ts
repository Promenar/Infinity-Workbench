
import { graphStore } from '../src/lib/rag/graph-store';
import { db, initDb } from '../src/lib/db';
import { generateId } from '../src/lib/utils/id-generator';

const runAudit = async () => {
    try {
        console.log('--- Initializing DB ---');
        await initDb();

        const testNodeName = `TestNode_${Date.now()}`;
        console.log(`--- Testing Node Creation: ${testNodeName} ---`);

        const nodeId = await graphStore.upsertNode(testNodeName, 'test_type');
        console.log(`Node Created/Upserted with ID: ${nodeId}`);

        const nodes = await graphStore.getAllNodes();
        const createdNode = nodes.find(n => n.id === nodeId);

        if (!createdNode) {
            console.error('❌ Failed to retrieve created node');
        } else {
            console.log('✅ Node retrieved successfully:', createdNode);
        }

        console.log('--- Testing Node Update ---');
        const newName = `${testNodeName}_updated`;
        await graphStore.updateNode(nodeId, { name: newName, type: 'updated_type' });

        const updatedNodes = await graphStore.getAllNodes();
        const updatedNode = updatedNodes.find(n => n.id === nodeId);

        if (updatedNode?.name === newName && updatedNode?.type === 'updated_type') {
            console.log('✅ Node updated successfully:', updatedNode);
        } else {
            console.error('❌ Node update failed. Expected:', { name: newName, type: 'updated_type' }, 'Got:', updatedNode);
        }

        console.log('--- Testing Unique Constraint on Update ---');
        // Create another node
        const conflictNodeName = `ConflictNode_${Date.now()}`;
        const conflictId = await graphStore.upsertNode(conflictNodeName, 'test');

        try {
            await graphStore.updateNode(nodeId, { name: conflictNodeName });
            console.error('❌ Unique constraint check FAILED. Should have thrown error.');
        } catch (e) {
            console.log('✅ Unique constraint check PASSED. Caught expected error:', e);
        }

        console.log('--- Testing Edge Creation ---');
        const edgeId = await graphStore.createEdge(nodeId, conflictId, 'test_relation');
        console.log(`Edge Created with ID: ${edgeId}`);

        const edges = await graphStore.getEdgesForNode(nodeId);
        const myEdge = edges.find(e => e.id === edgeId);
        if (myEdge) {
            console.log('✅ Edge retrieved successfully:', myEdge);
        } else {
            console.error('❌ Edge retrieval failed');
        }

        console.log('--- Testing Edge Update ---');
        await graphStore.updateEdge(edgeId, { relation: 'updated_relation' });
        const updatedEdges = await graphStore.getEdgesForNode(nodeId);
        const updatedEdge = updatedEdges.find(e => e.id === edgeId);

        if (updatedEdge?.relation === 'updated_relation') {
            console.log('✅ Edge updated successfully');
        } else {
            console.error('❌ Edge update failed');
        }

        console.log('--- Cleanup ---');
        await graphStore.deleteNode(nodeId);
        await graphStore.deleteNode(conflictId);
        console.log('✅ Cleanup finished');

    } catch (e) {
        console.error('🚨 Audit Failed:', e);
    }
};

runAudit();
