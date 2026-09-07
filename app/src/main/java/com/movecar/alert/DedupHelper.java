package com.movecar.alert;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨通道触发去重：
 * 通知监听（NotificationListener）与短信直读（SmsObserver）双保险并行，
 * 同一条短信只会触发一次告警。
 */
public final class DedupHelper {

    /** 去重时间窗口：15 秒内相同内容视为同一条消息 */
    private static final long WINDOW_MS = 15_000L;
    private static final Map<String, Long> sRecent = new HashMap<>();

    private DedupHelper() {
    }

    /**
     * @param channel 通道标识（"sms" / "app:包名"）
     * @param content 消息正文
     * @return true = 允许触发；false = 15 秒内已触发过（重复）
     */
    public static synchronized boolean tryTrigger(String channel, String content) {
        String key = channel + "#" + (content == null ? "" : content);
        long now = System.currentTimeMillis();
        Long last = sRecent.get(key);
        if (last != null && now - last < WINDOW_MS) return false;
        sRecent.put(key, now);
        if (sRecent.size() > 300) sRecent.clear();
        return true;
    }
}
