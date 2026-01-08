// import StaticServer from '@dr.pogodin/react-native-static-server';
import TcpSocket from 'react-native-tcp-socket';
import * as FileSystem from 'expo-file-system/legacy';
import { Asset } from 'expo-asset';
import { NetworkInfo } from 'react-native-network-info';
import { useWorkbenchStore } from '../../store/workbench-store';
import { Buffer } from 'buffer';
import { backgroundService } from '../BackgroundService';

// Require the web assets to bundle them (using .bundle extension to avoid Metro trying to bundle them)
const WEB_ASSETS = {
    indexHtml: require('../../../web-client/dist/index.html'),
    indexJs: require('../../../web-client/dist/assets/index.js.bundle'),
    indexCss: require('../../../web-client/dist/assets/index.css.bundle'),
    viteSvg: require('../../../web-client/dist/vite.svg'),
};

const SERVER_PORT = 3000;
const WWW_DIR = `${FileSystem.documentDirectory}www`;

class StaticServerService {
    private server: any = null;

    async start(): Promise<string> {
        const { setServerStatus, setServerUrl } = useWorkbenchStore.getState();

        try {
            setServerStatus('starting');
            console.log('[Workbench] Preparing assets...');
            await this.prepareAssets();

            await this.stop();

            console.log('[Workbench] Getting IP...');
            const ip = await NetworkInfo.getIPV4Address();
            if (!ip) throw new Error('Could not get Local IP');

            console.log('[Workbench] Starting server (TCP-HTTP)...');

            const startServer = async (retries = 10): Promise<void> => {
                return new Promise((resolve, reject) => {
                    // Force cleanup previous instance just in case
                    if (this.server) {
                        try { this.server.close(); } catch (e) { }
                        this.server = null;
                    }

                    this.server = TcpSocket.createServer((socket) => {
                        socket.on('data', async (data) => {
                            try {
                                const reqStr = data.toString();
                                const firstLine = reqStr.split('\r\n')[0];
                                const parts = firstLine.split(' ');
                                const method = parts[0];
                                const url = parts[1];

                                console.log(`[HTTP] ${method} ${url}`);

                                if (method !== 'GET') {
                                    socket.write('HTTP/1.1 405 Method Not Allowed\r\n\r\n');
                                    socket.end();
                                    return;
                                }

                                let filePath = url.split('?')[0];
                                if (filePath === '/') filePath = '/index.html';

                                if (filePath.includes('..')) {
                                    socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
                                    socket.end();
                                    return;
                                }

                                const absPath = `${WWW_DIR}${filePath}`;
                                const fileInfo = await FileSystem.getInfoAsync(absPath);

                                if (!fileInfo.exists || fileInfo.isDirectory) {
                                    // SPA Fallback: If not found and not an asset (no extension or not in assets), try index.html
                                    // Avoid infinite loop if index.html itself is missing
                                    const isAsset = filePath.includes('.') && !filePath.endsWith('.html');

                                    if (!isAsset && filePath !== '/index.html') {
                                        // console.log('[HTTP] SPA Fallback -> /index.html');
                                        // Rewrite path to index.html and retry logic
                                        // But easier to just read index.html here
                                        const indexAbsPath = `${WWW_DIR}/index.html`;
                                        const indexInfo = await FileSystem.getInfoAsync(indexAbsPath);
                                        if (indexInfo.exists) {
                                            const content = await FileSystem.readAsStringAsync(indexAbsPath, { encoding: FileSystem.EncodingType.Base64 });
                                            // Serve index.html
                                            const headers = [
                                                'HTTP/1.1 200 OK',
                                                'Content-Type: text/html',
                                                'Connection: close',
                                                '\r\n'
                                            ].join('\r\n');

                                            // Write logic duplicated here or can structure better. 
                                            // For minimal change, writing it out.
                                            const buffer = Buffer.from(content, 'base64');
                                            const headerWithLen = headers.replace('\r\n\r\n', `\r\nContent-Length: ${buffer.length}\r\n\r\n`);
                                            socket.write(headerWithLen);
                                            socket.write(buffer); // Index is small enough to write directly usually, or reused chunk logic? 
                                            // Reuse chunk logic is better but complex to refactor inside this block.
                                            // Given index.html is small (<16KB typically), direct write is risky if huge.
                                            // Safest is to just let it fall through? No, flow is linear.
                                            // Let's just use the strict chunk logic again.

                                            const CHUNK_SIZE = 16 * 1024;
                                            let offset = 0;
                                            while (offset < buffer.length) {
                                                const chunk = buffer.slice(offset, offset + CHUNK_SIZE);
                                                socket.write(chunk);
                                                offset += CHUNK_SIZE;
                                            }
                                            await new Promise(r => setTimeout(r, 100));
                                            socket.end();
                                            return;
                                        }
                                    }

                                    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
                                    socket.end();
                                    return;
                                }

                                const content = await FileSystem.readAsStringAsync(absPath, { encoding: FileSystem.EncodingType.Base64 });

                                let contentType = 'text/plain';
                                // Keep extensions aligned with index.html bundle requests
                                if (filePath.endsWith('.html')) contentType = 'text/html';
                                else if (filePath.endsWith('.js') || filePath.endsWith('.js.bundle')) contentType = 'application/javascript';
                                else if (filePath.endsWith('.css') || filePath.endsWith('.css.bundle')) contentType = 'text/css';
                                else if (filePath.endsWith('.png')) contentType = 'image/png';
                                else if (filePath.endsWith('.svg')) contentType = 'image/svg+xml';
                                else if (filePath.endsWith('.ico')) contentType = 'image/x-icon';

                                const headers = [
                                    'HTTP/1.1 200 OK',
                                    `Content-Type: ${contentType}`,
                                    // Calculate content length from base64 string is tricky without buffer, 
                                    // but we will cast to buffer first.
                                    'Connection: close',
                                    '\r\n'
                                ].join('\r\n');

                                // Chunked Write Implementation
                                const buffer = Buffer.from(content, 'base64');
                                // Inject correct length now we have buffer
                                const headerWithLen = headers.replace('\r\n\r\n', `\r\nContent-Length: ${buffer.length}\r\n\r\n`);
                                socket.write(headerWithLen);

                                const CHUNK_SIZE = 16 * 1024; // 16KB
                                let offset = 0;

                                try {
                                    while (offset < buffer.length) {
                                        // Safety check: if socket destroyed/closed (no API to check explicitly easily without property access)
                                        // We rely on try-catch around write.

                                        const chunk = buffer.slice(offset, offset + CHUNK_SIZE);
                                        const ok = socket.write(chunk);

                                        if (!ok) {
                                            await new Promise(r => setTimeout(r, 20));
                                        }
                                        offset += CHUNK_SIZE;
                                    }
                                } catch (e) {
                                    console.warn('[HTTP] Write failed (closed?):', e);
                                    // Stop writing if connection lost, don't crash
                                }

                                // Give native socket time to flush before closing
                                await new Promise(r => setTimeout(r, 100));
                                socket.end();

                            } catch (err) {
                                try {
                                    socket.write('HTTP/1.1 500 Internal Server Error\r\n\r\n');
                                    socket.end();
                                } catch { }
                            }
                        });

                        socket.on('error', (err) => {
                            console.log('[HTTP] Socket error', err);
                        });
                    });

                    this.server.listen({ port: SERVER_PORT, host: '0.0.0.0' }, () => {
                        console.log(`[Workbench] Server running at ${fullUrl}`);
                        resolve();
                    });

                    this.server.on('error', async (err: any) => {
                        if (err.message && err.message.includes('EADDRINUSE')) {
                            console.warn(`[Workbench] Port ${SERVER_PORT} busy, retrying... (${retries} left)`);
                            this.server?.close();
                            this.server = null;
                            if (retries > 0) {
                                const waitTime = 500 + (10 - retries) * 200; // 500ms, 700ms, 900ms...
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

            const fullUrl = `http://${ip}:${SERVER_PORT}`;
            await startServer(10);

            setServerUrl(fullUrl);
            setServerUrl(fullUrl);
            setServerStatus('running');

            // Start Background Service & Request Permissions
            await backgroundService.start();
            // Proactive Battery Request (optional, might be annoying if popups every time, but user asked for it)
            // We can check if already optimized? For now just call it, logic inside can decide.
            backgroundService.requestBatteryOptimization();

            return fullUrl;
        } catch (error) {
            console.error('[Workbench] Failed to start server:', error);
            setServerStatus('error');
            setServerUrl(null);
            throw error;
        }
    }

    async stop() {
        const { setServerStatus, setServerUrl } = useWorkbenchStore.getState();
        if (this.server) {
            console.log('[Workbench] Stopping server...');
            this.server.close();
            this.server = null;
        }
        await backgroundService.stop();
        setServerStatus('idle');
        setServerUrl(null);
    }

    private async prepareAssets() {
        const dirInfo = await FileSystem.getInfoAsync(WWW_DIR);
        if (!dirInfo.exists) {
            await FileSystem.makeDirectoryAsync(WWW_DIR);
        }

        const assetsDir = `${WWW_DIR}/assets`;
        const assetsDirInfo = await FileSystem.getInfoAsync(assetsDir);
        if (!assetsDirInfo.exists) {
            await FileSystem.makeDirectoryAsync(assetsDir);
        }

        const copyAsset = async (assetModule: number, targetPath: string) => {
            try {
                const asset = Asset.fromModule(assetModule);
                await asset.downloadAsync();
                if (asset.localUri) {
                    await FileSystem.copyAsync({
                        from: asset.localUri,
                        to: targetPath
                    });
                }
            } catch (e) {
                try {
                    await FileSystem.deleteAsync(targetPath, { idempotent: true });
                    if (assetModule) {
                        const asset = Asset.fromModule(assetModule);
                        if (asset.localUri) {
                            await FileSystem.copyAsync({ from: asset.localUri, to: targetPath });
                        }
                    }
                } catch (retryErr) {
                    console.error("Retry copy failed", retryErr);
                }
            }
        };

        try {
            // Copy assets keeping the .bundle extension to match index.html references
            await copyAsset(WEB_ASSETS.indexHtml, `${WWW_DIR}/index.html`);
            await copyAsset(WEB_ASSETS.indexJs, `${WWW_DIR}/assets/index.js.bundle`);
            await copyAsset(WEB_ASSETS.indexCss, `${WWW_DIR}/assets/index.css.bundle`);
            await copyAsset(WEB_ASSETS.viteSvg, `${WWW_DIR}/vite.svg`);
        } catch (e) {
            console.error("Error preparing assets", e);
            throw e;
        }
    }
}

export const staticServerService = new StaticServerService();
