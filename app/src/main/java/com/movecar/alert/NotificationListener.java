package com.movecar.alert;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.core.app.NotificationManagerCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知监听（双保险通道之一）：
 * 1) 系统短信通知：发件人（通知标题）包含连续数字 12123 → 触发；
 *    自定义号码/联系人名：标题包含该关键词 → 触发；
 * 2) 监控 APP 推送：通知来自监控列表内的应用（内置交管12123 APP，可自定义）→ 触发。
 * 触发后：记录历史 + 全屏弹窗 + 震动（零音频）。
 * 与 SmsObserver（短信直读）并行，经 DedupHelper 去重防止重复触发。
 */
public class NotificationListener extends NotificationListenerService {

    /** 常见系统短信应用包名（兜底，优先使用默认短信应用判断） */
    private static final Set<String> SMS_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.mms",
            "com.android.mms.service",
            "com.google.android.apps.messaging",
            "com.sonyericsson.conversations",
            "com.android.sms",
            "com.huawei.mms",
            "com.samsung.android.messaging"
    ));

    /** 监听服务是否已连接（系统绑定成功） */
    public static volatile boolean connected = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        DiagLog.init(this);
        DiagLog.d("NL", "通知监听服务已连接");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        DiagLog.d("NL", "通知监听服务断开");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (!PrefsManager.get().isServiceEnabled()) return;

        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        Notification n = sbn.getNotification();
        if (n == null) return;

        Bundle extras = n.extras;
        if (extras == null) return;

        String title = cs(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = cs(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = cs(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = !TextUtils.isEmpty(bigText) ? bigText : text;

        boolean smsApp = isSmsApp(pkg);
        DiagLog.d("NL", "收到 pkg=" + pkg + " sms=" + smsApp + " title=" + title);

        // 常驻类通知（前台服务/进行中）仅在短信应用时参与匹配
        if (!smsApp && (n.flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return;
        }

        // ---------- 匹配规则 1：监控 APP 推送 ----------
        List<String> monitoredApps = PrefsManager.get().getAllApps();
        if (monitoredApps.contains(pkg)) {
            String label = pkg.equals(PrefsManager.BUILTIN_APP_12123)
                    ? "12123 APP" : "APP：" + appLabel(pkg);
            if (DedupHelper.tryTrigger("app:" + pkg, body)) {
                DiagLog.d("NL", "APP命中 触发告警 label=" + label);
                trigger(label, title, body);
            } else {
                DiagLog.d("NL", "APP命中但去重已触发，跳过");
            }
            return;
        }

        // ---------- 匹配规则 2：系统短信 + 发件人匹配 ----------
        if (smsApp) {
            List<String> keywords = PrefsManager.get().getAllKeywords();
            // 优先匹配发件人（通知标题）；标题为空时回退匹配正文（部分短信应用把号码放正文）
            String senderField = !TextUtils.isEmpty(title) ? title : text;
            if (senderField == null) return;
            for (String kw : keywords) {
                if (kw != null && !kw.isEmpty() && senderField.contains(kw)) {
                    String label = kw.equals(PrefsManager.BUILTIN_KEYWORD_12123)
                            ? "12123 短信" : "短信：" + kw;
                    if (DedupHelper.tryTrigger("sms", body)) {
                        DiagLog.d("NL", "短信命中 kw=" + kw + " 触发告警");
                        trigger(label, title, body);
                    } else {
                        DiagLog.d("NL", "短信命中但去重已触发，跳过");
                    }
                    return;
                }
            }
        }
    }

    private void trigger(String label, String title, String body) {
        String content = body == null ? "" : body;
        AlertSessionManager.get(this).start(label,
                title == null ? "" : title,
                content.length() > 120 ? content.substring(0, 120) + "…" : content);
    }

    private boolean isSmsApp(String pkg) {
        String def = Telephony.Sms.getDefaultSmsPackage(this);
        return pkg.equals(def) || SMS_PACKAGES.contains(pkg);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String cs(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    /** 通知监听权限是否已授予 */
    public static boolean isListenerGranted(Context ctx) {
        return NotificationManagerCompat.getEnabledListenerPackages(ctx)
                .contains(ctx.getPackageName());
    }
}
