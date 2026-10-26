package com.example.classclip.habit;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 自定义分类管理整页（习惯页点「自定义分类」卡进入）：
 * - 顶栏：返回 + 标题「自定义分类」+ 右上角「＋ 新建分类」
 * - 列表：每个分类一行（名称 + 该分类下的习惯数）；点「管理」重命名，点「删除」删除
 * - 种子分类（学习/健康/生活，id 1/2/3）不可删除（删除按钮隐藏），可重命名
 * - 删除自定义分类时，其下习惯归到「学习」默认分类
 */
public class CategoryManageActivity extends AppCompatActivity {

    private LinearLayout categoryList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_manage);

        categoryList = findViewById(R.id.category_list);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_category_add).setOnClickListener(v -> showNameDialog(-1));

        reload();
    }

    private void reload() {
        List<DBHelper.HabitCategory> categories =
                DBHelper.getInstance(this).getHabitCategories();
        buildList(categories);
    }

    private void buildList(List<DBHelper.HabitCategory> categories) {
        categoryList.removeAllViews();
        DBHelper db = DBHelper.getInstance(this);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (DBHelper.HabitCategory c : categories) {
            View row = inflater.inflate(R.layout.item_category, categoryList, false);

            TextView name = row.findViewById(R.id.tv_category_name);
            name.setText(c.name);

            int count = db.getHabitCountOfCategory(c.id);
            TextView countText = row.findViewById(R.id.tv_category_count);
            countText.setText(getString(R.string.habit_category_count, count));

            // 编辑（重命名）
            row.findViewById(R.id.btn_category_edit).setOnClickListener(v ->
                    showNameDialog(c.id));

            // 删除：分类下习惯连带删除
            row.findViewById(R.id.btn_category_delete).setOnClickListener(v ->
                    confirmDelete(c));

            categoryList.addView(row);
        }

        // 空态提示（理论上至少有 3 个种子分类，不会出现）
        if (categories.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.habit_category_empty);
            empty.setTextSize(13);
            empty.setTextColor(ContextCompat.getColor(this, R.color.brand_text_gray));
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            categoryList.addView(empty);
        }
    }

    /** 新建（id<0）或重命名分类 */
    private void showNameDialog(long id) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.habit_category_name_hint);
        int pad = dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        // 重命名时预填原名
        if (id > 0) {
            for (DBHelper.HabitCategory c : DBHelper.getInstance(this).getHabitCategories()) {
                if (c.id == id) {
                    input.setText(c.name);
                    input.setSelection(c.name.length());
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(id > 0
                        ? R.string.habit_category_edit_title
                        : R.string.habit_category_new_title)
                .setView(input)
                .setPositiveButton(R.string.tt_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, R.string.habit_name_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (id > 0) {
                        DBHelper.getInstance(this).renameHabitCategory(id, name);
                    } else {
                        DBHelper.getInstance(this).insertHabitCategory(name);
                    }
                    Toast.makeText(this, R.string.habit_saved, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private void confirmDelete(DBHelper.HabitCategory c) {
        int count = DBHelper.getInstance(this).getHabitCountOfCategory(c.id);
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.habit_category_delete_confirm, c.name, count))
                .setPositiveButton(R.string.tt_delete, (dialog, which) -> {
                    DBHelper.getInstance(this).deleteHabitCategory(c.id);
                    Toast.makeText(this, R.string.habit_deleted, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
