export interface WebSocketClient {
    id: string;
    socket: any;
    handshakeComplete: boolean;
    authenticated: boolean;
    buffer: any;
    send: (json: any) => void;
}

export interface RouterContext {
    client: WebSocketClient;
    server: any; // CommandWebSocketServer instance
}

type CommandHandler = (payload: any, context: RouterContext) => Promise<any>;
type RouterHandler = CommandHandler; // Assuming RouterHandler is an alias for CommandHandler or compatible

export class WorkbenchRouter {
    private handlers: Map<string, CommandHandler> = new Map();

    register(type: string, handler: RouterHandler) {
        if (!handler) {
            console.error(`[Router] FAILED to register handler for ${type}: handler is undefined!`);
            return;
        }
        console.log(`[Router] Registered handler for ${type}`);
        this.handlers.set(type, handler);
    }

    async stop() {
        this.handlers.clear();
    }

    async handle(message: any, context: RouterContext) {
        const { id, type, payload } = message;

        if (!type) return;

        // Special handling for AUTH which is interceptor-like in the original code,
        // but can be a command here if we ensure unauthenticated clients can only call AUTH.

        const handler = this.handlers.get(type);
        if (handler) {
            try {
                const result = await handler(payload, context);

                // If ID is present, it's a request-response
                if (id) {
                    context.client.send({
                        id,
                        type: `${type}_RESPONSE`,
                        payload: result
                    });
                }
            } catch (error: any) {
                console.error(`[Router] Error handling ${type}`, error);
                if (id) {
                    context.client.send({
                        id,
                        type: `${type}_ERROR`,
                        error: error.message || 'Internal Server Error'
                    });
                }
            }
        } else {
            console.warn(`[Router] No handler for type: ${type}`);
            if (id) {
                context.client.send({ id, type: 'ERROR', error: `Unknown command: ${type}` });
            }
        }
    }
}

export const workbenchRouter = new WorkbenchRouter();
