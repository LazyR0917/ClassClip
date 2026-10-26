package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表管理整页（侧边抽屉进入）：
 * - 列出所有课表（名称 + 课程数），当前使用的蓝字「使用中」高亮
 * - 点卡片切换为当前课表（返回课程表页自动刷新）
 * - 每张卡支持重命名、删除（至少保留一张；删当前表自动切到剩余第一张）
 * - 底部可新建空白课表；导入的课表也会进这里（导入时自动命名并切换）
 */
public class TimetableListActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private DBHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timetable_list);

        db = DBHelper.getInstance(this);
        listContainer = findViewById(R.id.timetable_list);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_new_timetable).setOnClickListener(v -> createBlank());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 导入/其他入口可能改了课表，每次回到本页刷新列表
        rebuildList();
    }

    /* ---------------- 列表 ---------------- */

    private void rebuildList() {
        listContainer.removeAllViews();
        long activeId = db.getActiveTimetableId();
        List<DBHelper.Timetable> timetables = db.getTimetables();
        for (int i = 0; i < timetables.size(); i++) {
            View card = makeCard(timetables.get(i), activeId, i);
            if (i > 0) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.topMargin = dp(16);
                card.setLayoutParams(lp);
            }
            listContainer.addView(card);
        }
    }

    private View makeCard(final DBHelper.Timetable timetable, long activeId, int position) {
        final boolean active = timetable.id == activeId;

        // 卡片配色：按列表顺序循环课程块调色板（跟随原配色）
        int colorIdx = position % TimetableFragment.COLOR_BG.length;
        int paletteBg = color(TimetableFragment.COLOR_BG[colorIdx]);
        int paletteText = color(TimetableFragment.COLOR_TEXT[colorIdx]);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        if (active) {
            card.setBackgroundResource(R.drawable.bg_scheme_card_active);
        } else {
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            cardBg.setColor(paletteBg);
            cardBg.setCornerRadius(dp(12));
            card.setBackground(cardBg);
        }
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 第一行：彩色圆点 + 名称 + 使用中徽标
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 配色圆点
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(paletteText);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMargins(0, 0, dp(8), 0);
        row1.addView(dot, dotLp);

        TextView name = new TextView(this);
        name.setText(timetable.name);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(active ? color(R.color.brand_text_dark) : paletteText);
        row1.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (active) {
            TextView badge = new TextView(this);
            badge.setText(R.string.tt_in_use);
            badge.setTextSize(12);
            badge.setTextColor(color(R.color.brand_primary));
            badge.setBackgroundResource(R.drawable.bg_btn_light);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            row1.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 第二行：课程数 + 重命名/删除
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);
        card.addView(row2, row2Lp);

        TextView count = new TextView(this);
        count.setText(getString(R.string.tt_course_count, timetable.courseCount));
        count.setTextSize(12);
        count.setTextColor(color(R.color.brand_text_gray));
        row2.addView(count, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rename = new TextView(this);
        rename.setText(R.string.tt_rename);
        rename.setTextSize(13);
        rename.setTextColor(color(R.color.brand_primary));
        rename.setPadding(dp(12), dp(4), dp(4), dp(4));
        rename.setOnClickListener(v -> showRenameDialog(timetable));
        row2.addView(rename, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView delete = new TextView(this);
        delete.setText(R.string.tt_delete);
        delete.setTextSize(13);
        delete.setTextColor(color(R.color.brand_danger));
        delete.setPadding(dp(12), dp(4), 0, dp(4));
        delete.setOnClickListener(v -> showDeleteDialog(timetable));
        row2.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点卡片空白处：切换为当前课表
        card.setOnClickListener(v -> {
            if (!active) {
                db.setActiveTimetable(timetable.id);
                Toast.makeText(this,
                        getString(R.string.tt_switched, timetable.name),
                        Toast.LENGTH_SHORT).show();
                rebuildList();
            }
        });

        return card;
    }

    /* ---------------- 新建 / 重命名 / 删除 ---------------- */

    private void createBlank() {
        String base = getString(R.string.tt_blank_name);
        String name = base;
        List<String> names = existingNames();
        for (int i = 2; names.contains(name); i++) {
            name = base + " " + i;
        }
        db.insertTimetable(name);
        Toast.makeText(this, R.string.tt_created, Toast.LENGTH_SHORT).show();
        rebuildList();
    }

    private void showRenameDialog(final DBHelper.Timetable timetable) {
        final EditText input = new EditText(this);
        input.setText(timetable.name);
        input.setHint(R.string.tt_name_hint);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        int pad = dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.tt_rename_title)
                .setView(input)
                .setPositiveButton(R.string.tt_confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, R.string.tt_rename_empty,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    db.renameTimetable(timetable.id, name);
                    Toast.makeText(this, R.string.tt_renamed, Toast.LENGTH_SHORT).show();
                    rebuildList();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private void showDeleteDialog(final DBHelper.Timetable timetable) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.tt_delete_confirm_msg, timetable.name))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    if (!db.deleteTimetable(timetable.id)) {
                        Toast.makeText(this, R.string.tt_last_one, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, R.string.tt_deleted, Toast.LENGTH_SHORT).show();
                    rebuildList();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private List<String> existingNames() {
        List<String> names = new ArrayList<>();
        for (DBHelper.Timetable t : db.getTimetables()) {
            names.add(t.name);
        }
        return names;
    }

    /* ---------------- 工具 ---------------- */

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
