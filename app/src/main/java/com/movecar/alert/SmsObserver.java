package com.movecar.alert;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * 短信直读观察者（双保险通道之二，需 READ_SMS 权限）：
 * 监听系统收件箱内容变化，直接读取新短信并匹配发件人（号码或联系人名）。
 * - 与通知监听（NotificationListener）并行运行；
 * - 通过 DedupHelper 去重，同一封短信两个通道只会触发一次告警；
 * - 联系人名匹配依赖 READ_CONTACTS 权限（ContactsHelper 解析）。
 * 全程零音频：触发后仅全屏弹窗 + 震动。
 */
public class SmsObserver extends ContentObserver {

    private final Context mApp;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** 已处理短信的时间水位 */
    private long mLastSeen = System.currentTimeMillis();
    private boolean mRegistered = false;

    public SmsObserver(Context ctx) {
        super(new Handler(Looper.getMainLooper()));
        mApp = ctx.getApplicationContext();
    }

    public void register() {
        if (mRegistered) return;
        mRegistered = true;
        mLastSeen = System.currentTimeMillis();
        DiagLog.init(mApp);
        try {
            mApp.getContentResolver().registerContentObserver(
                    Uri.parse("content://sms"), true, this);
            DiagLog.d("SMS", "短信直读观察者已注册");
        } catch (Exception e) {
            DiagLog.e("SMS", "注册失败", e);
        }
    }

    public void unregister() {
        if (!mRegistered) return;
        mRegistered = false;
        try {
            mApp.getContentResolver().unregisterContentObserver(this);
            DiagLog.d("SMS", "短信直读观察者已注销");
        } catch (Exception e) {
            DiagLog.e("SMS", "注销失败", e);
        }
    }

    @Override
    public void onChange(boolean selfChange) {
        // 短信入库有延迟，稍等再查，避免查不到最新一条
        mMain.removeCallbacks(mQuery);
        mMain.postDelayed(mQuery, 400);
    }

    private final Runnable mQuery = this::queryLatest;

    private void queryLatest() {
        if (!mRegistered || !PrefsManager.get().isServiceEnabled()) return;
        try (Cursor c = mApp.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                "date > ?",
                new String[]{String.valueOf(mLastSeen - 1500)},
                "date DESC")) {
            if (c == null) {
                DiagLog.d("SMS", "查询返回空游标（可能无 READ_SMS 权限）");
                return;
            }
            DiagLog.d("SMS", "收件箱变化 新条数=" + c.getCount());
            while (c.moveToNext()) {
                String address = c.getString(0);
                String body = c.getString(1);
                long date = c.getLong(2);
                if (date > mLastSeen) mLastSeen = date;
                if (address == null || address.isEmpty() || body == null || body.isEmpty()) continue;
                match(address, body);
            }
        } catch (SecurityException e) {
            DiagLog.e("SMS", "无READ_SMS权限", e);
        } catch (Exception e) {
            DiagLog.e("SMS", "查询异常", e);
        }
    }

    /** 发件人匹配：内置 12123 + 自定义号码/联系人名 */
    private void match(String address, String body) {
        List<String> kws = PrefsManager.get().getAllKeywords();
        String normAddr = ContactsHelper.normalizePhone(address);

        for (String kw : kws) {
            if (kw == null || kw.isEmpty()) continue;

            String normKw = ContactsHelper.normalizePhone(kw);
            boolean kwIsNumber = normKw.matches("\\d{3,}");

            boolean match;
            if (kwIsNumber) {
                // 号码关键词：地址包含、或尾号双向匹配（处理 +86 前缀差异）
                match = normAddr.contains(normKw)
                        || (normKw.length() >= 7 && normAddr.endsWith(normKw))
                        || (normAddr.length() >= 7 && normKw.endsWith(normAddr));
            } else {
                // 联系人名关键词：通过通讯录把名字解析为号码再匹配
                match = ContactsHelper.phoneBelongsToContact(mApp, kw, address);
            }

            if (match) {
                // 与通知监听通道去重（同一封短信只触发一次）
                if (DedupHelper.tryTrigger("sms", body)) {
                    DiagLog.d("SMS", "命中 kw=" + kw + " addr=" + address + " 触发告警");
                    String label = kw.equals(PrefsManager.BUILTIN_KEYWORD_12123)
                            ? "12123 短信" : "短信：" + kw;
                    String clip = body.length() > 120 ? body.substring(0, 120) + "…" : body;
                    AlertSessionManager.get(mApp).start(label, address, clip);
                } else {
                    DiagLog.d("SMS", "命中 kw=" + kw + " 但去重已触发，跳过");
                }
                return;
            }
        }
    }
}
