import * as FileSystem from 'expo-file-system/legacy';
import { Skill } from '../../types/skills';
import { z } from 'zod';

const SKILLS_FILE = `${FileSystem.documentDirectory}user_skills.json`;

export interface StoredSkillData {
    id: string;
    name: string;
    description: string;
    code: string; // 函数体或完整的 JS 代码
    schemaJson: string; // 序列化的 Zod Schema
    isHighRisk?: boolean;
    category?: 'user' | 'model';
    author?: string;
    createdAt: number;
    updatedAt: number;
    configJson?: string; // 🔐 User configuration (API keys, etc.)
}

/**
 * 帮助类：处理用户技能的持久化存储
 */
export class UserSkillsStorage {

    static async loadSkills(): Promise<StoredSkillData[]> {
        try {
            const info = await FileSystem.getInfoAsync(SKILLS_FILE);
            if (!info.exists) return [];

            const content = await FileSystem.readAsStringAsync(SKILLS_FILE);
            return JSON.parse(content);
        } catch (e) {
            console.error('[UserSkillsStorage] Failed to load skills:', e);
            return [];
        }
    }

    static async saveSkill(data: StoredSkillData): Promise<void> {
        const skills = await this.loadSkills();
        const existingIndex = skills.findIndex(s => s.id === data.id);

        if (existingIndex >= 0) {
            skills[existingIndex] = { ...skills[existingIndex], ...data, updatedAt: Date.now() };
        } else {
            skills.push({ ...data, createdAt: Date.now(), updatedAt: Date.now() });
        }

        await FileSystem.writeAsStringAsync(SKILLS_FILE, JSON.stringify(skills, null, 2));
    }

    static async deleteSkill(id: string): Promise<void> {
        const skills = await this.loadSkills();
        const newSkills = skills.filter(s => s.id !== id);
        await FileSystem.writeAsStringAsync(SKILLS_FILE, JSON.stringify(newSkills, null, 2));
    }

    /**
     * 将存储的数据“水合” (Hydrate) 为可执行的 Skill 对象
     */
    static hydrateSkill(data: StoredSkillData): Skill {
        let schema: z.ZodSchema<any>;
        try {
            const jsonSchema = JSON.parse(data.schemaJson);
            schema = z.any().describe(JSON.stringify(jsonSchema));
        } catch (e) {
            schema = z.any();
        }

        // 🛡️ Safe Function Construction
        let executableFn: Function;
        let hydrationError: string | null = null;

        try {
            // Validate code based on simple Function constructor first
            // Note: We use the dynamic params injection at Runtime
            // We use 'console', 'params', 'fetch' as argument names for validation
            executableFn = new Function('console', 'params', 'fetch', `
                "use strict";
                ${data.code}
            `);
        } catch (err: any) {
            console.error(`[SkillHydration] Failed to compile code for ${data.id}:`, err);
            hydrationError = err.message;
            executableFn = () => { throw new Error(`Skill definition is broken: ${hydrationError}`); };
        }

        return {
            id: data.id,
            name: data.name,
            description: data.description,
            schema: schema,
            isHighRisk: data.isHighRisk,
            category: data.category as any,
            author: data.author,
            createdAt: data.createdAt,
            updatedAt: data.updatedAt,
            execute: async (params, context) => {
                if (hydrationError) {
                    return {
                        id: `exec_err_${Date.now()}`,
                        content: `❌ Skill Compilation Error: The code for this tool is invalid.\nDetails: ${hydrationError}`,
                        status: 'error'
                    };
                }

                // 🧩 Merge User Configuration (Default Params)
                let finalParams = params;
                if (data.configJson) {
                    try {
                        const config = JSON.parse(data.configJson);
                        // Runtime params override defaults
                        finalParams = { ...config, ...params };
                    } catch (e) {
                        console.warn('[CustomTool] Failed to parse configJson');
                    }
                }

                const sandboxGlobals = {
                    console: {
                        log: (...args: any[]) => console.log('[CustomTool]', ...args),
                        warn: (...args: any[]) => console.warn('[CustomTool]', ...args),
                        error: (...args: any[]) => console.error('[CustomTool]', ...args),
                    },
                    fetch: globalThis.fetch ? globalThis.fetch.bind(globalThis) : undefined,
                };

                try {
                    // Execute safely via the pre-compiled function if possible, or just re-run it
                    // Since executableFn was created with `new Function(...)`, calling it executes the code.
                    const result = executableFn(sandboxGlobals.console, finalParams, sandboxGlobals.fetch);

                    const resolvedResult = result instanceof Promise ? await result : result;

                    return {
                        id: `exec_${Date.now()}`,
                        content: typeof resolvedResult === 'string' ? resolvedResult : JSON.stringify(resolvedResult),
                        status: 'success',
                        data: resolvedResult
                    };
                } catch (err: any) {
                    return {
                        id: `exec_err_${Date.now()}`,
                        content: `Custom Tool Runtime Error: ${err.message}`,
                        status: 'error'
                    };
                }
            }
        };
    }
}
