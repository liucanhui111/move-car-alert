package com.movecar.alert;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PrefsManager.init(this);
        HistoryStore.init(this);
        DiagLog.init(this);
        DiagLog.d("App", "进程启动");
        // 若总开关开启，启动保活前台服务（无任何音频）
        if (PrefsManager.get().isServiceEnabled()) {
            KeepAliveService.start(this);
        }
    }
}
