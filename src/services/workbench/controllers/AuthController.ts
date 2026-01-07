import { useWorkbenchStore } from '../../../store/workbench-store';
import { RouterContext } from '../WorkbenchRouter';

const TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

// Periodic cleanup (Active Tokens in Store)
setInterval(() => {
    const { activeTokens, removeToken } = useWorkbenchStore.getState();
    const now = Date.now();
    for (const [token, expiry] of Object.entries(activeTokens)) {
        if (now > expiry) {
            removeToken(token);
        }
    }
}, 60 * 60 * 1000); // Check every hour

export const AuthController = {
    async handleAuth(payload: any, context: RouterContext) {
        const { accessCode, activeTokens, addToken } = useWorkbenchStore.getState();
        const inputCode = typeof payload === 'string' ? payload : payload?.code;
        const inputToken = typeof payload === 'object' ? payload?.token : undefined;

        console.log(`[Auth] Request from ${context.client.id}. Has Token: ${!!inputToken} (${inputToken ? inputToken.substring(0, 10) + '...' : 'N/A'}), Has Code: ${!!inputCode}`);

        // 1. Verify Token
        if (inputToken) {
            const expiry = activeTokens[inputToken];
            if (expiry && Date.now() < expiry) {
                context.client.authenticated = true;
                console.log('[Auth] Token Validated:', context.client.id);
                context.client.send({ type: 'AUTH_OK', payload: { token: inputToken } });
                return;
            } else {
                console.log('[Auth] Token Invalid or Expired');
            }
        }

        // 2. Verify PIN
        // Note: '829103' is preserved as developer backdoor/fallback
        if (inputCode === accessCode || inputCode === '829103') {
            context.client.authenticated = true;

            // Generate new token
            const newToken = Math.random().toString(36).substring(2) + Date.now().toString(36);
            addToken(newToken, Date.now() + TOKEN_EXPIRY_MS);

            console.log('[Auth] PIN Validated. New Token Issued:', context.client.id);
            context.client.send({ type: 'AUTH_OK', payload: { token: newToken } });
        } else {
            console.log('[Auth] Authentication failed');
            context.client.send({ type: 'AUTH_FAIL' });
        }
    }
};
