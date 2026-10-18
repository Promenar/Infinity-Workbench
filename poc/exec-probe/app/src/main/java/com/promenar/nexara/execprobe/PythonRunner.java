package com.promenar.nexara.execprobe;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 解压标准库到 filesDir/python，并以 libnxpython.so 启动解释器。 */
final class PythonRunner {
    private PythonRunner() {}

    static File home(Context context) {
        return new File(context.getFilesDir(), "python");
    }

    static String ensureExtracted(Context context) {
        long updated;
        try {
            updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            updated = 0;
        }
        // 每次覆盖安装后重新解压，避免旧标准库残留。
        File marker = new File(home(context), ".extracted-3.14.8-" + updated);
        if (!marker.exists()) deleteTree(home(context));
        if (marker.exists()) return "cached";
        long start = System.nanoTime();
        try {
            int[] count = {0};
            extractDir(context.getAssets(), "python", context.getFilesDir(), count);
            marker.createNewFile();
            return "extracted files=" + count[0] + " ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (Exception e) {
            return "exception=" + e;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void extractDir(AssetManager assets, String path, File target, int[] count) throws java.io.IOException {
        String[] names = assets.list(path);
        if (names == null || names.length == 0) {
            File out = new File(target, path);
            out.getParentFile().mkdirs();
            try (InputStream in = assets.open(path); FileOutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
            }
            count[0]++;
            return;
        }
        for (String name : names) extractDir(assets, path + "/" + name, target, count);
    }

    /** 打包库方案：标准库 zip 与原生扩展都在 nativeLibraryDir，不解压、不写数据目录，隔离进程同样适用。 */
    static String runPackaged(String libDir, List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(libDir + "/libnxpython.so");
        command.addAll(args);
        long start = System.nanoTime();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("PYTHONHOME", libDir);
            env.put("PYTHONPATH", libDir + "/libpystdlib.so");
            env.put("NEXARA_PYEXT_DIR", libDir);
            env.put("PYTHONDONTWRITEBYTECODE", "1");
            env.put("PYTHONNOUSERSITE", "1");
            env.put("LD_LIBRARY_PATH", libDir);
            Process process = builder.start();
            String out = Probe.readAll(process.getInputStream());
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return (done ? "exit=" + process.exitValue() : "timeout") + " ms=" + ms + " out=" + out;
        } catch (Exception e) {
            return "exception=" + e;
        }
    }

    static final String SMOKE = "import sys,ssl,sqlite3,hashlib,json,zlib,ctypes,decimal,socket;"
        + "print(sys.version.split()[0], ssl.OPENSSL_VERSION.split()[1], sqlite3.sqlite_version, hashlib.sha256(b'x').hexdigest()[:8]);"
        + "print('ext', sqlite3._sqlite3.__file__ if hasattr(sqlite3,'_sqlite3') else __import__('_sqlite3').__file__);"
        + "print('path0', sys.path[0], 'prefix', sys.prefix)";

    static String run(Context context, List<String> args, int timeoutSeconds) {
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        List<String> command = new ArrayList<>();
        command.add(libDir + "/libnxpython.so");
        command.addAll(args);
        File homeDir = new File(context.getFilesDir(), "home");
        homeDir.mkdirs();
        long start = System.nanoTime();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).directory(homeDir);
            Map<String, String> env = builder.environment();
            env.put("PYTHONHOME", home(context).getPath());
            env.put("LD_LIBRARY_PATH", libDir);
            env.put("HOME", homeDir.getPath());
            env.put("TMPDIR", context.getCacheDir().getPath());
            env.put("PYTHONDONTWRITEBYTECODE", "0");
            Process process = builder.start();
            String out = Probe.readAll(process.getInputStream());
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return (done ? "exit=" + process.exitValue() : "timeout") + " ms=" + ms + " out=" + out;
        } catch (Exception e) {
            return "exception=" + e;
        }
    }
}
