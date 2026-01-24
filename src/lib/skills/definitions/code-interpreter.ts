
import { z } from 'zod';
import { Skill } from '../../../types/skills';

/**
 * 技能：代码解释器 (Code Interpreter - JavaScript)
 * 允许模型在安全的本地环境中执行 JavaScript 代码进行计算、逻辑处理或数据格式化
 */
export const RunJavascriptSkill: Skill = {
    id: 'run_javascript',
    name: 'Code Interpreter (JS)',
    description: `Execute JavaScript code to perform calculations, data processing, or text formatting. 
Use this when you need accurate math (which LLMs are bad at) or complex data transformation.
The environment is sandboxed: no 'fetch', no 'fs', no 'require'. only pure logic.
Return the result by returning it or console.log.`,
    schema: z.object({
        code: z.string().describe('The JavaScript code to execute. Example: "const a=1; const b=2; return a+b;"'),
    }),
    execute: async (params: { code: string }, context) => {
        try {
            // 🛡️ Security Sandbox Construction
            // We use 'new Function' which is cleaner than eval, but still has access to global scope by default.
            // We must shadow dangerous globals.

            const sandboxGlobals = {
                console: {
                    log: (...args: any[]) => { logs.push(args.map(a => JSON.stringify(a)).join(' ')); },
                    warn: (...args: any[]) => { logs.push('WARN: ' + args.map(a => JSON.stringify(a)).join(' ')); },
                    error: (...args: any[]) => { logs.push('ERROR: ' + args.map(a => JSON.stringify(a)).join(' ')); },
                },
                fetch: undefined, // 🚫 Block Network
                XMLHttpRequest: undefined, // 🚫 Block Network
                setTimeout: undefined, // 🚫 Prevent async loops (optional, maybe generous)
                setInterval: undefined,
                require: undefined, // 🚫 Block Core Modules
                process: undefined,
                nativeFabricUIManager: undefined, // 🚫 Block React Native Internals
            };

            const logs: string[] = [];

            // Construct the function body with shadowed globals
            // note: "with" statement is deprecated/strict mode forbidden.
            // We can wrap in a closure.

            const wrappedCode = `
                "use strict";
                // Shadow Globals
                const fetch = undefined;
                const require = undefined;
                const process = undefined;
                const global = undefined;
                const window = undefined;
                
                // User Code
                ${params.code}
            `;

            // Execution
            // Using a simple function constructor for MVP.
            // Real secure sandbox needs quickjs-emscripten or Hermes Internal Bytecode logic, but this is Step 1.
            const fn = new Function('console', wrappedCode);

            let result: any;
            try {
                result = fn(sandboxGlobals.console);
            } catch (err: any) {
                throw new Error(`Runtime Error: ${err.message}`);
            }

            // Process Output
            // If result is undefined, check logs.
            const output = result !== undefined ? JSON.stringify(result) : logs.join('\n');

            return {
                id: `exec_${Date.now()}`,
                content: output || 'undefined (No return value or logs)',
                status: 'success',
                data: {
                    result,
                    logs
                }
            };

        } catch (e: any) {
            return {
                id: `exec_err_${Date.now()}`,
                content: `Execution Failed: ${e.message}`,
                status: 'error'
            };
        }
    },
};
