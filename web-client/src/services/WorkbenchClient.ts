import { EventEmitter } from 'eventemitter3';

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'authenticated' | 'error';

interface RpcRequest {
    id: string;
    type: string;
    payload?: any;
}

interface RpcResponse {
    id?: string; // Events might not have ID
    type: string;
    payload?: any;
    error?: string;
}

class WorkbenchClient extends EventEmitter {
    private ws: WebSocket | null = null;
    private status: ConnectionStatus = 'disconnected';
    private pendingRequests: Map<string, { resolve: (data: any) => void; reject: (err: any) => void; timer: any }> = new Map();


    constructor() {
        super();
    }

    public connect(url: string, accessCode: string) {
        // Prevent duplicate connection attempts (fixes React.StrictMode double-mount issue)
        if (this.status === 'connected' || this.status === 'authenticated' || this.status === 'connecting') {
            console.log('[WorkbenchClient] Already connected/connecting, ignoring duplicate call');
            return;
        }

        if (this.ws) {
            this.ws.close();
        }


        this.status = 'connecting';
        this.emit('statusKey', this.status);

        // Ensure ws protocol
        const wsUrl = url.replace('http', 'ws').replace(':3000', ':3001'); // Port convention
        console.log('[WorkbenchClient] Connecting to', wsUrl);

        this.ws = new WebSocket(wsUrl);

        this.ws.onopen = () => {
            console.log('[WorkbenchClient] Connected');
            this.status = 'connected';
            this.emit('statusChange', this.status);

            // Auto Authenticate with Token or Access Code
            const savedToken = localStorage.getItem('wb_token');
            if (savedToken) {
                console.log('[WorkbenchClient] Attempting Token Auth');
                this.send('AUTH', { token: savedToken });
            } else {
                // If we have an access code passed in connect (usually from manual entry), use it
                if (accessCode) {
                    this.send('AUTH', { code: accessCode });
                }
            }
        };

        this.ws.onclose = () => {
            console.log('[WorkbenchClient] Disconnected');
            this.status = 'disconnected';
            this.emit('statusChange', this.status);
            this.cleanup();
            // Auto reconnect if not manually closed? For now simple manual reconnect logic in UI
        };

        this.ws.onerror = (err) => {
            console.error('[WorkbenchClient] Error', err);
            this.status = 'error';
            this.emit('statusChange', this.status);
        };

        this.ws.onmessage = async (event) => {
            try {
                let data = event.data;
                if (data instanceof Blob || (data && typeof data.text === 'function')) {
                    data = await data.text();
                }
                const msg = JSON.parse(data);
                this.handleMessage(msg);
            } catch (e) {
                console.error('Failed to parse message', e);
            }
        };
    }

    public disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }

    async updateAgent(agent: any) {
        return this.request('CMD_UPDATE_AGENT', agent);
    }

    async getConfig() {
        return this.request('CMD_GET_CONFIG');
    }

    async updateConfig(config: any) {
        return this.request('CMD_UPDATE_CONFIG', config);
    }

    async getGraph(filters?: { docIds?: string[], sessionId?: string, agentId?: string }) {
        return this.request('CMD_GET_GRAPH', filters || {});
    }

    async getLibrary() {
        return this.request('CMD_GET_LIBRARY');
    }

    async uploadFile(fileData: { title: string; content: string; size: number; type: string; folderId?: string }) {
        return this.request('CMD_UPLOAD_FILE', fileData);
    }

    async deleteFile(id: string) {
        return this.request('CMD_DELETE_FILE', { id });
    }

    async createFolder(name: string, parentId?: string) {
        return this.request('CMD_CREATE_FOLDER', { name, parentId });
    }

    async deleteFolder(id: string) {
        return this.request('CMD_DELETE_FOLDER', { id });
    }

    // Chat Methods
    async sendMessage(sessionId: string, content: string, options?: any) {
        return this.request('CMD_SEND_MESSAGE', { sessionId, content, options });
    }

    async abortGeneration(sessionId: string) {
        return this.request('CMD_ABORT_GENERATION', { sessionId });
    }

    async deleteMessage(sessionId: string, messageId: string) {
        return this.request('CMD_DELETE_MESSAGE', { sessionId, messageId });
    }

    async regenerateMessage(sessionId: string, messageId: string) {
        return this.request('CMD_REGENERATE_MESSAGE', { sessionId, messageId });
    }

    async getHistory(id: string) {
        return this.request('CMD_GET_HISTORY', { id });
    }

    public getStatus() {
        return this.status;
    }



    public send(type: string, payload?: any) {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
        const msg = { type, payload };
        this.ws.send(JSON.stringify(msg));
    }

    public async request(type: string, payload?: any, timeout = 10000): Promise<any> {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) throw new Error('Not connected');

        // crypto.randomUUID() is only available in secure contexts (HTTPS/localhost).
        // Since we are running on local LAN (HTTP), we need a fallback.
        const id = Date.now().toString(36) + Math.random().toString(36).substr(2);
        const msg: RpcRequest = { id, type, payload };

        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                if (this.pendingRequests.has(id)) {
                    this.pendingRequests.delete(id);
                    reject(new Error('Request timeout'));
                }
            }, timeout);

            this.pendingRequests.set(id, { resolve, reject, timer });
            this.ws?.send(JSON.stringify(msg));
        });
    }

    public login(code: string) {
        if (this.status === 'connected') {
            this.send('AUTH', { code });
        } else {
            console.warn('[WorkbenchClient] Cannot login, not connected');
        }
    }

    private handleMessage(msg: RpcResponse) {
        // 1. Check if it's a response to a pending request
        if (msg.id && this.pendingRequests.has(msg.id)) {
            const { resolve, reject, timer } = this.pendingRequests.get(msg.id)!;
            clearTimeout(timer);
            this.pendingRequests.delete(msg.id);

            if (msg.error) {
                reject(new Error(msg.error));
            } else {
                resolve(msg.payload);
            }
            return;
        }

        // 2. Handle System Messages
        if (msg.type === 'AUTH_OK') {
            this.status = 'authenticated';
            // Save Token if provided
            if (msg.payload?.token) {
                localStorage.setItem('wb_token', msg.payload.token);
            }
            this.emit('statusChange', this.status);
        } else if (msg.type === 'AUTH_FAIL') {
            // Auth failed (invalid token or code), but connection is fine
            this.status = 'connected'; // Revert to connected (waiting for auth)
            // Clear invalid token
            localStorage.removeItem('wb_token');
            this.emit('statusChange', this.status);
            this.emit('auth_fail'); // Explicit event for UI to force logout
            // Do NOT emit 'error' to avoid UI thinking connection broke. 
            // The UI will see status != authenticated and show AuthScreen.
        }

        // 3. Emit general events
        this.emit('message', msg);
        this.emit(msg.type, msg.payload);
    }

    private cleanup() {
        this.pendingRequests.forEach(({ reject, timer }) => {
            clearTimeout(timer);
            reject(new Error('Connection closed'));
        });
        this.pendingRequests.clear();
    }
}

export const workbenchClient = new WorkbenchClient();
