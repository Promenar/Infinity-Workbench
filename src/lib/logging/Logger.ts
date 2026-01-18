import { LogDatabase } from './LogDatabase';
import { LogEntry, LogLevel } from './LogSchema';

/**
 * 简单的 UUID 生成器
 */
function generateUUID(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = (Math.random() * 16) | 0,
            v = c == 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

/**
 * 统一日志管理器 (Singleton)
 */
export class Logger {
    private static instance: Logger;
    private db: LogDatabase;

    // 环形缓冲区：始终保留最近 N 条日志在内存中，用于 crash 回溯或 UI 展示
    private ringBuffer: LogEntry[] = [];
    private readonly ringBufferSize: number = 200;

    // 待写入队列：用于暂存待批量写入 DB 的日志
    private pendingLogs: LogEntry[] = [];

    // 防抖计时器
    private flushTimer: NodeJS.Timeout | null = null;
    private readonly flushInterval: number = 500; // 500ms 批量写入一次

    // 当前会话 ID
    private sessionId: string;

    // 是否启用日志 (从设置商店同步)
    private enabled: boolean = true;

    private constructor() {
        this.db = LogDatabase.getInstance();
        this.sessionId = generateUUID();

        // 延迟初始化订阅，避免循环依赖或 Store 未准备好
        setTimeout(() => {
            try {
                const { useSettingsStore } = require('../../store/settings-store');
                // 初始化状态
                this.enabled = useSettingsStore.getState().loggingEnabled;
                // 订阅变更
                useSettingsStore.subscribe((state: any) => {
                    this.enabled = state.loggingEnabled;
                });
            } catch (e) {
                console.error('[Logger] Failed to bound settings store:', e);
            }
        }, 100);

        // 启动时清理旧日志
        setTimeout(async () => {
            await this.db.pruneLogs();
        }, 5000);
    }

    public static getInstance(): Logger {
        if (!Logger.instance) {
            Logger.instance = new Logger();
        }
        return Logger.instance;
    }

    /**
     * 核心日志方法
     */
    public log(level: LogLevel, tag: string, message: string, metadata?: any) {
        if (!this.enabled && level < LogLevel.ERROR) {
            // 如果日志被关闭，且不是 ERROR 级别，则跳过
            // ERROR 级别建议保留，或者根据用户需求严格关闭
            return;
        }

        const entry: LogEntry = {

            id: generateUUID(),
            level,
            tag,
            message,
            timestamp: new Date().toISOString(),
            metadata,
            session_id: this.sessionId
        };

        // 1. 写入环形缓冲区 (内存)
        this.addToRingBuffer(entry);

        // 2. 加入待写入队列
        this.pendingLogs.push(entry);

        // 3. 触发写入策略
        if (level >= LogLevel.ERROR) {
            // 错误日志立即写入，防止崩溃丢失
            this.flush();
        } else {
            // 普通日志延迟批量写入
            this.scheduleFlush();
        }

        // 开发环境同步输出到控制台，方便调试
        if (__DEV__) {
            const color = level >= LogLevel.ERROR ? '\x1b[31m' : (level === LogLevel.WARN ? '\x1b[33m' : '\x1b[36m');
            // console.log(`${color}[${LogLevel[level]}][${tag}] ${message}\x1b[0m`);
            // 为了避免这里的 console.log 又被重定向导致死循环 (如果我们做了 console hook)，这里使用原始 console 或者保持现状。
            // 目前还没有 Hook console，暂时保留。
        }
    }

    public debug(tag: string, message: string, metadata?: any) {
        this.log(LogLevel.DEBUG, tag, message, metadata);
    }

    public info(tag: string, message: string, metadata?: any) {
        this.log(LogLevel.INFO, tag, message, metadata);
    }

    public warn(tag: string, message: string, metadata?: any) {
        this.log(LogLevel.WARN, tag, message, metadata);
    }

    public error(tag: string, message: string, metadata?: any) {
        this.log(LogLevel.ERROR, tag, message, metadata);
    }

    /**
     * 维护内存环形缓冲区
     */
    private addToRingBuffer(entry: LogEntry) {
        if (this.ringBuffer.length >= this.ringBufferSize) {
            this.ringBuffer.shift(); // 移除最早的
        }
        this.ringBuffer.push(entry);
    }

    /**
     * 调度批量写入
     */
    private scheduleFlush() {
        if (!this.flushTimer) {
            this.flushTimer = setTimeout(() => {
                this.flush();
            }, this.flushInterval);
        }
    }

    /**
     * 执行写入数据库
     */
    // 写入锁，防止并发事务冲突
    private isFlushing: boolean = false;

    /**
     * 执行写入数据库
     */
    public async flush() {
        if (this.flushTimer) {
            clearTimeout(this.flushTimer);
            this.flushTimer = null;
        }

        if (this.pendingLogs.length === 0) return;

        // 如果正在写入，则本次跳过，等待下一次（反正已经在队列里了）
        // 或者等待？由于是单线程 JS，简单的 boolean 锁足够防止重入，但如果是异步 await 期间再次进入...
        // 策略: 如果正在 flush，我们应该让本次调用等待吗？
        // 不，简单的策略是：如果 isFlushing 为 true，说明有一个 flush 正在进行中，它会处理 pendingLogs。
        // 但是 wait，上面的代码是 `const logsToWrite = [...this.pendingLogs]; this.pendingLogs = [];`
        // 如果 flush 1 拿走了 logs，正在写 DB。
        // flush 2 进来，发现 pendingLogs 为空 (被拿走了)，直接 return。
        // flush 3 进来 (假如 flush 1 还没完)，又有新日志了。flush 3 也会尝试写。
        // 这时候 flush 1 的 DB transaction 还没 commit。flush 3 尝试 begin transaction -> CRASH.

        // 所以必须加锁：如果 isFlushing，则不要开启新的 DB 写入。
        // 但是 pendingLogs 不能丢。
        // 修正逻辑：
        if (this.isFlushing) {
            // 正在 flush 中，保持 pendingLogs，重新调度 timer 稍后重试
            this.scheduleFlush();
            return;
        }

        this.isFlushing = true;

        try {
            // 取出所有待写入日志
            const logsToWrite = [...this.pendingLogs];
            this.pendingLogs = []; // 清空队列

            // 写入数据库
            if (logsToWrite.length > 0) {
                await this.db.insertLogs(logsToWrite);
            }
        } catch (e) {
            console.error('[Logger] Flush failed:', e);
            // 失败不回滚内存队列，避免死循环阻塞，丢了就丢了
        } finally {
            this.isFlushing = false;
            // 如果在 flush 期间又积攒了新日志 (re-entrant while awaiting)，再次触发
            if (this.pendingLogs.length > 0) {
                this.scheduleFlush();
            }
        }
    }

    /**
     * 获取内存中的构建回溯日志
     */
    public getCrashDump(): string {
        return JSON.stringify(this.ringBuffer.map(l =>
            `[${l.timestamp}] [${LogLevel[l.level]}] [${l.tag}]: ${l.message}`
        ), null, 2);
    }

    /**
     * 获取最近日志 (优先内存，不足查库)
     */
    /**
     * 获取最近日志 (优先内存，不足查库)
     */
    public async getRecentLogs(limit: number = 100): Promise<LogEntry[]> {
        // 如果内存已够
        if (this.ringBuffer.length >= limit) {
            return this.ringBuffer.slice(-limit).reverse();
        }
        // 否则查库
        return await this.db.getRecentLogs(limit);
    }

    /**
     * 导出日志并呼起分享
     */
    public async exportLogs() {
        try {
            const FileSystem = require('expo-file-system/legacy');
            const Sharing = require('expo-sharing');

            // 1. 获取最近 2000 条日志
            const allLogs = await this.db.getRecentLogs(2000);

            // 2. 格式化
            const content = allLogs.map(l =>
                `[${l.timestamp}] [${LogLevel[l.level]}] [${l.tag}]: ${l.message} ${l.metadata ? '\nMetadata: ' + JSON.stringify(l.metadata, null, 2) : ''}`
            ).join('\n\n' + '-'.repeat(40) + '\n\n');


            const fileName = `Nexara_Log_${new Date().toISOString().replace(/[:.]/g, '-')}.txt`;
            const fileUri = `${FileSystem.cacheDirectory}${fileName}`;

            // 3. 写入临时文件
            await FileSystem.writeAsStringAsync(fileUri, content, { encoding: 'utf8' });


            // 4. 判断分享是否可用
            if (!(await Sharing.isAvailableAsync())) {
                console.error('[Logger] Sharing is not available on this platform');
                return;
            }

            // 5. 呼起分享
            await Sharing.shareAsync(fileUri, {
                mimeType: 'text/plain',
                dialogTitle: 'Export Nexara Runtime Logs',
                UTI: 'public.plain-text'
            });

        } catch (e) {
            console.error('[Logger] Failed to export logs:', e);
            throw e;
        }
    }
}

