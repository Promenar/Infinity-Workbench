import { EventEmitter } from 'eventemitter3';
import { workbenchClient } from './WorkbenchClient';

// Types
export interface Assistant {
    id: string;
    name: string;
    description: string;
    modelId: string;
    systemPrompt: string;
    // ... other fields
}

export interface Session {
    id: string;
    title: string;
    agentId: string;
    updatedAt: number;
    lastMessage?: string;
}

interface StoreState {
    assistants: Assistant[];
    sessions: Session[];
    sessionsByAgent: Record<string, Session[]>;
}

class StoreService extends EventEmitter {
    private state: StoreState = {
        assistants: [],
        sessions: [],
        sessionsByAgent: {}
    };

    private initialized = false;

    constructor() {
        super();
        this.setupListeners();
    }

    private setupListeners() {
        workbenchClient.on('SESSION_LIST_UPDATED', () => this.fetchSessions());
        // Add AGENT_LIST_UPDATED if/when we have it
    }

    public async init() {
        if (this.initialized) return;

        await Promise.all([
            this.fetchAssistants(),
            this.fetchSessions()
        ]);

        this.initialized = true;
        this.emit('initialized');
    }

    public async fetchAssistants() {
        try {
            const assistants = await workbenchClient.request('CMD_GET_AGENTS');
            // Ensure assistants have valid IDs. 
            // The backend returns an object or array? 
            // Let's assume array based on previous code.
            // Wait, previous code in Agents.tsx used CMD_GET_AGENTS.
            this.state.assistants = assistants;
            this.emit('assistants_updated', this.state.assistants);
            this.organizeSessions();
        } catch (e) {
            console.error('[StoreService] Failed to fetch assistants', e);
        }
    }

    public async fetchSessions() {
        try {
            const sessions = await workbenchClient.request('CMD_GET_SESSIONS');
            this.state.sessions = sessions.sort((a: Session, b: Session) => b.updatedAt - a.updatedAt);
            this.organizeSessions();
            this.emit('sessions_updated', this.state.sessions);
            this.emit('tree_updated', this.state.sessionsByAgent);
        } catch (e) {
            console.error('[StoreService] Failed to fetch sessions', e);
        }
    }

    private organizeSessions() {
        const byAgent: Record<string, Session[]> = {};

        // Initialize with known assistants to ensure they appear even if empty
        this.state.assistants.forEach(a => {
            byAgent[a.id] = [];
        });

        // Group sessions
        // Group sessions
        console.log('[StoreService] Organizing sessions...', {
            assistants: this.state.assistants.length,
            sessions: this.state.sessions.length
        });

        this.state.sessions.forEach(s => {
            if (!byAgent[s.agentId]) {
                // If this happens, it means we have a session for an unknown agent
                // OR agentId is undefined/null
                console.warn('[StoreService] Session has unknown agentId:', s.agentId, s.title);
                if (!byAgent['unknown']) byAgent['unknown'] = [];
                byAgent['unknown'].push(s);
            } else {
                byAgent[s.agentId].push(s);
            }
        });

        this.state.sessionsByAgent = byAgent;
    }

    public getAssistants() {
        return this.state.assistants;
    }

    public getSessions() {
        return this.state.sessions;
    }

    public getSessionsByAgent(agentId: string) {
        return this.state.sessionsByAgent[agentId] || [];
    }

    public getTree() {
        return this.state.sessionsByAgent;
    }
}

export const storeService = new StoreService();
