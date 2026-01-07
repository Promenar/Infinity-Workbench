import TcpSocket from 'react-native-tcp-socket';
import { KJUR, hextob64 } from 'jsrsasign';
import { useWorkbenchStore } from '../../store/workbench-store';
import { EventEmitter } from 'events';
import { Buffer } from 'buffer';

import { workbenchRouter } from './WorkbenchRouter';
import { AuthController } from './controllers/AuthController';
import { AgentController } from './controllers/AgentController';
import { ChatController } from './controllers/ChatController';
import { ConfigController } from './controllers/ConfigController';
import { LibraryController } from './controllers/LibraryController';
import { storeSyncService } from './StoreSyncService';

export const workbenchEvents = new EventEmitter();

const WS_PORT = 3001;
const MAGIC_STRING = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

interface WebSocketClient {
    id: string;
    socket: any;
    handshakeComplete: boolean;
    authenticated: boolean;
    buffer: Buffer;
    writeQueue: (() => Promise<void>)[];
    isWriting: boolean;
}

class CommandWebSocketServer {
    private server: any = null;
    private clients: Map<string, WebSocketClient> = new Map();

    // Imports moved to top

    async start() {
        if (this.server) {
            console.log('[WS] Server already running');
            return;
        }

        const startServer = async (retries = 10): Promise<void> => {
            return new Promise((resolve, reject) => {
                if (this.server) {
                    try { this.server.close(); } catch (e) { }
                    this.server = null;
                }

                // Register Routes (Ensure idempotent)
                // ... (Register routes logic is safe to repeat or cleaner to keep outside loop? 
                // Router registry is singleton, so okay.
                // But better to register once outside loop.

                this.server = TcpSocket.createServer((socket) => {
                    const clientId = `${socket.remoteAddress}:${socket.remotePort}`;
                    console.log('[WS] Client connected:', clientId);

                    const client: WebSocketClient = {
                        id: clientId,
                        socket,
                        handshakeComplete: false,
                        authenticated: false,
                        buffer: Buffer.alloc(0),
                        writeQueue: [],
                        isWriting: false,
                    };

                    this.clients.set(clientId, client);
                    this.updateClientCount();

                    socket.on('data', (data: Buffer | string) => {
                        this.handleData(client, typeof data === 'string' ? Buffer.from(data) : data);
                    });

                    socket.on('error', (error: any) => {
                        // Suppress common network errors usually caused by client disconnects
                        const msg = (typeof error === 'string' ? error : error?.message) || '';

                        if (msg.includes('Broken pipe') ||
                            msg.includes('ECONNRESET') ||
                            msg.includes('EPIPE') ||
                            msg.includes('Stream closed') ||
                            msg.includes('Socket closed')) {
                            // Just remove client quietly
                            this.removeClient(clientId);
                            return;
                        }

                        console.error('[WS] Client error:', clientId, error);
                        this.removeClient(clientId);
                    });

                    socket.on('close', () => {
                        console.log('[WS] Client disconnected:', clientId);
                        this.removeClient(clientId);
                    });
                });

                this.server.listen({ port: WS_PORT, host: '0.0.0.0' }, () => {
                    console.log('[WS] WebSocket Server listening on port', WS_PORT);
                    resolve();
                });

                this.server.on('error', (err: any) => {
                    if (err.message && err.message.includes('EADDRINUSE')) {
                        console.warn(`[WS] Port ${WS_PORT} busy, retrying... (${retries} left)`);
                        try { this.server?.close(); } catch (e) { }
                        this.server = null;

                        if (retries > 0) {
                            const waitTime = 500 + (10 - retries) * 200;
                            setTimeout(() => {
                                startServer(retries - 1).then(resolve).catch(reject);
                            }, waitTime);
                        } else {
                            reject(err);
                        }
                    } else {
                        reject(err);
                    }
                });
            });
        };

        // Register Routes (Once)
        workbenchRouter.register('AUTH', AuthController.handleAuth);
        workbenchRouter.register('CMD_GET_AGENTS', AgentController.getAgents);
        workbenchRouter.register('CMD_UPDATE_AGENT', AgentController.updateAgent);
        workbenchRouter.register('CMD_CREATE_AGENT', AgentController.createAgent);
        workbenchRouter.register('CMD_DELETE_AGENT', AgentController.deleteAgent);

        workbenchRouter.register('CMD_GET_SESSIONS', ChatController.getSessions);
        workbenchRouter.register('CMD_GET_HISTORY', ChatController.getSessionHistory);
        workbenchRouter.register('CMD_CREATE_SESSION', ChatController.createSession);
        workbenchRouter.register('CMD_DELETE_SESSION', ChatController.deleteSession);
        workbenchRouter.register('CMD_SEND_MESSAGE', ChatController.sendMessage);
        workbenchRouter.register('CMD_ABORT_GENERATION', ChatController.abortGeneration);
        workbenchRouter.register('CMD_DELETE_MESSAGE', ChatController.deleteMessage);
        workbenchRouter.register('CMD_REGENERATE_MESSAGE', ChatController.regenerateMessage);

        workbenchRouter.register('CMD_GET_CONFIG', ConfigController.getConfig);
        workbenchRouter.register('CMD_UPDATE_CONFIG', ConfigController.updateConfig);

        workbenchRouter.register('CMD_GET_LIBRARY', LibraryController.getLibrary);
        workbenchRouter.register('CMD_UPLOAD_FILE', LibraryController.uploadFile);
        workbenchRouter.register('CMD_DELETE_FILE', LibraryController.deleteFile);
        workbenchRouter.register('CMD_CREATE_FOLDER', LibraryController.createFolder);
        workbenchRouter.register('CMD_DELETE_FOLDER', LibraryController.deleteFolder);
        workbenchRouter.register('CMD_GET_GRAPH', LibraryController.getGraphData);

        try {
            await startServer(10);
            storeSyncService.start();
        } catch (e) {
            console.error('[WS] Failed to start server after retries:', e);
            // Don't crash app
        }
    }

    stop() {
        if (this.server) {
            this.server.close();
            this.server = null;
        }
        this.clients.clear();
        this.updateClientCount();
        workbenchRouter.stop();
        storeSyncService.stop();
    }

    private handleData(client: WebSocketClient, data: Buffer) {
        // console.log(`[WS] Received data from ${client.id}: ${data.length} bytes`);
        client.buffer = Buffer.concat([client.buffer, data]);

        if (!client.handshakeComplete) {
            this.tryHandshake(client);
        } else {
            this.processFrames(client);
        }
    }

    private tryHandshake(client: WebSocketClient) {
        const str = client.buffer.toString();
        if (str.includes('\r\n\r\n')) {
            // Found end of headers
            const lines = str.split('\r\n');
            const keyLine = lines.find(line => line.toLowerCase().startsWith('sec-websocket-key:'));

            if (keyLine) {
                const key = keyLine.split(':')[1].trim();
                const acceptKey = this.computeAcceptKey(key);

                const response = [
                    'HTTP/1.1 101 Switching Protocols',
                    'Upgrade: websocket',
                    'Connection: Upgrade',
                    `Sec-WebSocket-Accept: ${acceptKey}`,
                    '\r\n'
                ].join('\r\n');

                client.socket.write(response);
                client.handshakeComplete = true;

                // Remove headers from buffer
                const headerEndIndex = client.buffer.indexOf('\r\n\r\n') + 4;
                client.buffer = client.buffer.slice(headerEndIndex);

                // If there is remaining data, process it
                if (client.buffer.length > 0) {
                    this.processFrames(client);
                }
            } else {
                // Invalid WS request
                client.socket.end();
                this.removeClient(client.id);
            }
        }
    }

    private computeAcceptKey(key: string): string {
        const rawHash = KJUR.crypto.Util.sha1(key + MAGIC_STRING);
        return hextob64(rawHash);
    }

    private processFrames(client: WebSocketClient) {
        while (client.buffer.length >= 2) {
            const firstByte = client.buffer[0];
            const secondByte = client.buffer[1];

            const opcode = firstByte & 0x0f;
            const masked = (secondByte & 0x80) === 0x80;
            let payloadLength = secondByte & 0x7f;
            let offset = 2;

            if (payloadLength === 126) {
                if (client.buffer.length < 4) return; // Wait for more data
                payloadLength = client.buffer.readUInt16BE(2);
                offset += 2;
            } else if (payloadLength === 127) {
                if (client.buffer.length < 10) return; // Wait for more data
                payloadLength = client.buffer.readUInt32BE(6);
                offset += 8;
            }

            const maskingKeyOffset = offset;
            if (masked) {
                offset += 4;
            }

            if (client.buffer.length < offset + payloadLength) return; // Wait for full payload

            let payload = client.buffer.slice(offset, offset + payloadLength);

            if (masked) {
                const maskKey = client.buffer.slice(maskingKeyOffset, maskingKeyOffset + 4);
                payload = this.unmask(payload, maskKey) as any;
            }

            // Handle Opcode
            switch (opcode) {
                case 0x1: // Text
                    this.handleMessage(client, payload.toString());
                    break;
                case 0x8: // Close
                    client.socket.end();
                    this.removeClient(client.id);
                    return;
                case 0x9: // Ping
                    this.sendFrame(client, payload, 0xA); // Pong
                    break;
            }

            // Advance buffer
            client.buffer = client.buffer.slice(offset + payloadLength);
        }
    }

    private unmask(payload: Buffer, mask: Buffer): Buffer {
        const result = Buffer.alloc(payload.length);
        for (let i = 0; i < payload.length; i++) {
            result[i] = payload[i] ^ mask[i % 4];
        }
        return result as any;
    }

    private async sendFrame(client: WebSocketClient, data: Buffer | string, opcode = 0x2) {
        // Enqueue the write operation to ensure atomicity
        return new Promise<void>((resolve, reject) => {
            const task = async () => {
                try {
                    await this.performSendFrame(client, data, opcode);
                    resolve();
                } catch (e) {
                    reject(e);
                }
            };

            client.writeQueue.push(task);
            if (!client.isWriting) {
                this.processWriteQueue(client);
            }
        });
    }

    private async processWriteQueue(client: WebSocketClient) {
        if (client.isWriting || client.writeQueue.length === 0) return;

        client.isWriting = true;
        while (client.writeQueue.length > 0) {
            const task = client.writeQueue.shift();
            if (task) {
                try {
                    await task();
                } catch (e) {
                    console.error('[WS] Write task failed:', e);
                }
            }
        }
        client.isWriting = false;
    }

    private async performSendFrame(client: WebSocketClient, data: Buffer | string, opcode = 0x2) {
        if (typeof data === 'string') {
            data = Buffer.from(data);
        }

        // Server does NOT mask frames
        let length = data.length;
        let headerSize = 2;
        if (length > 65535) headerSize += 8;
        else if (length > 125) headerSize += 2;

        const header = Buffer.alloc(headerSize);
        header[0] = 0x80 | opcode; // Fin + Opcode

        if (length > 65535) {
            header[1] = 127;
            header.writeUInt32BE(0, 2); // High 32 (0)
            header.writeUInt32BE(length, 6); // Low 32
        } else if (length > 125) {
            header[1] = 126;
            header.writeUInt16BE(length, 2);
        } else {
            header[1] = length;
        }

        const fullPacket = Buffer.concat([header, data]);

        // Chunked Write for reliability
        const CHUNK_SIZE = 1400;
        let offset = 0;

        // Log large packets for debug
        if (fullPacket.length > 5000) {
            console.log(`[WS] Sending large frame: ${fullPacket.length} bytes, op=${opcode}`);
        }

        try {
            while (offset < fullPacket.length) {
                // Safety check
                if (!this.clients.has(client.id)) break;

                const chunk = fullPacket.slice(offset, offset + CHUNK_SIZE);
                // Convert to base64 for safe transport across RN Bridge
                const ok = client.socket.write(chunk.toString('base64'), 'base64');


                if (!ok) {
                    // Wait for drain
                    await new Promise<void>(resolve => {
                        const onDrain = () => {
                            client.socket.removeListener('drain', onDrain);
                            resolve();
                        };
                        client.socket.once('drain', onDrain);

                        // Fallback timeout in case drain never fires
                        setTimeout(() => {
                            client.socket.removeListener('drain', onDrain);
                            resolve();
                        }, 1000);
                    });
                }
                offset += CHUNK_SIZE;
            }
        } catch (e: any) {
            // Suppress write errors for closed sockets
            if (!e.message?.includes('Broken pipe') && !e.message?.includes('EPIPE') && !e.message?.includes('ECONNRESET')) {
                console.warn('[WS] Failed to write to socket:', e);
            }
        }
    }

    private handleMessage(client: WebSocketClient, message: string) {
        try {
            const msg = JSON.parse(message);

            // Enrich client for router but KEEP REFERENCE to original object for state mutations (authenticated)
            const routerContextClient = Object.assign(client, {
                send: (json: any) => this.sendJson(client, json)
            });

            // Pre-Auth Check: Allow only AUTH command if not authenticated
            if (!client.authenticated && msg.type !== 'AUTH') {
                console.warn(`[WS] Blocked unauthenticated command: ${msg.type} (Authenticated=${client.authenticated})`);
                this.sendJson(client, { type: 'AUTH_REQUIRED' });
                return;
            }

            workbenchRouter.handle(msg, {
                client: routerContextClient,
                server: this
            });

        } catch (e) {
            console.error('[WS] Failed to parse message', e);
        }
    }

    public broadcast(json: any) {
        const data = JSON.stringify(json);
        this.clients.forEach(client => {
            if (client.authenticated && client.handshakeComplete) {
                this.sendFrame(client, data);
            }
        });
    }

    private sendJson(client: WebSocketClient, json: any) {
        // Force Opcode 0x2 (Binary) to avoid Browser strict UTF-8 decoding on fragmented packets
        this.sendFrame(client, JSON.stringify(json), 0x2);
    }

    private removeClient(id: string) {
        if (this.clients.has(id)) {
            this.clients.delete(id);
            this.updateClientCount();
        }
    }

    private updateClientCount() {
        useWorkbenchStore.getState().setConnectedClients(this.clients.size);
    }
}

export const commandWebSocketServer = new CommandWebSocketServer();
