package com.example.classclip.common;
import com.example.classclip.R;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import java.util.Calendar;
import java.util.Locale;

/**
 * 课程表自定义日期选择弹窗（替代 MaterialDatePicker）：
 * - 周数条：第 1 ~ N 周横向滑动 chip，打开时自动定位到当前周；
 *   N 来自 SQLite（DBHelper）的 semester_weeks（由「设置上课时间 / 导入课表」写入，默认 20）
 * - 点周数 chip：跳到该周周一，日历中该周 7 天整周淡紫高亮（跨月正确）；
 *   直接点日期或「回到今天」时只标中选中日，不整周高亮
 * - 日历网格（周一起始）：点日期只选中并联动周数 chip；点「确定」才回调生效
 * - 点中间「xxxx年x月」：弹出年/月选择面板（年份步进 + 12 月网格），选月后日历直接跳转
 * - 「回到今天」快速复位；右上 × 关闭
 * - 学期起点（semester_start_millis）未设置时隐藏周数条，仅支持选日期
 *
 * 回调：展示该弹窗的父 Fragment 实现 {@link OnDatePickedListener} 即可接收结果。
 */
public class DatePickerDialog extends DialogFragment {

    /** 选中结果回调 */
    public interface OnDatePickedListener {
        void onDatePicked(Calendar date);
    }

    private static final int DEFAULT_TOTAL_WEEKS = 20;
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String[] WEEKDAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String ARG_SELECTED_MILLIS = "selected_millis";
    private static final String ARG_SHOW_WEEKS = "show_weeks";
    private static final String ARG_MONTH_ONLY = "month_only";

    private Calendar selectedDate;   // 当前选中的日期（零点）
    private Calendar viewMonth;      // 日历当前显示的月份（该月 1 号零点）
    private long semesterStart = -1; // 学期起点（第一周内任意一天）零点；-1 = 未设置
    private int totalWeeks = DEFAULT_TOTAL_WEEKS;
    private int panelYear;           // 年/月面板当前展示的年份
    /** 整周高亮开关：点周数 chip 时为 true（高亮该周 7 天）；直接点日期 / 回到今天时为 false（只标选中日） */
    private boolean highlightWeek;
    /** 是否显示周数条：课程表页 true；待办页传 false 隐藏周数，仅选日期 */
    private boolean showWeeks = true;
    /** 仅选年/月模式（月待办表用）：隐藏日历网格与「回到今天」，直接展开年/月面板，「确定」返回该月 1 号 */
    private boolean monthOnly = false;

    private TextView monthLabel;
    private TextView yearLabel;
    private HorizontalScrollView chipsScroll;
    private LinearLayout chipsRow;
    private GridLayout calendarGrid;
    private LinearLayout monthPanel;
    private GridLayout monthGrid;

    /** 直接设置的回调（Activity 等无父 Fragment 的场景优先用；未设置时回退父 Fragment） */
    private OnDatePickedListener listener;

    /** Activity 场景直接设置回调（父 Fragment 场景实现接口即可，无需调用） */
    public void setOnDatePickedListener(OnDatePickedListener listener) {
        this.listener = listener;
    }

    /** 创建弹窗并传入当前选中日期（毫秒），用于打开时高亮定位（默认显示周数条） */
    public static DatePickerDialog newInstance(long selectedDateMillis) {
        return newInstance(selectedDateMillis, true);
    }

    /** @param showWeeks 传 false 隐藏周数条（如待办页），仅选择日期 */
    public static DatePickerDialog newInstance(long selectedDateMillis, boolean showWeeks) {
        DatePickerDialog dialog = new DatePickerDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_SELECTED_MILLIS, selectedDateMillis);
        args.putBoolean(ARG_SHOW_WEEKS, showWeeks);
        dialog.setArguments(args);
        return dialog;
    }

    /** 仅选年/月模式：打开即展开年/月面板，「确定」回调所选月份的 1 号 */
    public static DatePickerDialog newInstanceYearMonth(long currentMillis) {
        DatePickerDialog dialog = new DatePickerDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_SELECTED_MILLIS, currentMillis);
        args.putBoolean(ARG_SHOW_WEEKS, false);
        args.putBoolean(ARG_MONTH_ONLY, true);
        dialog.setArguments(args);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_date_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        long selectedMillis = getArguments() != null
                ? getArguments().getLong(ARG_SELECTED_MILLIS, System.currentTimeMillis())
                : System.currentTimeMillis();
        showWeeks = getArguments() == null || getArguments().getBoolean(ARG_SHOW_WEEKS, true);
        monthOnly = getArguments() != null && getArguments().getBoolean(ARG_MONTH_ONLY, false);
        selectedDate = Calendar.getInstance();
        selectedDate.setTimeInMillis(selectedMillis);
        zeroTime(selectedDate);
        viewMonth = firstOfMonth(selectedDate);

        // 学期起点 / 总周数：与「设置上课时间」弹窗共用 SQLite（DBHelper）
        DBHelper db = DBHelper.getInstance(requireContext());
        if (db.hasSemesterStart()) {
            semesterStart = db.getSemesterStartMillis();
            totalWeeks = db.getSemesterWeeks();
        }

        chipsScroll = view.findViewById(R.id.chips_scroll);
        chipsRow = view.findViewById(R.id.chips_row);
        calendarGrid = view.findViewById(R.id.calendar_grid);
        monthPanel = view.findViewById(R.id.month_panel);
        monthGrid = view.findViewById(R.id.month_grid);
        monthLabel = view.findViewById(R.id.month_label);
        yearLabel = view.findViewById(R.id.year_label);

        view.findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btn_prev_month).setOnClickListener(v -> shiftMonth(-1));
        view.findViewById(R.id.btn_next_month).setOnClickListener(v -> shiftMonth(1));
        view.findViewById(R.id.month_label).setOnClickListener(v -> toggleMonthPanel());
        view.findViewById(R.id.btn_prev_year).setOnClickListener(v -> { panelYear--; refreshMonthPanel(); });
        view.findViewById(R.id.btn_next_year).setOnClickListener(v -> { panelYear++; refreshMonthPanel(); });
        view.findViewById(R.id.btn_today).setOnClickListener(v -> goToday());
        view.findViewById(R.id.btn_ok).setOnClickListener(v -> confirm());

        // 仅选年/月模式：藏起日历网格与「回到今天」，直接展开年/月面板
        if (monthOnly) {
            view.findViewById(R.id.calendar_grid).setVisibility(View.GONE);
            view.findViewById(R.id.btn_today).setVisibility(View.GONE);
            panelYear = viewMonth.get(Calendar.YEAR);
            refreshMonthPanel();
            monthPanel.setVisibility(View.VISIBLE);
        }

        buildChips();
        refreshCalendar();
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() != null ? getDialog().getWindow() : null;
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = requireContext().getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(dp(360), screenWidth - dp(48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /* ---------------- 周数条 ---------------- */

    /** 生成第 1 ~ N 周的 chip；学期起点未设置或调用方要求隐藏（showWeeks=false）时隐藏整条 */
    private void buildChips() {
        chipsRow.removeAllViews();
        if (semesterStart < 0 || !showWeeks) {
            chipsScroll.setVisibility(View.GONE);
            return;
        }
        for (int w = 1; w <= totalWeeks; w++) {
            final int week = w;
            TextView chip = new TextView(requireContext());
            chip.setTextSize(12);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(3), 0, dp(3), 0);
            chip.setLayoutParams(lp);
            chip.setText(getString(R.string.dialog_week_chip, week));
            chip.setOnClickListener(v -> {
                // 该周周一 = 学期起点所在周的周一 + (week-1) 周；点周数只切视图，不改变「确定」前未生效的日期
                Calendar start = Calendar.getInstance();
                start.setTimeInMillis(semesterStart);
                Calendar monday = mondayOf(start);
                monday.add(Calendar.DAY_OF_MONTH, (week - 1) * 7);
                selectedDate = monday;
                highlightWeek = true;
                viewMonth = firstOfMonth(monday);
                refreshCalendar();
            });
            chipsRow.addView(chip);
        }
    }

    /** 刷新 chip 选中态，并把当前周滚动到可视区域中间 */
    private void refreshChips() {
        if (semesterStart < 0 || !showWeeks) return;
        int currentWeek = computeWeekNumber(mondayOf(selectedDate));
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView chip = (TextView) chipsRow.getChildAt(i);
            boolean active = (i + 1) == currentWeek;
            chip.setBackgroundResource(active
                    ? R.drawable.bg_chip_selected : R.drawable.bg_week_chip_normal);
            chip.setTextColor(color(active ? R.color.white : R.color.brand_text_dark));
            chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (currentWeek >= 1 && currentWeek <= chipsRow.getChildCount()) {
            View target = chipsRow.getChildAt(currentWeek - 1);
            chipsScroll.post(() -> chipsScroll.smoothScrollTo(
                    Math.max(0, target.getLeft() - chipsScroll.getWidth() / 2 + target.getWidth() / 2), 0));
        }
    }

    /* ---------------- 日历网格 ---------------- */

    /** 重建日历：表头 + 当月日期；选中周整周高亮，选中日紫色块 */
    private void refreshCalendar() {
        monthLabel.setText(String.format(Locale.getDefault(), "%d年%d月",
                viewMonth.get(Calendar.YEAR), viewMonth.get(Calendar.MONTH) + 1));
        calendarGrid.removeAllViews();

        for (String name : WEEKDAY_NAMES) {
            TextView header = makeCell(dp(20));
            header.setText(name);
            header.setTextSize(11);
            header.setTypeface(null, Typeface.BOLD);
            header.setTextColor(color(R.color.brand_text_gray));
            calendarGrid.addView(header);
        }

        int lead = leadDays(viewMonth);
        int daysInMonth = viewMonth.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar weekMonday = mondayOf(selectedDate);
        Calendar weekSunday = (Calendar) weekMonday.clone();
        weekSunday.add(Calendar.DAY_OF_MONTH, 6);

        for (int i = 0; i < lead; i++) {
            calendarGrid.addView(makeCell(dp(40)));
        }
        for (int d = 1; d <= daysInMonth; d++) {
            Calendar day = (Calendar) viewMonth.clone();
            day.set(Calendar.DAY_OF_MONTH, d);

            TextView cell = makeCell(dp(40));
            cell.setText(String.valueOf(d));
            cell.setTextSize(13);

            boolean selected = sameDay(day, selectedDate);
            boolean inWeek = highlightWeek && !day.before(weekMonday) && !day.after(weekSunday);
            if (selected) {
                cell.setBackgroundResource(R.drawable.bg_day_selected);
                cell.setTextColor(color(R.color.white));
                cell.setTypeface(null, Typeface.BOLD);
            } else if (inWeek) {
                cell.setBackgroundResource(R.drawable.bg_day_week_highlight);
                cell.setTextColor(color(R.color.brand_text_dark));
            } else {
                cell.setTextColor(color(R.color.brand_text_dark));
            }

            final Calendar clicked = day;
            cell.setOnClickListener(v -> {
                selectedDate = (Calendar) clicked.clone();
                highlightWeek = false; // 直接点日期：只标中这一天，不整周高亮
                refreshCalendar();
            });
            calendarGrid.addView(cell);
        }
        refreshChips();
    }

    /* ---------------- 年/月选择面板 ---------------- */

    /** 点中间月份标签：展开 / 收起年月面板（仅选年/月模式下常驻，不响应） */
    private void toggleMonthPanel() {
        if (monthOnly) {
            return;
        }
        if (monthPanel.getVisibility() == View.VISIBLE) {
            monthPanel.setVisibility(View.GONE);
        } else {
            panelYear = viewMonth.get(Calendar.YEAR);
            refreshMonthPanel();
            monthPanel.setVisibility(View.VISIBLE);
        }
    }

    /** 重建 12 个月网格；当前显示的月份高亮 */
    private void refreshMonthPanel() {
        yearLabel.setText(getString(R.string.dialog_year_label, panelYear));
        monthGrid.removeAllViews();
        for (int m = 0; m < 12; m++) {
            TextView cell = makeCell(dp(44));
            cell.setText((m + 1) + "月");
            cell.setTextSize(13);
            boolean active = panelYear == viewMonth.get(Calendar.YEAR)
                    && m == viewMonth.get(Calendar.MONTH);
            cell.setBackgroundResource(active
                    ? R.drawable.bg_day_selected : R.drawable.bg_day_week_highlight);
            cell.setTextColor(color(active ? R.color.white : R.color.brand_text_dark));
            final int month = m;
            cell.setOnClickListener(v -> {
                viewMonth.set(Calendar.YEAR, panelYear);
                viewMonth.set(Calendar.MONTH, month);
                viewMonth.set(Calendar.DAY_OF_MONTH, 1);
                // 仅选年/月模式下面板常驻，不收起
                monthPanel.setVisibility(monthOnly ? View.VISIBLE : View.GONE);
                refreshMonthPanel();   // 重新渲染月份高亮（否则选中格不移动）
                refreshCalendar();
            });
            monthGrid.addView(cell);
        }
    }

    /* ---------------- 底部操作 ---------------- */

    private void shiftMonth(int delta) {
        viewMonth.add(Calendar.MONTH, delta);
        // 年/月面板开着时同步刷新（修 bug：箭头切月后下方月份格子高亮不更新）
        if (monthPanel.getVisibility() == View.VISIBLE) {
            panelYear = viewMonth.get(Calendar.YEAR);
            refreshMonthPanel();
        }
        refreshCalendar();
    }

    private void goToday() {
        selectedDate = Calendar.getInstance();
        zeroTime(selectedDate);
        highlightWeek = false;
        viewMonth = firstOfMonth(selectedDate);
        refreshCalendar();
    }

    /** 点「确定」才生效：优先回调直接设置的 listener，否则回退父 Fragment */
    private void confirm() {
        if (monthOnly) {
            // 仅选年/月：返回所选月份的 1 号
            selectedDate = (Calendar) viewMonth.clone();
        }
        if (listener != null) {
            listener.onDatePicked((Calendar) selectedDate.clone());
        } else {
            Fragment parent = getParentFragment();
            if (parent instanceof OnDatePickedListener) {
                ((OnDatePickedListener) parent).onDatePicked((Calendar) selectedDate.clone());
            }
        }
        dismiss();
    }

    /* ---------------- 工具方法 ---------------- */

    /** 生成一个GridLayout单元格（宽度按列平分） */
    private TextView makeCell(int heightPx) {
        TextView cell = new TextView(requireContext());
        cell.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = heightPx;
        lp.setMargins(dp(2), dp(1), dp(2), dp(1));
        cell.setLayoutParams(lp);
        return cell;
    }

    /** 计算学期周数：该周一相对「学期起点所在周的周一」的第几周（从 1 开始，负数/0 表示未开学） */
    private int computeWeekNumber(Calendar monday) {
        // 学期起点允许是第一周任意一天，统一折算到那一周的周一再算
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(semesterStart);
        long startMonday = mondayOf(start).getTimeInMillis();
        return (int) ((monday.getTimeInMillis() - startMonday) / WEEK_MS) + 1;
    }

    /** 该月 1 号前面需要补的空格数（周一为第一列） */
    private static int leadDays(Calendar firstOfMonth) {
        int dow = firstOfMonth.get(Calendar.DAY_OF_WEEK);
        return (dow + 5) % 7;
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 把日历清零到当天 0:00:00 */
    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /** 判断两个日历是否同一天（年月日） */
    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
                && a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH);
    }

    /** 返回某天所在周的周一（零点） */
    private static Calendar mondayOf(Calendar c) {
        Calendar result = (Calendar) c.clone();
        zeroTime(result);
        int dow = result.get(Calendar.DAY_OF_WEEK);
        int diff = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        result.add(Calendar.DAY_OF_MONTH, -diff);
        return result;
    }

    /** 返回某天所在月的 1 号（零点） */
    private static Calendar firstOfMonth(Calendar c) {
        Calendar result = (Calendar) c.clone();
        zeroTime(result);
        result.set(Calendar.DAY_OF_MONTH, 1);
        return result;
    }
}
