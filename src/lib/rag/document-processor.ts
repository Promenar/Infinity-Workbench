import * as FileSystem from 'expo-file-system/legacy';
import mammoth from 'mammoth';
import * as XLSX from 'xlsx';
import { PdfExtractorRef } from '../../components/rag/PdfExtractor';
import { imageDescriptionService } from './image-service';
import { readFileContent } from '../file-utils';

export type FileType = 'text' | 'pdf' | 'docx' | 'xlsx' | 'image' | 'unknown';

export class DocumentProcessor {
    private pdfExtractor: PdfExtractorRef | null = null;

    setPdfExtractor(ref: PdfExtractorRef | null) {
        this.pdfExtractor = ref;
    }

    async processFile(
        uri: string,
        fileName: string,
        mimeType?: string,
    ): Promise<{ content: string; type: 'text' | 'image' }> {
        const fileType = this.detectFileType(fileName, mimeType);
        console.log(`[DocumentProcessor] Processing ${fileName} as ${fileType}`);

        switch (fileType) {
            case 'image':
                return this.processImage(uri, fileName);
            case 'pdf':
                return { content: await this.processPdf(uri), type: 'text' };
            case 'docx':
                return { content: await this.processDocx(uri), type: 'text' };
            case 'xlsx':
                return { content: await this.processXlsx(uri), type: 'text' };
            case 'text':
            default:
                return { content: await this.processText(uri), type: 'text' };
        }
    }

    private detectFileType(fileName: string, mimeType?: string): FileType {
        const ext = fileName.split('.').pop()?.toLowerCase();

        if (mimeType?.startsWith('image/') || ['jpg', 'jpeg', 'png', 'webp', 'heic'].includes(ext || '')) {
            return 'image';
        }
        if (mimeType === 'application/pdf' || ext === 'pdf') {
            return 'pdf';
        }
        if (
            mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' ||
            ext === 'docx'
        ) {
            return 'docx';
        }
        if (
            mimeType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
            ext === 'xlsx' ||
            ext === 'xls'
        ) {
            return 'xlsx';
        }
        return 'text';
    }

    private async processText(uri: string): Promise<string> {
        return await readFileContent(uri);
    }

    private async processPdf(uri: string): Promise<string> {
        if (!this.pdfExtractor) {
            throw new Error('PDF Extractor not initialized');
        }
        const base64 = await FileSystem.readAsStringAsync(uri, {
            encoding: 'base64',
        });
        return await this.pdfExtractor.extractText(base64);
    }

    private async processDocx(uri: string): Promise<string> {
        // Mammouth requires array buffer or base64. In RN, we can pass base64 to a mock buffer maybe?
        // Mammoth.js (browser version) takes arrayBuffer.
        // React Native's FileSystem can read as base64.
        // We need to convert base64 to ArrayBuffer or find a way to make mammoth accept it.
        // Actually, mammoth has a .convertToHtml({ arrayBuffer: ... }) method.

        const base64 = await FileSystem.readAsStringAsync(uri, {
            encoding: 'base64',
        });

        // Polyfill for Buffer/ArrayBuffer conversion if needed, or use a library.
        // 'buffer' package is available in dependencies.
        const { Buffer } = require('buffer');
        const buffer = Buffer.from(base64, 'base64');

        // Mammoth usually expects node buffer or arraybuffer.
        const result = await mammoth.extractRawText({ buffer: buffer });
        return result.value.trim();
    }

    private async processXlsx(uri: string): Promise<string> {
        const base64 = await FileSystem.readAsStringAsync(uri, {
            encoding: 'base64',
        });

        const workbook = XLSX.read(base64, { type: 'base64' });
        let fullText = '';

        workbook.SheetNames.forEach(sheetName => {
            const sheet = workbook.Sheets[sheetName];
            const text = XLSX.utils.sheet_to_txt(sheet); // Convert to CSV handling
            fullText += `[Sheet: ${sheetName}]\n${text}\n\n`;
        });

        return fullText.trim();
    }

    private async processImage(uri: string, fileName: string): Promise<{ content: string; type: 'image' }> {
        const base64 = await FileSystem.readAsStringAsync(uri, {
            encoding: 'base64',
        });

        try {
            const description = await imageDescriptionService.describeImage(base64);
            return {
                content: `[Image Description for ${fileName}]\n\n${description}`,
                type: 'image'
            };
        } catch (e) {
            console.warn('[DocumentProcessor] 图片描述失败，将跳过向量化:', e);
            // 🛡️ 返回空内容，由调用方 (rag.tsx) 的 content.trim() 检查拦截
            // 避免 "Description failed" 这类无信息量文本被向量化占用空间
            return {
                content: '',
                type: 'image'
            };
        }
    }
}

export const documentProcessor = new DocumentProcessor();
