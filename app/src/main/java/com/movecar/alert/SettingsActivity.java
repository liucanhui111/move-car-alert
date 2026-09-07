package com.movecar.alert;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：提醒间隔 / 最大次数 / 震动参数 / 倒计时 / 自定义监控号码与APP / 弹窗图片。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGE = 20;

    private LinearLayout mKeywordList;
    private LinearLayout mAppList;
    private ImageView mImagePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mKeywordList = findViewById(R.id.keyword_list);
        mAppList = findViewById(R.id.app_list);
        mImagePreview = findViewById(R.id.image_preview);

        // 数值设置行
        bindNumberRow(R.id.row_interval, "提醒间隔（分钟）",
                () -> PrefsManager.get().getIntervalMinutes(),
                v -> PrefsManager.get().setIntervalMinutes(v), 1, 120, "未手动确认时，每隔该时长重复提醒一次");
        bindNumberRow(R.id.row_max_count, "最大提醒次数",
                () -> PrefsManager.get().getMaxRepeatCount(),
                v -> PrefsManager.get().setMaxRepeatCount(v), 1, 50, "达到该次数后自动停止本轮告警");
        bindNumberRow(R.id.row_vib_duration, "单次震动时长（毫秒）",
                () -> PrefsManager.get().getVibrateDurationMs(),
                v -> PrefsManager.get().setVibrateDurationMs(v), 100, 5000, "每次震动的持续时间");
        bindNumberRow(R.id.row_vib_gap, "震动间隔（毫秒）",
                () -> PrefsManager.get().getVibrateGapMs(),
                v -> PrefsManager.get().setVibrateGapMs(v), 50, 5000, "两次震动之间的停顿时长");
        bindNumberRow(R.id.row_vib_repeat, "震动重复次数",
                () -> PrefsManager.get().getVibrateRepeatCount(),
                v -> PrefsManager.get().setVibrateRepeatCount(v), 1, 20, "一轮提醒内的震动总次数");
        bindNumberRow(R.id.row_countdown, "挪车倒计时（分钟）",
                () -> PrefsManager.get().getCountdownMinutes(),
                v -> PrefsManager.get().setCountdownMinutes(v), 1, 120, "告警触发时开始倒计时，结束时强震动并标注「时间已到」");

        // 添加监控
        findViewById(R.id.btn_add_keyword).setOnClickListener(v -> showAddKeywordDialog());
        findViewById(R.id.btn_add_app).setOnClickListener(v -> showAddAppDialog());

        // 图片
        findViewById(R.id.btn_pick_image).setOnClickListener(v ->
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK_IMAGE));
        findViewById(R.id.btn_reset_image).setOnClickListener(v -> {
            PrefsManager.get().setAlertImagePath("");
            loadImagePreview();
            Toast.makeText(this, "已恢复默认图片", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_test_vibrate).setOnClickListener(v -> {
            VibrateHelper.vibratePattern(this);
            Toast.makeText(this, "已按当前参数震动一轮（无声音）", Toast.LENGTH_SHORT).show();
        });

        refreshLists();
        loadImagePreview();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ---------------- 数值行 ----------------

    private interface Getter {
        int get();
    }

    private interface Setter {
        void set(int v);
    }

    private void bindNumberRow(int rowId, String title, Getter getter, Setter setter,
                               int min, int max, String hint) {
        View row = findViewById(rowId);
        TextView value = row.findViewById(R.id.row_value);
        value.setText(String.valueOf(getter.get()));
        row.setOnClickListener(v -> showNumberDialog(title, getter, setter, min, max, hint, value));
    }

    private void showNumberDialog(String title, Getter getter, Setter setter,
                                  int min, int max, String hint, TextView valueView) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(getter.get()));
        input.setHint(hint);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        v = PrefsManager.clamp(v, min, max);
                        setter.set(v);
                        valueView.setText(String.valueOf(v));
                        Toast.makeText(this, title + " 已设为 " + v, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "请输入有效数字（" + min + " ~ " + max + "）",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 监控列表 ----------------

    private void refreshLists() {
        PrefsManager p = PrefsManager.get();

        mKeywordList.removeAllViews();
        // 内置 12123（固定）
        addListRow(mKeywordList, "12123", "内置 · 交管12123短信发件人", true, null);
        for (String kw : p.getCustomKeywords()) {
            addListRow(mKeywordList, kw, "自定义 · 短信发件人/联系人名", false, () -> {
                List<String> l = new ArrayList<>(p.getCustomKeywords());
                l.remove(kw);
                p.setCustomKeywords(l);
                refreshLists();
            });
        }

        mAppList.removeAllViews();
        addListRow(mAppList, "交管12123 APP", "内置 · " + PrefsManager.BUILTIN_APP_12123, true, null);
        for (String pkg : p.getCustomApps()) {
            String label = appLabel(pkg);
            addListRow(mAppList, label, pkg, false, () -> {
                List<String> l = new ArrayList<>(p.getCustomApps());
                l.remove(pkg);
                p.setCustomApps(l);
                refreshLists();
            });
        }
    }

    private void addListRow(LinearLayout parent, String title, String sub,
                            boolean builtin, Runnable onDelete) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_monitor, parent, false);
        ((TextView) v.findViewById(R.id.item_title)).setText(title);
        ((TextView) v.findViewById(R.id.item_sub)).setText(sub);
        ImageView del = v.findViewById(R.id.item_delete);
        if (builtin) {
            del.setVisibility(View.INVISIBLE);
        } else {
            del.setOnClickListener(x -> {
                VibrateHelper.vibrateTick(this);
                onDelete.run();
            });
        }
        parent.addView(v);
    }

    private void showAddKeywordDialog() {
        EditText input = new EditText(this);
        input.setHint("输入手机号或联系人名字\n（可一次输入多个，用逗号或换行分隔）");
        input.setMinLines(2);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle("添加短信监控")
                .setMessage("当系统短信应用收到发件人（通知标题）包含该号码/名字的短信时，立即触发告警。")
                .setView(wrap)
                .setPositiveButton("添加", (d, w) -> {
                    List<String> add = PrefsManager.splitTrim(input.getText().toString());
                    if (add.isEmpty()) return;
                    List<String> cur = new ArrayList<>(PrefsManager.get().getCustomKeywords());
                    for (String s : add) if (!cur.contains(s) && !s.equals("12123")) cur.add(s);
                    PrefsManager.get().setCustomKeywords(cur);
                    refreshLists();
                    Toast.makeText(this, "已添加 " + add.size() + " 项监控", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddAppDialog() {
        EditText input = new EditText(this);
        input.setHint("输入 APP 名称或包名\n（如：微信 / com.tencent.mm）");
        input.setMinLines(1);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(this)
                .setTitle("添加 APP 监控")
                .setMessage("监控该 APP 的通知推送，收到即触发告警（建议输入包名以精确匹配）。")
                .setView(wrap)
                .setPositiveButton("添加", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    String pkg = resolvePackage(raw);
                    if (pkg == null) {
                        Toast.makeText(this, "未找到该应用，请尝试输入包名（如 com.tmri.app）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<String> cur = new ArrayList<>(PrefsManager.get().getCustomApps());
                    if (!cur.contains(pkg) && !pkg.equals(PrefsManager.BUILTIN_APP_12123)) cur.add(pkg);
                    PrefsManager.get().setCustomApps(cur);
                    refreshLists();
                    Toast.makeText(this, "已监控：" + appLabel(pkg), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 名称或包名 → 包名 */
    private String resolvePackage(String raw) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            // 精确包名
            try {
                pm.getPackageInfo(raw, 0);
                return raw;
            } catch (Exception ignored) {
            }
            // 名称模糊匹配
            List<android.content.pm.ApplicationInfo> apps =
                    pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : apps) {
                String label = pm.getApplicationLabel(ai).toString();
                if (label.equalsIgnoreCase(raw) || label.contains(raw)) {
                    return ai.packageName;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------- 弹窗图片 ----------------

    private void loadImagePreview() {
        String path = PrefsManager.get().getAlertImagePath();
        if (path != null && !path.isEmpty() && new File(path).exists()) {
            try {
                mImagePreview.setImageBitmap(BitmapFactory.decodeFile(path));
                return;
            } catch (Exception ignored) {
            }
        }
        mImagePreview.setImageResource(R.drawable.ic_car_big);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                File out = new File(getFilesDir(), "alert_image.img");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                PrefsManager.get().setAlertImagePath(out.getAbsolutePath());
                loadImagePreview();
                Toast.makeText(this, "弹窗图片已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "图片读取失败，请重试", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
