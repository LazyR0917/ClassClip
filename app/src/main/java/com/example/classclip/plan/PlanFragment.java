package com.example.classclip.plan;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.todo.TodoAddActivity;
import com.example.classclip.todo.TodoDayListActivity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第 4 页：时间规划（真实待办数据）。
 * - 待办时间轴：全量未完成待办按日期分组，每个有待办的日期出节点；
 *   今天即使没有待办也始终留节点；无选中态（不变紫），整体横向滑动
 * - 四象限待办：全部未完成待办按 quadrant 分 4 组（quadrant 0 视为不紧急不重要），
 *   每个象限内部是可竖向滑动的列表
 * - 底部建议条：动态生成
 */
public class PlanFragment extends Fragment {

    /** 时间轴一天的数据 */
    private static class DayPlan {
        final long dateMillis;
        final String pill;      // "10.26 · 今天"
        final boolean isToday;
        final List<DBHelper.Todo> items = new ArrayList<>();

        DayPlan(long dateMillis, String pill, boolean isToday) {
            this.dateMillis = dateMillis;
            this.pill = pill;
            this.isToday = isToday;
        }
    }

    /** 四象限配色 */
    private static final int[] QUADRANT_BG = {
            R.drawable.bg_quadrant_pink, R.drawable.bg_quadrant_purple,
            R.drawable.bg_quadrant_orange, R.drawable.bg_quadrant_green};
    private static final int[] QUADRANT_TEXT = {
            R.color.course_pink_text, R.color.course_purple_text,
            R.color.course_orange_text, R.color.course_green_text};
    private static final int[] QUADRANT_TITLE = {
            R.string.quadrant1_title, R.string.quadrant2_title,
            R.string.quadrant3_title, R.string.quadrant4_title};
    private static final int[] QUADRANT_SUB = {
            R.string.quadrant1_sub, R.string.quadrant2_sub,
            R.string.quadrant3_sub, R.string.quadrant4_sub};

    private FrameLayout timelineContainer;
    private HorizontalScrollView daysScroll;
    private LinearLayout quadrantContainer;
    private TextView tvTimelineCount;
    private TextView tvSuggestion;
    private TextView tvSubtitle;
    private View rootScroll;

    private final List<DayPlan> days = new ArrayList<>();

    /** 四象限各象限 RecyclerView 的滑动状态（重建时恢复，避免勾选完成后跳回顶部） */
    private final int[] quadrantScrollPos = new int[4];
    private final int[] quadrantScrollOffset = new int[4];
    private final RecyclerView[] quadrantRecyclers = new RecyclerView[4];

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_plan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        timelineContainer = view.findViewById(R.id.plan_timeline_container);
        daysScroll = view.findViewById(R.id.plan_days_scroll);
        quadrantContainer = view.findViewById(R.id.plan_quadrant_container);
        tvTimelineCount = view.findViewById(R.id.plan_timeline_count);
        tvSuggestion = view.findViewById(R.id.plan_suggestion);
        tvSubtitle = view.findViewById(R.id.tv_plan_subtitle);
        rootScroll = view.findViewById(R.id.plan_scroll);

        view.findViewById(R.id.btn_add_plan).setOnClickListener(v ->
                startActivity(TodoAddActivity.intentOf(requireContext(), todayMillis())));

        view.findViewById(R.id.plan_back_today).setOnClickListener(v -> scrollToToday());

        reload();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) {
            reload();
        }
    }

    private void reload() {
        buildDayPlans();
        buildTimeline();
        buildQuadrants();
        buildSuggestion();
        updateSubtitle();
    }

    /* ---------------- 待办数据（时间轴/四象限共用） ---------------- */

    private long todayMillis() {
        Calendar c = Calendar.getInstance();
        zeroTime(c);
        return c.getTimeInMillis();
    }

    /** 拉取全部未完成待办（前后一年覆盖所有数据），按日期分组、日期升序 */
    private List<DBHelper.Todo> loadAllPending() {
        DBHelper db = DBHelper.getInstance(requireContext());
        Calendar from = Calendar.getInstance();
        from.add(Calendar.YEAR, -1);
        Calendar to = Calendar.getInstance();
        to.add(Calendar.YEAR, 1);
        List<DBHelper.Todo> all = db.getTodosBetween(from.getTimeInMillis(), to.getTimeInMillis());
        List<DBHelper.Todo> pending = new ArrayList<>();
        for (DBHelper.Todo t : all) {
            if (!t.done) {
                pending.add(t);
            }
        }
        return pending;
    }

    /* ---------------- 时间轴：有待办的天 + 今天 ---------------- */

    /**
     * 全量未完成待办按日期（零点毫秒）分组，每个有待办的日期生成节点；
     * 今天即使没有待办也插入节点。节点按日期升序排。
     */
    private void buildDayPlans() {
        days.clear();
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        long todayMillis = today.getTimeInMillis();

        // dateMillis(零点) → 该天待办（getTodosBetween 已按 日期+时间 升序）
        Map<Long, List<DBHelper.Todo>> byDay = new LinkedHashMap<>();
        for (DBHelper.Todo t : loadAllPending()) {
            List<DBHelper.Todo> list = byDay.get(t.dateMillis);
            if (list == null) {
                list = new ArrayList<>();
                byDay.put(t.dateMillis, list);
            }
            list.add(t);
        }

        // 有待办的日期升序出节点；今天没有待办也补一个节点
        List<Long> sortedDays = new ArrayList<>(byDay.keySet());
        java.util.Collections.sort(sortedDays);
        boolean todayHasNode = false;
        int timelineCount = 0;
        for (long dayMillis : sortedDays) {
            List<DBHelper.Todo> items = byDay.get(dayMillis);
            sortDayItems(items);
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(dayMillis);
            boolean isToday = dayMillis == todayMillis;
            todayHasNode |= isToday;

            DayPlan dp = new DayPlan(dayMillis, dateLabel(day, today), isToday);
            dp.items.addAll(items);
            days.add(dp);
            timelineCount += items.size();
        }
        if (!todayHasNode) {
            days.add(0, new DayPlan(todayMillis, dateLabel(today, today), true));
        }

        tvTimelineCount.setText(getString(R.string.plan_timeline_count_dyn, timelineCount));
    }

    /** 同一天内排序：优先级高在前，优先级相同按开始时间升序，无时间排最后 */
    private void sortDayItems(List<DBHelper.Todo> items) {
        java.util.Collections.sort(items, (a, b) -> {
            if (a.priority != b.priority) {
                return Integer.compare(b.priority, a.priority);
            }
            return Integer.compare(timeMinutes(a.time), timeMinutes(b.time));
        });
    }

    /** "HH:mm" / "HH:mm-HH:mm" → 开始分钟数；无时间返回大值排最后 */
    private static int timeMinutes(String time) {
        if (TextUtils.isEmpty(time)) {
            return Integer.MAX_VALUE;
        }
        try {
            String[] hm = time.split("-")[0].trim().split(":");
            return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 胶囊文案：M.D · 今天/明天/周X */
    private String dateLabel(Calendar day, Calendar today) {
        int diff = (int) ((day.getTimeInMillis() - today.getTimeInMillis())
                / (24L * 60 * 60 * 1000));
        String base = (day.get(Calendar.MONTH) + 1) + "." + day.get(Calendar.DAY_OF_MONTH);
        if (diff == 0) {
            return base + " · 今天";
        }
        if (diff == 1) {
            return base + " · 明天";
        }
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        return base + " · 周" + week[day.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /**
     * 时间轴：每个日期一列（定宽列 FrameLayout），列内从上到下依次是
     * 日期胶囊 → 节点圆点 → 待办卡片，三者同一水平中心，天然对齐。
     * 列与列之间用一条横线串起（画在列的顶部中线处）。
     */
    private void buildTimeline() {
        timelineContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int colW = (int) (160 * density);
        int gap = (int) (6 * density);
        int dotSize = (int) (10 * density);
        int dotCenterY = (int) (30 * density) + dotSize / 2; // 节点圆心垂直位置

        // 贯穿所有节点的连接横线（画在节点圆点位置，起点/终点内收半列宽）
        View line = new View(requireContext());
        line.setBackgroundColor(color(R.color.timeline_line));
        int lineH = (int) (2 * density);
        int totalW = (int) (2 * density) + days.size() * colW + (days.size() - 1) * gap;
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                Math.max(totalW - colW, colW), lineH);
        lineLp.topMargin = dotCenterY - lineH / 2;
        lineLp.leftMargin = colW / 2;
        timelineContainer.addView(line, lineLp);

        for (int i = 0; i < days.size(); i++) {
            DayPlan day = days.get(i);

            // 列容器：定宽，装 胶囊/节点/卡片；leftMargin 累加定位（第 0 列从 2dp 起）
            FrameLayout col = new FrameLayout(requireContext());
            FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(colW,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            colLp.leftMargin = (int) (2 * density) + i * (colW + gap);
            timelineContainer.addView(col, colLp);

            // 1) 日期胶囊（顶部，居中）
            TextView pill = new TextView(requireContext());
            pill.setTextSize(11);
            pill.setGravity(Gravity.CENTER);
            pill.setTypeface(day.isToday ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pill.setTextColor(color(day.isToday
                    ? R.color.brand_text_dark : R.color.brand_text_gray));
            pill.setText(day.pill);
            FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                    colW, (int) (28 * density), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            col.addView(pill, pillLp);

            // 2) 节点圆点（胶囊下方，水平居中）
            View dot = new View(requireContext());
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(color(R.color.brand_primary));
            dot.setBackground(dotBg);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                    dotSize, dotSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            dotLp.topMargin = (int) (30 * density);
            col.addView(dot, dotLp);

            // 3) 待办卡片（节点下方，与列同宽）：有待办用淡紫卡（白底在白色容器里看不见），空天用描边卡
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (10 * density), (int) (10 * density),
                    (int) (10 * density), (int) (10 * density));
            card.setBackgroundResource(day.items.isEmpty()
                    ? R.drawable.bg_card_outline : R.drawable.bg_progress_card);
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    colW, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cardLp.topMargin = (int) (40 * density);
            col.addView(card, cardLp);
            // 有待办的天点卡片进当天详情页
            if (!day.items.isEmpty()) {
                card.setOnClickListener(v ->
                        startActivity(TodoDayListActivity.intentOf(requireContext(), day.dateMillis)));
            }

            if (day.items.isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setTextSize(11);
                empty.setTextColor(color(R.color.brand_text_gray));
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, (int) (8 * density), 0, (int) (8 * density));
                empty.setText(R.string.plan_day_empty);
                card.addView(empty);
            } else {
                int shown = Math.min(day.items.size(), 3);
                for (int k = 0; k < shown; k++) {
                    DBHelper.Todo todo = day.items.get(k);
                    // 每条一行，水平居中
                    LinearLayout row = new LinearLayout(requireContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER);
                    row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

                    TextView time = new TextView(requireContext());
                    time.setTextSize(11);
                    time.setTypeface(Typeface.DEFAULT_BOLD);
                    time.setTextColor(color(R.color.brand_primary));
                    time.setText(shortTime(todo.time));

                    TextView title = new TextView(requireContext());
                    title.setTextSize(11);
                    title.setTextColor(color(R.color.brand_text_dark));
                    title.setPadding((int) (6 * density), 0, 0, 0);
                    title.setText(todo.title);

                    row.addView(time);
                    row.addView(title);
                    // 注意：LinearLayout.LayoutParams 三参构造是 (宽,高,weight)，居中必须用 gravity 字段
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.gravity = Gravity.CENTER_HORIZONTAL;
                    card.addView(row, rowLp);
                }
                // 超过 3 条：底部显示「+N 条」提示
                int extra = day.items.size() - shown;
                if (extra > 0) {
                    TextView more = new TextView(requireContext());
                    more.setTextSize(11);
                    more.setTypeface(Typeface.DEFAULT_BOLD);
                    more.setTextColor(color(R.color.brand_primary));
                    more.setGravity(Gravity.CENTER);
                    more.setPadding(0, (int) (2 * density), 0, (int) (4 * density));
                    more.setText(getString(R.string.plan_day_more, extra));
                    card.addView(more, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        }

        // 每次进入/刷新都默认定位到今天节点（今天列居中）
        daysScroll.post(() -> scrollToToday(false));
    }

    /** "HH:mm" 或 "HH:mm-HH:mm" → 取开始时刻；无时间显示「全天」 */
    private String shortTime(String time) {
        if (TextUtils.isEmpty(time)) {
            return "全天";
        }
        String[] parts = time.split("-")[0].trim().split(":");
        return parts[0] + ":" + parts[1];
    }

    /** 把时间轴滚回今天的节点（今天列居中） */
    private void scrollToToday() {
        scrollToToday(true);
    }

    /**
     * 把时间轴定位到今天节点。
     * @param smooth true=平滑滚动（按钮点击），false=立即定位（进入页面默认）
     */
    private void scrollToToday(boolean smooth) {
        int idx = -1;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).isToday) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;
        float density = getResources().getDisplayMetrics().density;
        int colW = (int) (160 * density);
        int gap = (int) (6 * density);
        // 第 idx 列中心 x（容器内坐标）
        int centerX = (int) (2 * density) + idx * (colW + gap) + colW / 2;
        int targetScroll = centerX - daysScroll.getWidth() / 2;
        targetScroll = Math.max(0, targetScroll);
        if (smooth) {
            daysScroll.smoothScrollTo(targetScroll, 0);
        } else {
            daysScroll.scrollTo(targetScroll, 0);
        }
    }

    /* ---------------- 四象限待办（内部滑动列表） ---------------- */

    private void buildQuadrants() {
        quadrantContainer.removeAllViews();

        List<DBHelper.Todo> pending = loadAllPending();

        List<List<DBHelper.Todo>> buckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            buckets.add(new ArrayList<>());
        }
        for (DBHelper.Todo t : pending) {
            int q = t.quadrant;
            // 旧数据 quadrant=0（未设置）归入「不紧急不重要」，保证全部待办都有归属
            if (q < 1 || q > 4) {
                q = 4;
            }
            buckets.get(q - 1).add(t);
        }

        float density = getResources().getDisplayMetrics().density;
        // 两行，每行两个象限
        for (int row = 0; row < 2; row++) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row == 1) {
                rowLp.topMargin = (int) (8 * density);
            }
            rowLayout.setLayoutParams(rowLp);

            for (int col = 0; col < 2; col++) {
                int q = row * 2 + col;
                rowLayout.addView(buildQuadrantCard(q, buckets.get(q)));
            }
            quadrantContainer.addView(rowLayout);
        }
    }

    private View buildQuadrantCard(int q, List<DBHelper.Todo> items) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (12 * density), (int) (12 * density),
                (int) (12 * density), (int) (12 * density));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                0, (int) (190 * density), 1f);
        cardLp.setMargins((int) ((colMargin(q) == 0 ? 0 : 4) * density), 0,
                (int) ((colMargin(q) == 1 ? 4 : 0) * density), 0);
        card.setLayoutParams(cardLp);
        card.setBackgroundResource(QUADRANT_BG[q]);
        // 点整张象限卡进明细页（查看该象限全部待办）
        card.setOnClickListener(v ->
                startActivity(PlanQuadrantActivity.intentOf(requireContext(), q + 1)));

        // 标题行
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(requireContext());
        title.setText(getString(QUADRANT_TITLE[q]));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(QUADRANT_TEXT[q]));
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = new TextView(requireContext());
        count.setText(String.valueOf(items.size()));
        count.setTextSize(11);
        count.setTextColor(color(QUADRANT_TEXT[q]));
        head.addView(count);
        card.addView(head);

        // 副标题
        TextView sub = new TextView(requireContext());
        sub.setText(getString(QUADRANT_SUB[q]));
        sub.setTextSize(10);
        sub.setAlpha(0.8f);
        sub.setTextColor(color(QUADRANT_TEXT[q]));
        sub.setPadding(0, (int) (2 * density), 0, 0);
        card.addView(sub);

        // 待办列表：固定卡高，内部 RecyclerView 可竖向滑动
        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setNestedScrollingEnabled(true);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        rv.setAdapter(new QuadrantAdapter(items));
        card.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 恢复该象限上次的滑动位置
        if (quadrantScrollPos[q] > 0) {
            rv.scrollToPosition(quadrantScrollPos[q]);
            final int offset = quadrantScrollOffset[q];
            if (offset != 0) {
                rv.post(() -> rv.scrollBy(0, offset));
            }
        }
        quadrantRecyclers[q] = rv;
        return card;
    }

    private int colMargin(int q) {
        return q % 2;   // 左列=0 右列=1
    }

    /** 记录每个象限 RecyclerView 当前的滑动位置（重建四象限前调用） */
    private void captureQuadrantScroll() {
        for (int i = 0; i < 4; i++) {
            RecyclerView rv = quadrantRecyclers[i];
            if (rv == null || rv.getLayoutManager() == null) {
                quadrantScrollPos[i] = 0;
                quadrantScrollOffset[i] = 0;
                continue;
            }
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            int pos = lm.findFirstVisibleItemPosition();
            if (pos == RecyclerView.NO_POSITION) {
                quadrantScrollPos[i] = 0;
                quadrantScrollOffset[i] = 0;
                continue;
            }
            View first = lm.findViewByPosition(pos);
            int offset = first == null ? 0 : first.getTop();
            quadrantScrollPos[i] = pos;
            quadrantScrollOffset[i] = offset;
        }
    }

    /** 象限内待办列表适配器：条目 = buildQuadrantItem 同款 */
    private class QuadrantAdapter extends RecyclerView.Adapter<QuadrantAdapter.Holder> {
        private final List<DBHelper.Todo> items;

        QuadrantAdapter(List<DBHelper.Todo> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 条目布局借 buildQuadrantItem 生成（无布局文件），包一层消除 recycle 冲突
            LinearLayout wrap = new LinearLayout(requireContext());
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(wrap);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            DBHelper.Todo t = items.get(position);
            holder.wrap.removeAllViews();
            View item = buildQuadrantItem(t);
            holder.wrap.addView(item);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final LinearLayout wrap;

            Holder(@NonNull View itemView) {
                super(itemView);
                wrap = (LinearLayout) itemView;
            }
        }
    }

    private View buildQuadrantItem(DBHelper.Todo t) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

        // 圆形勾选框（未完成态），点击即标记完成
        View checkbox = new View(requireContext());
        checkbox.setBackgroundResource(R.drawable.bg_checkbox_pending);
        checkbox.setOnClickListener(v -> {
            DBHelper.getInstance(requireContext()).setTodoDone(t.id, true);
            Toast.makeText(requireContext(), R.string.todo_saved, Toast.LENGTH_SHORT).show();
            // 先记录每个象限列表的滑动位置，再只刷新四象限，避免勾选后跳回顶部
            captureQuadrantScroll();
            final int scrollY = rootScroll != null
                    ? ((android.widget.ScrollView) rootScroll).getScrollY() : 0;
            buildQuadrants();
            if (rootScroll != null) {
                ((android.widget.ScrollView) rootScroll).post(() ->
                        ((android.widget.ScrollView) rootScroll).scrollTo(0, scrollY));
            }
        });
        item.addView(checkbox, new LinearLayout.LayoutParams(
                (int) (18 * density), (int) (18 * density)));

        LinearLayout textBox = new LinearLayout(requireContext());
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding((int) (8 * density), 0, 0, 0);

        TextView title = new TextView(requireContext());
        title.setText(t.title);
        title.setTextSize(11);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.brand_text_dark));
        textBox.addView(title);

        String meta = formatMeta(t);
        if (!TextUtils.isEmpty(meta)) {
            TextView metaTv = new TextView(requireContext());
            metaTv.setText(meta);
            metaTv.setTextSize(9);
            metaTv.setTextColor(color(R.color.brand_text_gray));
            metaTv.setPadding(0, (int) (1 * density), 0, 0);
            textBox.addView(metaTv);
        }

        item.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.setOnClickListener(v ->
                startActivity(TodoAddActivity.intentOfEdit(requireContext(), t.id)));
        return item;
    }

    private String formatMeta(DBHelper.Todo t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t.dateMillis);
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        String date;
        long diff = (c.getTimeInMillis() - today.getTimeInMillis())
                / (24L * 60 * 60 * 1000);
        if (diff == 0) date = "今天";
        else if (diff == 1) date = "明天";
        else date = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";

        if (TextUtils.isEmpty(t.time)) {
            return date;
        }
        return date + " " + shortTime(t.time);
    }

    /* ---------------- 建议条 ---------------- */

    private void buildSuggestion() {
        DBHelper db = DBHelper.getInstance(requireContext());
        List<DBHelper.Todo> today = db.getTodosOfDay(todayMillis());
        int pending = 0;
        for (DBHelper.Todo t : today) {
            if (!t.done) pending++;
        }
        if (pending == 0) {
            tvSuggestion.setText(R.string.plan_suggestion_done);
        } else {
            tvSuggestion.setText(getString(R.string.plan_suggestion_dyn, pending));
        }
    }

    private void updateSubtitle() {
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        int dow = today.get(Calendar.DAY_OF_WEEK) - 1;
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        tvSubtitle.setText(String.format(Locale.CHINA, "%d 月 %d 日，周%s · 把时间留给重要的事",
                today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH), week[dow]));
    }

    /* ---------------- 工具 ---------------- */

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }
}
