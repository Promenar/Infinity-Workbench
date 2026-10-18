package com.promenar.nexara.execprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 由 adb 驱动：am start -n com.promenar.nexara.execprobe/.ProbeActivity --es t <测试名>。
 * 测试名：basic、iso、fgs（--ei seconds --ez busy）、fgs_stop、bg（--ei seconds --ez busy）、status。
 */
public class ProbeActivity extends Activity {
    private static Process backgroundChild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String test = intent.getStringExtra("t");
        if (test == null) test = "basic";
        switch (test) {
            case "iso": runIso(); return;
            case "fgs": {
                Intent service = new Intent(this, HeartbeatService.class)
                    .putExtra("seconds", intent.getIntExtra("seconds", 600))
                    .putExtra("busy", intent.getBooleanExtra("busy", false));
                startForegroundService(service);
                finish();
                return;
            }
            case "fgs_stop": stopService(new Intent(this, HeartbeatService.class)); finish(); return;
            case "bg": startBackgroundChild(intent.getIntExtra("seconds", 600), intent.getBooleanExtra("busy", false)); finish(); return;
            case "status": status(); finish(); return;
            case "recon": {
                int count = intent.getIntExtra("n", 5000);
                int size = intent.getIntExtra("size", 4096);
                new Thread(() -> {
                    Reconcile.run(new File(getFilesDir(), "recon"), Probe.probePath(this), count, size);
                    Probe.log("recon_done", "ok");
                    runOnUiThread(this::finish);
                }).start();
                return;
            }
            case "pyz": {
                String code = intent.getStringExtra("code");
                new Thread(() -> {
                    java.util.List<String> args = java.util.Arrays.asList("-c", code == null ? PythonRunner.SMOKE : code);
                    Probe.log("pyz_run", PythonRunner.runPackaged(getApplicationInfo().nativeLibraryDir, args, 60));
                    runOnUiThread(this::finish);
                }).start();
                return;
            }
            case "py": {
                String code = intent.getStringExtra("code");
                String[] margs = intent.getStringArrayExtra("margs");
                int timeout = intent.getIntExtra("timeout", 120);
                new Thread(() -> {
                    Probe.log("py_extract", PythonRunner.ensureExtracted(getApplicationContext()));
                    java.util.List<String> args = new java.util.ArrayList<>();
                    if (code != null) { args.add("-c"); args.add(code); }
                    if (margs != null) { args.add("-m"); args.addAll(java.util.Arrays.asList(margs)); }
                    Probe.log("py_run", PythonRunner.run(getApplicationContext(), args, timeout));
                    // 运行期间保持前台，避免厂商冻结器干扰功能测试；后台行为由 bg/fgs 单独测。
                    runOnUiThread(this::finish);
                }).start();
                return;
            }
            default: new Thread(this::runBasic).start(); finish();
        }
    }

    private void runBasic() {
        Context context = getApplicationContext();
        String libDir = context.getApplicationInfo().nativeLibraryDir;
        Probe.log("libdir", libDir + " files=" + String.join(",", listOrEmpty(new File(libDir))));
        Probe.log("exec_libdir", Probe.run(Probe.probePath(context), "id"));
        Probe.log("sh_toybox", Probe.run("/system/bin/sh", "-c", "id; toybox --version; ls /system/bin | wc -l"));
        Probe.log("net_main", Probe.run(Probe.probePath(context), "net", "1.1.1.1", "443"));
        try {
            File copied = Probe.copy(new File(Probe.probePath(context)), new File(context.getFilesDir(), "nxprobe"));
            Probe.log("chmod_files", "setExecutable=" + copied.setExecutable(true, true));
            Probe.log("exec_files", Probe.run(copied.getPath(), "id"));
            Probe.log("exec_files_via_linker", Probe.run("/system/bin/linker64", copied.getPath(), "id"));
        } catch (Exception e) {
            Probe.log("exec_files", "exception=" + e);
        }
        try {
            System.loadLibrary("nxdl");
            Probe.log("dlopen_libdir", "ok");
        } catch (Throwable e) {
            Probe.log("dlopen_libdir", "failed " + e);
        }
        try {
            File copiedLib = Probe.copy(new File(libDir, "libnxdl.so"), new File(context.getFilesDir(), "libnxdl_copy.so"));
            System.load(copiedLib.getPath());
            Probe.log("dlopen_files", "ok");
        } catch (Throwable e) {
            Probe.log("dlopen_files", "failed " + e);
        }
        Probe.log("basic_done", "ok");
    }

    private void runIso() {
        File secret = new File(getFilesDir(), "secret.txt");
        try (FileOutputStream out = new FileOutputStream(secret)) {
            out.write("main-process-private-data".getBytes());
        } catch (Exception e) {
            Probe.log("iso_secret_write", "exception=" + e);
        }
        Messenger replyTo = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Bundle data = msg.getData();
                for (String key : data.keySet()) Probe.log(key, String.valueOf(data.getString(key)));
                Probe.log("iso_done", "ok");
                finish();
            }
        });
        bindService(new Intent(this, IsoService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Message msg = Message.obtain();
                msg.replyTo = replyTo;
                Bundle data = new Bundle();
                data.putString("secret", secret.getPath());
                msg.setData(data);
                try { new Messenger(service).send(msg); } catch (Exception e) { Probe.log("iso_send", "exception=" + e); }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Probe.log("iso_disconnected", name.toShortString());
            }
        }, BIND_AUTO_CREATE);
    }

    private void startBackgroundChild(int seconds, boolean busy) {
        File hb = new File(getExternalFilesDir(null), "hb-bg.txt");
        hb.delete();
        try {
            backgroundChild = new ProcessBuilder(Probe.probePath(this), "heartbeat", hb.getPath(), String.valueOf(seconds), busy ? "1" : "0").start();
            Probe.log("bg_start", backgroundChild.toString() + " seconds=" + seconds + " busy=" + busy);
        } catch (Exception e) {
            Probe.log("bg_start", "exception=" + e);
        }
    }

    private void status() {
        Probe.log("status_fgs", Probe.tail(new File(getExternalFilesDir(null), "hb-fgs.txt"), 2));
        Probe.log("status_bg", Probe.tail(new File(getExternalFilesDir(null), "hb-bg.txt"), 2));
        Probe.log("status_procs", Probe.run("/system/bin/sh", "-c", "ps -A -o PID,PPID,NAME | grep -E 'nxprobe|execprobe' | grep -v grep"));
    }

    private static String[] listOrEmpty(File dir) {
        String[] names = dir.list();
        return names == null ? new String[] {"<unreadable>"} : names;
    }
}
