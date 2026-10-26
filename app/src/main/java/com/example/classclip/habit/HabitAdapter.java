package com.example.classclip.habit;
import com.example.classclip.R;

import com.example.classclip.common.ChipsScrollView;
import com.example.classclip.common.DBHelper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 习惯打卡列表适配器（单 RecyclerView）：
 *   header(进度卡/chips) → 「未完成」分组头 → 未完成条目 → 「已完成」分组头 → 已完成条目 → footer(提示)
 * 分组标准 = 今天是否已打卡：已打卡→已完成组，未打卡→未完成组；两组头均可展开/收起。
 * 之前页面用 ScrollView 嵌 wrap_content 的 RecyclerView，自动测量只按一屏高度填充，
 * 数据增加后新条目永远不会被布局（计数 5~7 条列表只显示 4 条）；改为单列表 + header 彻底修复。
 * - 点「打卡」→ 当天记录落库；点「✓ 已打卡」→ 弹确认后取消打卡
 * - 本周 7 天格子：已打卡=习惯色底白勾；今天前未打卡=灰底"—"；今天及以后=灰底星期字符
 * - 副标题：分类 · 连续 N 天（连续=截至今天连续完成计划打卡日；非计划日不打断）
 * - 点卡片进编辑页；左滑删除由页面处理
 */
public class HabitAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SECTION = 1;
    private static final int TYPE_ITEM = 2;
    private static final int TYPE_FOOTER = 3;

    /** 打卡状态变化回调（刷新进度卡） */
    public interface OnProgressChangeListener {
        void onProgressChanged();
    }

    /** 页面交互回调 */
    public interface Callbacks {
        void onEdit(DBHelper.Habit habit);
        void onDelete(DBHelper.Habit habit);
        void onCheck(DBHelper.Habit habit);
        void onUncheck(DBHelper.Habit habit);
    }

    /** header 交互回调（＋ 新建 / 分类 chip 点击 / 分类管理） */
    public interface HeaderCallbacks {
        void onAddClicked();
        void onCategoryClicked(long categoryId);
        void onManageCategoryClicked();
    }

    private static final String[] DAY_LABELS = {"一", "二", "三", "四", "五", "六", "日"};

    /* ---------------- 分组数据 ---------------- */

    /** 列表条目（header/分组头/习惯/footer 统一用 Entry 表示） */
    private static class Entry {
        final int type;
        final DBHelper.Habit habit;
        final boolean doneSection;

        Entry(int type) {
            this.type = type;
            this.habit = null;
            this.doneSection = false;
        }

        Entry(int type, boolean doneSection) {
            this.type = type;
            this.habit = null;
            this.doneSection = doneSection;
        }

        Entry(DBHelper.Habit habit) {
            this.type = TYPE_ITEM;
            this.habit = habit;
            this.doneSection = false;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<DBHelper.Habit> allItems = new ArrayList<>();
    private final List<DBHelper.Habit> pendingItems = new ArrayList<>();
    private final List<DBHelper.Habit> doneItems = new ArrayList<>();
    private boolean pendingCollapsed = false;
    private boolean doneCollapsed = false;

    /** 每个习惯本周已打卡的日期集合（零点毫秒），key=habitId */
    private final java.util.Map<Long, Set<Long>> weekRecords = new java.util.HashMap<>();
    /** 本周一（零点）与今天（零点） */
    private long mondayMillis;
    private long todayMillis;
    /** 今天已打卡的习惯 id */
    private Set<Long> checkedToday = new java.util.HashSet<>();
    /** 分类 id → 名称 */
    private Map<Long, String> categoryNames = new java.util.HashMap<>();

    /* ---------------- header 数据 ---------------- */
    private String headerSubtitle = "";
    private String headerWeekRange = "";
    private int headerPercent;
    private int headerDone;
    private int headerRequired;
    private int allCount;
    private int selectedCategoryId = -1;
    private final List<DBHelper.HabitCategory> headerCategories = new ArrayList<>();
    /** 分类计数用全量习惯（不受当前分类筛选影响），由页面在 reload 时一并传入 */
    private final List<DBHelper.Habit> countHabits = new ArrayList<>();

    private final OnProgressChangeListener progressListener;
    private final Callbacks callbacks;
    private final HeaderCallbacks headerCallbacks;
    /** 左滑露出的条目位置（items 坐标） */
    private int openSwipePosition = -1;

    public HabitAdapter(OnProgressChangeListener progressListener, Callbacks callbacks,
                        HeaderCallbacks headerCallbacks) {
        this.progressListener = progressListener;
        this.callbacks = callbacks;
        this.headerCallbacks = headerCallbacks;
    }

    /** 重载数据：按「今天是否已打卡」分未完成/已完成两组 */
    public void reload(List<DBHelper.Habit> habits,
                       java.util.Map<Long, Set<Long>> weekRecords,
                       Set<Long> checkedToday, long mondayMillis, long todayMillis,
                       Map<Long, String> categoryNames) {
        allItems.clear();
        allItems.addAll(habits);
        pendingItems.clear();
        doneItems.clear();
        for (DBHelper.Habit h : habits) {
            if (checkedToday.contains(h.id)) {
                doneItems.add(h);
            } else {
                pendingItems.add(h);
            }
        }
        this.weekRecords.clear();
        this.weekRecords.putAll(weekRecords);
        this.checkedToday = checkedToday;
        this.mondayMillis = mondayMillis;
        this.todayMillis = todayMillis;
        this.categoryNames = categoryNames;
        openSwipePosition = -1;
        rebuildEntries();
        notifyDataSetChanged();
    }

    private void rebuildEntries() {
        entries.clear();
        entries.add(new Entry(TYPE_HEADER));
        entries.add(new Entry(TYPE_SECTION, false)); // 未完成头
        if (!pendingCollapsed) {
            for (DBHelper.Habit h : pendingItems) entries.add(new Entry(h));
        }
        entries.add(new Entry(TYPE_SECTION, true)); // 已完成头
        if (!doneCollapsed) {
            for (DBHelper.Habit h : doneItems) entries.add(new Entry(h));
        }
        entries.add(new Entry(TYPE_FOOTER));
    }

    /** 习惯条目在 entries 里的下标（用于左滑状态 +1 换算）；找不到返回 -1 */
    private int indexOfHabit(DBHelper.Habit habit) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type == TYPE_ITEM && e.habit.id == habit.id) return i;
        }
        return -1;
    }

    /* ---------------- header 数据更新（position 0 局部刷新） ---------------- */

    public void setHeaderInfo(String subtitle, String weekRange, int allCount,
                              List<DBHelper.HabitCategory> categories, int selectedId,
                              List<DBHelper.Habit> fullHabits) {
        headerSubtitle = subtitle;
        headerWeekRange = weekRange;
        this.allCount = allCount;
        headerCategories.clear();
        headerCategories.addAll(categories);
        selectedCategoryId = selectedId;
        countHabits.clear();
        countHabits.addAll(fullHabits);
        if (getItemCount() > 0) {
            notifyItemChanged(0);
        }
    }

    public void setSelectedCategory(int categoryId) {
        selectedCategoryId = categoryId;
        if (getItemCount() > 0) {
            notifyItemChanged(0);
        }
    }

    public void setProgress(int percent, int done, int required) {
        headerPercent = percent;
        headerDone = done;
        headerRequired = required;
        if (getItemCount() > 0) {
            notifyItemChanged(0);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderHolder(inflater.inflate(
                        R.layout.item_habit_header, parent, false));
            case TYPE_SECTION:
                return new SectionHolder(inflater.inflate(
                        R.layout.item_todo_section, parent, false));
            case TYPE_FOOTER:
                return new FooterHolder(inflater.inflate(
                        R.layout.item_habit_footer, parent, false));
            default:
                return new HabitViewHolder(inflater.inflate(
                        R.layout.item_habit, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        switch (entry.type) {
            case TYPE_HEADER:
                ((HeaderHolder) holder).bind();
                break;
            case TYPE_SECTION:
                ((SectionHolder) holder).bind(entry.doneSection);
                break;
            case TYPE_ITEM:
                ((HabitViewHolder) holder).bind(entry.habit, position);
                break;
            // footer 无需绑定
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /* ---------------- 左滑露出状态（坐标为 adapter 坐标，页面 ItemTouchHelper 调用） ---------------- */

    /** 该 adapter 位置是否是习惯条目（可左滑） */
    public boolean isItemPosition(int adapterPos) {
        return adapterPos >= 0 && adapterPos < entries.size()
                && entries.get(adapterPos).type == TYPE_ITEM;
    }

    public void setOpenSwipe(int position) {
        int old = openSwipePosition;
        openSwipePosition = position;
        if (old >= 0) {
            notifyItemChanged(old);
        }
        notifyItemChanged(position);
    }

    /** 右滑/点其他位置收回（touched 为 adapter 坐标，-1=点空白） */
    public void closeSwipe(int touchedAdapterPos) {
        if (openSwipePosition >= 0 && touchedAdapterPos != openSwipePosition) {
            int old = openSwipePosition;
            openSwipePosition = -1;
            notifyItemChanged(old);
        }
    }

    public void resetSwipe(int position) {
        if (position == openSwipePosition) {
            openSwipePosition = -1;
            notifyItemChanged(position);
        }
    }

    /* ================= header ================= */

    class HeaderHolder extends RecyclerView.ViewHolder {
        private final CircularProgressIndicator progressRing;
        private final TextView tvPercent;
        private final TextView tvProgressTitle;
        private final TextView tvSubtitle;
        private final TextView tvWeekRange;
        private final ChipsScrollView hsChips;
        private final LinearLayout llChips;
        private final View btnAdd;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            progressRing = itemView.findViewById(R.id.progress_habit_ring);
            tvPercent = itemView.findViewById(R.id.tv_habit_percent);
            tvProgressTitle = itemView.findViewById(R.id.tv_habit_progress_title);
            tvSubtitle = itemView.findViewById(R.id.tv_habit_subtitle);
            tvWeekRange = itemView.findViewById(R.id.tv_habit_week_range);
            hsChips = itemView.findViewById(R.id.hs_habit_chips);
            llChips = itemView.findViewById(R.id.ll_habit_chips);
            btnAdd = itemView.findViewById(R.id.btn_add_habit);
        }

        void bind() {
            android.content.Context ctx = itemView.getContext();
            tvSubtitle.setText(headerSubtitle);
            tvWeekRange.setText(headerWeekRange);
            tvProgressTitle.setText(ctx.getString(R.string.habit_progress_title,
                    headerDone, headerRequired));
            tvPercent.setText(headerPercent + "%");
            progressRing.setProgressCompat(headerPercent, true);
            btnAdd.setOnClickListener(v -> {
                if (headerCallbacks != null) {
                    headerCallbacks.onAddClicked();
                }
            });
            // 点「自定义分类」卡进管理页
            View categoryCard = itemView.findViewById(R.id.card_habit_category);
            if (categoryCard != null) {
                categoryCard.setOnClickListener(v -> {
                    if (headerCallbacks != null) {
                        headerCallbacks.onManageCategoryClicked();
                    }
                });
            }
            refreshChips();
        }

        /** chips 动态生成：全部 + 各分类，带数量、选中态 */
        private void refreshChips() {
            android.content.Context ctx = itemView.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;
            llChips.removeAllViews();

            addChip(ctx, density, "全部 " + allCount, selectedCategoryId == -1,
                    () -> { if (headerCallbacks != null) headerCallbacks.onCategoryClicked(-1); });
            for (DBHelper.HabitCategory c : headerCategories) {
                int count = 0;
                for (DBHelper.Habit h : countHabits) {
                    if (h.category == c.id) {
                        count++;
                    }
                }
                final long categoryId = c.id;
                addChip(ctx, density, c.name + " " + count, selectedCategoryId == categoryId,
                        () -> { if (headerCallbacks != null) headerCallbacks.onCategoryClicked(categoryId); });
            }

            // 把选中 chip 滚到可视区中间
            for (int i = 0; i < llChips.getChildCount(); i++) {
                View chip = llChips.getChildAt(i);
                if (chip.isSelected()) {
                    hsChips.post(() -> hsChips.smoothScrollTo(
                            Math.max(0, chip.getLeft() - hsChips.getWidth() / 2 + chip.getWidth() / 2),
                            0));
                    break;
                }
            }
        }

        private void addChip(android.content.Context ctx, float density,
                             String text, boolean selected, Runnable onClick) {
            TextView chip = new TextView(ctx);
            chip.setText(text);
            chip.setSelected(selected);
            chip.setTextSize(12);
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setBackgroundResource(selected
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
            chip.setTextColor(ContextCompat.getColor(ctx,
                    selected ? R.color.white : R.color.brand_text_dark));
            chip.setPadding(dp(14, density), dp(6, density),
                    dp(14, density), dp(6, density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (llChips.getChildCount() > 0) {
                lp.leftMargin = dp(8, density);
            }
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> onClick.run());
            llChips.addView(chip);
        }
    }

    /* ================= 分组头（复用待办页的 item_todo_section） ================= */

    private class SectionHolder extends RecyclerView.ViewHolder {
        private final TextView tvTitle;
        private final TextView tvAction;

        SectionHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_section_title);
            tvAction = itemView.findViewById(R.id.tv_section_action);
        }

        void bind(boolean doneSection) {
            android.content.Context ctx = itemView.getContext();
            if (doneSection) {
                tvTitle.setText(ctx.getString(R.string.habit_section_done, doneItems.size()));
                tvAction.setText(ctx.getString(doneCollapsed
                        ? R.string.todo_expand : R.string.todo_collapse));
                itemView.setOnClickListener(v -> {
                    doneCollapsed = !doneCollapsed;
                    rebuildEntries();
                    notifyDataSetChanged();
                });
            } else {
                tvTitle.setText(ctx.getString(R.string.habit_section_pending, pendingItems.size()));
                tvAction.setText(ctx.getString(pendingCollapsed
                        ? R.string.todo_expand : R.string.todo_collapse));
                itemView.setOnClickListener(v -> {
                    pendingCollapsed = !pendingCollapsed;
                    rebuildEntries();
                    notifyDataSetChanged();
                });
            }
        }
    }

    /* ================= footer ================= */

    static class FooterHolder extends RecyclerView.ViewHolder {
        FooterHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    /* ================= 习惯条目 ================= */

    class HabitViewHolder extends RecyclerView.ViewHolder {
        private final View root;
        private final View viewIconBg;
        private final View foreground;
        private final View btnDelete;
        private final ImageView ivIcon;
        private final TextView tvTitle, tvSubtitle;
        private final TextView btnCheck, tvDone;
        private final LinearLayout llWeek;
        private final View llWeekProgress;
        private final TextView tvWeekProgress;
        private final android.widget.ProgressBar progressWeek;

        HabitViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView;
            viewIconBg = itemView.findViewById(R.id.view_habit_icon_bg);
            foreground = itemView.findViewById(R.id.item_habit_foreground);
            btnDelete = itemView.findViewById(R.id.btn_habit_delete);
            ivIcon = itemView.findViewById(R.id.iv_habit_icon);
            tvTitle = itemView.findViewById(R.id.tv_habit_item_title);
            tvSubtitle = itemView.findViewById(R.id.tv_habit_item_subtitle);
            btnCheck = itemView.findViewById(R.id.btn_habit_check);
            tvDone = itemView.findViewById(R.id.tv_habit_done);
            llWeek = itemView.findViewById(R.id.ll_habit_week);
            llWeekProgress = itemView.findViewById(R.id.ll_habit_week_progress);
            tvWeekProgress = itemView.findViewById(R.id.tv_habit_week_progress);
            progressWeek = itemView.findViewById(R.id.progress_habit_week);
        }

        void bind(DBHelper.Habit habit, int position) {
            float density = root.getContext().getResources().getDisplayMetrics().density;
            int deepColor = ContextCompat.getColor(root.getContext(),
                    HabitEditActivity.HABIT_COLORS[habit.colorIndex]);

            // 左侧彩色圆角图标
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setColor(deepColor);
            iconBg.setCornerRadius(12 * density);
            viewIconBg.setBackground(iconBg);
            ivIcon.setImageResource(HabitEditActivity.HABIT_ICONS[habit.icon]);

            tvTitle.setText(habit.name);
            tvSubtitle.setText(buildSubtitle(habit));

            // 打卡状态：蓝色按钮 / 绿色已打卡 chip
            boolean doneToday = checkedToday.contains(habit.id);
            btnCheck.setVisibility(doneToday ? View.GONE : View.VISIBLE);
            tvDone.setVisibility(doneToday ? View.VISIBLE : View.GONE);
            btnCheck.setOnClickListener(v -> {
                if (callbacks != null) {
                    callbacks.onCheck(habit);
                }
            });
            // 再点「已打卡」可取消（防误触，弹确认）
            tvDone.setOnClickListener(v -> {
                if (callbacks != null) {
                    callbacks.onUncheck(habit);
                }
            });

            // 点卡片进编辑
            foreground.setOnClickListener(v -> {
                if (callbacks != null) {
                    callbacks.onEdit(habit);
                }
            });
            // 左滑删除按钮
            btnDelete.setOnClickListener(v -> {
                if (callbacks != null) {
                    callbacks.onDelete(habit);
                }
            });

            // 本周 7 天格子
            llWeek.removeAllViews();
            Set<Long> days = weekRecords.get(habit.id);
            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    dp(26, density), dp(26, density));
            cellParams.setMargins(dp(3, density), 0, 0, 0);

            Calendar day = Calendar.getInstance();
            for (int i = 0; i < 7; i++) {
                day.setTimeInMillis(mondayMillis);
                day.add(Calendar.DAY_OF_MONTH, i);
                long dayMillis = day.getTimeInMillis();
                boolean checked = days != null && days.contains(dayMillis);
                boolean isFuture = dayMillis > todayMillis;
                boolean isToday = dayMillis == todayMillis;

                TextView cell = new TextView(root.getContext());
                cell.setGravity(Gravity.CENTER);
                cell.setTypeface(Typeface.DEFAULT_BOLD);
                cell.setLayoutParams(cellParams);

                GradientDrawable cellBg = new GradientDrawable();
                if (checked) {
                    // 已打卡：习惯色底白勾
                    cellBg.setColor(deepColor);
                    cellBg.setCornerRadius(8 * density);
                    cell.setBackground(cellBg);
                    cell.setTextColor(ContextCompat.getColor(root.getContext(), R.color.white));
                    cell.setTextSize(11);
                    cell.setText("✓");
                } else {
                    cellBg.setColor(ContextCompat.getColor(root.getContext(), R.color.tag_gray_bg));
                    cellBg.setCornerRadius(8 * density);
                    cell.setBackground(cellBg);
                    if (isFuture) {
                        // 今天及以后：灰底星期字符
                        cell.setTextColor(ContextCompat.getColor(root.getContext(),
                                R.color.brand_text_gray));
                        cell.setTextSize(9);
                        cell.setText(DAY_LABELS[i]);
                    } else {
                        // 今天（含）之前未打卡：灰底 "—"
                        cell.setTextColor(ContextCompat.getColor(root.getContext(),
                                R.color.brand_text_gray));
                        cell.setTextSize(10);
                        cell.setText("—");
                    }
                }
                if (isToday && !checked) {
                    cellBg.setStroke(dp(2, density),
                            ContextCompat.getColor(root.getContext(), R.color.brand_primary));
                }
                llWeek.addView(cell);
            }

            // 每周 N 次：显示本周目标进度条；其他频率隐藏
            if (habit.freqType == DBHelper.HABIT_FREQ_TIMES) {
                int done = days == null ? 0 : days.size();
                llWeekProgress.setVisibility(View.VISIBLE);
                tvWeekProgress.setText(root.getContext()
                        .getString(R.string.habit_week_progress,
                                Math.min(done, habit.freqTimes), habit.freqTimes));
                progressWeek.setMax(habit.freqTimes);
                progressWeek.setProgress(Math.min(done, habit.freqTimes));
            } else {
                llWeekProgress.setVisibility(View.GONE);
            }

            // 恢复滑开状态
            if (position == openSwipePosition) {
                foreground.setTranslationX(-56 * density);
                btnDelete.setVisibility(View.VISIBLE);
            } else {
                foreground.setTranslationX(0);
                btnDelete.setVisibility(View.INVISIBLE);
            }
        }

        /** 副标题：分类 · 连续 N 天（连续完成计划打卡日；非计划日不打断；type3 按连续有记录天算） */
        private String buildSubtitle(DBHelper.Habit habit) {
            String streak = root.getContext()
                    .getString(R.string.habit_streak, streakDays(habit));
            String cat = categoryNames.get(habit.category);
            if (cat == null || habit.category == DBHelper.HABIT_CAT_NONE) {
                return streak;   // 无分类：只显示连续天数
            }
            return cat + " · " + streak;
        }

        /** 连续天数：从今天（未打卡则从昨天）往前，计划日都打了则累加，非计划日跳过 */
        private int streakDays(DBHelper.Habit habit) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(todayMillis);
            Set<Long> days = weekRecords.get(habit.id);
            // 本周记录可能不够往前数，超出本周的按天查询太贵，这里只数本周内的连续
            int streak = 0;
            // 起点：今天没打卡就从昨天开始数（今天打了算第 1 天）
            if (days != null && days.contains(todayMillis)) {
                streak = 1;
            }
            day.add(Calendar.DAY_OF_MONTH, -1);
            while (day.getTimeInMillis() >= mondayMillis) {
                long millis = day.getTimeInMillis();
                boolean planned = habit.freqType == DBHelper.HABIT_FREQ_TIMES
                        || habit.isPlannedOn(day.get(Calendar.DAY_OF_WEEK));
                if (planned) {
                    if (days != null && days.contains(millis)) {
                        streak++;
                    } else {
                        break;
                    }
                }
                day.add(Calendar.DAY_OF_MONTH, -1);
            }
            return streak;
        }
    }

    private static int dp(float value, float density) {
        return (int) (value * density + 0.5f);
    }
}
