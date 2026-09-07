package com.movecar.alert;

import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/**
 * 全屏告警页：
 * - 锁屏直接显示、自动点亮屏幕并保持常亮
 * - 实时显示倒计时剩余时间（分:秒）
 * - 倒计时结束标注「时间已到」
 * - 【停止提醒】立即终止本轮全部（弹窗 + 震动 + 调度）
 * 全程零音频。
 */
public class AlertActivity extends AppCompatActivity {

    public static volatile int sResumed = 0;

    private final Handler mTick = new Handler(Looper.getMainLooper());
    private TextView mCountdown;
    private TextView mTimeUp;
    private TextView mSource;
    private TextView mTitle;
    private TextView mContent;
    private TextView mCountLabel;
    private ImageView mImage;
    private View mStopBtn;

    private AlertSessionManager.Session mSession;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            updateCountdown();
            mTick.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏显示 + 点亮屏幕 + 保持常亮（零音频：无任何铃声）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_alert);

        mCountdown = findViewById(R.id.countdown);
        mTimeUp = findViewById(R.id.time_up);
        mSource = findViewById(R.id.source);
        mTitle = findViewById(R.id.title);
        mContent = findViewById(R.id.content);
        mCountLabel = findViewById(R.id.count_label);
        mImage = findViewById(R.id.alert_image);
        mStopBtn = findViewById(R.id.btn_stop);

        mStopBtn.setOnClickListener(v -> {
            VibrateHelper.vibrateTick(this);
            AlertSessionManager.get(this).stop();
            finish();
        });

        bindSession(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (AlertSessionManager.ACTION_STOP.equals(intent.getAction())) {
            finish();
            return;
        }
        bindSession(intent);
    }

    private void bindSession(android.content.Intent intent) {
        AlertSessionManager.Session s = AlertSessionManager.get(this).current();
        // 页面可能晚于会话启动（fullScreenIntent），current() 即最新会话
        mSession = s;
        if (s == null) {
            finish();
            return;
        }
        mSource.setText(s.source);
        mTitle.setText(s.title == null || s.title.isEmpty() ? "收到挪车提醒" : s.title);
        mContent.setText(s.content == null ? "" : s.content);
        mCountLabel.setText(String.format(Locale.CHINA, "第 %d 次提醒",
                Math.min(s.triggerCount, PrefsManager.get().getMaxRepeatCount())));
        loadImage();
        updateCountdown();
    }

    private void loadImage() {
        String path = PrefsManager.get().getAlertImagePath();
        if (path != null && !path.isEmpty() && new File(path).exists()) {
            try {
                mImage.setImageBitmap(BitmapFactory.decodeFile(path));
                mImage.setVisibility(View.VISIBLE);
                return;
            } catch (Exception ignored) {
            }
        }
        mImage.setImageResource(R.drawable.ic_car_big);
    }

    private void updateCountdown() {
        AlertSessionManager.Session s = mSession;
        if (s == null) {
            mCountdown.setText("00:00");
            mTimeUp.setVisibility(View.VISIBLE);
            return;
        }
        long remain = s.countdownEndAt - System.currentTimeMillis();
        if (remain <= 0 || s.timeUp) {
            mCountdown.setText("00:00");
            mTimeUp.setVisibility(View.VISIBLE);
        } else {
            mTimeUp.setVisibility(View.GONE);
            long totalSec = remain / 1000;
            mCountdown.setText(String.format(Locale.CHINA, "%02d:%02d",
                    totalSec / 60, totalSec % 60));
        }
        mCountLabel.setText(String.format(Locale.CHINA, "第 %d 次提醒",
                Math.min(s.triggerCount, PrefsManager.get().getMaxRepeatCount())));
    }

    @Override
    protected void onResume() {
        super.onResume();
        sResumed++;
        // 沉浸式全屏
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mTick.post(mTicker);
        // 通知可清除（弹窗已展示）
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(10001);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sResumed = Math.max(0, sResumed - 1);
        mTick.removeCallbacks(mTicker);
    }

    @Override
    public void onBackPressed() {
        // 返回键不停止本轮（需点【停止提醒】才终止），仅退出页面
        super.onBackPressed();
    }
}
