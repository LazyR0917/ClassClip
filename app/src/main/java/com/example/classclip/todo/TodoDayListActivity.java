package com.example.classclip.todo;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.timetable.TimetableFragment;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 某一天的待办列表整页（待办页「本月」视图点有待办的日期格进入）：
 * - 顶栏：返回 + 「M月d日 · 周X」+ ＋（给该天新建待办，时间不预填）
 * - 复用第 2 页的待办卡片与交互：未完成/已完成分组展开收起、勾选、左滑删除、点卡片编辑
 */
public class TodoDayListActivity extends AppCompatActivity {

    private static final String ARG_DATE_MILLIS = "date_millis";

    private long dateMillis;
    private TodoAdapter adapter;
    private View tvEmpty;

    /** 打开某天的待办列表（dateMillis 为当天零点） */
    public static android.content.Intent intentOf(android.content.Context context, long dateMillis) {
        android.content.Intent intent = new android.content.Intent(context, TodoDayListActivity.class);
        intent.putExtra(ARG_DATE_MILLIS, dateMillis);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo_day_list);

        dateMillis = getIntent().getLongExtra(ARG_DATE_MILLIS, -1);
        if (dateMillis <= 0) {
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);
            dateMillis = today.getTimeInMillis();
        }

        // 标题：M月d日 · 周X
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dateMillis);
        int dayIdx = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;
        ((TextView) findViewById(R.id.tv_todo_day_title)).setText(String.format(Locale.CHINA,
                "%d月%d日 · %s", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                TimetableFragment.DAY_NAMES[dayIdx]));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // ＋：给该天新建待办（默认日期=该天，时间不预填）
        findViewById(R.id.btn_todo_day_add).setOnClickListener(v ->
                startActivity(TodoAddActivity.intentOf(this, dateMillis)));

        tvEmpty = findViewById(R.id.tv_todo_day_empty);
        RecyclerView rv = findViewById(R.id.rv_todo_day);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TodoAdapter(this::openEdit, this::confirmDelete);
        rv.setAdapter(adapter);
        TodoSwipeDelete.attach(rv, adapter, this::resetTodo);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 新建/编辑/勾选后回来重载（点卡片编辑、＋ 新建都会回到本页）
        reload();
    }

    private void reload() {
        List<DBHelper.Todo> todos = DBHelper.getInstance(this).getTodosOfDay(dateMillis);
        adapter.reload(todos);
        tvEmpty.setVisibility(todos.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openEdit(DBHelper.Todo todo) {
        startActivity(TodoAddActivity.intentOfEdit(this, todo.id));
    }

    /** 左滑「重置」：把已完成待办变回未完成 */
    private void resetTodo(DBHelper.Todo todo) {
        if (!todo.done) {
            Toast.makeText(this, R.string.todo_already_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        DBHelper.getInstance(this).setTodoDone(todo.id, false);
        Toast.makeText(this, R.string.todo_reset_done, Toast.LENGTH_SHORT).show();
        reload();
    }

    private void confirmDelete(DBHelper.Todo todo, int position) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.cell_delete_confirm, todo.title))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper.getInstance(this).deleteTodo(todo.id);
                    Toast.makeText(this, R.string.todo_deleted, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.course_action_cancel, (dialog, which) -> { })
                .show();
    }
}
