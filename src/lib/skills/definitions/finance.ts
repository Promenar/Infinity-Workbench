import { z } from 'zod';
import { Skill, SkillResult } from '../../../types/skills';
import { useSettingsStore } from '../../../store/settings-store';

const API_BASE_URL = 'https://www.alphavantage.co/query';

export const QueryFinancialDataSkill: Skill = {
    id: 'query_financial_data',
    name: 'Query Financial Data',
    description: 'Query real-time stock prices (US), forex exchange rates, and precious metals data using the Alpha Vantage API.',
    schema: z.object({
        asset_type: z.enum(['stock', 'forex', 'crypto']).describe('The type of asset to query. Use "stock" for US equities, "forex" for currency pairs, "crypto" for digital currencies.'),
        symbol: z.string().describe('The symbol/ticker to query (e.g., "IBM", "AAPL", "EUR/USD", "BTC"). For Forex, use "FROM/TO" format like "EUR/USD".'),
        interval: z.enum(['1min', '5min', '15min', '30min', '60min', 'daily']).optional().describe('Time interval for intraday/daily data (optional, defaults to "daily" quote).')
    }),
    execute: async (params, context) => {
        const { asset_type, symbol, interval } = params;
        const apiKey = useSettingsStore.getState().alphaVantageApiKey;

        if (!apiKey) {
            return {
                id: context.sessionId || 'unknown',
                status: 'error',
                content: `Error: Alpha Vantage API Key is missing. Please configure it in Settings -> Agent Skills -> Query Financial Data.`
            };
        }

        try {
            let functionName = '';
            let queryParams = `apikey=${apiKey}`;

            if (asset_type === 'stock') {
                functionName = 'GLOBAL_QUOTE';
                queryParams += `&symbol=${symbol}`;
            } else if (asset_type === 'forex') {
                functionName = 'CURRENCY_EXCHANGE_RATE';
                const [from, to] = symbol.includes('/') ? symbol.split('/') : [symbol, 'USD'];
                queryParams += `&from_currency=${from}&to_currency=${to || 'USD'}`;
            } else if (asset_type === 'crypto') {
                functionName = 'CURRENCY_EXCHANGE_RATE';
                const [from, to] = symbol.includes('/') ? symbol.split('/') : [symbol, 'USD'];
                queryParams += `&from_currency=${from}&to_currency=${to || 'USD'}`;
            }

            const url = `${API_BASE_URL}?function=${functionName}&${queryParams}`;
            console.log(`[FinanceSkill] Fetching: ${functionName} for ${symbol}`);

            const response = await fetch(url);
            const data = await response.json();

            // Error Handling
            if (data['Error Message']) {
                return {
                    id: context.sessionId || 'unknown',
                    status: 'error',
                    content: `API Error: ${data['Error Message']}`
                };
            }
            if (data['Note']) {
                return {
                    id: context.sessionId || 'unknown',
                    status: 'error',
                    content: `API Note/Limit: ${data['Note']}`
                };
            }

            let resultString = JSON.stringify(data, null, 2);

            // Formatting
            if (functionName === 'GLOBAL_QUOTE' && data['Global Quote']) {
                const q = data['Global Quote'];
                const formatted = {
                    symbol: q['01. symbol'],
                    price: q['05. price'],
                    change_percent: q['10. change percent'],
                    latest_trading_day: q['07. latest trading day'],
                    source: 'Alpha Vantage'
                };
                resultString = JSON.stringify(formatted, null, 2);
            } else if (functionName === 'CURRENCY_EXCHANGE_RATE' && data['Realtime Currency Exchange Rate']) {
                const r = data['Realtime Currency Exchange Rate'];
                const formatted = {
                    from_code: r['1. From_Currency Code'],
                    from_name: r['2. From_Currency Name'],
                    to_code: r['3. To_Currency Code'],
                    to_name: r['4. To_Currency Name'],
                    exchange_rate: r['5. Exchange Rate'],
                    last_refresh: r['6. Last Refreshed'],
                    time_zone: r['7. Time Zone'],
                    source: 'Alpha Vantage'
                };
                resultString = JSON.stringify(formatted, null, 2);
            }

            return {
                id: context.sessionId || 'unknown',
                status: 'success',
                content: resultString,
                data: data // Return raw data as well for potential UI
            };

        } catch (error: any) {
            return {
                id: context.sessionId || 'unknown',
                status: 'error',
                content: `Network/System Error: ${error.message}`
            };
        }
    }
};
