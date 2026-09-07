package com.movecar.alert;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 震动控制：所有提醒仅通过震动表达（零音频，不使用任何铃声/提示音/媒体播放）
 */
public final class VibrateHelper {

    private VibrateHelper() {
    }

    /** 设备是否支持震动 */
    public static boolean hasVibrator(Context ctx) {
        Vibrator v = vibrator(ctx);
        return v != null && v.hasVibrator();
    }

    private static Vibrator vibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * 按用户参数执行一轮模式震动：
     * 单次震动时长、震动间隔、震动重复次数。
     */
    public static void vibratePattern(Context ctx) {
        Vibrator v = vibrator(ctx);
        if (v == null || !v.hasVibrator()) return;
        PrefsManager p = PrefsManager.get();

        long single = p.getVibrateDurationMs();
        long gap = p.getVibrateGapMs();
        int repeat = p.getVibrateRepeatCount();

        long[] timings = new long[repeat * 2];
        for (int i = 0; i < repeat; i++) {
            timings[i * 2] = single;
            timings[i * 2 + 1] = gap;
        }
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, -1));
        } catch (Exception ignored) {
        }
    }

    /**
     * 倒计时结束的强震动：使用用户参数但振幅拉满、次数翻倍。
     */
    public static void vibrateStrong(Context ctx) {
        Vibrator v = vibrator(ctx);
        if (v == null || !v.hasVibrator()) return;
        PrefsManager p = PrefsManager.get();

        long single = Math.min(p.getVibrateDurationMs() * 2, 4000);
        long gap = p.getVibrateGapMs();
        int repeat = Math.min(p.getVibrateRepeatCount() * 2, 20);

        long[] timings = new long[repeat * 2];
        int[] amplitudes = new int[repeat * 2];
        for (int i = 0; i < repeat; i++) {
            timings[i * 2] = single;
            timings[i * 2 + 1] = gap;
            amplitudes[i * 2] = 255;
            amplitudes[i * 2 + 1] = 0;
        }
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
        } catch (Exception ignored) {
            try {
                v.vibrate(VibrationEffect.createWaveform(timings, -1));
            } catch (Exception ignored2) {
            }
        }
    }

    /** 短震动（界面操作反馈） */
    public static void vibrateTick(Context ctx) {
        Vibrator v = vibrator(ctx);
        if (v == null || !v.hasVibrator()) return;
        try {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }
}
