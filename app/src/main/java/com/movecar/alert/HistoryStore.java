package com.movecar.alert;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警历史：本地存储最近 30 条（时间 / 来源 / 内容片段）
 */
public final class HistoryStore {

    public static final int MAX_ITEMS = 30;

    public static class Item {
        public final long time;
        public final String source;
        public final String content;

        public Item(long time, String source, String content) {
            this.time = time;
            this.source = source;
            this.content = content;
        }
    }

    private static HistoryStore sInstance;
    private final SharedPreferences mPrefs;

    private HistoryStore(Context ctx) {
        mPrefs = ctx.getSharedPreferences("movecar_history", Context.MODE_PRIVATE);
    }

    public static void init(Context ctx) {
        if (sInstance == null) sInstance = new HistoryStore(ctx.getApplicationContext());
    }

    public static HistoryStore get() {
        if (sInstance == null) throw new IllegalStateException("HistoryStore not initialized");
        return sInstance;
    }

    /** 新增一条记录，自动截断为最近 30 条（最新的在最前） */
    public synchronized void add(String source, String content) {
        try {
            JSONArray arr = new JSONArray(mPrefs.getString("history", "[]"));
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("source", source == null ? "" : source);
            o.put("content", content == null ? "" : content);
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < MAX_ITEMS; i++) out.put(arr.get(i));
            mPrefs.edit().putString("history", out.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public synchronized List<Item> getAll() {
        List<Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(mPrefs.getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Item(o.optLong("time"), o.optString("source"), o.optString("content")));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public synchronized void clear() {
        mPrefs.edit().putString("history", "[]").apply();
    }
}
