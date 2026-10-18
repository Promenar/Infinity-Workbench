package com.promenar.nexara.execprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.io.File;

/** 前台服务下启动心跳子进程，观察熄屏与后台时子进程能否存活。 */
public class HeartbeatService extends Service {
    private Process child;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("probe", "执行探针", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "probe")
            .setContentTitle("执行探针心跳运行中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (child == null) {
            int seconds = intent == null ? 600 : intent.getIntExtra("seconds", 600);
            String busy = intent != null && intent.getBooleanExtra("busy", false) ? "1" : "0";
            File hb = new File(getExternalFilesDir(null), "hb-fgs.txt");
            hb.delete();
            try {
                child = new ProcessBuilder(Probe.probePath(this), "heartbeat", hb.getPath(), String.valueOf(seconds), busy).start();
                Probe.log("fgs_start", child.toString() + " seconds=" + seconds + " busy=" + busy);
            } catch (Exception e) {
                Probe.log("fgs_start", "exception=" + e);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Probe.log("fgs_destroy", "child_alive=" + (child != null && child.isAlive()));
        if (child != null) child.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
