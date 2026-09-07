package com.movecar.alert;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * 悬浮窗兜底告警（解决 Android 10+ 后台启动 Activity 被限制、以及部分厂商阉割 FullScreenIntent 的问题）。
 * 优先使用 TYPE_APPLICATION_OVERLAY 显示全屏悬浮窗，确保任何状态（锁屏除外，锁屏交给 FullScreenIntent）都能强制弹窗。
 * 若未授予悬浮窗权限：静默失败（不影响其他两条路径）。
 * 全程零音频：仅震动。
 */
public class OverlayAlertService extends Service {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private WindowManager mWM;
    private View mRoot;
    private CountDownTimer mCountdown;
    private Runnable mStopTick;
    private final android.os.Handler mMain = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 尝试显示悬浮窗弹窗；失败返回 false */
    public static boolean showIfPossible(Context ctx, long sessionId) {
        try {
            if (Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(ctx)) {
                return false;
            }
            Intent i = new Intent(ctx, OverlayAlertService.class);
            i.putExtra(EXTRA_SESSION_ID, sessionId);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWM = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // 前台服务（后台启动需要），使用低可见性通知（保活通知复用即可，这里 startForeground 即可）
        android.app.NotificationChannel ch;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ch = new android.app.NotificationChannel("channel_overlay", "强制弹窗",
                    android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            ch.enableVibration(false);
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        androidx.core.app.NotificationCompat.Builder nb =
                new androidx.core.app.NotificationCompat.Builder(this, "channel_overlay")
                        .setSmallIcon(R.drawable.ic_notif)
                        .setContentTitle("挪车提醒 · 强制弹窗运行中")
                        .setContentIntent(pi)
                        .setSound(null)
                        .setVibrate(null)
                        .setOngoing(true);
        startForeground(10003, nb.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mRoot != null) {
            try { mWM.removeView(mRoot); } catch (Throwable ignored) {}
            mRoot = null;
            if (mCountdown != null) { mCountdown.cancel(); mCountdown = null; }
            if (mStopTick != null) { mMain.removeCallbacks(mStopTick); mStopTick = null; }
        }

        AlertSessionManager.Session s = AlertSessionManager.get(this).current();
        if (s == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showView(s);
        return START_NOT_STICKY;
    }

    private void showView(final AlertSessionManager.Session s) {
        try {
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;

            mRoot = LayoutInflater.from(this).inflate(R.layout.activity_alert,
                    new FrameLayout(this), false);

            // 全屏点击穿透区域外行为：空白区响应点击不退出（需点停止按钮才退出）
            mRoot.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return false; // 让子控件处理
                }
            });

            TextView countdown = mRoot.findViewById(R.id.countdown);
            TextView timeUp = mRoot.findViewById(R.id.time_up);
            TextView source = mRoot.findViewById(R.id.source);
            TextView title = mRoot.findViewById(R.id.title);
            TextView content = mRoot.findViewById(R.id.content);
            TextView count = mRoot.findViewById(R.id.count_label);
            ImageView img = mRoot.findViewById(R.id.alert_image);
            View btnStop = mRoot.findViewById(R.id.btn_stop);

            source.setText(s.source);
            title.setText(s.title == null || s.title.isEmpty() ? "收到挪车提醒" : s.title);
            content.setText(s.content == null ? "" : s.content);
            count.setText(String.format(Locale.CHINA, "第 %d 次提醒",
                    Math.min(s.triggerCount, PrefsManager.get().getMaxRepeatCount())));

            // 自定义图片
            String path = PrefsManager.get().getAlertImagePath();
            if (path != null && !path.isEmpty() && new File(path).exists()) {
                try { img.setImageBitmap(BitmapFactory.decodeFile(path)); }
                catch (Throwable ignored) { img.setImageResource(R.drawable.ic_car_big); }
            } else {
                img.setImageResource(R.drawable.ic_car_big);
            }

            // 背景
            try {
                mRoot.setBackgroundColor(0xFF7F1010);
                View bg = mRoot.findViewById(android.R.id.content);
            } catch (Throwable ignored) {}
            try {
                mRoot.findViewById(R.id.source)
                        .setBackgroundColor(Color.parseColor("#33FFFFFF"));
            } catch (Throwable ignored) {}

            final TextView cdl = countdown;
            final TextView tup = timeUp;
            final TextView cl = count;

            // 倒计时
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    AlertSessionManager.Session cur = AlertSessionManager.get(OverlayAlertService.this).current();
                    if (cur == null) {
                        close();
                        return;
                    }
                    long remain = cur.countdownEndAt - System.currentTimeMillis();
                    if (remain <= 0 || cur.timeUp) {
                        cdl.setText("00:00");
                        tup.setVisibility(View.VISIBLE);
                    } else {
                        tup.setVisibility(View.GONE);
                        long ts = remain / 1000;
                        cdl.setText(String.format(Locale.CHINA, "%02d:%02d", ts / 60, ts % 60));
                    }
                    cl.setText(String.format(Locale.CHINA, "第 %d 次提醒",
                            Math.min(cur.triggerCount, PrefsManager.get().getMaxRepeatCount())));
                    mStopTick = this;
                    mMain.postDelayed(this, 500);
                }
            };
            mMain.post(tick);

            btnStop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    vibrateTick();
                    AlertSessionManager.get(OverlayAlertService.this).stop();
                    close();
                }
            });

            mWM.addView(mRoot, lp);
        } catch (Throwable t) {
            close();
        }
    }

    private void vibrateTick() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {}
    }

    private void close() {
        if (mStopTick != null) { mMain.removeCallbacks(mStopTick); mStopTick = null; }
        if (mRoot != null) {
            try { mWM.removeView(mRoot); } catch (Throwable ignored) {}
            mRoot = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
