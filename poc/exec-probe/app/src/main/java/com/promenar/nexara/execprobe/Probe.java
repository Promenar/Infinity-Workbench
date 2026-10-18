package com.promenar.nexara.execprobe;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 探针公共方法：结果统一以 NXPROBE 标签写入 logcat，供 adb 读取。 */
final class Probe {
    static final String TAG = "NXPROBE";

    private Probe() {}

    static void log(String key, String value) {
        Log.i(TAG, key + " => " + value.trim().replace("\n", " | "));
    }

    static String probePath(Context context) {
        return context.getApplicationInfo().nativeLibraryDir + "/libnxprobe.so";
    }

    static String run(String... command) {
        return run(Arrays.asList(command));
    }

    static String run(List<String> command) {
        long start = System.nanoTime();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = readAll(process.getInputStream());
            boolean done = process.waitFor(20, TimeUnit.SECONDS);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return (done ? "exit=" + process.exitValue() : "timeout") + " ms=" + ms + " out=" + out;
        } catch (Exception e) {
            return "exception=" + e;
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
        return buffer.toString("UTF-8");
    }

    static File copy(File source, File target) throws java.io.IOException {
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        }
        return target;
    }

    static String tail(File file, int lines) {
        try {
            String[] all = readAll(new FileInputStream(file)).split("\n");
            int from = Math.max(0, all.length - lines);
            return "lines=" + all.length + " tail=" + String.join(" ; ", Arrays.copyOfRange(all, from, all.length));
        } catch (Exception e) {
            return "exception=" + e;
        }
    }
}
