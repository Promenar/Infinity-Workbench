import { XMLParser } from 'fast-xml-parser';
import { encode } from 'base-64';

export interface WebDavConfig {
  url: string;
  username: string;
  password?: string;
}

export interface WebDavFile {
  filename: string;
  lastModified: string;
  size: number;
  type: 'file' | 'directory';
}

export class WebDavClient {
  private config: WebDavConfig;
  private headers: HeadersInit;

  constructor(config: WebDavConfig) {
    // Ensure URL ends with /
    const url = config.url.endsWith('/') ? config.url : config.url + '/';
    this.config = { ...config, url };

    // UTF-8 Safe Base64 Encoding
    // Compatible with btoa(unescape(encodeURIComponent(str))) pattern
    const credentials = `${config.username}:${config.password || ''}`;
    const utf8Credentials = unescape(encodeURIComponent(credentials));

    const auth = encode(utf8Credentials);

    this.headers = {
      Authorization: `Basic ${auth}`,
      'User-Agent': 'Nexara/1.0',
      Accept: '*/*',
    };

    console.log(`[WebDav] Initialized for ${this.config.url}`);
  }

  /**
   * Test connection by listing root
   */
  async checkConnection(): Promise<boolean> {
    try {
      console.log('[WebDav] Checking connection...');
      await this.listFiles('/');
      return true;
    } catch (e) {
      // console.error('[WebDav] Connection check failed:', e); // Let the caller handle display
      throw e;
    }
  }

  /**
   * List files in a directory
   */
  async listFiles(path: string = '/'): Promise<WebDavFile[]> {
    const url = this.getUrl(path);
    console.log('[WebDav] PROPFIND', url);

    const response = await fetch(url, {
      method: 'PROPFIND',
      headers: {
        ...this.headers,
        Depth: '1', // Only immediate children
        'Content-Type': 'xml/text', // Try simpler content type
      },
      redirect: 'manual', // Prevent auto-redirects stripping auth headers
    });

    console.log(`[WebDav] Status: ${response.status}`);

    // Handle Redirects Manually (to preserve Auth header)
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location');
      console.log(`[WebDav] Redirect detected to: ${location}`);
      if (location) {
        // If relative path, construct full
        const newUrl = location.startsWith('http') ? location : new URL(location, url).toString();
        console.log(`[WebDav] Following redirect to ${newUrl} with credentials...`);
        // Recursive call or custom fetch for new URL
        // For now, simpler: just throw to let user know, or we can try to follow manually.
        throw new Error(`WebDAV Redirected. Please use the exact URL: ${location}`);
      }
    }

    if (!response.ok) {
      console.log('[WebDav] Response Headers:', JSON.stringify([...response.headers.entries()]));
      const authHeader = response.headers.get('www-authenticate');
      if (authHeader) console.log('[WebDav] WWW-Authenticate:', authHeader);

      const contentType = response.headers.get('Content-Type') || '';
      const errorText = await response.text();

      // Rule 8.4: Capture HTML error pages
      if (errorText.trim().startsWith('<') || !contentType.includes('xml')) {
        throw new Error(
          `WebDAV Error: ${response.status}. Received non-XML response (possibly HTML error page).`,
        );
      }

      throw new Error(`WebDAV Error: ${response.status} (${authHeader || response.statusText})`);
    }

    const text = await response.text();
    return this.parsePropfind(text);
  }

  /**
   * Upload a file
   */
  async uploadFile(
    path: string,
    content: string,
    contentType: string = 'application/json',
  ): Promise<void> {
    const url = this.getUrl(path);
    console.log('[WebDav] PUT', url);

    const response = await fetch(url, {
      method: 'PUT',
      headers: {
        ...this.headers,
        'Content-Type': contentType,
      },
      body: content,
    });

    if (!response.ok) {
      throw new Error(`Upload Failed: ${response.status} ${response.statusText}`);
    }
  }

  /**
   * Download a file
   */
  async downloadFile(path: string): Promise<string> {
    const url = this.getUrl(path);
    console.log('[WebDav] GET', url);

    const response = await fetch(url, {
      method: 'GET',
      headers: this.headers,
    });

    if (!response.ok) {
      throw new Error(`Download Failed: ${response.status} ${response.statusText}`);
    }

    return await response.text();
  }

  private getUrl(path: string): string {
    // Remove leading slash if both have it to avoid double slash (though URL handles it usually)
    const cleanPath = path.startsWith('/') ? path.substring(1) : path;
    return this.config.url + cleanPath;
  }

  private parsePropfind(xml: string): WebDavFile[] {
    const parser = new XMLParser({
      ignoreAttributes: false,
      removeNSPrefix: true, // "d:response" -> "response"
    });
    const jsonObj = parser.parse(xml);

    // Structure is standard WebDAV: <multistatus><response>...</response></multistatus>
    const responses = jsonObj?.multistatus?.response;

    if (!responses) return [];

    // Ensure array
    const list = Array.isArray(responses) ? responses : [responses];

    return list
      .map((res: any) => {
        const props = res.propstat?.prop || res.propstat?.[0]?.prop;
        const href = res.href; // "/dav/filename"

        // Extract filename from href
        // Decode URI component to handle spaces/unicode
        const decodedHref = decodeURIComponent(href);
        // Gets 'filename' from 'http://.../filename' or '/dav/filename'
        // We use a safe split approach
        const parts = decodedHref.split('/').filter((p: string) => p.length > 0);
        const filename = parts.length > 0 ? parts[parts.length - 1] : '/';

        const isCollection = props.resourcetype?.collection !== undefined;

        return {
          filename: filename,
          lastModified: props.getlastmodified,
          size: parseInt(props.getcontentlength || '0'),
          type: isCollection ? 'directory' : 'file',
        };
      })
      .filter((f: any) => f.filename !== '') as WebDavFile[]; // Filter out empty if any
  }
}
