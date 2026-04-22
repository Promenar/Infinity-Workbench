/**
 * 带颜色的日志工具
 */

const COLORS = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  dim: '\x1b[2m',
  // 前景色
  black: '\x1b[30m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  white: '\x1b[37m',
  // 前景亮色
  brightRed: '\x1b[91m',
  brightGreen: '\x1b[92m',
  brightYellow: '\x1b[93m',
  brightBlue: '\x1b[94m',
  brightMagenta: '\x1b[95m',
  brightCyan: '\x1b[96m',
} as const;

type Color = keyof typeof COLORS;

function colorize(color: Color, message: string): string {
  return `${COLORS[color]}${message}${COLORS.reset}`;
}

export class Logger {
  constructor(private verbose: boolean = false) {}

  info(msg: string): void {
    console.log(colorize('blue', 'ℹ'), colorize('blue', msg));
  }

  success(msg: string): void {
    console.log(colorize('green', '✓'), colorize('green', msg));
  }

  warn(msg: string): void {
    console.log(colorize('yellow', '⚠'), colorize('yellow', msg));
  }

  error(msg: string): void {
    console.log(colorize('red', '✗'), colorize('red', msg));
  }

  debug(msg: string): void {
    if (this.verbose) {
      console.log(colorize('dim', '▸'), colorize('dim', msg));
    }
  }

  section(title: string): void {
    const line = '─'.repeat(50);
    console.log('');
    console.log(colorize('cyan', line));
    console.log(colorize('bright', colorize('cyan', ` ${title}`)));
    console.log(colorize('cyan', line));
  }

  // 格式化输出对象（用于调试）
  obj(label: string, obj: unknown): void {
    console.log(colorize('magenta', `▶ ${label}:`));
    console.log(JSON.stringify(obj, null, 2));
  }
}

export const logger = new Logger();
