package com.movecar.alert;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置管理：总开关 / 提醒间隔 / 最大次数 / 震动参数 / 倒计时时长 / 监控列表 / 弹窗图片
 */
public final class PrefsManager {

    public static final String BUILTIN_KEYWORD_12123 = "12123";
    /** 交管12123 官方 APP 包名 */
    public static final String BUILTIN_APP_12123 = "com.tmri.app";

    private static PrefsManager sInstance;
    private final SharedPreferences mPrefs;

    private PrefsManager(Context ctx) {
        mPrefs = ctx.getSharedPreferences("movecar_prefs", Context.MODE_PRIVATE);
    }

    public static void init(Context ctx) {
        if (sInstance == null) sInstance = new PrefsManager(ctx.getApplicationContext());
    }

    public static PrefsManager get() {
        if (sInstance == null) throw new IllegalStateException("PrefsManager not initialized");
        return sInstance;
    }

    // ---------- 监听服务总开关 ----------
    public boolean isServiceEnabled() {
        return mPrefs.getBoolean("service_enabled", true);
    }

    public void setServiceEnabled(boolean enabled) {
        mPrefs.edit().putBoolean("service_enabled", enabled).apply();
    }

    // ---------- 提醒间隔（分钟，默认 3） ----------
    public int getIntervalMinutes() {
        return clamp(mPrefs.getInt("interval_minutes", 3), 1, 120);
    }

    public void setIntervalMinutes(int v) {
        mPrefs.edit().putInt("interval_minutes", clamp(v, 1, 120)).apply();
    }

    // ---------- 最大提醒次数（默认 8） ----------
    public int getMaxRepeatCount() {
        return clamp(mPrefs.getInt("max_repeat_count", 8), 1, 50);
    }

    public void setMaxRepeatCount(int v) {
        mPrefs.edit().putInt("max_repeat_count", clamp(v, 1, 50)).apply();
    }

    // ---------- 震动参数 ----------
    /** 单次震动时长（毫秒，默认 600） */
    public int getVibrateDurationMs() {
        return clamp(mPrefs.getInt("vibrate_duration_ms", 600), 100, 5000);
    }

    public void setVibrateDurationMs(int v) {
        mPrefs.edit().putInt("vibrate_duration_ms", clamp(v, 100, 5000)).apply();
    }

    /** 震动间隔（毫秒，默认 400） */
    public int getVibrateGapMs() {
        return clamp(mPrefs.getInt("vibrate_gap_ms", 400), 50, 5000);
    }

    public void setVibrateGapMs(int v) {
        mPrefs.edit().putInt("vibrate_gap_ms", clamp(v, 50, 5000)).apply();
    }

    /** 震动重复次数（默认 4） */
    public int getVibrateRepeatCount() {
        return clamp(mPrefs.getInt("vibrate_repeat_count", 4), 1, 20);
    }

    public void setVibrateRepeatCount(int v) {
        mPrefs.edit().putInt("vibrate_repeat_count", clamp(v, 1, 20)).apply();
    }

    // ---------- 挪车倒计时时长（分钟，默认 10） ----------
    public int getCountdownMinutes() {
        return clamp(mPrefs.getInt("countdown_minutes", 10), 1, 120);
    }

    public void setCountdownMinutes(int v) {
        mPrefs.edit().putInt("countdown_minutes", clamp(v, 1, 120)).apply();
    }

    // ---------- 自定义监控：号码 / 联系人名字（短信匹配，内置 12123 不可删） ----------
    public List<String> getCustomKeywords() {
        return readList("custom_keywords");
    }

    public void setCustomKeywords(List<String> list) {
        writeList("custom_keywords", list);
    }

    /** 全部短信匹配关键词 = 内置 12123 + 自定义 */
    public List<String> getAllKeywords() {
        List<String> all = new ArrayList<>();
        all.add(BUILTIN_KEYWORD_12123);
        for (String s : getCustomKeywords()) {
            if (!all.contains(s)) all.add(s);
        }
        return all;
    }

    // ---------- 自定义监控 APP（包名，内置 交管12123 不可删） ----------
    public List<String> getCustomApps() {
        return readList("custom_apps");
    }

    public void setCustomApps(List<String> list) {
        writeList("custom_apps", list);
    }

    /** 全部监控 APP 包名 = 内置 12123 APP + 自定义 */
    public List<String> getAllApps() {
        List<String> all = new ArrayList<>();
        all.add(BUILTIN_APP_12123);
        for (String s : getCustomApps()) {
            if (!all.contains(s)) all.add(s);
        }
        return all;
    }

    // ---------- 自定义弹窗图片 ----------
    public String getAlertImagePath() {
        return mPrefs.getString("alert_image_path", "");
    }

    public void setAlertImagePath(String path) {
        mPrefs.edit().putString("alert_image_path", path == null ? "" : path).apply();
    }

    // ---------- 内部工具 ----------
    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(mPrefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void writeList(String key, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        mPrefs.edit().putString(key, arr.toString()).apply();
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static List<String> splitTrim(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("[,，;；\\n\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
