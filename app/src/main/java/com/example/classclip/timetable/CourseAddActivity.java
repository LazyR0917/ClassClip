package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 添加课程整页表单（点课程表空格子 → tips 选「添加课程」进入）：
 * - 顶栏：返回 + 标题 + 保存
 * - 学期信息提示条（按开学日期推算当前周）
 * - 课程名称（全局唯一一行）
 * - 时段卡片列表：每张卡含 时段N + 删除入口、课程周期（起止周滚轮）、课程节数
 *   （星期/起止节三滚轮，未选时红色提示）、教室地点、任课老师；可不断添加新时段
 * 保存时每个时段写一条 courses 记录：同名，周期/节数/教室/老师各用各时段的。
 */
public class CourseAddActivity extends AppCompatActivity {

    private static final String ARG_DAY = "day";
    private static final String ARG_START = "start_period";
    private static final String ARG_END = "end_period";
    private static final String ARG_COURSE_ID = "course_id";

    private static final int WEEK_MAX = 30;
    private static final int PERIOD_MAX = 15;
    private static final int MAX_SLOTS = 10;

    /** 一个上课时段：周期 + 星期节数 + 教室 + 老师（每个时段独立） */
    private static class Slot {
        int day = -1;            // -1 = 课程节数未选择
        int startPeriod = 1;
        int endPeriod = 1;
        int weekStart = 1;
        int weekEnd = 20;
        String roomText = "";
        String teacherText = "";
        EditText etRoom;         // 重建卡片时重新绑定
        EditText etTeacher;
    }

    private final List<Slot> slots = new ArrayList<>();
    private int defaultWeekEnd = 20;
    /** 编辑模式：被编辑课程的 id（-1 = 新建）与原名（保存时先删同名全部再重插） */
    private long editCourseId = -1;
    private String editOriginName = null;

    private EditText etName;
    private TextView tvPageTitle;
    private LinearLayout slotRows;
    private TextView btnAddSlot;
    private TextView tvBannerMain;
    private TextView tvBannerSub;

    /** 打开添加课程页（星期/小节由所点格子带入，作为第一个时段的课程节数） */
    public static android.content.Intent intentOf(android.content.Context context,
                                                  int day, int startPeriod, int endPeriod) {
        android.content.Intent intent = new android.content.Intent(context, CourseAddActivity.class);
        intent.putExtra(ARG_DAY, day);
        intent.putExtra(ARG_START, startPeriod);
        intent.putExtra(ARG_END, endPeriod);
        return intent;
    }

    /** 打开编辑课程页：预填该课程及其同名课程的全部时段，保存时整体替换 */
    public static android.content.Intent intentOfEdit(android.content.Context context,
                                                      long courseId) {
        android.content.Intent intent = new android.content.Intent(context, CourseAddActivity.class);
        intent.putExtra(ARG_COURSE_ID, courseId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course_add);

        etName = findViewById(R.id.et_course_name);
        tvPageTitle = findViewById(R.id.tv_page_title);
        slotRows = findViewById(R.id.slot_rows);
        btnAddSlot = findViewById(R.id.btn_add_slot);
        tvBannerMain = findViewById(R.id.tv_banner_main);
        tvBannerSub = findViewById(R.id.tv_banner_sub);

        DBHelper db = DBHelper.getInstance(this);
        if (db.hasSemesterStart()) {
            defaultWeekEnd = db.getSemesterWeeks();
            updateBanner(db);
        } else {
            tvBannerMain.setText(R.string.course_add_banner_no_semester);
            tvBannerSub.setText(R.string.course_add_banner_hint);
        }

        // 第一个时段：课程节数来自所点格子；编辑模式则预填同名课程的全部时段
        editCourseId = getIntent().getLongExtra(ARG_COURSE_ID, -1);
        if (editCourseId > 0) {
            loadForEdit(db);
        } else {
            Slot first = newSlot();
            first.day = getIntent().getIntExtra(ARG_DAY, 0);
            first.startPeriod = getIntent().getIntExtra(ARG_START, 1);
            first.endPeriod = getIntent().getIntExtra(ARG_END, first.startPeriod);
            slots.add(first);
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        btnAddSlot.setOnClickListener(v -> {
            harvestInputs(); // 先收已填的教室/老师，避免重建丢字
            slots.add(newSlot());
            rebuildSlotRows();
        });

        rebuildSlotRows();
    }

    /** 编辑模式：按被点课程查出同名全部课程，每条记录预填为一个时段 */
    private void loadForEdit(DBHelper db) {
        DBHelper.Course origin = db.getCourse(editCourseId);
        if (origin == null) {
            editCourseId = -1;
            Slot first = newSlot();
            first.day = getIntent().getIntExtra(ARG_DAY, 0);
            slots.add(first);
            return;
        }
        tvPageTitle.setText(R.string.course_edit_title);
        editOriginName = origin.name;
        etName.setText(origin.name);
        for (DBHelper.Course c : db.getCoursesByName(origin.timetableId, origin.name)) {
            Slot slot = newSlot();
            slot.day = c.day;
            slot.startPeriod = c.startPeriod;
            slot.endPeriod = c.endPeriod;
            slot.weekStart = c.weekStart;
            slot.weekEnd = c.weekEnd;
            slot.roomText = c.room;
            slot.teacherText = c.teacher;
            slots.add(slot);
        }
    }

    private Slot newSlot() {
        Slot slot = new Slot();
        slot.weekEnd = defaultWeekEnd;
        return slot;
    }

    /* ---------------- 提示条 ---------------- */

    private void updateBanner(DBHelper db) {
        Calendar monday = mondayOf(Calendar.getInstance());
        int week = computeWeekNumber(monday, db.getSemesterStartMillis());
        int total = db.getSemesterWeeks();
        if (week >= 1 && week <= total) {
            tvBannerMain.setText(getString(R.string.course_add_banner_week, week, total));
        } else {
            tvBannerMain.setText(R.string.course_add_banner_no_semester);
        }
        tvBannerSub.setText(R.string.course_add_banner_hint);
    }

    /* ---------------- 时段卡片 ---------------- */

    /** 把卡片上已输入的教室/老师收回模型（重建/保存前调用，避免丢字） */
    private void harvestInputs() {
        for (Slot slot : slots) {
            if (slot.etRoom != null) {
                slot.roomText = slot.etRoom.getText().toString().trim();
            }
            if (slot.etTeacher != null) {
                slot.teacherText = slot.etTeacher.getText().toString().trim();
            }
        }
    }

    private void rebuildSlotRows() {
        slotRows.removeAllViews();
        for (int i = 0; i < slots.size(); i++) {
            View card = makeSlotCard(slots.get(i), i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.topMargin = dp(i > 0 ? 12 : 10); // 第一个时段卡与「课程名称」卡之间也留间隔
            card.setLayoutParams(lp);
            slotRows.addView(card);
        }
        btnAddSlot.setVisibility(slots.size() >= MAX_SLOTS ? View.GONE : View.VISIBLE);
    }

    private View makeSlotCard(final Slot slot, final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_white);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        // 头：时段N + 删除时段
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(getString(R.string.course_slot_title, index + 1));
        title.setTextSize(13);
        title.setTextColor(color(R.color.brand_text_gray));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (slots.size() > 1) {
            TextView delete = new TextView(this);
            delete.setText(R.string.course_slot_delete);
            delete.setTextSize(13);
            delete.setTextColor(color(R.color.brand_danger));
            delete.setPadding(dp(8), dp(4), 0, dp(4));
            delete.setOnClickListener(v -> {
                harvestInputs();
                slots.remove(slot);
                rebuildSlotRows();
            });
            header.addView(delete, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        card.addView(makeDivider());

        // 课程周期
        LinearLayout weekRow = makeValueRow(getString(R.string.course_week_label),
                getString(R.string.course_week_value, slot.weekStart, slot.weekEnd), true);
        weekRow.setOnClickListener(v -> {
            harvestInputs();
            showWeekPicker(slot);
        });
        card.addView(weekRow);

        card.addView(makeDivider());

        // 课程节数（未选时红色提示）
        TextView periodValue = new TextView(this);
        if (slot.day < 0) {
            periodValue.setText(R.string.course_period_unset);
            periodValue.setTextColor(color(R.color.brand_danger));
        } else {
            periodValue.setText(periodText(slot));
            periodValue.setTextColor(color(R.color.brand_text_dark));
        }
        LinearLayout periodRow = makeValueRow(getString(R.string.course_period_label),
                periodValue, true);
        periodRow.setOnClickListener(v -> {
            harvestInputs();
            showSlotPicker(slot);
        });
        card.addView(periodRow);

        card.addView(makeDivider());

        // 教室地点
        EditText etRoom = new EditText(this);
        etRoom.setHint(R.string.course_room_hint);
        slot.etRoom = etRoom;
        card.addView(makeInputRow(getString(R.string.course_room_label2), etRoom,
                slot.roomText));

        card.addView(makeDivider());

        // 任课老师
        EditText etTeacher = new EditText(this);
        etTeacher.setHint(R.string.course_teacher_hint);
        slot.etTeacher = etTeacher;
        card.addView(makeInputRow(getString(R.string.course_teacher_label), etTeacher,
                slot.teacherText));

        return card;
    }

    private String periodText(Slot slot) {
        if (slot.endPeriod > slot.startPeriod) {
            return getString(R.string.course_add_slot_range,
                    TimetableFragment.DAY_NAMES[slot.day], slot.startPeriod, slot.endPeriod);
        }
        return getString(R.string.course_add_slot_single,
                TimetableFragment.DAY_NAMES[slot.day], slot.startPeriod);
    }

    private View makeDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.card_stroke));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    /** 标签在左、右侧值可选中行（带右箭头）；value 传 String 或现成的 TextView（如红色未选提示） */
    private LinearLayout makeValueRow(String label, Object value, boolean chevron) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(color(R.color.brand_text_dark));
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView;
        if (value instanceof TextView) {
            valueView = (TextView) value;
        } else {
            valueView = new TextView(this);
            valueView.setText((CharSequence) value);
        }
        valueView.setTextSize(14);
        row.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (chevron) {
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(18);
            arrow.setTextColor(color(R.color.brand_text_gray));
            arrow.setPadding(dp(6), 0, 0, 0);
            row.addView(arrow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return row;
    }

    /** 标签在左、右侧输入行 */
    private LinearLayout makeInputRow(String label, EditText input, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(15);
        labelView.setTextColor(color(R.color.brand_text_dark));
        row.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        input.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        input.setBackground(null);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        input.setTextSize(14);
        input.setTextColor(color(R.color.brand_text_dark));
        input.setHintTextColor(color(R.color.brand_text_gray));
        input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setText(text);
        row.addView(input);

        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return row;
    }

    /* ---------------- 课程周期滚轮 ---------------- */

    private void showWeekPicker(final Slot slot) {
        String[] weekValues = new String[WEEK_MAX];
        for (int i = 0; i < WEEK_MAX; i++) {
            weekValues[i] = String.valueOf(i + 1);
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);

        TextView fromLabel = new TextView(this);
        fromLabel.setText(R.string.course_week_from);
        fromLabel.setTextSize(14);
        fromLabel.setTextColor(color(R.color.brand_text_gray));
        content.addView(fromLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final WheelView fromWheel = new WheelView(this, weekValues, slot.weekStart - 1);
        content.addView(fromWheel, new LinearLayout.LayoutParams(0, dp(220), 1f));

        TextView toLabel = new TextView(this);
        toLabel.setText(R.string.course_week_to);
        toLabel.setTextSize(14);
        toLabel.setTextColor(color(R.color.brand_text_gray));
        content.addView(toLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final WheelView toWheel = new WheelView(this, weekValues, slot.weekEnd - 1);
        content.addView(toWheel, new LinearLayout.LayoutParams(0, dp(220), 1f));

        makeBottomSheet(getString(R.string.course_week_picker_title), content, () -> {
            slot.weekStart = fromWheel.getSelection() + 1;
            slot.weekEnd = toWheel.getSelection() + 1;
            if (slot.weekStart > slot.weekEnd) {
                int tmp = slot.weekStart;
                slot.weekStart = slot.weekEnd;
                slot.weekEnd = tmp;
            }
            rebuildSlotRows();
        }).show();
    }

    /* ---------------- 课程节数滚轮 ---------------- */

    private void showSlotPicker(final Slot slot) {
        String[] dayValues = TimetableFragment.DAY_NAMES;
        String[] periodValues = new String[PERIOD_MAX];
        for (int i = 0; i < PERIOD_MAX; i++) {
            periodValues[i] = getString(R.string.course_add_slot_single, "", i + 1).trim();
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);

        final WheelView dayWheel = new WheelView(this, dayValues,
                slot.day < 0 ? 0 : slot.day);
        content.addView(dayWheel, new LinearLayout.LayoutParams(0, dp(220), 1f));

        final WheelView startWheel = new WheelView(this, periodValues, slot.startPeriod - 1);
        content.addView(startWheel, new LinearLayout.LayoutParams(0, dp(220), 1f));

        final WheelView endWheel = new WheelView(this, periodValues, slot.endPeriod - 1);
        content.addView(endWheel, new LinearLayout.LayoutParams(0, dp(220), 1f));

        startWheel.setOnCenterChangedListener(i -> {
            if (endWheel.getSelection() < i) {
                endWheel.setSelection(i);
            }
        });

        makeBottomSheet(getString(R.string.course_slot_picker_title), content, () -> {
            slot.day = dayWheel.getSelection();
            slot.startPeriod = startWheel.getSelection() + 1;
            slot.endPeriod = Math.max(endWheel.getSelection() + 1, slot.startPeriod);
            rebuildSlotRows();
        }).show();
    }

    /* ---------------- 保存 ---------------- */

    private void save() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etName.setError(getString(R.string.course_name_required));
            return;
        }
        harvestInputs();
        for (Slot slot : slots) {
            if (slot.day < 0) {
                Toast.makeText(this, R.string.course_slot_required, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        DBHelper db = DBHelper.getInstance(this);
        // 编辑模式：先把同名课程全部删掉，再按当前表单整体重插（时段可增删改）
        if (editCourseId > 0 && !TextUtils.isEmpty(editOriginName)) {
            for (DBHelper.Course old
                    : db.getCoursesByName(db.getActiveTimetableId(), editOriginName)) {
                db.deleteCourse(old.id);
            }
        }
        Set<String> seen = new HashSet<>();
        int inserted = 0;
        for (Slot slot : slots) {
            String key = slot.day + "-" + slot.startPeriod;
            if (!seen.add(key)) continue; // 重复时段只写一次
            int colorIndex = (slot.day * 5 + slot.startPeriod) % TimetableFragment.COLOR_BG.length;
            if (db.insertCourse(slot.day, slot.startPeriod, slot.endPeriod,
                    name, slot.roomText, colorIndex, slot.teacherText,
                    slot.weekStart, slot.weekEnd, db.getActiveTimetableId()) > 0) {
                inserted++;
            }
        }
        Toast.makeText(this, getString(R.string.course_created_multi, inserted),
                Toast.LENGTH_SHORT).show();
        finish();
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

    private Calendar mondayOf(Calendar date) {
        Calendar monday = (Calendar) date.clone();
        zeroTime(monday);
        int offset = monday.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (offset < 0) offset += 7;
        monday.add(Calendar.DAY_OF_MONTH, -offset);
        return monday;
    }

    private void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int computeWeekNumber(Calendar monday, long semesterStartMillis) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(semesterStartMillis);
        long startMonday = mondayOf(start).getTimeInMillis();
        long diff = monday.getTimeInMillis() - startMonday;
        return (int) (diff / (7 * 24 * 60 * 60 * 1000L)) + 1;
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
