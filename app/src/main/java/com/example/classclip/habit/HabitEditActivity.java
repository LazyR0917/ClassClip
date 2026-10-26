package com.example.classclip.habit;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 新建/编辑习惯整页（第 3 页右上角 ＋ / 点习惯卡进入）：
 * - 表单：名称 / 分类（学习·健康·生活）/ 打卡频率（每天·指定周几·每周 N 次）/ 图标 / 颜色
 * - 频率均只是目标：指定周几/每周 N 次之外的日子也可以打卡
 * - 编辑态底部有删除入口（连同打卡记录一起删）
 */
public class HabitEditActivity extends AppCompatActivity {

    private static final String ARG_HABIT_ID = "habit_id";
    private static final String ARG_CATEGORY_ID = "category_id";

    /** 图标可选集（与列表页共用索引） */
    static final int[] HABIT_ICONS = {
            R.drawable.ic_habit_translate, R.drawable.ic_habit_book,
            R.drawable.ic_habit_run, R.drawable.ic_habit_moon};

    /** 习惯配色可选集（深色：图标底/打卡格；索引即 color_index） */
    static final int[] HABIT_COLORS = {
            R.color.icon_word_bg, R.color.icon_read_bg, R.color.icon_run_bg,
            R.color.icon_sleep_bg, R.color.course_pink_text, R.color.course_blue_text};

    private long editHabitId = -1;
    private int category = DBHelper.HABIT_CAT_NONE;   // 默认无分类
    private int iconIndex = 0;
    private int colorIndex = 0;
    private int freqType = DBHelper.HABIT_FREQ_DAILY;
    private int freqDays = 0x7F;   // bit0=周一 … bit6=周日
    private int freqTimes = 3;

    private EditText etName;
    private TextView tvCategory;
    private TextView tvFreq;
    private LinearLayout llIcons;
    private LinearLayout llColors;
    private LinearLayout llWeekdays;
    private View btnDelete;

    /** 新建习惯 */
    public static android.content.Intent intentOf(android.content.Context context) {
        return new android.content.Intent(context, HabitEditActivity.class);
    }

    /** 新建习惯并预选分类（categoryId<=0 表示不预选，用默认） */
    public static android.content.Intent intentOf(android.content.Context context, int categoryId) {
        android.content.Intent intent = new android.content.Intent(context, HabitEditActivity.class);
        intent.putExtra(ARG_CATEGORY_ID, categoryId);
        return intent;
    }

    /** 编辑习惯 */
    public static android.content.Intent intentOfEdit(android.content.Context context, long habitId) {
        android.content.Intent intent = new android.content.Intent(context, HabitEditActivity.class);
        intent.putExtra(ARG_HABIT_ID, habitId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_habit_edit);

        etName = findViewById(R.id.et_habit_name);
        tvCategory = findViewById(R.id.tv_habit_category);
        tvFreq = findViewById(R.id.tv_habit_freq);
        llIcons = findViewById(R.id.ll_habit_icons);
        llColors = findViewById(R.id.ll_habit_colors);
        llWeekdays = findViewById(R.id.ll_habit_weekdays);
        btnDelete = findViewById(R.id.btn_habit_delete);

        editHabitId = getIntent().getLongExtra(ARG_HABIT_ID, -1);
        if (editHabitId > 0) {
            DBHelper.Habit habit = DBHelper.getInstance(this).getHabit(editHabitId);
            if (habit == null) {
                finish();
                return;
            }
            ((TextView) findViewById(R.id.tv_habit_edit_title))
                    .setText(R.string.habit_edit_edit_title);
            etName.setText(habit.name);
            category = habit.category;
            iconIndex = habit.icon;
            colorIndex = habit.colorIndex;
            freqType = habit.freqType;
            freqDays = habit.freqDays;
            freqTimes = habit.freqTimes;
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
        } else {
            // 新建：预选分类（从习惯页当前选中的分类带入）
            int presetCategory = getIntent().getIntExtra(ARG_CATEGORY_ID, -1);
            if (presetCategory > 0) {
                category = presetCategory;
            }
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_habit_save).setOnClickListener(v -> save());
        findViewById(R.id.row_habit_category).setOnClickListener(v -> showCategoryPicker());
        findViewById(R.id.row_habit_freq).setOnClickListener(v -> showFreqPicker());

        buildIconRow();
        buildColorRow();
        buildWeekdayRow();
        updateCategoryText();
        updateFreqModeView();
        updateFreqText();
    }

    /* ---------------- 星期表（点选周几，频率自动识别） ---------------- */

    private void buildWeekdayRow() {
        llWeekdays.removeAllViews();
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            final int day = i;
            boolean on = (freqDays & (1 << i)) != 0;

            TextView cell = new TextView(this);
            cell.setText(names[i]);
            cell.setTextSize(12);
            cell.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            cell.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(8));
            bg.setColor(color(on ? R.color.brand_primary : R.color.tag_gray_bg));
            cell.setBackground(bg);
            cell.setTextColor(color(on ? R.color.white : R.color.brand_text_gray));
            cell.setPadding(dp(2), dp(8), dp(2), dp(8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            if (i > 0) {
                lp.leftMargin = dp(6);
            }
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> toggleWeekday(day));
            llWeekdays.addView(cell);
        }
    }

    /** 点星期格：切换该天选中；全选 7 天自动识别为「每天」，否则「指定周几」 */
    private void toggleWeekday(int day) {
        freqDays ^= (1 << day);
        if (freqDays == 0x7F) {
            freqType = DBHelper.HABIT_FREQ_DAILY;
        } else {
            freqType = DBHelper.HABIT_FREQ_WEEKDAYS;
        }
        buildWeekdayRow();
        updateFreqText();
    }

    /* ---------------- 图标 / 颜色选择行 ---------------- */

    private void buildIconRow() {
        llIcons.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int size = dp(40);
        for (int i = 0; i < HABIT_ICONS.length; i++) {
            final int index = i;
            FrameLayout wrap = new FrameLayout(this);

            View bg = new View(this);
            GradientDrawable bgDrawable = new GradientDrawable();
            bgDrawable.setShape(GradientDrawable.RECTANGLE);
            bgDrawable.setCornerRadius(dp(12));
            bgDrawable.setColor(ContextCompat.getColor(this,
                    index == iconIndex ? HABIT_COLORS[colorIndex] : R.color.tag_gray_bg));
            bg.setBackground(bgDrawable);
            wrap.addView(bg, new FrameLayout.LayoutParams(size, size));

            ImageView icon = new ImageView(this);
            icon.setImageResource(HABIT_ICONS[i]);
            icon.setColorFilter(ContextCompat.getColor(this,
                    index == iconIndex ? R.color.white : R.color.brand_text_gray));
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(20), dp(20),
                    Gravity.CENTER);
            icon.setLayoutParams(iconLp);
            wrap.addView(icon);

            // 选中描边
            GradientDrawable stroke = new GradientDrawable();
            stroke.setShape(GradientDrawable.RECTANGLE);
            stroke.setCornerRadius(dp(12));
            stroke.setColor(android.graphics.Color.TRANSPARENT);
            stroke.setStroke(dp(2), index == iconIndex
                    ? ContextCompat.getColor(this, HABIT_COLORS[colorIndex])
                    : android.graphics.Color.TRANSPARENT);
            wrap.setForeground(stroke);

            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) {
                wrapLp.leftMargin = dp(10);
            }
            wrap.setLayoutParams(wrapLp);
            wrap.setOnClickListener(v -> {
                iconIndex = index;
                buildIconRow();
                buildColorRow();
            });
            llIcons.addView(wrap);
        }
    }

    private void buildColorRow() {
        llColors.removeAllViews();
        int size = dp(28);
        for (int i = 0; i < HABIT_COLORS.length; i++) {
            final int index = i;
            FrameLayout wrap = new FrameLayout(this);

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(ContextCompat.getColor(this, HABIT_COLORS[i]));
            dot.setBackground(dotBg);
            wrap.addView(dot, new FrameLayout.LayoutParams(size, size));

            GradientDrawable stroke = new GradientDrawable();
            stroke.setShape(GradientDrawable.OVAL);
            stroke.setColor(android.graphics.Color.TRANSPARENT);
            stroke.setStroke(dp(2), index == colorIndex
                    ? ContextCompat.getColor(this, R.color.brand_text_dark)
                    : android.graphics.Color.TRANSPARENT);
            wrap.setForeground(stroke);

            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) {
                wrapLp.leftMargin = dp(12);
            }
            wrap.setLayoutParams(wrapLp);
            wrap.setOnClickListener(v -> {
                colorIndex = index;
                buildIconRow();
                buildColorRow();
            });
            llColors.addView(wrap);
        }
    }

    /* ---------------- 分类 / 频率选择 ---------------- */

    /** 分类弹窗：右上角「＋新建」可自定义分类（新建后自动选中） */
    private void showCategoryPicker() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(10), pad, pad);

        // 标题行：分类 + ＋新建
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.habit_category_label);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(color(R.color.brand_text_dark));
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView addBtn = new TextView(this);
        addBtn.setText(R.string.habit_category_add);
        addBtn.setTextSize(14);
        addBtn.setTypeface(null, Typeface.BOLD);
        addBtn.setTextColor(color(R.color.brand_primary));
        addBtn.setPadding(dp(8), dp(4), 0, dp(4));
        titleRow.addView(addBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(titleRow);

        // 分类单选列表（新建后重渲染）
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        Runnable[] renderList = new Runnable[1];

        renderList[0] = () -> {
            list.removeAllViews();

            // 「无」选项：不归任何分类，只在「全部」显示
            TextView noneItem = new TextView(this);
            noneItem.setText(R.string.habit_category_none);
            noneItem.setTextSize(15);
            noneItem.setTypeface(null, category == DBHelper.HABIT_CAT_NONE
                    ? Typeface.BOLD : Typeface.NORMAL);
            noneItem.setTextColor(color(category == DBHelper.HABIT_CAT_NONE
                    ? R.color.brand_primary : R.color.brand_text_dark));
            noneItem.setPadding(0, dp(14), 0, dp(14));
            noneItem.setOnClickListener(v -> {
                category = DBHelper.HABIT_CAT_NONE;
                updateCategoryText();
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
            });
            list.addView(noneItem, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            for (DBHelper.HabitCategory c : DBHelper.getInstance(this).getHabitCategories()) {
                TextView item = new TextView(this);
                item.setText(c.name);
                item.setTextSize(15);
                boolean selected = c.id == category;
                item.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                item.setTextColor(color(selected
                        ? R.color.brand_primary : R.color.brand_text_dark));
                item.setPadding(0, dp(14), 0, dp(14));
                long categoryId = c.id;
                item.setOnClickListener(v -> {
                    category = (int) categoryId;
                    updateCategoryText();
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                });
                list.addView(item, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        };
        renderList[0].run();

        // ＋新建：输入名称 → 落库 → 自动选中并关闭
        addBtn.setOnClickListener(v -> {
            final EditText input = new EditText(this);
            input.setHint(R.string.habit_category_name_hint);
            input.setSingleLine(true);
            int inputPad = dp(20);
            input.setPadding(inputPad, inputPad / 2, inputPad, inputPad / 2);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.habit_category_new_title)
                    .setView(input)
                    .setPositiveButton(R.string.tt_confirm, (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (TextUtils.isEmpty(name)) {
                            return;
                        }
                        long id = DBHelper.getInstance(this).insertHabitCategory(name);
                        category = (int) id;
                        updateCategoryText();
                        if (dialogHolder[0] != null) {
                            dialogHolder[0].dismiss();
                        }
                    })
                    .setNegativeButton(R.string.course_action_cancel, null)
                    .show();
        });

        dialogHolder[0] = new AlertDialog.Builder(this)
                .setView(content)
                .show();
    }

    private void showFreqPicker() {
        final String[] labels = {
                getString(R.string.habit_freq_mode_weekdays),
                getString(R.string.habit_freq_mode_times)};
        int checked = freqType == DBHelper.HABIT_FREQ_TIMES ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.habit_freq_pick_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        // 按星期：由星期表自动识别每天/指定周几
                        if (freqType == DBHelper.HABIT_FREQ_TIMES) {
                            freqType = (freqDays == 0x7F)
                                    ? DBHelper.HABIT_FREQ_DAILY
                                    : DBHelper.HABIT_FREQ_WEEKDAYS;
                        }
                        updateFreqModeView();
                        updateFreqText();
                    } else {
                        // 每周 N 次：直接选次数
                        pickTimes();
                    }
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    /** 每周目标次数：1~7 单选（直接选次数，无需选星期几） */
    private void pickTimes() {
        final String[] labels = new String[7];
        for (int i = 0; i < 7; i++) {
            labels[i] = getString(R.string.habit_freq_times, i + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.habit_times_pick_title)
                .setSingleChoiceItems(labels, freqTimes - 1, (dialog, which) -> {
                    freqType = DBHelper.HABIT_FREQ_TIMES;
                    freqTimes = which + 1;
                    updateFreqModeView();
                    updateFreqText();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private void updateCategoryText() {
        if (category == DBHelper.HABIT_CAT_NONE) {
            tvCategory.setText(R.string.habit_category_none);
            return;
        }
        String name = null;
        for (DBHelper.HabitCategory c : DBHelper.getInstance(this).getHabitCategories()) {
            if (c.id == category) {
                name = c.name;
                break;
            }
        }
        tvCategory.setText(name != null ? name : getString(R.string.habit_category_none));
    }

    /** 按当前频率模式显隐星期表：每周 N 次模式下隐藏星期表（只选次数） */
    private void updateFreqModeView() {
        llWeekdays.setVisibility(freqType == DBHelper.HABIT_FREQ_TIMES
                ? View.GONE : View.VISIBLE);
    }

    private void updateFreqText() {
        switch (freqType) {
            case DBHelper.HABIT_FREQ_WEEKDAYS:
                tvFreq.setText(getString(R.string.habit_weekdays_text, weekdaysText()));
                break;
            case DBHelper.HABIT_FREQ_TIMES:
                tvFreq.setText(getString(R.string.habit_freq_times, freqTimes));
                break;
            default:
                tvFreq.setText(R.string.habit_freq_daily);
                break;
        }
    }

    /** bit0=周一 … → "一三五" */
    private String weekdaysText() {
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((freqDays & (1 << i)) != 0) {
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    /* ---------------- 保存 / 删除 ---------------- */

    private void save() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etName.setError(getString(R.string.habit_name_required));
            return;
        }
        DBHelper db = DBHelper.getInstance(this);
        if (editHabitId > 0) {
            db.updateHabit(editHabitId, name, category, iconIndex, colorIndex,
                    freqType, freqDays, freqTimes);
        } else {
            db.insertHabit(name, category, iconIndex, colorIndex,
                    freqType, freqDays, freqTimes);
        }
        Toast.makeText(this, R.string.habit_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        String name = etName.getText().toString().trim();
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.habit_delete_confirm, name))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper.getInstance(this).deleteHabit(editHabitId);
                    Toast.makeText(this, R.string.habit_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }
}
