package com.movecar.alert;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/**
 * 告警会话管理：
 * - 触发瞬间启动全屏弹窗（三重兜底：悬浮窗 → Activity → FullScreenIntent）
 * - 未确认时按设定间隔重复（默认 3 分钟），达到最大次数（默认 8 次）自动停止
 * - 告警触发瞬间启动倒计时（默认 10 分钟），结束时强震动 + 标注「时间已到」
 * - 【停止提醒】立即终止本轮全部
 * 全程零音频：无任何铃声/提示音/媒体播放代码。
 */
public final class AlertSessionManager {

    public static final String ACTION_REPEAT = "com.movecar.alert.ACTION_REPEAT";
    public static final String ACTION_COUNTDOWN_END = "com.movecar.alert.ACTION_COUNTDOWN_END";
    public static final String ACTION_STOP = "com.movecar.alert.ACTION_STOP";

    private static final String CHANNEL_FULLSCREEN = "channel_fullscreen";
    private static final int NOTIF_ID = 10001;

    private static AlertSessionManager sInstance;

    public static synchronized AlertSessionManager get(Context ctx) {
        if (sInstance == null) sInstance = new AlertSessionManager(ctx.getApplicationContext());
        return sInstance;
    }

    public static class Session {
        public final long id;
        public final String source;
        public final String title;
        public final String content;
        public final long startedAt;
        public final long countdownEndAt;
        public int triggerCount;
        public boolean stopped;
        public boolean timeUp;

        Session(long id, String source, String title, String content,
                long startedAt, long countdownEndAt) {
            this.id = id;
            this.source = source;
            this.title = title;
            this.content = content;
            this.startedAt = startedAt;
            this.countdownEndAt = countdownEndAt;
        }
    }

    private final Context mApp;
    private Session mCurrent;

    private AlertSessionManager(Context app) {
        mApp = app;
    }

    public Session current() {
        return mCurrent;
    }

    public boolean isActive() {
        Session s = mCurrent;
        return s != null && !s.stopped && s.triggerCount <= PrefsManager.get().getMaxRepeatCount();
    }

    /** 触发一次新告警（真实通知或一键测试都走这里） */
    public synchronized void start(String source, String title, String content) {
        PrefsManager p = PrefsManager.get();
        long now = System.currentTimeMillis();

        mCurrent = new Session(System.nanoTime(), source, title, content,
                now, now + p.getCountdownMinutes() * 60_000L);
        mCurrent.triggerCount = 1;

        HistoryStore.get().add(source, title + "｜" + content);
        DiagLog.d("ALERT", "触发新告警 来源=" + source + " 标题=" + title);

        // 三重兜底 + 震动
        showAlert(mCurrent);
        VibrateHelper.vibratePattern(mApp);

        // 调度下一次重复
        scheduleNext(mCurrent);
        // 调度倒计时结束
        scheduleCountdownEnd(mCurrent);
    }

    /** 间隔到：重复提醒 */
    public synchronized void onRepeatFired() {
        Session s = mCurrent;
        if (s == null || s.stopped) return;
        PrefsManager p = PrefsManager.get();
        s.triggerCount++;
        if (s.triggerCount > p.getMaxRepeatCount()) {
            DiagLog.d("ALERT", "达到最大提醒次数 " + p.getMaxRepeatCount() + "，自动停止本轮");
            mCurrent = null;
            cancelAlarms();
            return;
        }
        DiagLog.d("ALERT", "重复提醒 # " + s.triggerCount);
        showAlert(s);
        VibrateHelper.vibratePattern(mApp);
        scheduleNext(s);
    }

    /** 倒计时结束：强震动 + 弹窗标注「时间已到」 */
    public synchronized void onCountdownEnd() {
        Session s = mCurrent;
        if (s == null || s.stopped || s.timeUp) return;
        s.timeUp = true;
        DiagLog.d("ALERT", "倒计时结束，强震动");
        showAlert(s);
        VibrateHelper.vibrateStrong(mApp);
    }

    /** 用户点击【停止提醒】：立即终止本轮全部 */
    public synchronized void stop() {
        Session s = mCurrent;
        if (s != null) s.stopped = true;
        DiagLog.d("ALERT", "本轮告警已停止");
        mCurrent = null;
        cancelAlarms();
        NotificationManager nm = (NotificationManager) mApp.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
        Intent i = new Intent(mApp, AlertActivity.class);
        i.setAction(ACTION_STOP);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            mApp.startActivity(i);
        } catch (Exception e) {
            DiagLog.e("ALERT", "stop时关AlertActivity失败", e);
        }
    }

    // ---------------- 弹窗展示（三重兜底） ----------------

    private void showAlert(Session s) {
        wakeUpScreen();

        Intent i = new Intent(mApp, AlertActivity.class);
        i.putExtra("sessionId", s.id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        boolean launched = false;

        // ----- 兜底 1：悬浮窗（TYPE_APPLICATION_OVERLAY）—— 后台稳定强弹 -----
        boolean overlayOk = false;
        try {
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(mApp)) {
                overlayOk = OverlayAlertService.showIfPossible(mApp, s.id);
                DiagLog.d("ALERT", "悬浮窗启动=" + overlayOk);
                launched |= overlayOk;
            } else {
                DiagLog.d("ALERT", "悬浮窗权限未授予，跳过");
            }
        } catch (Throwable t) {
            DiagLog.e("ALERT", "悬浮窗异常", t);
        }

        // ----- 兜底 2：直接 startActivity（应用在前台/有"后台弹出界面"权限/厂商放行时生效）-----
        try {
            mApp.startActivity(i);
            launched = true;
            DiagLog.d("ALERT", "直接startActivity成功");
        } catch (Throwable t) {
            DiagLog.e("ALERT", "startActivity失败（后台限制）", t);
        }

        // ----- 兜底 3：全屏通知 FullScreenIntent（锁屏/熄屏场景） -----
        sendFullScreenNotification(s);
        DiagLog.d("ALERT", "FullScreenIntent已发出；launched=" + launched);
    }

    private void sendFullScreenNotification(Session s) {
        NotificationManager nm = (NotificationManager) mApp.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL_FULLSCREEN,
                    "挪车全屏告警", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setImportance(NotificationManager.IMPORTANCE_MAX);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }

        PendingIntent fullScreen = PendingIntent.getActivity(
                mApp, 0,
                new Intent(mApp, AlertActivity.class).putExtra("sessionId", s.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntentFlags());

        String body = (s.title == null || s.title.isEmpty() ? "" : s.title + "\n") + s.content;

        NotificationCompat.Builder b = new NotificationCompat.Builder(mApp, CHANNEL_FULLSCREEN)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("挪车提醒 · " + s.source)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSound(null)
                .setVibrate(null);
        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            DiagLog.e("ALERT", "notify失败", t);
        }
    }

    private int PendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    // ---------------- 闹钟调度 ----------------

    private void scheduleNext(Session s) {
        PrefsManager p = PrefsManager.get();
        long at = System.currentTimeMillis() + p.getIntervalMinutes() * 60_000L;
        setExact(alarmIntent(ACTION_REPEAT), at);
    }

    private void scheduleCountdownEnd(Session s) {
        setExact(alarmIntent(ACTION_COUNTDOWN_END), s.countdownEndAt);
    }

    private PendingIntent alarmIntent(String action) {
        Intent i = new Intent(mApp, AlarmReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(mApp, action.hashCode(), i, PendingIntentFlags());
    }

    private void setExact(PendingIntent pi, long at) {
        AlarmManager am = (AlarmManager) mApp.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                DiagLog.d("ALERT", "setExact不可用，降级setAndAllowWhileIdle");
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            DiagLog.e("ALERT", "alarm调度失败", t);
        }
    }

    private void cancelAlarms() {
        AlarmManager am = (AlarmManager) mApp.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(alarmIntent(ACTION_REPEAT));
            am.cancel(alarmIntent(ACTION_COUNTDOWN_END));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 辅助 ----------------

    private void wakeUpScreen() {
        try {
            PowerManager pm = (PowerManager) mApp.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "movecar:alert");
                wl.acquire(5000);
                wl.release();
            }
        } catch (Throwable t) {
            DiagLog.e("ALERT", "wakeUp失败", t);
        }
    }
}
