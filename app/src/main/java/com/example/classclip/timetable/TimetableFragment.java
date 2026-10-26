package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.common.DatePickerDialog;
import com.example.classclip.common.MainActivity;
import com.example.classclip.todo.TodoAddActivity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第 1 页：课程表。
 * - 网格 7 列（周一~周日）x N 行小节（行数 = 当前时间方案的总节数，上/下/晚节数可自定义），
 *   可上下滑动；课程块按起止小节纵向跨行显示；课程数据存 SQLite（courses 表，按课表隔离）
 * - 点空格子弹 tips 选「添加课程 / 添加待办」；点有安排的格子（课程块/带待办的空格子）
 *   进 CellDetailActivity 卡片列表整页（课程在前，左滑可删，＋新建）
 * - 非本周的课程灰阶「非本周」样式；格子里的待办以小标签叠在课程块下方（最多 2 条 +N）
 * - 周选择条：左侧菜单按钮打开侧边抽屉；左右箭头切换周；日历按钮选日期
 * - 学期第一周、上课周数由「设置开学日期」整页写入；上课时间由「上课时间」整页管理
 */
public class TimetableFragment extends Fragment
        implements View.OnClickListener, DatePickerDialog.OnDatePickedListener,
        ImportTimetableDialog.OnImportListener {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 星期标签（供弹窗等同类复用），0=周一 ... 6=周日 */
    public static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 网格参数：行高与左侧时间列宽 */
    private static final int ROW_HEIGHT_DP = 76;
    private static final int LEFT_COLUMN_DP = 44;
    private static final int PAGE_H_PADDING_DP = 16;

    /** 课程块配色盘：下标即数据库 color_index（与 colors.xml 的 course_*_* 一一对应） */
    public static final int[] COLOR_BG = {
            R.color.course_blue_bg, R.color.course_green_bg, R.color.course_purple_bg,
            R.color.course_orange_bg, R.color.course_teal_bg, R.color.course_pink_bg};
    public static final int[] COLOR_TEXT = {
            R.color.course_blue_text, R.color.course_green_text, R.color.course_purple_text,
            R.color.course_orange_text, R.color.course_teal_text, R.color.course_pink_text};

    private TextView tvWeekLabel;
    private LinearLayout dateStrip;
    private LinearLayout gridContainer;
    private TextView tvNextLabel;
    private TextView tvNextName;
    private TextView tvNextTime;
    private final LinearLayout[] dayCells = new LinearLayout[7];
    private final TextView[] dayLabels = new TextView[7];
    private final TextView[] dayNumbers = new TextView[7];

    private Calendar selectedDate;
    /** 切周动画播放中，忽略新的切周请求 */
    private boolean weekAnimRunning;
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("MM.dd", Locale.CHINA);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_timetable, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvWeekLabel = view.findViewById(R.id.tv_week_label);
        dateStrip = view.findViewById(R.id.date_strip);
        gridContainer = view.findViewById(R.id.grid_container);
        tvNextLabel = view.findViewById(R.id.tv_next_label);
        tvNextName = view.findViewById(R.id.tv_next_name);
        tvNextTime = view.findViewById(R.id.tv_next_time);

        view.findViewById(R.id.btn_menu).setOnClickListener(this);
        view.findViewById(R.id.btn_prev_week).setOnClickListener(this);
        view.findViewById(R.id.btn_next_week).setOnClickListener(this);
        view.findViewById(R.id.btn_pick_date).setOnClickListener(this);
        // 主页右上角 ＋：白色选择框 导入课表 / 新建课程
        view.findViewById(R.id.btn_add).setOnClickListener(v -> showTopAddMenu(v));

        // 默认选中今天
        selectedDate = Calendar.getInstance();
        zeroTime(selectedDate);

        buildDateStrip();
        buildGrid();
        refresh();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_menu) {
            // 打开侧边抽屉
            ((MainActivity) requireActivity()).openDrawer();
        } else if (id == R.id.btn_prev_week) {
            animateWeekSwitch(-7);
        } else if (id == R.id.btn_next_week) {
            animateWeekSwitch(7);
        } else if (id == R.id.btn_pick_date) {
            onPickDateClick();
        }
    }

    /** 切周动画：旧一周向按钮方向滑出，新一周滑入，周标签同步淡入淡出 */
    private void animateWeekSwitch(int days) {
        if (weekAnimRunning) return;
        weekAnimRunning = true;

        float exitX = days > 0 ? -dp(48) : dp(48);
        dateStrip.animate().translationX(exitX).alpha(0f).setDuration(130).start();
        tvWeekLabel.animate().alpha(0f).setDuration(130)
                .withEndAction(() -> {
                    selectedDate.add(Calendar.DAY_OF_MONTH, days);
                    refresh();
                    rebuildGrid(); // 课程块按周过滤，切周后需重建
                    dateStrip.setTranslationX(-exitX);
                    dateStrip.setAlpha(0f);
                    dateStrip.animate().translationX(0f).alpha(1f).setDuration(160).start();
                    tvWeekLabel.animate().alpha(1f).setDuration(160)
                            .withEndAction(() -> weekAnimRunning = false).start();
                }).start();
    }

    /* ---------------- 日历选择 ---------------- */

    private void onPickDateClick() {
        // 自定义弹窗：支持点日期 + 周数 chip 选周 + 年/月面板
        DatePickerDialog dialog = DatePickerDialog.newInstance(selectedDate.getTimeInMillis());
        dialog.show(getChildFragmentManager(), "date_picker");
    }

    @Override
    public void onDatePicked(Calendar date) {
        // 弹窗「确定」后回调；日历选日期跳转不加切周动画，直接刷新
        selectedDate = date;
        refresh();
        rebuildGrid(); // 课程块按周过滤，换周后需重建
    }

    /* ---------------- 上课时间 / 导入 ---------------- */

    /** 供侧边抽屉调用：打开「导入课程表」弹窗 */
    public void showImportTimetable() {
        ImportTimetableDialog dialog = new ImportTimetableDialog();
        dialog.show(getChildFragmentManager(), "import_timetable");
    }

    @Override
    public void onResume() {
        super.onResume();
        // 上课时间 / 开学日期等在独立页面修改，回到本页时统一刷新
        rebuildGrid();
        refresh();
    }

    @Override
    public void onImportFinished() {
        // 导入新课表后：重建网格
        rebuildGrid();
    }

    /** 重建网格并恢复滚动位置（简单起见：重建后滚回顶部） */
    private void rebuildGrid() {
        gridContainer.removeAllViews();
        buildGrid();
    }

    /* ---------------- 日期条 ---------------- */

    private void buildDateStrip() {
        dateStrip.removeAllViews();
        for (int i = 0; i < 7; i++) {
            final int index = i;

            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    0, dp(56), 1f);
            cellParams.setMargins(dp(2), 0, dp(2), 0);
            cell.setLayoutParams(cellParams);
            cell.setOnClickListener(v -> {
                // 点击日期条：把选中日期切到该天（同一周内）
                Calendar monday = mondayOf(selectedDate);
                monday.add(Calendar.DAY_OF_MONTH, index);
                selectedDate = monday;
                refresh();
                rebuildGrid(); // 课程块按周过滤，换天后需重建
            });

            TextView label = new TextView(requireContext());
            label.setTextSize(10);
            label.setText(DAY_NAMES[i]);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView number = new TextView(requireContext());
            number.setTextSize(15);
            number.setTypeface(Typeface.DEFAULT_BOLD);
            number.setGravity(Gravity.CENTER);
            number.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            cell.addView(label);
            cell.addView(number);
            dateStrip.addView(cell);

            dayCells[i] = cell;
            dayLabels[i] = label;
            dayNumbers[i] = number;
        }
    }

    /* ---------------- 课程网格 ---------------- */

    /**
     * 网格结构（随方案节数 N 动态变化，整体可上下滑动）：
     * [左侧时间列 N 行] + [FrameLayout 网格区]
     * 网格区底层是 N 行 7 列的空格子（可点击新建），上层按绝对坐标放课程块（跨节纵向拉长）。
     */
    private void buildGrid() {
        gridContainer.removeAllViews();

        // 上课时间（当前方案）、当前使用课表的课程数据都从 SQLite 读取
        List<DBHelper.ClassTime> classTimes = db().getActiveClassTimes();
        List<DBHelper.Course> courses =
                db().getCourses(db().getActiveTimetableId());
        int periodCount = classTimes.size();

        // 当前显示周是第几周（1~总周数）；算不出（未设开学日期/超出学期）返回 -1，此时全部正常显示
        int weekNumber = currentWeekNumber();

        if (periodCount == 0) {
            TextView empty = new TextView(requireContext());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, 0);
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.brand_text_gray));
            empty.setText(R.string.timetable_no_scheme);
            gridContainer.addView(empty, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        int rowH = dp(ROW_HEIGHT_DP);
        int leftW = dp(LEFT_COLUMN_DP);

        // 可用宽度 = 屏宽 - 页面左右 padding - 左侧时间列
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int gridW = dm.widthPixels - dp(2 * PAGE_H_PADDING_DP) - leftW;
        float colW = gridW / 7f;

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.HORIZONTAL);

        // 左侧时间列：小节序号 + 起止时间
        LinearLayout leftCol = new LinearLayout(requireContext());
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setLayoutParams(new LinearLayout.LayoutParams(leftW, periodCount * rowH));
        for (int p = 0; p < periodCount; p++) {
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setLayoutParams(new LinearLayout.LayoutParams(leftW, rowH));

            DBHelper.ClassTime ct = classTimes.get(p);

            TextView number = new TextView(requireContext());
            number.setTextSize(12);
            number.setTypeface(Typeface.DEFAULT_BOLD);
            number.setTextColor(color(R.color.brand_text_dark));
            number.setGravity(Gravity.CENTER);
            number.setText(String.valueOf(ct.periodIndex));

            TextView start = new TextView(requireContext());
            start.setTextSize(9);
            start.setTextColor(color(R.color.brand_text_gray));
            start.setGravity(Gravity.CENTER);
            start.setText(ct.start);

            TextView end = new TextView(requireContext());
            end.setTextSize(9);
            end.setTextColor(color(R.color.brand_text_gray));
            end.setGravity(Gravity.CENTER);
            end.setText(ct.end);

            cell.addView(number);
            cell.addView(start);
            cell.addView(end);
            leftCol.addView(cell);
        }

        // 当前显示周的待办：一次查出，按 日期+时刻落入的节 分组给格子用
        Calendar monday = mondayOf(selectedDate);
        Calendar sunday = (Calendar) monday.clone();
        sunday.add(Calendar.DAY_OF_MONTH, 6);
        Map<String, List<DBHelper.Todo>> todoMap = new HashMap<>();
        for (DBHelper.Todo t : db().getTodosBetween(
                monday.getTimeInMillis(), sunday.getTimeInMillis())) {
            int period = periodIndexOfTime(classTimes, t.time);
            if (period < 0) continue; // 无时间或不在任何上课区间内：不在格子显示
            String key = t.dateMillis + "-" + period;
            List<DBHelper.Todo> list = todoMap.get(key);
            if (list == null) {
                list = new ArrayList<>();
                todoMap.put(key, list);
            }
            list.add(t);
        }

        // 网格区：底层空格子 + 上层课程块
        FrameLayout gridArea = new FrameLayout(requireContext());
        gridArea.setLayoutParams(new LinearLayout.LayoutParams(gridW, periodCount * rowH));
        gridArea.addView(buildEmptyGrid(periodCount, rowH, todoMap, monday));
        addCourseBlocks(gridArea, courses, classTimes, periodCount, rowH, colW,
                weekNumber, todoMap, monday);

        content.addView(leftCol);
        content.addView(gridArea);
        gridContainer.addView(content);
    }

    /** 底层空格子：N 行 7 列；有待办的格子叠小标签，点击弹安排列表，否则弹新建 tips */
    private LinearLayout buildEmptyGrid(int periodCount, int rowH,
                                        Map<String, List<DBHelper.Todo>> todoMap,
                                        Calendar monday) {
        LinearLayout rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int p = 0; p < periodCount; p++) {
            final int periodNo = p + 1;
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH));

            for (int d = 0; d < 7; d++) {
                final int dayIdx = d;
                final long colMillis = columnMillis(monday, dayIdx);
                final List<DBHelper.Todo> cellTodos = todosAt(todoMap, colMillis, periodNo);

                // 空格子容器：默认居中「—」，有待办时换成小标签
                LinearLayout empty = new LinearLayout(requireContext());
                empty.setOrientation(LinearLayout.VERTICAL);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(2), dp(2), dp(2), dp(2));

                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                cellParams.setMargins(dp(2), dp(2), dp(2), dp(2));
                empty.setLayoutParams(cellParams);

                if (cellTodos == null || cellTodos.isEmpty()) {
                    TextView dash = new TextView(requireContext());
                    dash.setTextSize(11);
                    dash.setGravity(Gravity.CENTER);
                    dash.setTextColor(color(R.color.brand_text_gray));
                    dash.setAlpha(0.4f);
                    dash.setText("—");
                    empty.addView(dash, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    // 空格子：tips 新建（星期/小节由所点格子带入）
                    empty.setOnClickListener(v -> showAddTips(v, dayIdx, periodNo));
                } else {
                    // 只有待办的格子：叠小标签，点击弹安排列表
                    addTodoChips(empty, cellTodos);
                    empty.setOnClickListener(v ->
                            openCellDetail(dayIdx, periodNo, colMillis));
                }

                row.addView(empty);
            }
            rows.addView(row);
        }
        return rows;
    }

    /** 某列日期的毫秒值（monday + dayIdx 天，零点） */
    private long columnMillis(Calendar monday, int dayIdx) {
        Calendar c = (Calendar) monday.clone();
        c.add(Calendar.DAY_OF_MONTH, dayIdx);
        return c.getTimeInMillis();
    }

    private List<DBHelper.Todo> todosAt(Map<String, List<DBHelper.Todo>> todoMap,
                                        long colMillis, int periodNo) {
        return todoMap.get(colMillis + "-" + periodNo);
    }

    /** 在格子/课程块下方叠待办小标签：最多 2 条标题，超出显示 +N；小标签用调色板浅色底+深字 */
    private void addTodoChips(ViewGroup host, List<DBHelper.Todo> todos) {
        int show = Math.min(todos.size(), 2);
        for (int i = 0; i < show; i++) {
            DBHelper.Todo t = todos.get(i);
            TextView chip = new TextView(requireContext());
            chip.setTextSize(8);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), 0, dp(4), 0);
            chip.setText(t.title);
            int colorIdx = (int) (t.id % COLOR_BG.length);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color(COLOR_BG[colorIdx]));
            bg.setCornerRadius(dp(4));
            chip.setBackground(bg);
            chip.setTextColor(color(COLOR_TEXT[colorIdx]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
            lp.topMargin = dp(2);
            host.addView(chip, lp);
        }
        if (todos.size() > show) {
            TextView more = new TextView(requireContext());
            more.setTextSize(8);
            more.setTextColor(color(R.color.brand_text_gray));
            more.setGravity(Gravity.CENTER);
            more.setText("+" + (todos.size() - show));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
            lp.topMargin = dp(2);
            host.addView(more, lp);
        }
    }

    /** 主页右上角 ＋：白色选择框 导入课表 / 新建课程（新建默认今天、第 1 节，进页面可改） */
    private void showTopAddMenu(View anchor) {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_tips_menu);
        content.setElevation(dp(8));

        final PopupWindow popup = new PopupWindow(content,
                dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);

        TextView importItem = makeTipsItem(R.string.drawer_import);
        content.addView(importItem);
        View divider = new View(requireContext());
        divider.setBackgroundColor(color(R.color.card_stroke));
        content.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        TextView newCourseItem = makeTipsItem(R.string.menu_new_course);
        content.addView(newCourseItem);

        importItem.setOnClickListener(v -> {
            popup.dismiss();
            showImportTimetable();
        });
        newCourseItem.setOnClickListener(v -> {
            popup.dismiss();
            int todayIdx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                    - Calendar.MONDAY + 7) % 7;
            startActivity(CourseAddActivity.intentOf(requireContext(), todayIdx, 1, 1));
        });

        popup.showAsDropDown(anchor, 0, dp(6));
    }

    /** 点空格子的 tips 小弹窗：选添加课程 / 添加待办 */
    private void showAddTips(View anchor, final int dayIdx, final int periodNo) {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_tips_menu);
        content.setElevation(dp(8));

        final PopupWindow popup = new PopupWindow(content,
                dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);

        TextView courseItem = makeTipsItem(R.string.grid_add_course);
        content.addView(courseItem);
        View divider = new View(requireContext());
        divider.setBackgroundColor(color(R.color.card_stroke));
        content.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        TextView todoItem = makeTipsItem(R.string.grid_add_todo);
        content.addView(todoItem);

        courseItem.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(CourseAddActivity.intentOf(
                    requireContext(), dayIdx, periodNo, periodNo));
        });
        todoItem.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(TodoAddActivity.intentOf(requireContext(),
                    columnMillis(mondayOf(selectedDate), dayIdx), periodNo));
        });

        // 弹在所点格子下方；贴底时翻到上方
        content.measure(View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean below = loc[1] + anchor.getHeight() + content.getMeasuredHeight()
                < screenH - dp(80);
        if (below) {
            popup.showAsDropDown(anchor, 0, dp(4));
        } else {
            popup.showAsDropDown(anchor, 0,
                    -(anchor.getHeight() + content.getMeasuredHeight()) - dp(4));
        }
    }

    private TextView makeTipsItem(int textRes) {
        TextView item = new TextView(requireContext());
        item.setText(textRes);
        item.setTextSize(15);
        item.setTextColor(color(R.color.brand_text_dark));
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(14), 0, dp(14));
        return item;
    }

    /** 上层课程块：按起止小节绝对定位，纵向跨行；非本周灰阶「非本周」样式，块内可叠待办小标签 */
    private void addCourseBlocks(FrameLayout gridArea, List<DBHelper.Course> courses,
                                 List<DBHelper.ClassTime> classTimes,
                                 int periodCount, int rowH, float colW, int weekNumber,
                                 Map<String, List<DBHelper.Todo>> todoMap, Calendar monday) {
        for (DBHelper.Course course : courses) {
            int start = course.startPeriod;
            int end = course.endPeriod;
            if (start < 1 || end < start || start > periodCount) continue;
            if (end > periodCount) end = periodCount;
            int span = end - start + 1;

            // 非本周的课程：本周视图下不显示（用户明确要求只显示本周的课）
            boolean dim = weekNumber > 0
                    && (course.weekStart > weekNumber || course.weekEnd < weekNumber);
            if (dim) {
                continue;
            }

            int colorIndex = course.colorIndex;
            if (colorIndex < 0 || colorIndex >= COLOR_BG.length) {
                colorIndex = 0;
            }

            LinearLayout block = new LinearLayout(requireContext());
            block.setOrientation(LinearLayout.VERTICAL);
            // 正常块内容整体居中
            block.setGravity(Gravity.CENTER);
            block.setPadding(0, 0, 0, dp(2));
            block.setClickable(true);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color(COLOR_BG[colorIndex]));
            bg.setCornerRadius(dp(12));
            block.setBackground(bg);

            int textColor = color(COLOR_TEXT[colorIndex]);

            TextView name = new TextView(requireContext());
            name.setTextSize(10);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(textColor);
            name.setText(course.name);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dp(2);
            block.addView(name, nameLp);

            TextView room = new TextView(requireContext());
            room.setTextSize(9);
            room.setGravity(Gravity.CENTER);
            room.setTextColor(textColor);
            room.setAlpha(0.75f);
            room.setText(course.room);
            block.addView(room);

            // 单元格内显示任课老师（导入或添加时填写过才显示）
            if (!TextUtils.isEmpty(course.teacher)) {
                TextView teacher = new TextView(requireContext());
                teacher.setTextSize(9);
                teacher.setGravity(Gravity.CENTER);
                teacher.setTextColor(textColor);
                teacher.setAlpha(0.75f);
                teacher.setText(course.teacher);
                block.addView(teacher);
            }

            // 点击课程块：弹该格子的安排列表（课程 + 待办）
            final long colMillis = columnMillis(monday, course.day);
            final int cellDay = course.day;
            final int cellPeriod = course.startPeriod;

            // 该格子的待办：画在课程块外面（自有彩色小标签，不包进课程框）
            List<DBHelper.Todo> cellTodos =
                    todosAt(todoMap, columnMillis(monday, course.day), course.startPeriod);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    (int) colW - dp(4), span * rowH - dp(4));
            lp.leftMargin = (int) (course.day * colW) + dp(2);
            lp.topMargin = (start - 1) * rowH + dp(2);

            if (cellTodos != null && !cellTodos.isEmpty()) {
                // 上下排布：课程块占满剩余空间，待办小标签贴在下方（与课程框分离、自有配色）
                LinearLayout wrapper = new LinearLayout(requireContext());
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.setLayoutParams(lp);
                wrapper.setClickable(true);
                wrapper.setOnClickListener(v ->
                        openCellDetail(cellDay, cellPeriod, colMillis));

                // 课程块自身也是 clickable 的，必须同样绑监听，否则点击被吞掉
                block.setOnClickListener(v ->
                        openCellDetail(cellDay, cellPeriod, colMillis));
                block.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                wrapper.addView(block);

                LinearLayout chipBox = new LinearLayout(requireContext());
                chipBox.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams chipBoxLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                chipBoxLp.topMargin = dp(3);
                wrapper.addView(chipBox, chipBoxLp);
                addTodoChips(chipBox, cellTodos);

                gridArea.addView(wrapper);
            } else {
                block.setOnClickListener(v ->
                        openCellDetail(cellDay, cellPeriod, colMillis));
                block.setLayoutParams(lp);
                gridArea.addView(block);
            }
        }
    }

    /** 打开某格子的安排列表整页（课程 + 待办，＋新建，左滑删除） */
    private void openCellDetail(int day, int period, long colMillis) {
        startActivity(CellDetailActivity.intentOf(
                requireContext(), day, period, colMillis, periodTimeText(period)));
    }

    /** 某小节的 "8:00 - 9:40" 文案（无数据时返回空串） */
    private String periodTimeText(int periodNo) {
        List<DBHelper.ClassTime> classTimes = db().getActiveClassTimes();
        for (DBHelper.ClassTime ct : classTimes) {
            if (ct.periodIndex == periodNo) {
                return ct.start + " - " + ct.end;
            }
        }
        return "";
    }

    /* ---------------- 状态刷新 ---------------- */

    private void refresh() {
        zeroTime(selectedDate);
        Calendar monday = mondayOf(selectedDate);

        // 周标签：设置了第一周且周数在 1~总周数 内显示"第 X 周"，否则只显示日期范围
        DBHelper db = db();
        Calendar sunday = (Calendar) monday.clone();
        sunday.add(Calendar.DAY_OF_MONTH, 6);
        if (db.hasSemesterStart()) {
            int week = computeWeekNumber(monday, db.getSemesterStartMillis());
            if (week >= 1 && week <= db.getSemesterWeeks()) {
                tvWeekLabel.setText(getString(R.string.timetable_week_label,
                        week, dayFormat.format(monday.getTime()), dayFormat.format(sunday.getTime())));
            } else {
                tvWeekLabel.setText(getString(R.string.timetable_week_range,
                        dayFormat.format(monday.getTime()), dayFormat.format(sunday.getTime())));
            }
        } else {
            tvWeekLabel.setText(getString(R.string.timetable_week_range,
                    dayFormat.format(monday.getTime()), dayFormat.format(sunday.getTime())));
        }

        // 日期条：从周一到周日
        for (int i = 0; i < 7; i++) {
            Calendar day = (Calendar) monday.clone();
            day.add(Calendar.DAY_OF_MONTH, i);

            boolean isSelected = sameDay(day, selectedDate);
            if (isSelected) {
                dayCells[i].setBackgroundResource(R.drawable.bg_day_selected);
                dayLabels[i].setTextColor(color(R.color.white));
                dayNumbers[i].setTextColor(color(R.color.white));
            } else {
                dayCells[i].setBackgroundResource(android.R.color.transparent);
                dayLabels[i].setTextColor(color(R.color.brand_text_gray));
                dayNumbers[i].setTextColor(color(R.color.brand_text_dark));
            }
            dayNumbers[i].setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
        }

        refreshNextCourse();
    }

    /* ---------------- 下一节课卡片 ---------------- */

    /** 按当前真实时间找下一节要上的课，填底部卡片；今天无课依次看明天/后天/N天后/下周X */
    private void refreshNextCourse() {
        DBHelper db = db();
        List<DBHelper.Course> courses = db.getCourses(db.getActiveTimetableId());
        List<DBHelper.ClassTime> times = db.getActiveClassTimes();
        long now = System.currentTimeMillis();

        DBHelper.Course best = null;
        DBHelper.ClassTime bestStartCt = null;
        DBHelper.ClassTime bestEndCt = null;
        long bestStart = Long.MAX_VALUE;
        int bestOffset = -1;
        int bestDayIdx = -1;

        for (int offset = 0; offset < 14; offset++) {
            Calendar date = Calendar.getInstance();
            date.add(Calendar.DAY_OF_MONTH, offset);
            int dayIdx = (date.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;

            // 该日所在周的周数；不在学期内视为 -1（不区分周过滤，和网格一致）
            int week = -1;
            if (db.hasSemesterStart()) {
                Calendar monday = mondayOf(date);
                int w = computeWeekNumber(monday, db.getSemesterStartMillis());
                if (w >= 1 && w <= db.getSemesterWeeks()) {
                    week = w;
                }
            }

            for (DBHelper.Course c : courses) {
                if (c.day != dayIdx) continue;
                if (week > 0 && (c.weekStart > week || c.weekEnd < week)) continue;
                DBHelper.ClassTime ct = classTimeOf(times, c.startPeriod);
                if (ct == null) continue;
                long startMillis = atTimeMillis(date, ct.start);
                if (startMillis <= now || startMillis >= bestStart) continue;
                best = c;
                bestStart = startMillis;
                bestStartCt = ct;
                bestEndCt = classTimeOf(times, c.endPeriod);
                bestOffset = offset;
                bestDayIdx = dayIdx;
            }
        }

        if (best == null) {
            tvNextLabel.setText(R.string.timetable_next_label);
            tvNextName.setText(R.string.timetable_next_none);
            tvNextTime.setText("");
            return;
        }

        // 距离文案：N分钟后 / 今天 HH:mm / 明天 / 后天 / N天后 / 下周X
        String dist;
        if (bestOffset == 0) {
            long minutes = (bestStart - now) / 60000;
            dist = minutes < 60 ? minutes + "分钟后" : "今天 " + bestStartCt.start;
        } else if (bestOffset == 1) {
            dist = "明天";
        } else if (bestOffset == 2) {
            dist = "后天";
        } else if (bestOffset < 7) {
            dist = bestOffset + "天后";
        } else {
            dist = "下周" + DAY_NAMES[bestDayIdx];
        }

        tvNextLabel.setText("下一节 · " + best.name + " " + dist);
        tvNextName.setText(best.name
                + (TextUtils.isEmpty(best.room) ? "" : " · " + best.room));
        StringBuilder timeLine = new StringBuilder(bestStartCt.start);
        if (bestEndCt != null) {
            timeLine.append(" - ").append(bestEndCt.end);
        }
        if (!TextUtils.isEmpty(best.teacher)) {
            timeLine.append(" · ").append(best.teacher);
        }
        tvNextTime.setText(timeLine);
    }

    private DBHelper.ClassTime classTimeOf(List<DBHelper.ClassTime> times, int periodNo) {
        for (DBHelper.ClassTime ct : times) {
            if (ct.periodIndex == periodNo) {
                return ct;
            }
        }
        return null;
    }

    /** HH:mm 转分钟数（兼容 "HH:mm-HH:mm" 区间取开始时刻；解析失败返回 -1） */
    private static int toMinutesOfDay(String hhmm) {
        try {
            String time = hhmm.split("-")[0].trim();
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 待办时刻落在哪一节课区间内（含端点）；无时间或不在任何区间返回 -1 */
    private int periodIndexOfTime(List<DBHelper.ClassTime> times, String hhmm) {
        int minutes = toMinutesOfDay(hhmm);
        if (minutes < 0) {
            return -1;
        }
        for (DBHelper.ClassTime ct : times) {
            int start = toMinutesOfDay(ct.start);
            int end = toMinutesOfDay(ct.end);
            if (start >= 0 && end >= 0 && minutes >= start && minutes <= end) {
                return ct.periodIndex;
            }
        }
        return -1;
    }

    /** 某天的 HH:mm 时刻毫秒值 */
    private long atTimeMillis(Calendar date, String hhmm) {
        String[] parts = hhmm.split(":");
        Calendar c = (Calendar) date.clone();
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private int computeWeekNumber(Calendar monday, long semesterStartMillis) {
        // 学期起点允许是第一周任意一天，统一折算到那一周的周一再算
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(semesterStartMillis);
        long startMonday = mondayOf(start).getTimeInMillis();
        long diff = monday.getTimeInMillis() - startMonday;
        return (int) (diff / (7 * DAY_MS)) + 1;
    }

    /** 当前显示周的周数（1~总周数）；未设开学日期或超出学期范围返回 -1 */
    private int currentWeekNumber() {
        DBHelper db = db();
        if (!db.hasSemesterStart()) {
            return -1;
        }
        int week = computeWeekNumber(mondayOf(selectedDate), db.getSemesterStartMillis());
        return (week >= 1 && week <= db.getSemesterWeeks()) ? week : -1;
    }

    /* ---------------- 工具方法 ---------------- */

    private DBHelper db() {
        return DBHelper.getInstance(requireContext());
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
}
