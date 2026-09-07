package com.movecar.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/应用更新后恢复监听保活 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (PrefsManager.get().isServiceEnabled()) {
                KeepAliveService.start(context);
            }
        }
    }
}
