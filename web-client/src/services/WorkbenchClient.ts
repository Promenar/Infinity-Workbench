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

        this.ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
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
            this.status = 'error';
            // Clear invalid token
            localStorage.removeItem('wb_token');
            this.emit('statusChange', this.status);
            this.emit('error', 'Authentication Failed');
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
