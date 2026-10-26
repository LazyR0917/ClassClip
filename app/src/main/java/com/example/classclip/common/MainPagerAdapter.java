package com.example.classclip.common;
import com.example.classclip.R;

import com.example.classclip.habit.HabitFragment;
import com.example.classclip.plan.PlanFragment;
import com.example.classclip.timetable.TimetableFragment;
import com.example.classclip.todo.TodoFragment;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * ViewPager2 适配器：管理 4 个滑动页面。
 * 页面顺序：0 课程表 / 1 待办 / 2 习惯 / 3 规划。
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_COUNT = 4;

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new TimetableFragment();
            case 1:
                return new TodoFragment();
            case 2:
                return new HabitFragment();
            default:
                return new PlanFragment();
        }
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
