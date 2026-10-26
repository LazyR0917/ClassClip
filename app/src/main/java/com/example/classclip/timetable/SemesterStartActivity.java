package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.os.Bundle;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Calendar;

/**
 * 设置开学日期（独立整页，仿原型）：
 * - 实时推算：根据所选开学日期推算「今天是第几周」（开学第一天可以是第一周任意一天，
 *   计算时折算到那一周的周一）
 * - 年 / 月 / 日三列滚轮选择 + 上课周数步进器
 * - 确认后写入 SQLite（semester_start_millis / semester_weeks），课程表页立即生效
 */
public class SemesterStartActivity extends AppCompatActivity {

    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int YEAR_MIN = 2020;
    private static final int YEAR_MAX = 2035;

    private NumberPicker pickerYear;
    private NumberPicker pickerMonth;
    private NumberPicker pickerDay;
    private TextView tvWeekPreview;
    private TextView tvWeeksValue;

    private int semesterWeeks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_semester_start);

        tvWeekPreview = findViewById(R.id.tv_week_preview);
        tvWeeksValue = findViewById(R.id.tv_weeks_value);
        pickerYear = findViewById(R.id.picker_year);
        pickerMonth = findViewById(R.id.picker_month);
        pickerDay = findViewById(R.id.picker_day);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_confirm).setOnClickListener(v -> confirm());

        // 默认选中已设置的日期，没设置过则用今天
        Calendar initial = Calendar.getInstance();
        DBHelper db = DBHelper.getInstance(this);
        if (db.hasSemesterStart()) {
            initial.setTimeInMillis(db.getSemesterStartMillis());
        }
        semesterWeeks = db.getSemesterWeeks();

        setupPickers(initial);
        setupWeeksStepper();

        refreshPreview();
    }

    private void setupPickers(Calendar initial) {
        pickerYear.setMinValue(YEAR_MIN);
        pickerYear.setMaxValue(YEAR_MAX);
        pickerYear.setValue(initial.get(Calendar.YEAR));
        pickerYear.setWrapSelectorWheel(false);
        pickerYear.setFormatter(v -> v + " 年");

        pickerMonth.setMinValue(1);
        pickerMonth.setMaxValue(12);
        pickerMonth.setValue(initial.get(Calendar.MONTH) + 1);
        pickerMonth.setFormatter(v -> String.format("%02d月", v));

        pickerDay.setMinValue(1);
        pickerDay.setMaxValue(31);
        pickerDay.setValue(initial.get(Calendar.DAY_OF_MONTH));

        NumberPicker.OnValueChangeListener listener = (p, oldVal, newVal) -> {
            clampDay();
            refreshPreview();
        };
        pickerYear.setOnValueChangedListener(listener);
        pickerMonth.setOnValueChangedListener(listener);
        pickerDay.setOnValueChangedListener(listener);
    }

    /** 按年月收窄「日」的范围（如 2 月最多 28/29 天） */
    private void clampDay() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(pickerYear.getValue(), pickerMonth.getValue() - 1, 1);
        int maxDay = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        pickerDay.setMaxValue(maxDay);
        if (pickerDay.getValue() > maxDay) {
            pickerDay.setValue(maxDay);
        }
    }

    private void setupWeeksStepper() {
        tvWeeksValue.setText(String.valueOf(semesterWeeks));
        findViewById(R.id.btn_weeks_minus).setOnClickListener(v -> setWeeks(semesterWeeks - 1));
        findViewById(R.id.btn_weeks_plus).setOnClickListener(v -> setWeeks(semesterWeeks + 1));
    }

    private void setWeeks(int weeks) {
        if (weeks < 1 || weeks > 30) return;
        semesterWeeks = weeks;
        tvWeeksValue.setText(String.valueOf(weeks));
        refreshPreview();
    }

    /* ---------------- 实时推算 ---------------- */

    /** 当前选中的开学日期（零点） */
    private Calendar selectedDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(pickerYear.getValue(), pickerMonth.getValue() - 1, pickerDay.getValue());
        return c;
    }

    private void refreshPreview() {
        Calendar startMonday = mondayOf(selectedDate());
        Calendar todayMonday = mondayOf(Calendar.getInstance());

        int week = (int) ((todayMonday.getTimeInMillis() - startMonday.getTimeInMillis())
                / WEEK_MS) + 1;

        if (week < 1) {
            tvWeekPreview.setText(R.string.semester_not_started);
        } else if (week > semesterWeeks) {
            tvWeekPreview.setText(R.string.semester_over);
        } else {
            tvWeekPreview.setText(getString(R.string.semester_week_fmt, week));
        }
    }

    /* ---------------- 保存 ---------------- */

    private void confirm() {
        DBHelper db = DBHelper.getInstance(this);
        db.setSemesterStartMillis(selectedDate().getTimeInMillis());
        db.setSemesterWeeks(semesterWeeks);
        Toast.makeText(this, R.string.semester_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /* ---------------- 工具 ---------------- */

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    /** 返回某天所在周的周一（零点） */
    private static Calendar mondayOf(Calendar c) {
        Calendar result = (Calendar) c.clone();
        result.set(Calendar.HOUR_OF_DAY, 0);
        result.set(Calendar.MINUTE, 0);
        result.set(Calendar.SECOND, 0);
        result.set(Calendar.MILLISECOND, 0);
        int dow = result.get(Calendar.DAY_OF_WEEK);
        int diff = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        result.add(Calendar.DAY_OF_MONTH, -diff);
        return result;
    }
}
