package com.promenar.nexara.data.linux

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.LinuxExecPtyBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.io.InterruptedIOException
import android.system.ErrnoException
import android.system.StructPollfd
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 测试可注入宿主假进程；只有 Android 实现加载 JNI。 */
fun interface LinuxExecPtyFactory {
    fun start(argv: List<String>, directory: File, environment: Map<String, String>): Process
}

object LinuxExecNativePtyFactory : LinuxExecPtyFactory {
    override fun start(argv: List<String>, directory: File, environment: Map<String, String>): Process =
        LinuxExecPtyProcess(argv, directory, environment)
}

private class LinuxExecPtyProcess(argv: List<String>, directory: File, environment: Map<String, String>) : Process(), AutoCloseable {
    private val pid = IntArray(1)
    private val master = ParcelFileDescriptor.adoptFd(LinuxExecPtyBridge.create(argv.first(), directory.path,
        argv.toTypedArray(), (environment + ("TERM" to "xterm-256color")).map { (k, v) -> "$k=$v" }.toTypedArray(), pid))
    private val readFd = ParcelFileDescriptor.dup(master.fileDescriptor)
    private val writeFd = ParcelFileDescriptor.dup(master.fileDescriptor)
    init {
        // 非阻塞读写配合短轮询，让输入超时与取消能真正退出本地系统调用。
        val flags = Os.fcntlInt(master.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(master.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
    private fun poll(fd: java.io.FileDescriptor, events: Short) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PTY IO interrupted")
        try { Os.poll(arrayOf(StructPollfd().apply { this.fd = fd; this.events = events }), 100) }
        catch (error: ErrnoException) { throw IOException("PTY poll failed", error) }
    }
    private val input = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                try { return Os.read(readFd.fileDescriptor, bytes, offset, length).let { if (it == 0) -1 else it } }
                catch (error: ErrnoException) {
                    when (error.errno) {
                        OsConstants.EIO -> return -1
                        OsConstants.EAGAIN, OsConstants.EINTR -> poll(readFd.fileDescriptor, OsConstants.POLLIN.toShort())
                        else -> throw IOException("PTY read failed", error)
                    }
                }
            }
        }
        override fun close() = readFd.close()
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var written = 0
            while (written < length) {
                if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) throw InterruptedIOException("PTY input timeout")
                try {
                    written += Os.write(writeFd.fileDescriptor, bytes, offset + written, minOf(1024, length - written))
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) poll(writeFd.fileDescriptor, OsConstants.POLLOUT.toShort())
                    else throw IOException("PTY write failed", error)
                }
            }
        }
        override fun close() = writeFd.close()
    }
    private val ended = CountDownLatch(1)
    @Volatile private var code = 0
    init {
        Thread({
            try { code = LinuxExecPtyBridge.waitFor(pid[0]).let { if (it < 0) 128 - it else it } }
            finally { ended.countDown() }
        }, "linux-exec-pty-wait").apply { isDaemon = true; start() }
    }
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int { ended.await(); return code }
    override fun waitFor(timeout: Long, unit: TimeUnit) = ended.await(timeout, unit)
    override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return code }
    override fun isAlive() = ended.count > 0
    private fun signal(number: Int) {
        if (!isAlive || pid[0] <= 1) return
        runCatching { Os.kill(-pid[0], number) }
        // 同时覆盖 JNI fork 返回后、子进程 setsid 前的窗口。
        if (isAlive) runCatching { Os.kill(pid[0], number) }
    }
    override fun destroy() = signal(OsConstants.SIGTERM)
    override fun destroyForcibly(): Process { signal(OsConstants.SIGKILL); return this }
    override fun close() { runCatching { input.close() }; runCatching { output.close() }; master.close() }
}
