package com.movecar.alert;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：监听总开关 / 运行时权限请求 / 连通状态实时检测（绿=正常 红=异常，可点击修复）
 * / 监听服务自动重连 / 一键测试。
 */
public class MainActivity extends AppCompatActivity {

    public static volatile int sResumed = 0;

    private static final int REQ_RUNTIME_PERMS = 10;

    private SwitchMaterial mMasterSwitch;
    private LinearLayout mStatusList;
    private TextView mSummary;

    /** 页面可见时每 2 秒自动刷新状态（授权回来后自动变绿） */
    private final Handler mRefresher = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mRefresher.postDelayed(this, 2000);
        }
    };
    /** 状态签名：不变则不重绘，避免干扰用户点击 */
    private String mLastSignature = "";

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMasterSwitch = findViewById(R.id.master_switch);
        mStatusList = findViewById(R.id.status_list);
        mSummary = findViewById(R.id.summary);

        mMasterSwitch.setChecked(PrefsManager.get().isServiceEnabled());
        mMasterSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PrefsManager.get().setServiceEnabled(checked);
            if (checked) {
                KeepAliveService.start(this);
            } else {
                KeepAliveService.stop(this);
                AlertSessionManager.get(this).stop();
            }
            VibrateHelper.vibrateTick(this);
            mLastSignature = ""; // 强制重绘
            refreshStatus();
        });

        findViewById(R.id.btn_test).setOnClickListener(v -> {
            VibrateHelper.vibrateTick(this);
            Toast.makeText(this, "模拟触发告警：弹窗 + 震动 + 重复调度已启动", Toast.LENGTH_SHORT).show();
            AlertSessionManager.get(this).start(
                    "一键测试",
                    "模拟挪车提醒",
                    "这是一键测试触发的模拟告警：弹窗、震动、重复提醒与倒计时均已启动，点击【停止提醒】可终止本轮。");
        });

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_history).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        findViewById(R.id.card_monitor).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btn_diag).setOnClickListener(v -> showDiagDialog());

        // 启动即请求全部运行时危险权限（短信 / 通讯录 / 通知）
        requestRuntimePermissions();
    }

    /** 请求运行时权限：READ_SMS / READ_CONTACTS / POST_NOTIFICATIONS */
    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_SMS);
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CONTACTS);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_RUNTIME_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME_PERMS) {
            mLastSignature = "";
            refreshStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumed++;
        mLastSignature = "";
        mRefresher.removeCallbacks(mRefreshTask);
        mRefresher.post(mRefreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sResumed = Math.max(0, sResumed - 1);
        mRefresher.removeCallbacks(mRefreshTask);
    }

    // ---------------- 监听服务重连 ----------------

    /** 促使系统重新绑定通知监听服务（授权后未连接时自动调用） */
    private void rebindListener(boolean showToast) {
        try {
            ComponentName cn = new ComponentName(this, NotificationListener.class);
            NotificationListener.requestRebind(cn);
            if (showToast) {
                Toast.makeText(this, "正在重新连接监听服务，稍候…", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            // 兜底：组件禁用再启用，强制系统重绑
            try {
                ComponentName cn = new ComponentName(this, NotificationListener.class);
                android.content.pm.PackageManager pm = getPackageManager();
                pm.setComponentEnabledSetting(cn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                pm.setComponentEnabledSetting(cn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {
            }
            if (showToast) {
                Toast.makeText(this, "请尝试：关闭再重新打开通知监听权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------- 状态检测 ----------------

    private static class Status {
        final String name;
        final boolean ok;
        final View.OnClickListener fix;

        Status(String name, boolean ok, View.OnClickListener fix) {
            this.name = name;
            this.ok = ok;
            this.fix = fix;
        }
    }

    private void refreshStatus() {
        PrefsManager p = PrefsManager.get();
        List<Status> statuses = new ArrayList<>();

        // 1. 通知监听权限（通道一）
        boolean listenerOk = NotificationListener.isListenerGranted(this);
        statuses.add(new Status("通知监听权限（通道一）", listenerOk, v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        // 2. 监听服务已连接；未连接时自动尝试重绑
        boolean connectedOk = listenerOk && NotificationListener.connected;
        if (listenerOk && !connectedOk) {
            rebindListener(false); // 静默自动重连
        }
        if (listenerOk) {
            statuses.add(new Status("监听服务连接", connectedOk, v -> rebindListener(true)));
        }

        // 3. 短信读取权限（通道二：短信直读）
        boolean smsOk = checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
        statuses.add(new Status("短信读取权限（通道二·直读）", smsOk, v ->
                requestPermissions(new String[]{Manifest.permission.READ_SMS}, REQ_RUNTIME_PERMS)));

        // 4. 通讯录权限（联系人名监控）
        boolean contactsOk = checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
        statuses.add(new Status("通讯录权限（联系人监控）", contactsOk, v ->
                requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_RUNTIME_PERMS)));

        // 5. 保活服务运行中
        boolean serviceOk = !p.isServiceEnabled() || KeepAliveService.running;
        statuses.add(new Status("保活服务运行", serviceOk, v -> {
            if (p.isServiceEnabled()) KeepAliveService.start(this);
        }));

        // 6. 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled();
            statuses.add(new Status("通知显示权限", notifOk, v ->
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        }

        // 7. 精确闹钟（Android 12+）
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            boolean exactOk = am != null && am.canScheduleExactAlarms();
            statuses.add(new Status("精确闹钟（准时重复提醒）", exactOk, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, "请在系统设置中允许精确闹钟", Toast.LENGTH_SHORT).show();
                }
            }));
        }

        // 8. 悬浮窗（后台强制弹窗）
        boolean overlayOk = Settings.canDrawOverlays(this);
        statuses.add(new Status("悬浮窗权限（锁屏强弹）", overlayOk, v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));

        // 9. 电池优化白名单
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        statuses.add(new Status("电池优化已忽略（后台存活）", batteryOk, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));

        // 10. 震动可用
        statuses.add(new Status("震动模块可用", VibrateHelper.hasVibrator(this), null));

        // 状态签名不变则只更新摘要，不重绘（避免干扰点击）
        StringBuilder sig = new StringBuilder();
        for (Status s : statuses) sig.append(s.ok ? '1' : '0');
        if (!sig.toString().equals(mLastSignature)) {
            mLastSignature = sig.toString();
            mStatusList.removeAllViews();
            for (Status s : statuses) addStatus(s);
        }

        // 摘要
        int apps = p.getAllApps().size();
        int kws = p.getAllKeywords().size();
        mSummary.setText(String.format(java.util.Locale.CHINA,
                "提醒间隔 %d 分钟 · 最多 %d 次 · 倒计时 %d 分钟\n监控 %d 个APP · %d 个号码/关键词 · 双通道监听",
                p.getIntervalMinutes(), p.getMaxRepeatCount(), p.getCountdownMinutes(), apps, kws));
    }

    private void addStatus(Status s) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_status, mStatusList, false);
        TextView name = v.findViewById(R.id.status_name);
        TextView state = v.findViewById(R.id.status_state);
        ImageView icon = v.findViewById(R.id.status_icon);
        name.setText(s.name);
        if (s.ok) {
            state.setText("正常");
            state.setTextColor(ContextCompat.getColor(this, R.color.status_ok));
            icon.setImageResource(R.drawable.ic_check);
            icon.setColorFilter(ContextCompat.getColor(this, R.color.status_ok));
            v.setClickable(false);
        } else {
            state.setText(s.fix == null ? "未连接" : "去修复");
            state.setTextColor(ContextCompat.getColor(this, R.color.status_bad));
            icon.setImageResource(R.drawable.ic_close);
            icon.setColorFilter(ContextCompat.getColor(this, R.color.status_bad));
            if (s.fix != null) {
                v.setOnClickListener(s.fix);
                v.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_status_bad));
            }
        }
        mStatusList.addView(v);
    }

    /** 显示运行日志（帮助定位通道是否正常） */
    private void showDiagDialog() {
        java.util.List<String> lines = DiagLog.readAll();
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) {
            sb.append("（暂无日志）\n\n请先：\n1. 把所有状态项授权为绿色\n2. 用 12123 或自定义号码发一条短信\n3. 或点「一键测试」触发一次\n再回来看日志，可判断是哪条通道没触发");
        } else {
            for (String l : lines) {
                sb.append(l).append('\n');
            }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(12);
        tv.setLineSpacing(0f, 1.15f);
        tv.setTextColor(0xFF1C1B1B);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setText(sb);
        sv.addView(tv);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("运行日志 · 调试用")
                .setMessage("每条日志展示哪条通道收到消息、是否命中、是否成功拉起弹窗。如未触发，可据此判断是 监听未收到 / 收到未命中 / 命中但无法弹窗。")
                .setView(sv)
                .setPositiveButton("清空日志", (d, w) -> {
                    DiagLog.clear();
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("关闭", null)
                .show();
    }
}
