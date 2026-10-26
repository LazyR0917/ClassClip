package com.example.classclip.common;
import com.example.classclip.R;

import com.example.classclip.timetable.SemesterStartActivity;
import com.example.classclip.timetable.TimeSchemeListActivity;
import com.example.classclip.timetable.TimetableFragment;
import com.example.classclip.timetable.TimetableListActivity;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private DrawerLayout drawerLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        viewPager = findViewById(R.id.view_pager);
        bottomNav = findViewById(R.id.bottom_nav);
        drawerLayout = findViewById(R.id.drawer_layout);

        // 滑动换页：4 个页面（禁用左右滑翻页，仅底部 Tab 切换）
        viewPager.setAdapter(new MainPagerAdapter(this));
        viewPager.setUserInputEnabled(false);

        // 滑动页面 -> 同步底部 Tab 选中态
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 直接按位置选中对应 Tab，与滑动方向无关
                bottomNav.getMenu().getItem(position).setChecked(true);
            }
        });

        // 点击底部 Tab -> 切换到对应页面（带平滑滚动动画）
        bottomNav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_schedule) {
                    viewPager.setCurrentItem(0, true);
                    return true;
                } else if (id == R.id.nav_todo) {
                    viewPager.setCurrentItem(1, true);
                    return true;
                } else if (id == R.id.nav_habit) {
                    viewPager.setCurrentItem(2, true);
                    return true;
                } else if (id == R.id.nav_plan) {
                    viewPager.setCurrentItem(3, true);
                    return true;
                }
                return false;
            }
        });

        // 侧边抽屉菜单：课表管理（多课表切换）
        findViewById(R.id.drawer_item_timetables).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new android.content.Intent(this, TimetableListActivity.class));
        });
        // 上课时间（独立整页）
        findViewById(R.id.drawer_item_settings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new android.content.Intent(this, TimeSchemeListActivity.class));
        });
        // 设置开学日期（独立整页）
        findViewById(R.id.drawer_item_semester).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new android.content.Intent(this, SemesterStartActivity.class));
        });
        findViewById(R.id.drawer_item_import).setOnClickListener(v -> {
            closeDrawer();
            androidx.fragment.app.Fragment f =
                    getSupportFragmentManager().findFragmentByTag("f0");
            if (f instanceof TimetableFragment) {
                ((TimetableFragment) f).showImportTimetable();
            }
        });
    }

    /** 供课程表页的三横杠按钮调用：打开侧边抽屉 */
    public void openDrawer() {
        drawerLayout.openDrawer(GravityCompat.START);
    }

    private void closeDrawer() {
        drawerLayout.closeDrawer(GravityCompat.START);
    }

    /** 系统返回键：抽屉开着时先关抽屉 */
    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}
