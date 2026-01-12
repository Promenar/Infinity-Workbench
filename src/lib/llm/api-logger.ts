
/**
 * API Debug Logger
 * Captures request and response payloads for debugging multi-model issues.
 */
class ApiLogger {
    private static instance: ApiLogger;
    private logs: any[] = [];
    private maxLogs = 50;

    private constructor() { }

    static getInstance() {
        if (!ApiLogger.instance) {
            ApiLogger.instance = new ApiLogger();
        }
        return ApiLogger.instance;
    }

    logRequest(provider: string, endpoint: string, body: any) {
        const entry = {
            timestamp: new Date().toISOString(),
            type: 'request',
            provider,
            endpoint,
            body: JSON.parse(JSON.stringify(body)) // Deep copy
        };
        this.addEntry(entry);
        console.log(`[API_DEBUG][REQ][${provider}] -> ${endpoint}`);
        console.log(`[API_DEBUG][REQ_BODY] ${JSON.stringify(body).substring(0, 500)}...`);
    }

    logResponse(provider: string, status: number, body: any) {
        const entry = {
            timestamp: new Date().toISOString(),
            type: 'response',
            provider,
            status,
            body: typeof body === 'string' ? body.substring(0, 1000) : JSON.parse(JSON.stringify(body))
        };
        this.addEntry(entry);
        console.log(`[API_DEBUG][RES][${provider}] <- ${status}`);
        const resText = typeof body === 'string' ? body : JSON.stringify(body);
        console.log(`[API_DEBUG][RES_BODY] ${resText.substring(0, 500)}...`);
    }

    private addEntry(entry: any) {
        this.logs.unshift(entry);
        if (this.logs.length > this.maxLogs) {
            this.logs.pop();
        }
    }

    getLogs() {
        return this.logs;
    }

    clear() {
        this.logs = [];
    }
}

export const apiLogger = ApiLogger.getInstance();
