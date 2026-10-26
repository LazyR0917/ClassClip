package com.example.classclip.todo;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.common.DatePickerDialog;
import com.example.classclip.common.HorizontalSwipeLayout;
import com.example.classclip.timetable.TimetableFragment;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 2 页：待办列表（真实数据，复用 todos 表）。
 * - 三种视图切换：本周（日期条+当天列表）/ 本月（月历网格）/ 全部（所有待办列表）
 * - 未完成 / 已完成 两组，均可展开/收起
 * - 左滑卡片露出删除按钮（复用第 1 页明细页交互），点删除弹确认
 * - 日期条：左右滑切周；右侧日历按钮选日期
 */
public class TodoFragment extends Fragment implements DatePickerDialog.OnDatePickedListener {

    private static final String[] DAY_LABELS = {"一", "二", "三", "四", "五", "六", "日"};

    /** 月历格子内最多显示几条待办标签，超出显示 "+N"；按 优先级→时间 排序后取前几条 */
    private static final int MAX_MONTH_CHIPS = 4;

    /** 中文月份（与月历样式一致：九月 / 十月） */
    private static final String[] MONTH_CN = {"一", "二", "三", "四", "五", "六",
            "七", "八", "九", "十", "十一", "十二"};

    /** 视图模式 */
    private static final int MODE_WEEK = 0;
    private static final int MODE_MONTH = 1;
    private static final int MODE_ALL = 2;
    private int mode = MODE_WEEK;

    private LinearLayout dateStrip;
    private View dateStripRow;
    private TextView tvMonthTitle;
    private View monthView;
    private LinearLayout monthContent;
    private RecyclerView rvTodo;
    private TodoAdapter adapter;
    private HorizontalSwipeLayout swipeLayout;

    /** 当前选中的日期（本地时区，零点） */
    private Calendar selectedDate;
    /** 周视图是否「选中了某天」：true=只看该天，false=显示整周（默认） */
    private boolean weekDaySelected = false;
    private boolean weekAnimRunning;
    private boolean monthAnimRunning;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_todo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dateStrip = view.findViewById(R.id.todo_date_strip);
        dateStripRow = view.findViewById(R.id.todo_date_strip_row);
        tvMonthTitle = view.findViewById(R.id.tv_todo_month_title);
        monthView = view.findViewById(R.id.todo_month_view);
        monthContent = view.findViewById(R.id.todo_month_content);
        rvTodo = view.findViewById(R.id.rv_todo);

        selectedDate = Calendar.getInstance();
        zeroTime(selectedDate);

        view.findViewById(R.id.btn_pick_todo_date).setOnClickListener(v -> openPicker());
        // 月待办表：点月份标题弹年/月选择
        tvMonthTitle.setOnClickListener(v -> showYearMonthPicker());

        // 右上角 ＋：新建待办（默认日期=当前选中天，时间不预填）
        view.findViewById(R.id.btn_add_todo).setOnClickListener(v ->
                startActivity(TodoAddActivity.intentOf(requireContext(),
                        selectedDate.getTimeInMillis())));

        // 视图切换 chips
        View chipWeek = view.findViewById(R.id.chip_todo_week);
        View chipMonth = view.findViewById(R.id.chip_todo_month);
        View chipAll = view.findViewById(R.id.chip_todo_all);
        chipWeek.setOnClickListener(v -> setMode(MODE_WEEK));
        chipMonth.setOnClickListener(v -> setMode(MODE_MONTH));
        chipAll.setOnClickListener(v -> setMode(MODE_ALL));

        // 待办页内部横向手势：周待办表切周、月待办表切月（HorizontalSwipeLayout 拦截，不翻页）
        swipeLayout = (HorizontalSwipeLayout) view;
        swipeLayout.setOnHorizontalSwipe(toLeft -> {
            if (mode == MODE_WEEK) {
                switchWeek(toLeft ? 7 : -7);
                return true;
            }
            if (mode == MODE_MONTH) {
                switchMonth(toLeft ? 1 : -1);
                return true;
            }
            return false;
        });

        rvTodo.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TodoAdapter(this::openEdit, this::confirmDelete);
        rvTodo.setAdapter(adapter);
        TodoSwipeDelete.attach(rvTodo, adapter, this::resetTodo);

        setMode(MODE_WEEK);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCurrentView();
    }

    /* ---------------- 视图切换 ---------------- */

    private void setMode(int newMode) {
        this.mode = newMode;
        // chips 选中态
        setChipSelected(getView().findViewById(R.id.chip_todo_week), newMode == MODE_WEEK);
        setChipSelected(getView().findViewById(R.id.chip_todo_month), newMode == MODE_MONTH);
        setChipSelected(getView().findViewById(R.id.chip_todo_all), newMode == MODE_ALL);

        boolean isMonth = newMode == MODE_MONTH;
        // 日期条只在「本周」显示：「本月」有自己的月历标题，「全部」不限日期
        dateStripRow.setVisibility(newMode == MODE_WEEK ? View.VISIBLE : View.GONE);
        tvMonthTitle.setVisibility(isMonth ? View.VISIBLE : View.GONE);
        monthView.setVisibility(isMonth ? View.VISIBLE : View.GONE);
        rvTodo.setVisibility(isMonth ? View.GONE : View.VISIBLE);

        // 切周/切月热区：本周=日期条（星期条），本月=月历区域；避免占用待办条目左滑手势
        // （全部视图不响应横向滑动）
        if (swipeLayout != null) {
            if (newMode == MODE_WEEK) {
                swipeLayout.setSwipeHotspot(dateStripRow);
            } else if (newMode == MODE_MONTH) {
                swipeLayout.setSwipeHotspot(monthView);
            } else {
                swipeLayout.setSwipeHotspot(null);
            }
        }

        refreshCurrentView();
    }

    private void setChipSelected(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
        chip.setTextColor(ContextCompat.getColor(requireContext(),
                selected ? R.color.white : R.color.brand_text_dark));
    }

    private void refreshCurrentView() {
        if (getView() == null) return;
        if (mode == MODE_MONTH) {
            refreshMonthView();
        } else {
            refreshDateStrip();
            adapter.setMode(mode);
            adapter.reload(loadTodosForMode(mode));
        }
    }

    /** 按当前模式加载待办：本周（未选中某天=整周，选中某天=当天），全部=所有 */
    private List<DBHelper.Todo> loadTodosForMode(int mode) {
        DBHelper db = DBHelper.getInstance(requireContext());
        if (mode == MODE_WEEK) {
            if (weekDaySelected) {
                // 选中了某天：只显示当天
                return db.getTodosOfDay(selectedDate.getTimeInMillis());
            }
            Calendar monday = mondayOf(selectedDate);
            Calendar sunday = (Calendar) monday.clone();
            sunday.add(Calendar.DAY_OF_MONTH, 6);
            return db.getTodosBetween(monday.getTimeInMillis(), sunday.getTimeInMillis());
        }
        // MODE_ALL：拉一个大范围（足够覆盖所有）
        Calendar from = Calendar.getInstance();
        from.add(Calendar.YEAR, -1);
        Calendar to = Calendar.getInstance();
        to.add(Calendar.YEAR, 1);
        return db.getTodosBetween(from.getTimeInMillis(), to.getTimeInMillis());
    }

    /* ---------------- 手势：左右滑切周 / 切月（HorizontalSwipeLayout 拦截） ---------------- */

    /** 月待办表：左右滑切上/下个月（与切周一致的滑出滑入动画） */
    private void switchMonth(int months) {
        if (monthAnimRunning) return;
        monthAnimRunning = true;
        float exitX = months > 0 ? -dp(48) : dp(48);
        monthView.animate().translationX(exitX).alpha(0f).setDuration(130)
                .withEndAction(() -> {
                    selectedDate.add(Calendar.MONTH, months);
                    refreshMonthView();
                    monthView.setTranslationX(-exitX);
                    monthView.setAlpha(0f);
                    monthView.animate().translationX(0f).alpha(1f).setDuration(160)
                            .withEndAction(() -> monthAnimRunning = false).start();
                }).start();
    }

    private void switchWeek(int days) {
        if (weekAnimRunning) return;
        weekAnimRunning = true;
        float exitX = days > 0 ? -dp(48) : dp(48);
        dateStrip.animate().translationX(exitX).alpha(0f).setDuration(130)
                .withEndAction(() -> {
                    selectedDate.add(Calendar.DAY_OF_MONTH, days);
                    weekDaySelected = false;   // 换周后回到整周视图
                    refreshDateStrip();
                    adapter.reload(loadTodosForMode(mode));   // 切周后刷新列表
                    dateStrip.setTranslationX(-exitX);
                    dateStrip.setAlpha(0f);
                    dateStrip.animate().translationX(0f).alpha(1f).setDuration(160)
                            .withEndAction(() -> weekAnimRunning = false).start();
                }).start();
    }

    /* ---------------- 本月视图：月历网格 ---------------- */

    private void refreshMonthView() {
        monthContent.removeAllViews();
        Calendar first = (Calendar) selectedDate.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        zeroTime(first);

        tvMonthTitle.setText(MONTH_CN[first.get(Calendar.MONTH)] + "月");

        // 该月 1 号是周几（0=周一）
        int firstDow = first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (firstDow < 0) firstDow += 7;

        // 月首所在周的周一
        Calendar gridStart = (Calendar) first.clone();
        gridStart.add(Calendar.DAY_OF_MONTH, -firstDow);

        // 该月有多少天
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);

        // 周标题行：一 二 三 四 五 六 日（单字、灰、居中）
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(6), 0, dp(6));
        for (String d : DAY_LABELS) {
            TextView tv = new TextView(requireContext());
            tv.setText(d);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_text_gray));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, dp(28), 1f);
            hlp.setMargins(dp(2), 0, dp(2), 0);
            header.addView(tv, hlp);
        }
        monthContent.addView(header);

        // 查整个网格范围（最多 6 周）的待办，按日期分组
        Calendar gridEnd = (Calendar) gridStart.clone();
        gridEnd.add(Calendar.DAY_OF_MONTH, 41);
        List<DBHelper.Todo> monthTodos = DBHelper.getInstance(requireContext())
                .getTodosBetween(gridStart.getTimeInMillis(), gridEnd.getTimeInMillis());
        Map<String, List<DBHelper.Todo>> todoByDay = new HashMap<>();
        for (DBHelper.Todo t : monthTodos) {
            String key = dayKey(t.dateMillis);
            todoByDay.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        // 每天内部排序：优先级高在前，同级按时间早在前（无时间排最后）
        for (List<DBHelper.Todo> list : todoByDay.values()) {
            list.sort(TodoFragment::compareTodo);
        }

        // 生成 6 行 × 7 列
        Calendar cell = (Calendar) gridStart.clone();
        for (int week = 0; week < 6; week++) {
            // 最后一行若整行都在下月则省略
            if (week == 5 && cell.get(Calendar.MONTH) != first.get(Calendar.MONTH)
                    && cell.get(Calendar.DAY_OF_MONTH) > 7) {
                break;
            }
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 7; col++) {
                boolean inMonth = cell.get(Calendar.MONTH) == first.get(Calendar.MONTH);
                boolean isToday = isSameDay(cell, selectedDate);
                row.addView(makeMonthCell(cell, inMonth, isToday,
                        todoByDay.get(dayKey(cell.getTimeInMillis()))));
                cell.add(Calendar.DAY_OF_MONTH, 1);
            }
            monthContent.addView(row);
        }
    }

    private View makeMonthCell(Calendar day, boolean inMonth, boolean isToday,
                               List<DBHelper.Todo> todos) {
        boolean hasTodo = todos != null && !todos.isEmpty();

        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        // 高度交给行内最大内容决定，同列等高（行高随待办条数撑开）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(6));
        cell.setLayoutParams(lp);
        cell.setPadding(dp(2), dp(4), dp(2), dp(6));
        cell.setMinimumHeight(dp(70));

        // 日期数字：居中；今天用品牌色实心圆 + 白字
        LinearLayout numRow = new LinearLayout(requireContext());
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.CENTER);
        numRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        TextView num = new TextView(requireContext());
        num.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
        num.setTextSize(15);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setGravity(Gravity.CENTER);
        int numColor;
        if (isToday) {
            numColor = ContextCompat.getColor(requireContext(), R.color.white);
        } else if (!inMonth) {
            numColor = ContextCompat.getColor(requireContext(), R.color.brand_text_gray);
        } else {
            numColor = ContextCompat.getColor(requireContext(), R.color.brand_text_dark);
        }
        num.setTextColor(numColor);
        if (isToday) {
            num.setBackgroundResource(R.drawable.bg_month_today_circle);
            numRow.addView(num, new LinearLayout.LayoutParams(dp(30), dp(30)));
        } else {
            numRow.addView(num, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        cell.addView(numRow);

        // 待办小标签：圆角浅底 + 同色系深字（最多 MAX_MONTH_CHIPS 条 + "+N"）
        if (hasTodo) {
            int show = Math.min(todos.size(), MAX_MONTH_CHIPS);
            for (int i = 0; i < show; i++) {
                DBHelper.Todo t = todos.get(i);
                TextView chip = new TextView(requireContext());
                chip.setText(t.title);
                chip.setTextSize(10);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setPadding(dp(5), dp(3), dp(5), dp(3));
                int colorIdx = (int) (t.id % TimetableFragment.COLOR_BG.length);
                chip.setBackground(roundedBg(ContextCompat.getColor(requireContext(),
                        TimetableFragment.COLOR_BG[colorIdx]), 6));
                chip.setTextColor(ContextCompat.getColor(requireContext(),
                        TimetableFragment.COLOR_TEXT[colorIdx]));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(3);
                cell.addView(chip, clp);
            }
            if (todos.size() > MAX_MONTH_CHIPS) {
                TextView more = new TextView(requireContext());
                more.setText("+" + (todos.size() - MAX_MONTH_CHIPS));
                more.setTextSize(10);
                more.setPadding(dp(5), dp(2), dp(5), 0);
                more.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_text_gray));
                cell.addView(more);
            }
        }

        // 非本月日期整体淡出，弱化但保留待办提示
        if (!inMonth) {
            cell.setAlpha(0.38f);
        }

        // 点日期：有待办的打开该天待办列表整页；空格子直接新建该天的待办
        final long dayMillis = day.getTimeInMillis();
        cell.setBackground(rippleBg());
        if (hasTodo) {
            cell.setOnClickListener(v ->
                    startActivity(TodoDayListActivity.intentOf(requireContext(), dayMillis)));
        } else {
            cell.setOnClickListener(v ->
                    startActivity(TodoAddActivity.intentOf(requireContext(), dayMillis)));
        }
        return cell;
    }

    /** 圆角纯色底（月历待办小标签用） */
    private android.graphics.drawable.GradientDrawable roundedBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    /** 系统点按水波纹（月历有待办的格子） */
    private android.graphics.drawable.Drawable rippleBg() {
        android.util.TypedValue out = new android.util.TypedValue();
        requireContext().getTheme()
                .resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId == 0
                ? null : ContextCompat.getDrawable(requireContext(), out.resourceId);
    }

    /** 月历排序：优先级高在前，同级按时间早在前（无时间排最后） */
    private static int compareTodo(DBHelper.Todo a, DBHelper.Todo b) {
        if (a.priority != b.priority) {
            return Integer.compare(b.priority, a.priority);
        }
        return timeKey(a.time).compareTo(timeKey(b.time));
    }

    private static String timeKey(String time) {
        return (time == null || time.isEmpty()) ? "99:99" : time;
    }

    private String dayKey(long millis) {        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.MONTH) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
                && a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH);
    }

    /* ---------------- 日历选择 ---------------- */

    /** 月待办表：点月份标题 → 年/月选择（与周待办表同款弹窗，直接展开年/月面板） */
    private void showYearMonthPicker() {
        DatePickerDialog dialog =
                DatePickerDialog.newInstanceYearMonth(selectedDate.getTimeInMillis());
        dialog.setOnDatePickedListener(date -> {
            selectedDate = date;   // 所选月份的 1 号
            zeroTime(selectedDate);
            refreshMonthView();
        });
        dialog.show(getChildFragmentManager(), "todo_month_picker");
    }

    private void openPicker() {
        DatePickerDialog dialog = DatePickerDialog.newInstance(selectedDate.getTimeInMillis(), false);
        dialog.show(getChildFragmentManager(), "todo_date_picker");
    }

    @Override
    public void onDatePicked(Calendar date) {
        selectedDate = date;
        weekDaySelected = true;   // 从日历选了某天 → 选中该天
        refreshCurrentView();
    }

    /* ---------------- 日期条 ---------------- */

    private void refreshDateStrip() {
        zeroTime(selectedDate);
        Calendar monday = mondayOf(selectedDate);
        long diff = selectedDate.getTimeInMillis() - monday.getTimeInMillis();
        int selectedIndex = (int) (diff / (24L * 60 * 60 * 1000));

        dateStrip.removeAllViews();
        for (int i = 0; i < 7; i++) {
            Calendar day = (Calendar) monday.clone();
            day.add(Calendar.DAY_OF_MONTH, i);
            // 只有「选中了某天」且正好是这天才高亮；未选中时整条无选中态
            boolean isSelected = weekDaySelected && i == selectedIndex;

            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
            cellParams.setMargins(dp(3), 0, dp(3), 0);
            cell.setLayoutParams(cellParams);
            cell.setBackgroundResource(isSelected
                    ? R.drawable.bg_day_selected : R.drawable.bg_date_card_normal);

            TextView label = new TextView(requireContext());
            label.setTextSize(10);
            label.setText(DAY_LABELS[i]);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.white : R.color.brand_text_gray));

            TextView number = new TextView(requireContext());
            number.setTextSize(15);
            number.setTypeface(Typeface.DEFAULT_BOLD);
            number.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
            number.setGravity(Gravity.CENTER);
            number.setTextColor(ContextCompat.getColor(requireContext(),
                    isSelected ? R.color.white : R.color.brand_text_dark));

            cell.addView(label);
            cell.addView(number);
            final long dayMillis = day.getTimeInMillis();
            cell.setOnClickListener(v -> {
                // 再次点同一天：取消选中，回到整周；否则选中该天
                if (weekDaySelected && selectedDate.getTimeInMillis() == dayMillis) {
                    weekDaySelected = false;
                } else {
                    weekDaySelected = true;
                    selectedDate.setTimeInMillis(dayMillis);
                    zeroTime(selectedDate);
                }
                refreshCurrentView();
            });
            dateStrip.addView(cell);
        }
    }

    /* ---------------- 左滑删除：见 TodoSwipeDelete（与某天待办列表页共用） ---------------- */

    private void openEdit(DBHelper.Todo todo) {
        startActivity(TodoAddActivity.intentOfEdit(requireContext(), todo.id));
    }

    /** 左滑「重置」：把已完成待办变回未完成 */
    private void resetTodo(DBHelper.Todo todo) {
        if (!todo.done) {
            Toast.makeText(requireContext(), R.string.todo_already_pending,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        DBHelper.getInstance(requireContext()).setTodoDone(todo.id, false);
        Toast.makeText(requireContext(), R.string.todo_reset_done, Toast.LENGTH_SHORT).show();
        refreshCurrentView();
    }

    private void confirmDelete(DBHelper.Todo todo, int position) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.cell_delete_confirm, todo.title))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper.getInstance(requireContext()).deleteTodo(todo.id);
                    adapter.removeItem(todo);
                })
                .setNegativeButton(R.string.course_action_cancel, (dialog, which) -> { })
                .show();
    }

    /* ---------------- 工具方法 ---------------- */

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static Calendar mondayOf(Calendar c) {
        Calendar result = (Calendar) c.clone();
        zeroTime(result);
        int dow = result.get(Calendar.DAY_OF_WEEK);
        int diff = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        result.add(Calendar.DAY_OF_MONTH, -diff);
        return result;
    }
}
