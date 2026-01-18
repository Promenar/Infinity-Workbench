/**
 * 日志级别枚举
 */
export enum LogLevel {
    DEBUG = 0,
    INFO = 1,
    WARN = 2,
    ERROR = 3,
}

/**
 * 结构化日志条目接口
 */
export interface LogEntry {
    /** 唯一标识符 (UUID v4) */
    id: string;
    /** 日志级别 */
    level: LogLevel;
    /** 标签/模块名 */
    tag: string;
    /** 日志主要内容 */
    message: string;
    /** 发生时间 (ISO 8601) */
    timestamp: string;
    /** 额外的上下文数据 (将被序列化存储) */
    metadata?: Record<string, any>;
    /** 会话 ID (用于关联用户当前 Session) */
    session_id?: string;
}

/**
 * 数据库存储行结构 (扁平化)
 */
export interface LogRow {
    id: string;
    level: number;
    tag: string;
    message: string;
    timestamp: string;
    metadata: string | null; // JSON string
    session_id: string | null;
}
