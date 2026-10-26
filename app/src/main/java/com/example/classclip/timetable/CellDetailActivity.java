package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.todo.TodoAddActivity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 格子明细整页（点网格中有安排的格子进入）：
 * - 卡片列表展示该格子的全部安排：课程卡（课名/周期/位置/教室/老师 + 编辑）、
 *   待办卡（标题/备注/日期时间 + 编辑）；课程恒排最上面
 * - 右上角 ＋：页内弹白色选择框 添加课程 / 添加待办（保存后回到本页，不退出）
 * - 左滑卡片：露出固定宽度的红色「删除」按钮，滑到底弹确认框，确认后删除
 */
public class CellDetailActivity extends AppCompatActivity {

    private static final String ARG_DAY = "day";
    private static final String ARG_PERIOD = "period";
    private static final String ARG_DATE_MILLIS = "date_millis";
    private static final String ARG_TIME_TEXT = "time_text";

    private static final int TYPE_COURSE = 0;
    private static final int TYPE_TODO = 1;

    /** 列表项：课程或待办（课程恒排在前面） */
    private static class Item {
        final int type;
        final DBHelper.Course course;
        final DBHelper.Todo todo;

        Item(DBHelper.Course course) {
            this.type = TYPE_COURSE;
            this.course = course;
            this.todo = null;
        }

        Item(DBHelper.Todo todo) {
            this.type = TYPE_TODO;
            this.course = null;
            this.todo = todo;
        }
    }

    private int day;
    private int period;
    private long dateMillis;

    private final List<Item> items = new ArrayList<>();
    private ItemAdapter adapter;
    private View tvEmpty;

    /** 当前保持滑开（露出删除按钮）的列表位置，-1 表示全部收起 */
    private int openPosition = -1;
    /** 是否正在执行「点击其他地方收回」的动画，避免误触发 */
    private boolean closingAll = false;

    /** 打开某格子的安排列表（星期/节/该列日期/时间文案由格子带入） */
    public static Intent intentOf(android.content.Context context, int day, int period,
                                  long dateMillis, String timeText) {
        Intent intent = new Intent(context, CellDetailActivity.class);
        intent.putExtra(ARG_DAY, day);
        intent.putExtra(ARG_PERIOD, period);
        intent.putExtra(ARG_DATE_MILLIS, dateMillis);
        intent.putExtra(ARG_TIME_TEXT, timeText);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cell_detail);

        day = getIntent().getIntExtra(ARG_DAY, 0);
        period = getIntent().getIntExtra(ARG_PERIOD, 1);
        dateMillis = getIntent().getLongExtra(ARG_DATE_MILLIS, 0);
        String timeText = getIntent().getStringExtra(ARG_TIME_TEXT);

        StringBuilder title = new StringBuilder(TimetableFragment.DAY_NAMES[day])
                .append(" · 第").append(period).append("节");
        if (!TextUtils.isEmpty(timeText)) {
            title.append(" · ").append(timeText);
        }
        ((TextView) findViewById(R.id.tv_cell_title)).setText(title);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // ＋：页内弹白色选择框 添加课程 / 添加待办，保存后返回本页
        findViewById(R.id.btn_cell_add).setOnClickListener(this::showAddChooser);

        RecyclerView rv = findViewById(R.id.rv_cell_items);
        tvEmpty = findViewById(R.id.tv_cell_empty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter();
        rv.setAdapter(adapter);
        attachSwipeToDelete(rv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 编辑页返回后数据可能变了，每次回到本页重载列表
        reloadItems();
    }

    private void reloadItems() {
        items.clear();
        openPosition = -1; // 数据重载，滑开状态复位
        closingAll = false;
        DBHelper db = DBHelper.getInstance(this);
        for (DBHelper.Course c : db.getCoursesForCell(
                db.getActiveTimetableId(), day, period)) {
            items.add(new Item(c));
        }
        // 待办按 日期+时刻对区间 归属本格子
        List<DBHelper.ClassTime> times = db.getActiveClassTimes();
        for (DBHelper.Todo t : db.getTodosOfDay(dateMillis)) {
            if (periodIndexOfTime(times, t.time) == period) {
                items.add(new Item(t));
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /* ---------------- ＋ 新建选择框 ---------------- */

    /** 白色选择框：添加课程 / 添加待办（带回格子位置；保存后返回本页） */
    private void showAddChooser(View anchor) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.bg_tips_menu);
        content.setElevation(dp(8));

        final PopupWindow popup = new PopupWindow(content,
                dp(160), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);

        TextView courseItem = makeMenuItem(R.string.grid_add_course);
        content.addView(courseItem);
        View divider = new View(this);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.card_stroke));
        content.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        TextView todoItem = makeMenuItem(R.string.grid_add_todo);
        content.addView(todoItem);

        courseItem.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(CourseAddActivity.intentOf(this, day, period, period));
        });
        todoItem.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(TodoAddActivity.intentOf(this, dateMillis, period));
        });

        popup.showAsDropDown(anchor, 0, dp(6));
    }

    private TextView makeMenuItem(int textRes) {
        TextView item = new TextView(this);
        item.setText(textRes);
        item.setTextSize(15);
        item.setTextColor(ContextCompat.getColor(this, R.color.brand_text_dark));
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(14), 0, dp(14));
        return item;
    }

    /* ---------------- 左滑删除：露出底层红钮，松手后按滑程决定停住/弹回 ---------------- */

    /** 删除按钮固定宽度（dp）；待办额外多一个「重置」按钮，宽度翻倍 */
    private static final int DELETE_BTN_DP = 56;
    private static final int DELETE_BTN_TODO_DP = 112;

    /** 该列表项左滑露出的总宽度：待办=重置+删除，课程=仅删除 */
    private int swipeWidthOf(int viewType) {
        return dp(viewType == TYPE_TODO ? DELETE_BTN_TODO_DP : DELETE_BTN_DP);
    }

    private void attachSwipeToDelete(RecyclerView rv) {
        // 同时支持左滑（露出删除）和右滑（把已滑开的推回去盖住）
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            /** 松手后：左滑露出删除（保持滑开），右滑/滑程不足则收回盖住；始终不删除 */
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                if (position < 0 || position >= items.size()) {
                    return;
                }
                if (direction == ItemTouchHelper.RIGHT) {
                    // 右滑：把这一项收回盖住（若无滑开项则仅复位）
                    if (position == openPosition) {
                        openPosition = -1;
                    }
                    adapter.notifyItemChanged(position);
                    return;
                }
                // 左滑
                if (openPosition >= 0 && openPosition != position) {
                    // 滑开另一项：先收起之前那项
                    closeOpenItem();
                }
                openPosition = position;
                adapter.notifyItemChanged(position);
            }

            /** 滑到按钮宽度的一半即判定滑开 */
            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                return 0.5f;
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas canvas,
                                    @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState,
                                    boolean isCurrentlyActive) {
                int viewType = viewHolder.getBindingAdapterPosition() >= 0
                        ? items.get(viewHolder.getBindingAdapterPosition()).type : TYPE_COURSE;
                float btnW = swipeWidthOf(viewType);
                float clamped;
                if (dX < 0) {
                    // 左滑：最多露出按钮宽
                    clamped = Math.max(dX, -btnW);
                } else {
                    // 右滑：从滑开位置推回 0，不允许越过右边距
                    clamped = Math.min(dX, 0);
                }
                SwipeHolder sh = (SwipeHolder) viewHolder;
                sh.getForeground().setTranslationX(clamped);
                // 左滑（或停在滑开位置）时显示底层按钮，否则隐藏
                boolean open = clamped < 0;
                sh.getDeleteBtn().setVisibility(open ? View.VISIBLE : View.INVISIBLE);
                View reset = sh.getResetBtn();
                if (reset != null) {
                    reset.setVisibility(open ? View.VISIBLE : View.INVISIBLE);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                // 松手后由 onSwiped 决定；若是「点击其他地方收回」，这里恢复 0 位移
                int pos = viewHolder.getBindingAdapterPosition();
                if (closingAll && pos == openPosition) {
                    SwipeHolder sh = (SwipeHolder) viewHolder;
                    viewHolder.itemView.setTranslationX(0);
                    sh.getForeground().setTranslationX(0);
                    sh.getDeleteBtn().setVisibility(View.INVISIBLE);
                    if (sh.getResetBtn() != null) {
                        sh.getResetBtn().setVisibility(View.INVISIBLE);
                    }
                }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(rv);

        // 点击其他卡片 / 列表空白处：收回当前滑开的按钮
        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    View child = rv.findChildViewUnder(e.getX(), e.getY());
                    int touched = (child == null) ? -1 : rv.getChildAdapterPosition(child);
                    if (openPosition >= 0 && touched != openPosition) {
                        closeOpenItem();
                    }
                }
                return false; // 不拦截，正常分发
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv,
                                     @NonNull android.view.MotionEvent e) { }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
        });
    }

    /** 收起当前滑开的项（露出状态 → 弹回盖住删除按钮） */
    private void closeOpenItem() {
        if (openPosition < 0 || openPosition >= items.size()) {
            openPosition = -1;
            return;
        }
        int old = openPosition;
        openPosition = -1;
        RecyclerView.ViewHolder h = findViewHolder(old);
        if (h instanceof SwipeHolder) {
            SwipeHolder sh = (SwipeHolder) h;
            closingAll = true;
            // 动画弹回，结束后恢复按钮隐藏
            sh.getForeground().animate()
                    .translationX(0)
                    .setDuration(200)
                    .withEndAction(() -> {
                        closingAll = false;
                        sh.getDeleteBtn().setVisibility(View.INVISIBLE);
                        if (sh.getResetBtn() != null) {
                            sh.getResetBtn().setVisibility(View.INVISIBLE);
                        }
                        sh.getForeground().setTranslationX(0);
                    })
                    .start();
            sh.getDeleteBtn().setVisibility(View.INVISIBLE);
            if (sh.getResetBtn() != null) {
                sh.getResetBtn().setVisibility(View.INVISIBLE);
            }
        }
    }

    private RecyclerView.ViewHolder findViewHolder(int position) {
        RecyclerView rv = findViewById(R.id.rv_cell_items);
        RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(position);
        return h;
    }

    private void confirmDelete(final int position) {
        if (position < 0 || position >= items.size()) return;
        // 删除前先收回（该卡片即将移除）
        if (position == openPosition) {
            openPosition = -1;
        }
        final Item item = items.get(position);
        String label = item.type == TYPE_COURSE
                ? item.course.name : item.todo.title;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.cell_delete_confirm, label))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper db = DBHelper.getInstance(this);
                    if (item.type == TYPE_COURSE) {
                        db.deleteCourse(item.course.id);
                    } else {
                        db.deleteTodo(item.todo.id);
                        Toast.makeText(this, R.string.todo_deleted,
                                Toast.LENGTH_SHORT).show();
                    }
                    items.remove(position);
                    adapter.notifyItemRemoved(position);
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                })
                .setNegativeButton(R.string.course_action_cancel, (dialog, which) -> { })
                // 取消删除：保持滑开状态，不收起
                .show();
    }

    /* ---------------- 列表适配 ---------------- */

    private class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                                          int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_COURSE) {
                return new CourseHolder(inflater.inflate(
                        R.layout.item_cell_course, parent, false));
            }
            return new TodoHolder(inflater.inflate(
                    R.layout.item_cell_todo, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
                                     int position) {
            Item item = items.get(position);
            if (holder instanceof CourseHolder) {
                bindCourse((CourseHolder) holder, item.course);
            } else {
                bindTodo((TodoHolder) holder, item.todo);
            }
            // 重建后恢复滑开状态（露出底层按钮 + 上层卡片位移）
            SwipeHolder sh = (SwipeHolder) holder;
            if (position == openPosition) {
                sh.getForeground().setTranslationX(-swipeWidthOf(item.type));
                sh.getDeleteBtn().setVisibility(View.VISIBLE);
                if (sh.getResetBtn() != null) {
                    sh.getResetBtn().setVisibility(View.VISIBLE);
                }
            } else {
                sh.getForeground().setTranslationX(0);
                sh.getDeleteBtn().setVisibility(View.INVISIBLE);
                if (sh.getResetBtn() != null) {
                    sh.getResetBtn().setVisibility(View.INVISIBLE);
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private void bindCourse(CourseHolder h, final DBHelper.Course course) {
        // 课程卡：用课程自己的调色板色（与网格课程块同一套浅底深字）
        int idx = course.colorIndex >= 0 && course.colorIndex < TimetableFragment.COLOR_BG.length
                ? course.colorIndex : 0;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(ContextCompat.getColor(this, TimetableFragment.COLOR_BG[idx]));
        bg.setCornerRadius(dp(12));
        h.foreground.setBackground(bg);
        h.name.setTextColor(ContextCompat.getColor(this, TimetableFragment.COLOR_TEXT[idx]));

        h.name.setText(course.name);
        h.weeks.setText(getString(R.string.course_week_value,
                course.weekStart, course.weekEnd));
        StringBuilder slot = new StringBuilder(TimetableFragment.DAY_NAMES[course.day])
                .append(" | 第").append(course.startPeriod);
        if (course.endPeriod > course.startPeriod) {
            slot.append("-").append(course.endPeriod);
        }
        slot.append("节");
        String time = periodTimeText(course.startPeriod);
        if (!TextUtils.isEmpty(time)) {
            slot.append(" | ").append(time);
        }
        h.slot.setText(slot);
        h.room.setText(getString(R.string.cell_room_prefix, course.room));
        if (TextUtils.isEmpty(course.teacher)) {
            h.teacher.setVisibility(View.GONE);
        } else {
            h.teacher.setVisibility(View.VISIBLE);
            h.teacher.setText(getString(R.string.cell_teacher_prefix, course.teacher));
        }
        Runnable edit = () -> {
            startActivity(CourseAddActivity.intentOfEdit(this, course.id));
        };
        // 上层卡片点击进编辑；底层删除按钮（左滑露出）点击触发删除
        h.foreground.setOnClickListener(v -> edit.run());
        h.deleteBtn.setOnClickListener(v -> confirmDelete(
                h.getBindingAdapterPosition()));
    }

    private void bindTodo(TodoHolder h, final DBHelper.Todo todo) {
        // 待办卡：用调色板浅色底（与月历小标签同色），标题用同色系深色
        int colorIdx = (int) (todo.id % TimetableFragment.COLOR_BG.length);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(ContextCompat.getColor(this, TimetableFragment.COLOR_BG[colorIdx]));
        bg.setCornerRadius(dp(12));
        h.foreground.setBackground(bg);
        h.title.setText(todo.title);
        if (TextUtils.isEmpty(todo.note)) {
            h.note.setVisibility(View.GONE);
        } else {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText(getString(R.string.cell_note_prefix, todo.note));
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(todo.dateMillis);
        StringBuilder time = new StringBuilder(String.format(Locale.CHINA, "%d.%d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
        if (!TextUtils.isEmpty(todo.time)) {
            time.append(" ").append(todo.time);
        }
        h.time.setText(time);
        Runnable edit = () -> {
            startActivity(TodoAddActivity.intentOfEdit(this, todo.id));
        };
        h.foreground.setOnClickListener(v -> edit.run());
        h.deleteBtn.setOnClickListener(v -> confirmDelete(
                h.getBindingAdapterPosition()));
        // 重置：变回未完成
        h.resetBtn.setOnClickListener(v -> {
            if (!todo.done) {
                Toast.makeText(this, R.string.todo_already_pending, Toast.LENGTH_SHORT).show();
                return;
            }
            DBHelper.getInstance(this).setTodoDone(todo.id, false);
            Toast.makeText(this, R.string.todo_reset_done, Toast.LENGTH_SHORT).show();
            openPosition = -1;
            reloadItems();
        });
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

    private static int toMinutesOfDay(String hhmm) {
        try {
            String time = hhmm.split("-")[0].trim();
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 某小节的 "10:00 - 11:40" 文案（无数据时返回空串） */
    private String periodTimeText(int periodNo) {
        List<DBHelper.ClassTime> classTimes =
                DBHelper.getInstance(this).getActiveClassTimes();
        for (DBHelper.ClassTime ct : classTimes) {
            if (ct.periodIndex == periodNo) {
                return ct.start + " - " + ct.end;
            }
        }
        return "";
    }

    /** 支持左滑露出删除的 Holder：暴露上层卡片与底层按钮 */
    private interface SwipeHolder {
        View getForeground();
        View getDeleteBtn();
        /** 重置按钮（仅待办有；课程返回 null） */
        View getResetBtn();
    }

    private static class CourseHolder extends RecyclerView.ViewHolder implements SwipeHolder {
        final View foreground;
        final View deleteBtn;
        final TextView name;
        final TextView weeks;
        final TextView slot;
        final TextView room;
        final TextView teacher;

        CourseHolder(View view) {
            super(view);
            foreground = view.findViewById(R.id.item_foreground);
            deleteBtn = view.findViewById(R.id.btn_item_delete);
            name = view.findViewById(R.id.tv_item_course_name);
            weeks = view.findViewById(R.id.tv_item_course_weeks);
            slot = view.findViewById(R.id.tv_item_course_slot);
            room = view.findViewById(R.id.tv_item_course_room);
            teacher = view.findViewById(R.id.tv_item_course_teacher);
        }

        @Override
        public View getForeground() { return foreground; }

        @Override
        public View getDeleteBtn() { return deleteBtn; }

        @Override
        public View getResetBtn() { return null; }
    }

    private static class TodoHolder extends RecyclerView.ViewHolder implements SwipeHolder {
        final View foreground;
        final View deleteBtn;
        final View resetBtn;
        final TextView title;
        final TextView note;
        final TextView time;

        TodoHolder(View view) {
            super(view);
            foreground = view.findViewById(R.id.item_foreground);
            deleteBtn = view.findViewById(R.id.btn_item_delete);
            resetBtn = view.findViewById(R.id.btn_item_reset);
            title = view.findViewById(R.id.tv_item_todo_title);
            note = view.findViewById(R.id.tv_item_todo_note);
            time = view.findViewById(R.id.tv_item_todo_time);
        }

        @Override
        public View getForeground() { return foreground; }

        @Override
        public View getDeleteBtn() { return deleteBtn; }

        @Override
        public View getResetBtn() { return resetBtn; }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
