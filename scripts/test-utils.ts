import fs from 'fs';
import path from 'path';

// Load secure_env/test_api.json
export function loadTestConfig() {
    const configPath = path.resolve(__dirname, '../secure_env/test_api.json');
    if (!fs.existsSync(configPath)) {
        throw new Error(`❌ Missing config file: ${configPath}\nPlease create it based on templates in secure_env/test_api.json`);
    }
    return JSON.parse(fs.readFileSync(configPath, 'utf8'));
}

// Polyfill environment for OpenAiClient
export function setupEnvironment() {
    // Polyfill XMLHttpRequest
    if (typeof (global as any).XMLHttpRequest === 'undefined') {
        (global as any).XMLHttpRequest = require('xhr2');
    }
}

// Helper to determine active provider
export function getActiveProvider(config: any): { provider: string; config: any } {
    const args = process.argv.slice(2);
    const providerArg = args.find(arg => !arg.startsWith('-'));

    // Priority: CLI Arg > Zhipu > Vertex > Ollama
    let provider = providerArg;

    if (!provider) {
        if (config['zhipu-ai'] && config['zhipu-ai'].apiKey !== 'YOUR_API_KEY') {
            provider = 'zhipu-ai';
        } else if (config['vertex-ai'] && (config['vertex-ai'].keyFile || config['vertex-ai'].apiKey.includes('{') === false)) {
            provider = 'vertex-ai';
        } else if (config['ollama']) {
            provider = 'ollama';
        } else {
            throw new Error('❌ No valid provider found in test_api.json. Please configure at least one.');
        }
    }

    const providerConfig = config[provider];
    if (!providerConfig) {
        throw new Error(`❌ Provider '${provider}' not found in configuration.`);
    }

    return { provider, config: providerConfig };
}
