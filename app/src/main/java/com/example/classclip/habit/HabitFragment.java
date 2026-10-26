package com.example.classclip.habit;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第 3 页：习惯打卡（真实数据，habits + habit_records 表，DB v11）。
 * 页面结构为单个 RecyclerView（header 进度卡/chips + 习惯卡片 + footer 提示）：
 * 旧版 ScrollView 嵌 wrap_content 的 RecyclerView 自动测量只按一屏高度填充，
 * 数据增加后新条目永远不会被布局，改为单列表结构修复。
 * - 进度环 = 今天已打卡数 / 今天应打卡数（每天/指定周几命中当天/每周 N 次未达标），按选中分类独立统计
 * - 分类 chips（全部 + 各分类）真实筛选，带数量；左右滑也可循环切换分类
 * - 点「打卡」当天落库；点「✓ 已打卡」弹确认取消；点卡片进编辑页；左滑删除
 * - 右上角 ＋ 进 HabitEditActivity 新建
 */
public class HabitFragment extends Fragment
        implements HabitAdapter.OnProgressChangeListener, HabitAdapter.Callbacks,
        HabitAdapter.HeaderCallbacks {

    private static final int CATEGORY_ALL = -1;

    private HabitAdapter adapter;
    private RecyclerView rvHabit;
    private final List<DBHelper.Habit> allHabits = new ArrayList<>();
    private List<DBHelper.HabitCategory> categories = new ArrayList<>();
    private int selectedCategory = CATEGORY_ALL;

    /** 本周一 / 今天（零点） */
    private long mondayMillis;
    private long todayMillis;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_habit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvHabit = (RecyclerView) view;
        rvHabit.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new HabitAdapter(this, this, this);
        rvHabit.setAdapter(adapter);
        attachSwipeToDelete(rvHabit);

        // 左右滑切换分类（全部/各分类 循环）；纵向滚动不受影响
        attachCategorySwipe(rvHabit);
    }

    /** 左右滑切换分类：横向滑动明显大于纵向时，切换上一个/下一个分类 chip */
    private void attachCategorySwipe(View root) {
        final GestureDetector detector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (Math.abs(velocityX) > Math.abs(velocityY)
                                && Math.abs(velocityX) > 1000) {
                            switchCategory(velocityX < 0 ? 1 : -1);
                            return true;
                        }
                        return false;
                    }
                });
        root.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            // 不消费事件，交给子控件正常处理（滚动/点击/左滑删除）
            return false;
        });
    }

    /** 切换分类：step=1 下一个，step=-1 上一个（到队首/队尾即停，不循环） */
    private void switchCategory(int step) {
        int[] order = new int[categories.size() + 1];
        order[0] = CATEGORY_ALL;
        for (int i = 0; i < categories.size(); i++) {
            order[i + 1] = (int) categories.get(i).id;
        }
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == selectedCategory) {
                idx = i;
                break;
            }
        }
        idx += step;
        if (idx < 0 || idx >= order.length) {
            return;   // 已在队首/队尾，不再切换
        }
        selectedCategory = order[idx];
        adapter.setSelectedCategory(selectedCategory);
        reloadList();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadAll();
    }

    /* ---------------- 数据加载 ---------------- */

    private void reloadAll() {
        computeWeekRange();
        allHabits.clear();
        allHabits.addAll(DBHelper.getInstance(requireContext()).getHabits());
        categories.clear();
        categories.addAll(DBHelper.getInstance(requireContext()).getHabitCategories());

        // 一次查出本周各习惯的打卡记录
        Map<Long, Set<Long>> weekRecords = new HashMap<>();
        DBHelper db = DBHelper.getInstance(requireContext());
        for (DBHelper.Habit h : allHabits) {
            weekRecords.put(h.id,
                    db.getHabitRecordDays(h.id, mondayMillis,
                            mondayMillis + 6L * 24 * 60 * 60 * 1000));
        }
        Set<Long> checkedToday = db.getCheckedHabitIdsOfDay(todayMillis);

        // header 文案：顶栏日期 + 本周范围
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(todayMillis);
        int dowIdx = (today.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        String[] dayNames = {"一", "二", "三", "四", "五", "六", "日"};
        String subtitle = String.format(Locale.CHINA, "%d 月 %d 日，周%s · 让小坚持成为日常",
                today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH), dayNames[dowIdx]);
        Calendar sunday = Calendar.getInstance();
        sunday.setTimeInMillis(mondayMillis + 6L * 24 * 60 * 60 * 1000);
        String weekRange = String.format(Locale.CHINA, "本周 %d.%d–%d.%d",
                today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH),
                sunday.get(Calendar.MONTH) + 1, sunday.get(Calendar.DAY_OF_MONTH));

        // 分类计数基于全量习惯（不受当前筛选影响），列表 reload 仍传筛选后的
        adapter.setHeaderInfo(subtitle, weekRange, allHabits.size(), categories,
                selectedCategory, allHabits);
        adapter.reload(filterHabits(), weekRecords, checkedToday, mondayMillis, todayMillis,
                loadCategoryNames());
        onProgressChanged();
    }

    /** 分类 id → 名称（供副标题显示） */
    private Map<Long, String> loadCategoryNames() {
        Map<Long, String> names = new HashMap<>();
        for (DBHelper.HabitCategory c : categories) {
            names.put(c.id, c.name);
        }
        return names;
    }

    private List<DBHelper.Habit> filterHabits() {
        if (selectedCategory == CATEGORY_ALL) {
            return allHabits;
        }
        List<DBHelper.Habit> result = new ArrayList<>();
        for (DBHelper.Habit h : allHabits) {
            if (h.category == selectedCategory) {
                result.add(h);
            }
        }
        return result;
    }

    private void computeWeekRange() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        todayMillis = today.getTimeInMillis();
        int dow = today.get(Calendar.DAY_OF_WEEK);
        int diff = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        today.add(Calendar.DAY_OF_MONTH, -diff);
        mondayMillis = today.getTimeInMillis();
    }

    @Override
    public void onProgressChanged() {
        // 进度环只统计当前选中分类里的习惯（全部=所有习惯），各分类相互独立
        int required = 0;
        int done = 0;
        DBHelper db = DBHelper.getInstance(requireContext());
        Set<Long> checkedToday = db.getCheckedHabitIdsOfDay(todayMillis);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(todayMillis);
        for (DBHelper.Habit h : allHabits) {
            if (selectedCategory != CATEGORY_ALL && h.category != selectedCategory) {
                continue;
            }
            boolean requiredToday;
            if (h.freqType == DBHelper.HABIT_FREQ_TIMES) {
                // 每周 N 次：本周还没达标就算今天的任务
                Set<Long> days = db.getHabitRecordDays(h.id, mondayMillis,
                        mondayMillis + 6L * 24 * 60 * 60 * 1000);
                requiredToday = days.size() < h.freqTimes;
            } else {
                requiredToday = h.isPlannedOn(today.get(Calendar.DAY_OF_WEEK));
            }
            if (requiredToday) {
                required++;
                if (checkedToday.contains(h.id)) {
                    done++;
                }
            }
        }
        int percent = required == 0 ? 0 : Math.round(done * 100f / required);
        adapter.setProgress(percent, done, required);
    }

    private void reloadList() {
        adapter.reload(filterHabits(), loadWeekRecords(),
                DBHelper.getInstance(requireContext()).getCheckedHabitIdsOfDay(todayMillis),
                mondayMillis, todayMillis, loadCategoryNames());
        onProgressChanged();
    }

    private Map<Long, Set<Long>> loadWeekRecords() {
        Map<Long, Set<Long>> weekRecords = new HashMap<>();
        List<DBHelper.Habit> habits = filterHabits();
        DBHelper db = DBHelper.getInstance(requireContext());
        for (DBHelper.Habit h : habits) {
            weekRecords.put(h.id, db.getHabitRecordDays(h.id, mondayMillis,
                    mondayMillis + 6L * 24 * 60 * 60 * 1000));
        }
        return weekRecords;
    }

    /* ---------------- header 回调 ---------------- */

    @Override
    public void onAddClicked() {
        // 预选当前分类（「全部」时 selectedCategory=-1，不预选，用默认学习）
        startActivity(HabitEditActivity.intentOf(requireContext(), selectedCategory));
    }

    @Override
    public void onCategoryClicked(long categoryId) {
        selectedCategory = (int) categoryId;
        adapter.setSelectedCategory(selectedCategory);
        reloadList();
    }

    @Override
    public void onManageCategoryClicked() {
        startActivity(new android.content.Intent(requireContext(),
                CategoryManageActivity.class));
    }

    /* ---------------- 交互回调 ---------------- */

    @Override
    public void onEdit(DBHelper.Habit habit) {
        startActivity(HabitEditActivity.intentOfEdit(requireContext(), habit.id));
    }

    @Override
    public void onDelete(DBHelper.Habit habit) {
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.habit_delete_confirm, habit.name))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper.getInstance(requireContext()).deleteHabit(habit.id);
                    Toast.makeText(requireContext(), R.string.habit_deleted,
                            Toast.LENGTH_SHORT).show();
                    reloadAll();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    @Override
    public void onCheck(DBHelper.Habit habit) {
        DBHelper.getInstance(requireContext()).insertHabitRecord(habit.id, todayMillis);
        reloadList();
    }

    @Override
    public void onUncheck(DBHelper.Habit habit) {
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.habit_uncheck_confirm)
                .setPositiveButton(R.string.tt_confirm, (dialog, which) -> {
                    DBHelper.getInstance(requireContext()).deleteHabitRecord(habit.id, todayMillis);
                    reloadList();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    /* ---------------- 左滑删除（露出红钮，右滑/点其他收回） ---------------- */

    private void attachSwipeToDelete(RecyclerView rv) {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int adapterPos = holder.getBindingAdapterPosition();
                // 只有习惯条目（非 header/分组头/footer）参与左滑
                if (!adapter.isItemPosition(adapterPos)) {
                    return;
                }
                if (direction == ItemTouchHelper.RIGHT) {
                    adapter.resetSwipe(adapterPos);
                } else {
                    adapter.setOpenSwipe(adapterPos);
                }
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                return 0.5f;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
                // 只有习惯条目可左右滑（分组头/header/footer 不参与）
                int pos = viewHolder.getBindingAdapterPosition();
                return adapter.isItemPosition(pos)
                        ? (ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) : 0;
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas,
                                    @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState,
                                    boolean isCurrentlyActive) {
                if (!(viewHolder instanceof HabitAdapter.HabitViewHolder)) {
                    return;   // header/footer 不参与滑动
                }
                float density = getResources().getDisplayMetrics().density;
                float maxW = 56 * density;
                float clamped = dX < 0 ? Math.max(dX, -maxW) : Math.min(dX, 0);
                View fg = viewHolder.itemView.findViewById(R.id.item_habit_foreground);
                View del = viewHolder.itemView.findViewById(R.id.btn_habit_delete);
                if (fg != null) {
                    fg.setTranslationX(clamped);
                    del.setVisibility(clamped < 0 ? View.VISIBLE : View.INVISIBLE);
                }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(rv);

        // 点其他卡片/空白收回
        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    View child = rv.findChildViewUnder(e.getX(), e.getY());
                    int touched = (child == null) ? -1 : rv.getChildAdapterPosition(child);
                    adapter.closeSwipe(touched);
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) { }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
        });
    }
}
