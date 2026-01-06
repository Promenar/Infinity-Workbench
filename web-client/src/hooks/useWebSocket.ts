import { useState, useRef, useCallback } from 'react';

type ConnectionStatus = 'idle' | 'connecting' | 'connected' | 'authenticated' | 'disconnected' | 'auth_failed';

export interface ChatMessage {
    id: string;
    role: 'user' | 'assistant';
    content: string;
}

export function useWebSocket() {
    const [status, setStatus] = useState<ConnectionStatus>('idle');
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const wsRef = useRef<WebSocket | null>(null);

    const connect = useCallback((code: string) => {
        // Determine WS URL relative to current page host
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const host = window.location.hostname;
        // Port 3001 as defined in server
        // Note: If served from port 3000, we need to correct port to 3001
        const port = 3001;
        const url = `${protocol}//${host}:${port}`;

        console.log('Connecting to', url);
        setStatus('connecting');

        const ws = new WebSocket(url);
        wsRef.current = ws;

        ws.onopen = () => {
            console.log('WS Connected');
            setStatus('connected');
            // Send Auth immediately
            ws.send(JSON.stringify({ type: 'AUTH', payload: code }));
        };

        ws.onclose = () => {
            console.log('WS Disconnected');
            setStatus('disconnected');
        };

        ws.onerror = (e) => {
            console.error('WS Error', e);
            setStatus('disconnected');
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                console.log('WS Message', msg);

                switch (msg.type) {
                    case 'AUTH_OK':
                        setStatus('authenticated');
                        // Save access code to session storage
                        sessionStorage.setItem('wb_access_code', code);
                        break;
                    case 'AUTH_FAIL':
                        setStatus('auth_failed');
                        sessionStorage.removeItem('wb_access_code');
                        ws.close();
                        break;
                    case 'CHAT_RESPONSE': // For Echo test
                    case 'TOKEN': // For Streaming
                        const content = msg.payload;
                        // Simple assumption: If last message is assistant, append. Else create new.
                        setMessages(prev => {
                            const last = prev[prev.length - 1];
                            if (last && last.role === 'assistant') {
                                // If type is Token, append. If CHAT_RESPONSE, maybe replace?
                                // For echo test, simple append.
                                return [
                                    ...prev.slice(0, -1),
                                    { ...last, content: last.content + content }
                                ];
                            } else {
                                return [
                                    ...prev,
                                    { id: Date.now().toString(), role: 'assistant', content }
                                ];
                            }
                        });
                        break;
                    default:
                        break;
                }
            } catch (e) {
                console.error("Parse Error", e);
            }
        };
    }, []);

    const sendMessage = useCallback((text: string) => {
        if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
            // Add user message locally immediately
            setMessages(prev => [...prev, { id: Date.now().toString(), role: 'user', content: text }]);

            wsRef.current.send(JSON.stringify({ type: 'CMD_CHAT', payload: text }));
            // Add placeholder for assistant?
            setMessages(prev => [...prev, { id: (Date.now() + 1).toString(), role: 'assistant', content: '' }]);
        }
    }, []);

    const disconnect = useCallback(() => {
        if (wsRef.current) {
            wsRef.current.close();
            wsRef.current = null;
        }
        setStatus('idle');
    }, []);

    return { status, connect, disconnect, sendMessage, messages };
}
