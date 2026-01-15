
import { z } from 'zod';
import { Skill } from '../../../types/skills';
import * as FileSystem from 'expo-file-system/legacy';
import { Platform } from 'react-native';

// 🛡️ 安全沙箱：限制所有操作在 DocumentDirectory/agent_sandbox/ 内
const RAW_SANDBOX_ROOT = (FileSystem as any).documentDirectory || (FileSystem as any).cacheDirectory;
const SANDBOX_FOLDER = 'agent_sandbox/workspace/';
const SANDBOX_ROOT = RAW_SANDBOX_ROOT + SANDBOX_FOLDER;

/**
 * 路径安全校验辅助函数
 * 防止路径遍历攻击 (Directory Traversal) 并确保沙箱目录存在
 */
const resolveSafePath = async (relativePath: string): Promise<string> => {
    // 确保沙箱根目录存在
    const sandboxInfo = await FileSystem.getInfoAsync(SANDBOX_ROOT);
    if (!sandboxInfo.exists) {
        await FileSystem.makeDirectoryAsync(SANDBOX_ROOT, { intermediates: true });
    }

    // 1. 规范化路径：移除开头的 ./ 或 /
    let cleanPath = relativePath.replace(/^(\.\/|\/)+/, '');

    // 2. 检查非法字符 (如 ..)
    if (cleanPath.includes('..')) {
        throw new Error('Security Violation: Access to parent directory (..) is denied.');
    }

    // 3. 构建完整路径
    return SANDBOX_ROOT + cleanPath;
};

/**
 * 技能：写入文件 (Write File)
 * 支持文本写入，自动创建不存在的目录
 */
export const WriteFileSkill: Skill = {
    id: 'write_file',
    name: 'Write File',
    description: 'Create or overwrite a text file in the local storage. Use this to save notes, code, or logs. The file path is relative to the app\'s sandbox root.',
    schema: z.object({
        path: z.string().describe('Relative path to the file (e.g., "notes/meeting.txt")'),
        content: z.string().describe('The text content to write into the file'),
    }),
    execute: async (params: { path: string; content: string }, context) => {
        try {
            const fullPath = await resolveSafePath(params.path);

            // 自动创建父目录 (Robust physical write)
            const directory = fullPath.substring(0, fullPath.lastIndexOf('/'));
            const dirInfo = await FileSystem.getInfoAsync(directory);
            if (!dirInfo.exists) {
                await FileSystem.makeDirectoryAsync(directory, { intermediates: true });
            }

            // 写入文件 (Physical Write)
            await FileSystem.writeAsStringAsync(fullPath, params.content, {
                encoding: (FileSystem as any).EncodingType.UTF8
            });

            // 🛡️ RAG Sync: Register document in RAG Store
            try {
                // Dynamic import to avoid cycles if any, though store is usually safe
                const { useRagStore } = require('../../../store/rag-store');
                const store = useRagStore.getState();

                // 1. Ensure folders are loaded to find 'workspace'
                if (store.folders.length === 0) {
                    await store.loadFolders();
                }

                // 2. Find Workspace Folder
                const workspaceFolder = store.folders.find((f: any) => f.name === 'workspace' && !f.parentId);

                if (workspaceFolder) {
                    // Check if doc already exists to update instead of add? 
                    // addDocument in store doesn't check dupes, it generates new ID. 
                    // But we want to avoid duplicates if agent overwrites.
                    // Let's check simply by title+folderId
                    const existingDoc = store.documents.find((d: any) => d.title === params.path && d.folderId === workspaceFolder.id);

                    if (existingDoc) {
                        await store.updateDocumentContent(existingDoc.id, params.content);
                        console.log('[FileSystem] RAG Document updated:', params.path);
                    } else {
                        // 3. Register New Document
                        // Note: addDocument will perform a 2nd write, which is fine as dirs are ensured above.
                        await store.addDocument(
                            params.path,    // Title (relative path)
                            params.content,
                            params.content.length,
                            'text',         // Type
                            workspaceFolder.id,
                            undefined       // Thumbnail
                        );
                        console.log('[FileSystem] RAG Document registered:', params.path);
                    }
                } else {
                    console.warn('[FileSystem] RAG Sync skipped: Workspace folder not found.');
                }
            } catch (err) {
                console.warn('[FileSystem] RAG Sync failed (Physical write succeeded):', err);
                // We do NOT fail the skill execution because the file WAS written.
            }

            console.log('[FileSystem] File written successfully:', {
                inputPath: params.path,
                fullPath,
                size: params.content.length
            });

            return {
                id: `write_${Date.now()}`,
                content: `Successfully wrote ${params.content.length} characters to "${params.path}".`,
                status: 'success',
                data: {
                    path: params.path,
                    fullPath,
                    size: params.content.length
                }
            };
        } catch (e: any) {
            return {
                id: `write_err_${Date.now()}`,
                content: `Failed to write file "${params.path}": ${e.message}`,
                status: 'error'
            };
        }
    },
};

/**
 * 技能：读取文件 (Read File)
 * 读取文本文件内容
 */
export const ReadFileSkill: Skill = {
    id: 'read_file',
    name: 'Read File',
    description: 'Read the content of a local text file. Use this to retrieve previously saved information.',
    schema: z.object({
        path: z.string().describe('Relative path to the file (e.g., "notes/meeting.txt")'),
    }),
    execute: async (params: { path: string }, context) => {
        try {
            const fullPath = await resolveSafePath(params.path);

            const fileInfo = await FileSystem.getInfoAsync(fullPath);
            if (!fileInfo.exists) {
                throw new Error('File not found.');
            }
            if (fileInfo.isDirectory) {
                throw new Error('Target is a directory, not a file. Use list_directory instead.');
            }

            const content = await FileSystem.readAsStringAsync(fullPath, {
                encoding: (FileSystem as any).EncodingType.UTF8
            });

            return {
                id: `read_${Date.now()}`,
                content: content,
                status: 'success',
                data: {
                    path: params.path,
                    size: content.length
                }
            };
        } catch (e: any) {
            return {
                id: `read_err_${Date.now()}`,
                content: `Failed to read file "${params.path}": ${e.message}`,
                status: 'error'
            };
        }
    },
};

/**
 * 技能：列出目录 (List Directory)
 * 查看目录下的文件结构
 */
export const ListDirSkill: Skill = {
    id: 'list_directory',
    name: 'List Directory',
    description: 'List files and subdirectories in a specific folder. Use this to explore the file structure.',
    schema: z.object({
        path: z.string().optional().describe('Relative path to the directory (default is root "./")'),
    }),
    execute: async (params: { path?: string }, context) => {
        try {
            const targetPath = params.path || '';
            const fullPath = await resolveSafePath(targetPath);

            const dirInfo = await FileSystem.getInfoAsync(fullPath);
            if (!dirInfo.exists) {
                throw new Error('Directory not found.');
            }
            if (!dirInfo.isDirectory) {
                throw new Error('Target is a file, not a directory.');
            }

            const files = await FileSystem.readDirectoryAsync(fullPath);

            // 获取详细信息 (区分文件和文件夹) - Optional: 可以增强为并发获取详情
            const details = await Promise.all(files.map(async (file) => {
                const itemPath = fullPath + (fullPath.endsWith('/') ? '' : '/') + file;
                const info = await FileSystem.getInfoAsync(itemPath);
                return {
                    name: file,
                    type: info.isDirectory ? 'directory' : 'file',
                    size: info.exists && !info.isDirectory ? info.size : undefined
                };
            }));

            // 格式化输出
            const output = details.map(item =>
                `[${item.type === 'directory' ? 'DIR ' : 'FILE'}] ${item.name} ${item.type === 'file' ? `(${item.size} bytes)` : ''}`
            ).join('\n');

            return {
                id: `ls_${Date.now()}`,
                content: output || '(Empty Directory)',
                status: 'success',
                data: {
                    path: targetPath,
                    items: details
                }
            };
        } catch (e: any) {
            return {
                id: `ls_err_${Date.now()}`,
                content: `Failed to list directory "${params.path || './'}": ${e.message}`,
                status: 'error'
            };
        }
    },
};
