import { useAgentStore } from '../../../store/agent-store';
import { RouterContext } from '../WorkbenchRouter';

export const AgentController = {
    async getAgents(payload: any, context: RouterContext) {
        const { agents } = useAgentStore.getState();
        return agents;
    },

    async updateAgent(payload: any, context: RouterContext) {
        const { id, updates } = payload;
        const { updateAgent, getAgent } = useAgentStore.getState();

        if (!id) throw new Error('Agent ID required');

        // Security/Validation check could go here

        updateAgent(id, updates);

        // Return updated agent
        const updated = getAgent(id);

        // Optional: Broadcast update to other clients?
        // context.server.broadcast({ type: 'AGENT_UPDATED', payload: updated });

        return updated;
    },

    async createAgent(payload: any, context: RouterContext) {
        const { addAgent } = useAgentStore.getState();
        const newAgent = { ...payload, created: Date.now() };
        // Basic validation
        if (!newAgent.id || !newAgent.name) throw new Error('Invalid agent data');

        addAgent(newAgent);
        return newAgent;
    },

    async deleteAgent(payload: any, context: RouterContext) {
        const { id } = payload;
        const { deleteAgent } = useAgentStore.getState();
        if (!id) throw new Error('ID required');

        deleteAgent(id);
        return { success: true, id };
    }
};
