package com.movecar.alert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 保活前台服务：
 * - 显示"监听运行中"常驻通知（无声音、无震动，纯状态展示）；
 * - 托管短信直读观察者（SmsObserver），服务存活期间持续监听收件箱。
 */
public class KeepAliveService extends Service {

    public static volatile boolean running = false;
    private static final String CHANNEL_ID = "channel_service";
    private static final int NOTIF_ID = 10002;

    private SmsObserver mSmsObserver;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            ctx.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSmsObserver = new SmsObserver(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "监听服务状态", NotificationManager.IMPORTANCE_MIN);
        ch.setSound(null, null); // 零音频
        ch.setShowBadge(false);
        if (nm != null) nm.createNotificationChannel(ch);

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("挪车提醒 · 监听运行中")
                .setContentText("正在监控 12123 及自定义来源（仅震动与弹窗提醒）")
                .setOngoing(true)
                .setContentIntent(pi)
                .setSound(null)
                .setVibrate(null)
                .build();

        running = true;
        startForeground(NOTIF_ID, n);
        if (PrefsManager.get().isServiceEnabled()) {
            // 短信直读通道：随服务常驻注册
            mSmsObserver.register();
            // 促使系统重新绑定通知监听（开机自启/进程重启后自动恢复连接）
            try {
                NotificationListener.requestRebind(
                        new android.content.ComponentName(this, NotificationListener.class));
            } catch (Throwable ignored) {
            }
        } else {
            mSmsObserver.unregister();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (mSmsObserver != null) mSmsObserver.unregister();
    }
}
