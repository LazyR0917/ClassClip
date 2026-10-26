package com.example.classclip.plan;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.todo.TodoAdapter;
import com.example.classclip.todo.TodoAddActivity;
import com.example.classclip.todo.TodoSwipeDelete;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 四象限某一条限的待办明细整页（规划页点象限卡进入）：
 * - 顶栏：返回 + 象限标题 + 计数
 * - 复用待办卡片交互：勾选、左滑删除/重置、点卡片编辑
 * - 显示该象限全部「未完成」待办
 */
public class PlanQuadrantActivity extends AppCompatActivity {

    private static final String ARG_QUADRANT = "quadrant";

    private static final int[] TITLE_RES = {
            R.string.quadrant1_title, R.string.quadrant2_title,
            R.string.quadrant3_title, R.string.quadrant4_title};

    private int quadrant;   // 1~4
    private TodoAdapter adapter;
    private View tvEmpty;

    public static android.content.Intent intentOf(android.content.Context context, int quadrant) {
        android.content.Intent intent = new android.content.Intent(context, PlanQuadrantActivity.class);
        intent.putExtra(ARG_QUADRANT, quadrant);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plan_quadrant);

        quadrant = getIntent().getIntExtra(ARG_QUADRANT, 4);
        if (quadrant < 1 || quadrant > 4) {
            quadrant = 4;
        }

        ((TextView) findViewById(R.id.tv_quadrant_detail_title))
                .setText(TITLE_RES[quadrant - 1]);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_quadrant_detail_empty);
        RecyclerView rv = findViewById(R.id.rv_quadrant_detail);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TodoAdapter(this::openEdit, this::confirmDelete);
        rv.setAdapter(adapter);
        TodoSwipeDelete.attach(rv, adapter, this::resetTodo);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<DBHelper.Todo> list = loadQuadrantTodos();
        adapter.reload(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        // 标题带计数
        ((TextView) findViewById(R.id.tv_quadrant_detail_title))
                .setText(getString(R.string.plan_quadrant_detail_title,
                        getString(TITLE_RES[quadrant - 1]), list.size()));
    }

    /**
     * 该象限全部待办（未完成 + 已完成，前后一年覆盖）。
     * 排序：未完成按日期/时间升序；已完成按日期/时间降序（离查看时近的在上）。
     */
    private List<DBHelper.Todo> loadQuadrantTodos() {
        DBHelper db = DBHelper.getInstance(this);
        Calendar from = Calendar.getInstance();
        from.add(Calendar.YEAR, -1);
        Calendar to = Calendar.getInstance();
        to.add(Calendar.YEAR, 1);
        List<DBHelper.Todo> all = db.getTodosBetween(from.getTimeInMillis(), to.getTimeInMillis());

        List<DBHelper.Todo> pending = new ArrayList<>();
        List<DBHelper.Todo> done = new ArrayList<>();
        for (DBHelper.Todo t : all) {
            int q = t.quadrant;
            if (q < 1 || q > 4) {
                q = 4;
            }
            if (q != quadrant) {
                continue;
            }
            if (t.done) {
                done.add(t);
            } else {
                pending.add(t);
            }
        }

        // 未完成：日期升序 → 时间升序（无时间排最后）
        java.util.Collections.sort(pending, (a, b) -> {
            if (a.dateMillis != b.dateMillis) {
                return Long.compare(a.dateMillis, b.dateMillis);
            }
            return Integer.compare(timeMinutes(a.time), timeMinutes(b.time));
        });
        // 已完成：日期降序 → 时间降序（离查看时近的在上）
        java.util.Collections.sort(done, (a, b) -> {
            if (a.dateMillis != b.dateMillis) {
                return Long.compare(b.dateMillis, a.dateMillis);
            }
            return Integer.compare(timeMinutes(b.time), timeMinutes(a.time));
        });

        List<DBHelper.Todo> result = new ArrayList<>(pending);
        result.addAll(done);
        return result;
    }

    /** "HH:mm" / "HH:mm-HH:mm" → 开始分钟数；无时间返回大值 */
    private static int timeMinutes(String time) {
        if (time == null || time.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            String[] hm = time.split("-")[0].trim().split(":");
            return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void openEdit(DBHelper.Todo todo) {
        startActivity(TodoAddActivity.intentOfEdit(this, todo.id));
    }

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
