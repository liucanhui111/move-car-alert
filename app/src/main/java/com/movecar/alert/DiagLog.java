package com.movecar.alert;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轻量本地运行日志：记录每个通道的触发/拦截/异常，帮助用户定位"为什么没弹窗"。
 * 保留最近 200 行，存储在 files/diag.log。
 */
public final class DiagLog {

    private static final int MAX_LINES = 200;
    private static File sFile;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    public static void init(Context ctx) {
        if (sFile == null) {
            sFile = new File(ctx.getApplicationContext().getFilesDir(), "diag.log");
        }
    }

    public static synchronized void d(String tag, String msg) {
        write("[" + tag + "] " + msg);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        write("[" + tag + "][ERR] " + msg + (t == null ? "" : " :: " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "")));
    }

    public static synchronized List<String> readAll() {
        if (sFile == null || !sFile.exists()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(sFile, "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                String dec = new String(line.getBytes("ISO-8859-1"), "UTF-8");
                if (!dec.isEmpty()) out.add(dec);
            }
        } catch (Throwable ignored) {
        }
        Collections.reverse(out); // 最新在前
        return out;
    }

    public static synchronized void clear() {
        if (sFile != null && sFile.exists()) sFile.delete();
    }

    private static void write(String line) {
        try {
            if (sFile == null) return;
            String ts = FMT.format(new Date());
            String full = ts + " " + line + "\n";
            // 读取现有
            List<String> existing = new ArrayList<>();
            if (sFile.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(sFile, "r")) {
                    String l;
                    while ((l = raf.readLine()) != null) {
                        String dec = new String(l.getBytes("ISO-8859-1"), "UTF-8");
                        if (!dec.isEmpty()) existing.add(dec);
                    }
                }
            }
            existing.add(full);
            while (existing.size() > MAX_LINES) existing.remove(0);
            try (FileOutputStream fos = new FileOutputStream(sFile, false)) {
                for (String l : existing) fos.write(l.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
