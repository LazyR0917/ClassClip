package com.example.classclip.todo;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.common.DatePickerDialog;
import com.example.classclip.timetable.TimetableFragment;
import com.example.classclip.common.WheelView;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 添加/编辑待办整页表单（点格子 → tips 选「添加待办」或格子明细页点待办卡进入）。
 * - 标题 / 备注 / 日期（日历弹窗选择）/ 时间（时、分双滚轮，5 分钟步进）
 * - 待办存「日期 + HH:mm 时刻」，网格按时刻落在哪节课的区间归属到对应格子
 * - 从格子进入时默认日期=该列日期、默认时间=所点节的开始时刻
 * - 编辑模式预填全部字段，保存为更新
 */
public class TodoAddActivity extends AppCompatActivity {

    private static final String ARG_DATE_MILLIS = "date_millis";
    private static final String ARG_PERIOD = "period";
    private static final String ARG_TODO_ID = "todo_id";

    private long selectedMillis;
    private String selectedTime;   // "HH:mm"，null 表示未选择
    private int selectedPriority = DBHelper.PRIORITY_MID;   // 默认中优先级
    private int selectedQuadrant = DBHelper.QUADRANT_NEITHER;  // 默认不紧急不重要
    private long editTodoId = -1;
    private EditText etTitle;
    private EditText etNote;
    private TextView tvDate;
    private TextView tvTime;
    private TextView tvPriority;
    private TextView tvQuadrant;
    private TextView tvPageTitle;

    /** 新建待办（dateMillis=所点列日期，period=所点格子节数，默认时间=该节开始时刻） */
    public static android.content.Intent intentOf(android.content.Context context,
                                                  long dateMillis, int period) {
        android.content.Intent intent = new android.content.Intent(context, TodoAddActivity.class);
        intent.putExtra(ARG_DATE_MILLIS, dateMillis);
        intent.putExtra(ARG_PERIOD, period);
        return intent;
    }

    /** 新建待办（只带默认日期，不带课节：时间不预填。待办页 ＋ / 某天待办列表页用） */
    public static android.content.Intent intentOf(android.content.Context context,
                                                  long dateMillis) {
        android.content.Intent intent = new android.content.Intent(context, TodoAddActivity.class);
        intent.putExtra(ARG_DATE_MILLIS, dateMillis);
        return intent;
    }

    /** 编辑待办（格子明细页点待办卡进入） */
    public static android.content.Intent intentOfEdit(android.content.Context context,
                                                      long todoId) {
        android.content.Intent intent = new android.content.Intent(context, TodoAddActivity.class);
        intent.putExtra(ARG_TODO_ID, todoId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo_add);

        etTitle = findViewById(R.id.et_todo_title);
        etNote = findViewById(R.id.et_todo_note);
        tvDate = findViewById(R.id.tv_todo_date);
        tvTime = findViewById(R.id.tv_todo_time);
        tvPriority = findViewById(R.id.tv_todo_priority);
        tvQuadrant = findViewById(R.id.tv_todo_quadrant);
        tvPageTitle = findViewById(R.id.tv_todo_title_label);

        editTodoId = getIntent().getLongExtra(ARG_TODO_ID, -1);
        if (editTodoId > 0) {
            loadForEdit();
        } else {
            selectedMillis = getIntent().getLongExtra(ARG_DATE_MILLIS, -1);
            if (selectedMillis <= 0) {
                selectedMillis = todayMillis();
            }
            // 默认时间 = 所点格子的节开始时刻（deadline）；未传课节时不预填（显示「未选择」）
            int period = getIntent().getIntExtra(ARG_PERIOD, -1);
            selectedTime = period > 0 ? startTimeOfPeriod(period) : null;
        }
        updateDateText();
        updateTimeText();
        updatePriorityText();
        updateQuadrantText();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.row_todo_date).setOnClickListener(v -> showDatePicker());
        findViewById(R.id.row_todo_time).setOnClickListener(v -> showTimePicker());
        findViewById(R.id.row_todo_priority).setOnClickListener(v -> showPriorityPicker());
        findViewById(R.id.row_todo_quadrant).setOnClickListener(v -> showQuadrantPicker());
    }

    private void loadForEdit() {
        DBHelper.Todo todo = DBHelper.getInstance(this).getTodo(editTodoId);
        if (todo == null) {
            editTodoId = -1;
            selectedMillis = todayMillis();
            return;
        }
        tvPageTitle.setText(R.string.todo_edit_title);
        etTitle.setText(todo.title);
        etNote.setText(todo.note);
        selectedMillis = todo.dateMillis;
        selectedTime = todo.time;   // "HH:mm-HH:mm" 或 "HH:mm"
        selectedPriority = todo.priority;
        selectedQuadrant = todo.quadrant == DBHelper.QUADRANT_NONE
                ? DBHelper.QUADRANT_NEITHER : todo.quadrant;
    }

    /** 当前上课时间方案里第 period 节的开始时刻（"HH:mm"），无方案/无该节返回 null */
    private String startTimeOfPeriod(int period) {
        List<DBHelper.ClassTime> times = DBHelper.getInstance(this).getActiveClassTimes();
        for (DBHelper.ClassTime ct : times) {
            if (ct.periodIndex == period) {
                return ct.start;
            }
        }
        return null;
    }

    private long todayMillis() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today.getTimeInMillis();
    }

    private void save() {
        String title = etTitle.getText().toString().trim();
        if (TextUtils.isEmpty(title)) {
            etTitle.setError(getString(R.string.todo_title_required));
            return;
        }
        String note = etNote.getText().toString().trim();
        DBHelper db = DBHelper.getInstance(this);
        if (editTodoId > 0) {
            db.updateTodo(editTodoId, title, note, selectedMillis, selectedTime,
                    selectedPriority, selectedQuadrant);
        } else {
            db.insertTodo(title, note, selectedMillis, selectedTime,
                    selectedPriority, selectedQuadrant);
        }
        Toast.makeText(this, R.string.todo_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /* ---------------- 优先级 / 四象限选择（单选弹窗，可不设置） ---------------- */

    private void showPriorityPicker() {
        final String[] labels = {
                getString(R.string.todo_priority_high),
                getString(R.string.todo_priority_mid),
                getString(R.string.todo_priority_low),
                getString(R.string.todo_unset)};
        final int[] values = {DBHelper.PRIORITY_HIGH, DBHelper.PRIORITY_MID,
                DBHelper.PRIORITY_LOW, DBHelper.PRIORITY_NONE};
        int checked = indexOf(values, selectedPriority);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.todo_priority_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedPriority = values[which];
                    updatePriorityText();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private void showQuadrantPicker() {
        final String[] labels = {
                getString(R.string.todo_quadrant_iu),
                getString(R.string.todo_quadrant_inu),
                getString(R.string.todo_quadrant_uni),
                getString(R.string.todo_quadrant_nnu)};
        final int[] values = {DBHelper.QUADRANT_IMPORTANT_URGENT,
                DBHelper.QUADRANT_IMPORTANT_NOT_URGENT,
                DBHelper.QUADRANT_URGENT_NOT_IMPORTANT,
                DBHelper.QUADRANT_NEITHER};
        int checked = indexOf(values, selectedQuadrant);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.todo_quadrant_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectedQuadrant = values[which];
                    updateQuadrantText();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return 0;
    }

    private void updatePriorityText() {
        int res;
        switch (selectedPriority) {
            case DBHelper.PRIORITY_HIGH: res = R.string.todo_priority_high; break;
            case DBHelper.PRIORITY_LOW:  res = R.string.todo_priority_low; break;
            case DBHelper.PRIORITY_NONE: res = R.string.todo_unset; break;
            default:                     res = R.string.todo_priority_mid; break;
        }
        tvPriority.setText(res);
    }

    private void updateQuadrantText() {
        int res;
        switch (selectedQuadrant) {
            case DBHelper.QUADRANT_IMPORTANT_URGENT:       res = R.string.todo_quadrant_iu; break;
            case DBHelper.QUADRANT_IMPORTANT_NOT_URGENT:   res = R.string.todo_quadrant_inu; break;
            case DBHelper.QUADRANT_URGENT_NOT_IMPORTANT:   res = R.string.todo_quadrant_uni; break;
            default:                                       res = R.string.todo_quadrant_nnu; break;
        }
        tvQuadrant.setText(res);
    }

    /* ---------------- 日期选择（复用课程表页的日历弹窗） ---------------- */

    private void showDatePicker() {
        DatePickerDialog dialog = DatePickerDialog.newInstance(selectedMillis, false);
        dialog.setOnDatePickedListener(date -> {
            selectedMillis = date.getTimeInMillis();
            updateDateText();
        });
        dialog.show(getSupportFragmentManager(), "todo_date_picker");
    }

    private void updateDateText() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(selectedMillis);
        int offset = c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (offset < 0) offset += 7;
        tvDate.setText(String.format(Locale.CHINA, "%d年%d月%d日 %s",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), TimetableFragment.DAY_NAMES[offset]));
    }

    /* ---------------- 时间选择（时:分 双滚轮，deadline 单时刻） ---------------- */

    private void showTimePicker() {
        String[] hourValues = new String[24];
        String[] minuteValues = new String[60];
        for (int i = 0; i < 24; i++) {
            hourValues[i] = String.format(Locale.CHINA, "%02d", i);
        }
        for (int i = 0; i < 60; i++) {
            minuteValues[i] = String.format(Locale.CHINA, "%02d", i);
        }

        int startMin = 8 * 60;
        if (!TextUtils.isEmpty(selectedTime)) {
            int m = parseSingleTime(selectedTime);
            if (m >= 0) {
                startMin = m;
            }
        }

        final WheelView hourWheel = new WheelView(this, hourValues, startMin / 60);
        final WheelView minuteWheel = new WheelView(this, minuteValues, startMin % 60);

        LinearLayout wheels = new LinearLayout(this);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(dp(56), dp(220));
        wheels.addView(hourWheel, new LinearLayout.LayoutParams(wheelLp));
        wheels.addView(makeWheelSeparator("："));
        wheels.addView(minuteWheel, new LinearLayout.LayoutParams(wheelLp));

        // 蓝框垫在滚轮下层，横跨整行套住中间项（与设置时间表一致）
        FrameLayout content = new FrameLayout(this);
        View box = new View(this);
        box.setBackgroundResource(R.drawable.bg_wheel_center);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER_VERTICAL);
        boxLp.leftMargin = dp(16);
        boxLp.rightMargin = dp(16);
        content.addView(box, boxLp);
        content.addView(wheels, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(220), Gravity.CENTER));

        makeBottomSheet(getString(R.string.todo_time_label), content, () -> {
            int h = hourWheel.getSelection();
            int m = minuteWheel.getSelection();
            selectedTime = String.format(Locale.CHINA, "%02d:%02d", h, m);
            updateTimeText();
        }).show();
    }

    /** "HH:mm" 或 "HH:mm-HH:mm" → 分钟数（区间取开始时刻），解析失败返回 -1 */
    private static int parseSingleTime(String text) {
        try {
            String[] parts = text.split("-")[0].trim().split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private TextView makeWheelSeparator(String text) {
        TextView sep = new TextView(this);
        sep.setText(text);
        sep.setTextSize(16);
        sep.setTypeface(Typeface.DEFAULT_BOLD);
        sep.setTextColor(color(R.color.brand_text_gray));
        sep.setGravity(Gravity.CENTER);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sep;
    }

    private void updateTimeText() {
        if (TextUtils.isEmpty(selectedTime)) {
            tvTime.setText(R.string.todo_time_unset);
        } else {
            tvTime.setText(selectedTime);
        }
    }

    /* ---------------- 底部滚轮弹窗骨架（复用课程编辑页样式） ---------------- */

    private Dialog makeBottomSheet(String title, View content, Runnable onSave) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_course_sheet);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, pad);

        View handle = new View(this);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(handle, handleLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(12), 0, dp(4));

        TextView cancel = new TextView(this);
        cancel.setText(R.string.course_action_cancel);
        cancel.setTextSize(15);
        cancel.setTextColor(color(R.color.brand_text_gray));
        cancel.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(color(R.color.brand_text_dark));
        tvTitle.setGravity(Gravity.CENTER);
        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = new TextView(this);
        save.setText(R.string.course_action_save);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(color(R.color.brand_primary));
        save.setOnClickListener(v -> {
            onSave.run();
            dialog.dismiss();
        });
        titleRow.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.CourseSheetAnim);
        }
        return dialog;
    }

    /* ---------------- 工具 ---------------- */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int color(int resId) {
        return getResources().getColor(resId);
    }
}
