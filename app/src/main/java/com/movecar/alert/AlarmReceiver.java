package com.movecar.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 精确闹钟接收：重复提醒 / 倒计时结束。
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        AlertSessionManager m = AlertSessionManager.get(context);
        switch (intent.getAction()) {
            case AlertSessionManager.ACTION_REPEAT:
                m.onRepeatFired();
                break;
            case AlertSessionManager.ACTION_COUNTDOWN_END:
                m.onCountdownEnd();
                break;
            default:
        }
    }
}
