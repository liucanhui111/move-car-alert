package com.movecar.alert;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 告警历史：本地最近 30 条（时间 / 来源 / 内容片段） */
public class HistoryActivity extends AppCompatActivity {

    private LinearLayout mList;
    private View mEmpty;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mList = findViewById(R.id.history_list);
        mEmpty = findViewById(R.id.empty_view);

        findViewById(R.id.btn_clear).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("清空历史")
                        .setMessage("确定删除全部告警记录吗？")
                        .setPositiveButton("清空", (d, w) -> {
                            HistoryStore.get().clear();
                            refresh();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        refresh();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        mList.removeAllViews();
        List<HistoryStore.Item> items = HistoryStore.get().getAll();
        mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        for (HistoryStore.Item it : items) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_history, mList, false);
            ((TextView) v.findViewById(R.id.h_time)).setText(fmt.format(new Date(it.time)));
            ((TextView) v.findViewById(R.id.h_source)).setText(it.source);
            ((TextView) v.findViewById(R.id.h_content)).setText(it.content);
            mList.addView(v);
        }
    }
}
