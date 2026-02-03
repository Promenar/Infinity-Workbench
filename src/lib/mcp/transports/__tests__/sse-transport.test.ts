import { SseTransport } from '../sse-transport';

// 1. Define Mock Logic
const mockListeners: Record<string, Function[]> = {};
const mockAddEventListener = jest.fn((event, cb) => {
    if (!mockListeners[event]) mockListeners[event] = [];
    mockListeners[event].push(cb);
});
const mockRemoveEventListener = jest.fn();
const mockClose = jest.fn();

// Helper to simulate server events
const emitEvent = (event: string, data: any) => {
    if (mockListeners[event]) {
        mockListeners[event].forEach(cb => cb(data));
    }
};

// 2. Mock 'react-native-sse'
jest.mock('react-native-sse', () => {
    return {
        __esModule: true,
        default: jest.fn().mockImplementation(() => {
            return {
                addEventListener: mockAddEventListener,
                removeEventListener: mockRemoveEventListener,
                close: mockClose,
            };
        })
    };
});

describe('SseTransport', () => {
    let transport: SseTransport;
    const baseUrl = 'http://test-server.com/sse';

    beforeEach(() => {
        jest.clearAllMocks();
        // Clear listeners between tests
        for (const key in mockListeners) delete mockListeners[key];

        (global.fetch as jest.Mock) = jest.fn();
        transport = new SseTransport(baseUrl);
    });

    it('should connect and handle endpoint event', async () => {
        const connectPromise = transport.connect();

        // Emit open
        emitEvent('open', { type: 'open' });

        // Emit endpoint
        emitEvent('endpoint', { data: '/api/messages' });

        await connectPromise;

        expect(mockAddEventListener).toHaveBeenCalledWith('endpoint', expect.any(Function));
    });

    it('should send JSON-RPC request and handle response via SSE correlation', async () => {
        const connectPromise = transport.connect();
        emitEvent('open', {});
        emitEvent('endpoint', { data: '/api/messages' });
        await connectPromise;

        // Mock Fetch (202 Accepted)
        (global.fetch as jest.Mock).mockResolvedValueOnce({
            ok: true,
            status: 202,
            text: () => Promise.resolve('Accepted')
        });

        const toolName = 'test_tool';
        const toolArgs = { foo: 'bar' };

        const callPromise = transport.callTool(toolName, toolArgs);

        // Verify Fetch Call
        expect(global.fetch).toHaveBeenCalledWith(
            'http://test-server.com/api/messages',
            expect.objectContaining({
                method: 'POST',
                body: expect.stringContaining(toolName)
            })
        );

        // Get Request ID
        const fetchCall = (global.fetch as jest.Mock).mock.calls[0];
        const requestBody = JSON.parse(fetchCall[1].body);
        const requestId = requestBody.id;

        // Emit Response
        const responseData = {
            jsonrpc: '2.0',
            id: requestId,
            result: { success: true }
        };

        // Verify async response handling
        setTimeout(() => {
            emitEvent('message', { type: 'message', data: JSON.stringify(responseData) });
        }, 0);

        const result = await callPromise;
        expect(result).toEqual({ success: true });
    });

    it('should handle JSON-RPC errors from server', async () => {
        const connectPromise = transport.connect();
        emitEvent('endpoint', { data: '/api/messages' });
        await connectPromise;

        (global.fetch as jest.Mock).mockResolvedValueOnce({ ok: true });

        const callPromise = transport.callTool('error_tool', {});

        const fetchCall = (global.fetch as jest.Mock).mock.calls[0];
        const requestBody = JSON.parse(fetchCall[1].body);

        const errorResponse = {
            jsonrpc: '2.0',
            id: requestBody.id,
            error: {
                code: -32600,
                message: 'Invalid Request'
            }
        };

        // Emit error async
        setTimeout(() => {
            emitEvent('message', { type: 'message', data: JSON.stringify(errorResponse) });
        }, 0);

        await expect(callPromise).rejects.toThrow('Invalid Request');
    });

    it('should reject pending requests on disconnect', async () => {
        const connectPromise = transport.connect();
        emitEvent('endpoint', { data: '/api/messages' });
        await connectPromise;

        (global.fetch as jest.Mock).mockResolvedValueOnce({ ok: true });

        const callPromise = transport.callTool('hang_tool', {});

        await transport.disconnect();

        await expect(callPromise).rejects.toThrow('Transport disconnected');
        expect(mockClose).toHaveBeenCalled();
    });
});
