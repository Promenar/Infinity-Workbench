import { Logger } from './Logger';
import { setNativeExceptionHandler } from 'react-native-exception-handler';

/**
 * 全局崩溃异常捕获初始化
 * 应在 App 入口 (index.js / _layout.tsx) 尽早调用
 */
export function initCrashHandler() {
    const logger = Logger.getInstance();

    // 1. 捕获 JS 全局未捕获异常
    const defaultHandler = (global as any).ErrorUtils?.getGlobalHandler();

    // ... (keep existing JS handler)
    (global as any).ErrorUtils?.setGlobalHandler((error: any, isFatal?: boolean) => {
        try {
            logger.error('CrashHandler', 'Uncaught Exception detected', {
                message: error.message,
                stack: error.stack,
                isFatal
            });
            logger.flush();
            console.error('🔥 CRASH CAUGHT BY LOGGER 🔥', error);
        } catch (e) {
            console.error('Failed to log crash:', e);
        } finally {
            if (defaultHandler) {
                defaultHandler(error, isFatal);
            }
        }
    });

    // 2. 捕获 Native 层未捕获异常 (Java/ObjC/C++)
    // 注意：在 Native 崩溃时，JS 线程可能已挂起，此回调只能尽力而为。
    setNativeExceptionHandler((exceptionString) => {
        try {
            logger.error('CrashHandler', 'NATIVE CRASH DETECTED', {
                raw: exceptionString,
                isFatal: true
            });

            // 尝试同步刷新 (虽然 Logger 是异步的，但我们尝试触发)
            logger.flush();
            console.error('🔥 NATIVE CRASH CAUGHT 🔥', exceptionString);
        } catch (e) {
            console.error('Failed to log native crash:', e);
        }
        // Native 异常通常无法恢复，记录后听天由命
    }, false, true); // forceAppQuit=false (尝试让 JS 跑完), executeDefaultHandler=true

    logger.info('CrashHandler', 'Global JS & Native crash handlers initialized');
}
