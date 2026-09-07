package com.movecar.alert;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * 通讯录辅助：联系人名 ↔ 号码 解析（需 READ_CONTACTS 权限，无权限时静默返回空）。
 * 用于"监控联系人名字"场景：收到短信时把发件号码反查为联系人再匹配。
 */
public final class ContactsHelper {

    private ContactsHelper() {
    }

    /** 号码标准化：去空格/横线/括号，去 +86 / 86 前缀 */
    public static String normalizePhone(String s) {
        if (s == null) return "";
        String r = s.replaceAll("[\\s\\-()]", "");
        if (r.startsWith("+86")) r = r.substring(3);
        else if (r.startsWith("86") && r.length() > 11) r = r.substring(2);
        return r;
    }

    /** 发件号码是否属于指定名字的联系人 */
    public static boolean phoneBelongsToContact(Context ctx, String name, String address) {
        String normAddr = normalizePhone(address);
        if (normAddr.isEmpty()) return false;
        for (String n : contactNumbers(ctx, name)) {
            String norm = normalizePhone(n);
            if (norm.isEmpty()) continue;
            if (norm.equals(normAddr)) return true;
            if (norm.length() >= 7 && normAddr.endsWith(norm)) return true;
            if (normAddr.length() >= 7 && norm.endsWith(normAddr)) return true;
        }
        return false;
    }

    /** 查询名字匹配（精确 + 包含）联系人的全部号码 */
    public static List<String> contactNumbers(Context ctx, String name) {
        List<String> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ? OR "
                        + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{name, "%" + name + "%"},
                null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) out.add(n);
            }
        } catch (Exception ignored) {
            // 无 READ_CONTACTS 权限或通讯录为空：返回空列表
        }
        return out;
    }
}
