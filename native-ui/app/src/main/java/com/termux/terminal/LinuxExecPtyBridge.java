package com.termux.terminal;

/** 复用现有 libtermux 的包内 JNI，命令执行不依赖终端页面与主线程 Handler。 */
public final class LinuxExecPtyBridge {
    private LinuxExecPtyBridge() {}
    public static int create(String executable, String cwd, String[] argv, String[] env, int[] pid) {
        return JNI.createSubprocess(executable, cwd, argv, env, pid, 40, 120);
    }
    public static int waitFor(int pid) { return JNI.waitFor(pid); }
}
