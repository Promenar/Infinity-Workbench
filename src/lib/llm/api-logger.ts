import { Logger } from '../logging/Logger';

/**
 * API Debug Logger
 * Captures request and response payloads for debugging.
 * Wraps the unified Logger system.
 */
class ApiLogger {
    private static instance: ApiLogger;
    private logger: Logger;

    private constructor() {
        this.logger = Logger.getInstance();
    }

    static getInstance() {
        if (!ApiLogger.instance) {
            ApiLogger.instance = new ApiLogger();
        }
        return ApiLogger.instance;
    }

    logRequest(provider: string, endpoint: string, body: any) {
        // console.log output is kept for dev debugging if needed, or we rely on Logger's internal dev output
        // Logger.info uses the 'tag' argument. We use 'ApiLogger' or provider name as tag?
        // Let's use 'ApiLogger' as tag, and put provider info in message or metadata.

        this.logger.info('ApiLogger', `REQ [${provider}] -> ${endpoint}`, {
            type: 'request',
            provider,
            endpoint,
            body
        });
    }

    logResponse(provider: string, status: number, body: any) {
        this.logger.info('ApiLogger', `RES [${provider}] <- ${status}`, {
            type: 'response',
            provider,
            status,
            body
        });
    }

    /**
     * @deprecated Use Logger.getInstance().getRecentLogs() instead
     */
    getLogs() {
        // For compatibility, return empty or map recent logs. 
        // Returning empty array to avoid breaking if caller expects specific format not matching generic LogEntry.
        return [];
    }

    clear() {
        // No-op or clear global logger? Better no-op to avoid clearing valuable app logs
    }
}

export const apiLogger = ApiLogger.getInstance();
