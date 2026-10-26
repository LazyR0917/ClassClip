package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 上课时间方案列表（独立整页，仿「设置时间表」原型）：
 * - 顶栏：返回 + 标题 + 右上角「创建」
 * - 当前使用方案蓝框高亮（按钮显示「使用中」），其余方案点「使用」切换
 * - 点卡片进入 TimeSchemeEditActivity 编辑
 */
public class TimeSchemeListActivity extends AppCompatActivity {

    private LinearLayout schemeList;
    private List<DBHelper.Scheme> schemes;
    private long activeId = -1;

    private final SimpleDateFormat createdFormat =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_time_scheme_list);

        schemeList = findViewById(R.id.scheme_list);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_create).setOnClickListener(v ->
                startActivity(TimeSchemeEditActivity.createIntent(this, -1)));

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑页返回后刷新（新建/删除/改名都可能发生）
        reload();
    }

    private void reload() {
        DBHelper db = DBHelper.getInstance(this);
        schemes = db.getSchemes();
        activeId = db.getActiveSchemeId();
        buildList();
    }

    private void buildList() {
        schemeList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        DBHelper db = DBHelper.getInstance(this);

        for (DBHelper.Scheme scheme : schemes) {
            View card = inflater.inflate(R.layout.item_scheme_page, schemeList, false);
            boolean active = scheme.id == activeId;

            card.setBackgroundResource(active
                    ? R.drawable.bg_scheme_card_active : R.drawable.bg_card_white);

            TextView name = card.findViewById(R.id.tv_scheme_name);
            name.setText(scheme.name);
            if (active) {
                name.setTextColor(color(R.color.brand_primary));
            }

            TextView created = card.findViewById(R.id.tv_scheme_created);
            created.setText(getString(R.string.scheme_created_at_fmt,
                    createdFormat.format(new Date(scheme.createdAt))));
            if (scheme.createdAt <= 0) {
                // v3 迁移来的老方案没有创建时间
                created.setText(R.string.scheme_tap_edit);
            }

            TextView btnUse = card.findViewById(R.id.btn_use);
            if (active) {
                btnUse.setText(R.string.scheme_in_use);
                btnUse.setBackgroundResource(R.drawable.bg_btn_light);
                btnUse.setTextColor(color(R.color.brand_primary));
                btnUse.setOnClickListener(null);
                btnUse.setClickable(false);
            } else {
                btnUse.setText(R.string.scheme_use);
                btnUse.setBackgroundResource(R.drawable.bg_btn_primary);
                btnUse.setTextColor(color(R.color.white));
                btnUse.setOnClickListener(v -> {
                    db.setActiveSchemeId(scheme.id);
                    activeId = scheme.id;
                    buildList();
                });
            }

            card.setOnClickListener(v ->
                    startActivity(TimeSchemeEditActivity.createIntent(this, scheme.id)));

            schemeList.addView(card);
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }
}
