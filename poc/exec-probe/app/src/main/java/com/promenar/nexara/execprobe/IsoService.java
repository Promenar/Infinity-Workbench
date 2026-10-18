package com.promenar.nexara.execprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;

/** isolatedProcess 内的执行探针：验证能否执行打包二进制，以及能否读取主进程私有数据与访问网络。 */
public class IsoService extends Service {
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Messenger reply = msg.replyTo;
            String secret = msg.getData().getString("secret");
            String probe = getApplicationInfo().nativeLibraryDir + "/libnxprobe.so";
            new Thread(() -> {
                Bundle data = new Bundle();
                data.putString("iso_exec_id", Probe.run(probe, "id"));
                data.putString("iso_native_cat_secret", Probe.run(probe, "cat", secret));
                data.putString("iso_java_read_secret", javaRead(secret));
                data.putString("iso_net", Probe.run(probe, "net", "1.1.1.1", "443"));
                data.putString("iso_python_packaged", PythonRunner.runPackaged(getApplicationInfo().nativeLibraryDir,
                    java.util.Arrays.asList("-c", PythonRunner.SMOKE + ";open('/data/user/0/com.promenar.nexara.execprobe/files/secret.txt')"), 60));
                data.putString("iso_sh", Probe.run("/system/bin/sh", "-c", "id; echo ok"));
                data.putString("iso_cache_dir", String.valueOf(getCacheDir()) + " writable=" + writable(getCacheDir()));
                Message out = Message.obtain();
                out.setData(data);
                try { reply.send(out); } catch (RemoteException ignored) {}
            }).start();
        }
    });

    private static String javaRead(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            return "ok bytes=" + in.available();
        } catch (Exception e) {
            return "denied " + e;
        }
    }

    private static String writable(File dir) {
        try {
            File f = new File(dir, "iso-write-test");
            boolean ok = f.createNewFile() || f.exists();
            f.delete();
            return String.valueOf(ok);
        } catch (Exception e) {
            return "exception " + e;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }
}
