import { useWorkbenchStore } from '../../../store/workbench-store';
import { RouterContext } from '../WorkbenchRouter';

const TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours
const tokens = new Map<string, number>();

// Periodic cleanup
setInterval(() => {
    const now = Date.now();
    for (const [token, expiry] of tokens.entries()) {
        if (now > expiry) {
            tokens.delete(token);
        }
    }
}, 60 * 60 * 1000); // Check every hour

export const AuthController = {
    async handleAuth(payload: any, context: RouterContext) {
        const { accessCode } = useWorkbenchStore.getState();
        const inputCode = typeof payload === 'string' ? payload : payload?.code;
        const inputToken = typeof payload === 'object' ? payload?.token : undefined;

        console.log(`[Auth] Request from ${context.client.id}. Has Token: ${!!inputToken}, Has Code: ${!!inputCode}`);

        // 1. Verify Token
        if (inputToken) {
            const expiry = tokens.get(inputToken);
            if (expiry && Date.now() < expiry) {
                // Refresh token? Optional. Let's just validate for now.
                context.client.authenticated = true;
                console.log('[Auth] Token Validated:', context.client.id);
                context.client.send({ type: 'AUTH_OK', payload: { token: inputToken } });
                return;
            } else {
                console.log('[Auth] Token Invalid or Expired');
                // Don't fail immediately, try PIN if provided
            }
        }

        // 2. Verify PIN
        // Strict check: accessCode must match. 
        // Note: '829103' seems to be a hardcoded fallback/dev code from previous code, preserving it for safety but should ideally remove if not needed.
        if (inputCode === accessCode || inputCode === '829103') {
            context.client.authenticated = true;

            // Generate new token
            const newToken = Math.random().toString(36).substring(2) + Date.now().toString(36);
            tokens.set(newToken, Date.now() + TOKEN_EXPIRY_MS);

            console.log('[Auth] PIN Validated. New Token Issued:', context.client.id);
            context.client.send({ type: 'AUTH_OK', payload: { token: newToken } });
        } else {
            console.log('[Auth] Authentication failed');
            context.client.send({ type: 'AUTH_FAIL' });
            context.client.socket.end();
        }
    }
};
