export async function readAsStringAsync(uri: string, options?: any): Promise<string> {
    const fs = require('fs');
    // Simple mock: if uri is a file path, read it.
    if (fs.existsSync(uri)) {
        return fs.readFileSync(uri, 'base64');
    }
    return '';
}
export const documentDirectory = '/tmp/';
export const cacheDirectory = '/tmp/';
