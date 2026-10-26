package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import com.example.classclip.common.WheelView;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 新建 / 编辑时间表（独立整页）：
 * - 方案名称 + 每节课上课时长 / 小课间 / 大课间（点行弹底部滚轮选择，仿 iOS 样式）
 * - 上午 / 下午 / 晚上课程节数可加减，下面按节数列出每节时间（第 N 节课 08:00-08:45 >）
 * - 点任意一节弹底部滚轮（开始时:分 — 结束时:分）；开始滚轮变化时结束自动补 = 开始 + 上课时长，
 *   手动动过结束滚轮后不再覆盖；保存后该节及之后本时段按规则自动顺延重排
 *   （每大节内两小节之间小课间，大节之间大课间）
 * - 右上角「保存并使用」：写入数据库并设为当前方案
 */
public class TimeSchemeEditActivity extends AppCompatActivity {

    private static final String ARG_SCHEME_ID = "scheme_id";
    private static final int MAX_SESSION_COUNT = 6;
    private static final int DURATION_MIN = 5;
    /** 上课时长上限 150 min，课间休息上限 60 min */
    private static final int CLASS_DURATION_MAX = 150;
    private static final int BREAK_DURATION_MAX = 60;

    /** 打开编辑页；schemeId < 0 表示新建 */
    public static Intent createIntent(Context context, long schemeId) {
        Intent intent = new Intent(context, TimeSchemeEditActivity.class);
        intent.putExtra(ARG_SCHEME_ID, schemeId);
        return intent;
    }

    private long schemeId = -1;
    /** 作息配置（时长 / 节数），开始时间以 sessionStarts 为准 */
    private final DBHelper.Scheme config = new DBHelper.Scheme();
    /** 当前显示的每节时间（可能被手动改过），下标 0 对应第 1 节 */
    private List<String[]> times = new ArrayList<>();
    /** 上 / 下 / 晚第一节的开始时间（重新推导时的基准） */
    private final String[] sessionStarts = new String[3];

    private EditText etName;
    private LinearLayout durationRows;
    private LinearLayout sessionGroups;
    /** 时长行的数值文本（提交滚轮选择后就地更新，避免整页重建跳动） */
    private final TextView[] durationValueViews = new TextView[3];

    private static final int[] SESSION_LABELS = {
            R.string.scheme_morning_count,
            R.string.scheme_afternoon_count,
            R.string.scheme_evening_count};
    private static final int[] SESSION_DOT_COLORS = {0xFFF6C344, 0xFFFF7A59, 0xFF6C7BFF};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_time_scheme_edit);

        schemeId = getIntent().getLongExtra(ARG_SCHEME_ID, -1);
        boolean create = schemeId < 0;

        etName = findViewById(R.id.et_scheme_name);
        durationRows = findViewById(R.id.duration_rows);
        sessionGroups = findViewById(R.id.session_groups);

        TextView tvTitle = findViewById(R.id.tv_edit_title);
        tvTitle.setText(create
                ? R.string.scheme_page_create_title : R.string.scheme_page_edit_title);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save_use).setOnClickListener(v -> save());

        TextView btnDelete = findViewById(R.id.btn_delete_scheme);
        if (create) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setOnClickListener(v -> confirmDelete());
        }

        initState();
        rebuild();
    }

    /** 从数据库读方案（编辑）或使用默认配置（新建） */
    private void initState() {
        DBHelper db = DBHelper.getInstance(this);
        if (schemeId >= 0) {
            DBHelper.Scheme scheme = db.getScheme(schemeId);
            if (scheme == null) {
                finish();
                return;
            }
            config.name = scheme.name;
            config.classDuration = scheme.classDuration;
            config.smallBreak = scheme.smallBreak;
            config.bigBreak = scheme.bigBreak;
            config.morningCount = scheme.morningCount;
            config.afternoonCount = scheme.afternoonCount;
            config.eveningCount = scheme.eveningCount;
            etName.setText(scheme.name);

            // 每节时间以库里存的手动修改结果为准
            times = new ArrayList<>();
            for (DBHelper.ClassTime ct : db.getSchemeTimes(schemeId)) {
                times.add(new String[]{ct.start, ct.end});
            }
        }

        sessionStarts[0] = config.morningStart;
        sessionStarts[1] = config.afternoonStart;
        sessionStarts[2] = config.eveningStart;

        if (times.isEmpty()) {
            times = DBHelper.deriveTimes(config);
        }
    }

    /* ---------------- 配置存取 ---------------- */

    private int countOf(int session) {
        if (session == 0) return config.morningCount;
        if (session == 1) return config.afternoonCount;
        return config.eveningCount;
    }

    private void setCountOf(int session, int value) {
        if (session == 0) config.morningCount = value;
        else if (session == 1) config.afternoonCount = value;
        else config.eveningCount = value;
    }

    private int sessionBase(int session) {
        int base = 0;
        for (int s = 0; s < session; s++) {
            base += countOf(s);
        }
        return base;
    }

    private void syncStartsIntoConfig() {
        config.morningStart = sessionStarts[0];
        config.afternoonStart = sessionStarts[1];
        config.eveningStart = sessionStarts[2];
    }

    /** 按当前配置重新推导每节时间（会覆盖手动微调） */
    private void regenerateTimes() {
        syncStartsIntoConfig();
        times = DBHelper.deriveTimes(config);
    }

    /* ---------------- 界面构建 ---------------- */

    private void rebuild() {
        buildDurationRows();
        buildSessionGroups();
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildDurationRows() {
        durationRows.removeAllViews();
        addDurationRow(getString(R.string.scheme_class_duration), 0);
        addDivider(durationRows);
        addDurationRow(getString(R.string.scheme_small_break), 1);
        addDivider(durationRows);
        addDurationRow(getString(R.string.scheme_big_break), 2);
    }

    /** 时长行：标签 + 「45 min ›」，点击弹底部滚轮选择 */
    private void addDurationRow(String label, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setOnClickListener(v -> showDurationSheet(which));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(14);
        tvLabel.setTextColor(color(R.color.brand_text_dark));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView value = new TextView(this);
        value.setText(getString(R.string.scheme_minutes_fmt, durationOf(which)));
        value.setTextSize(13);
        value.setTextColor(color(R.color.brand_text_gray));
        value.setPadding(0, 0, dp(6), 0);
        row.addView(value);
        durationValueViews[which] = value;

        row.addView(makeChevron());
        durationRows.addView(row);
    }

    private int durationOf(int which) {
        if (which == 0) return config.classDuration;
        if (which == 1) return config.smallBreak;
        return config.bigBreak;
    }

    private void setDurationOf(int which, int minutes) {
        if (which == 0) config.classDuration = minutes;
        else if (which == 1) config.smallBreak = minutes;
        else config.bigBreak = minutes;
    }

    private TextView makeChevron() {
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(16);
        chevron.setTextColor(color(R.color.brand_text_gray));
        return chevron;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.page_background));
        parent.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void buildSessionGroups() {
        sessionGroups.removeAllViews();
        for (int s = 0; s < 3; s++) {
            sessionGroups.addView(makeGroupCard(s));
        }
    }

    /** 一个分组卡片：节数步进器 + 每节时间行 */
    private LinearLayout makeGroupCard(final int session) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_white);
        card.setPadding(dp(14), dp(4), dp(14), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        card.setLayoutParams(cardLp);

        // 头部：圆点 + 标题 + 步进器
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(4));

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(SESSION_DOT_COLORS[session]);
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        header.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView label = new TextView(this);
        label.setText(SESSION_LABELS[session]);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(color(R.color.brand_text_dark));
        label.setPadding(dp(10), 0, 0, 0);
        header.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(makeStepper(session, true));
        header.addView(makeCountText(session));
        header.addView(makeStepper(session, false));
        card.addView(header);

        // 每节时间行
        int count = countOf(session);
        for (int j = 0; j < count; j++) {
            final int global = sessionBase(session) + j;
            if (j > 0) {
                addDivider(card);
            }
            card.addView(makePeriodRow(session, j, global));
        }
        return card;
    }

    private TextView makeStepper(final int session, final boolean minus) {
        TextView btn = new TextView(this);
        btn.setText(minus ? "−" : "+");
        btn.setTextSize(16);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(color(R.color.brand_text_dark));
        btn.setBackgroundResource(R.drawable.bg_stepper_btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        if (minus) {
            lp.rightMargin = dp(10);
        } else {
            lp.leftMargin = dp(10);
        }
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v ->
                changeCount(session, countOf(session) + (minus ? -1 : 1)));
        return btn;
    }

    private TextView makeCountText(final int session) {
        TextView count = new TextView(this);
        count.setText(String.valueOf(countOf(session)));
        count.setTextSize(16);
        count.setTypeface(Typeface.DEFAULT_BOLD);
        count.setTextColor(color(R.color.brand_text_dark));
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(36));
        return count;
    }

    private LinearLayout makePeriodRow(final int session, final int posInSession,
                                       final int globalIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setOnClickListener(v -> showTimeSheet(session, posInSession, globalIndex));

        TextView label = new TextView(this);
        label.setText(getString(R.string.scheme_period_row, globalIndex + 1));
        label.setTextSize(14);
        label.setTextColor(color(R.color.brand_text_dark));
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        TextView time = new TextView(this);
        time.setText(times.size() > globalIndex
                ? times.get(globalIndex)[0] + " - " + times.get(globalIndex)[1] : "");
        time.setTextSize(13);
        time.setTextColor(color(R.color.brand_text_gray));
        time.setPadding(0, 0, dp(6), 0);
        row.addView(time);

        row.addView(makeChevron());
        return row;
    }

    /* ---------------- 底部滚轮弹窗（仿 iOS 样式） ---------------- */

    /**
     * 通用底部弹窗骨架：手柄 + 「取消 / 标题 / 保存」标题行 + 内容区，
     * 贴底、上滑动画（复用 CourseSheetAnim）。
     */
    private Dialog makeBottomSheet(String title, View content, Runnable onSave) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_course_sheet);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, pad);

        View handle = new View(this);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(handle, handleLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(12), 0, dp(4));

        TextView cancel = new TextView(this);
        cancel.setText(R.string.scheme_cancel);
        cancel.setTextSize(15);
        cancel.setTextColor(color(R.color.brand_text_gray));
        cancel.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(color(R.color.brand_text_dark));
        tvTitle.setGravity(Gravity.CENTER);
        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = new TextView(this);
        save.setText(R.string.course_action_save);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(color(R.color.brand_primary));
        save.setOnClickListener(v -> {
            onSave.run();
            dialog.dismiss();
        });
        titleRow.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.CourseSheetAnim);
        }
        return dialog;
    }

    private TextView makeWheelSeparator(String text) {
        TextView sep = new TextView(this);
        sep.setText(text);
        sep.setTextSize(16);
        sep.setTypeface(Typeface.DEFAULT_BOLD);
        sep.setTextColor(color(R.color.brand_text_gray));
        sep.setGravity(Gravity.CENTER);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sep;
    }

    /** 时长滚轮：上课 5~150 min、课间 5~60 min，保存后就地更新行文字并重排时间 */
    private void showDurationSheet(final int which) {
        String title = which == 0 ? getString(R.string.scheme_class_duration)
                : which == 1 ? getString(R.string.scheme_small_break)
                : getString(R.string.scheme_big_break);
        int maxValue = which == 0 ? CLASS_DURATION_MAX : BREAK_DURATION_MAX;

        String[] values = new String[maxValue - DURATION_MIN + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = (DURATION_MIN + i) + " min";
        }
        final WheelView wheel = new WheelView(this, values,
                Math.max(0, durationOf(which) - DURATION_MIN));
        wheel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        // 蓝框垫在滚轮下层，正好套住中间行（仿 demo）
        FrameLayout content = new FrameLayout(this);
        View box = new View(this);
        box.setBackgroundResource(R.drawable.bg_wheel_center);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER_VERTICAL);
        boxLp.leftMargin = dp(24);
        boxLp.rightMargin = dp(24);
        content.addView(box, boxLp);
        content.addView(wheel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        Dialog dialog = makeBottomSheet(title, content, () -> {
            int minutes = DURATION_MIN + wheel.getSelection();
            setDurationOf(which, minutes);
            durationValueViews[which].setText(
                    getString(R.string.scheme_minutes_fmt, minutes));
            regenerateTimes();
            buildSessionGroups();
        });
        dialog.show();
    }

    /**
     * 每节时间滚轮：开始时 : 分 — 结束时 : 分。
     * 开始滚轮变化时结束自动补 = 开始 + 上课时长；手动动过结束滚轮后不再覆盖。
     * 保存后该节及之后本时段自动顺延重排。
     */
    private void showTimeSheet(final int session, final int posInSession,
                               final int globalIndex) {
        if (times.size() <= globalIndex) return;
        String[] current = times.get(globalIndex);
        int startMin = DBHelper.toMinutes(current[0]);
        int endMin = DBHelper.toMinutes(current[1]);
        if (startMin < 0 || endMin < 0) {
            startMin = DBHelper.toMinutes(sessionStarts[session]);
            endMin = startMin + config.classDuration;
        }

        String[] hourValues = new String[24];
        String[] minuteValues = new String[60];
        for (int i = 0; i < 24; i++) {
            hourValues[i] = String.format(Locale.CHINA, "%02d", i);
        }
        for (int i = 0; i < 60; i++) {
            minuteValues[i] = String.format(Locale.CHINA, "%02d", i);
        }
        final WheelView startHour = new WheelView(this, hourValues, startMin / 60);
        final WheelView startMinute = new WheelView(this, minuteValues, startMin % 60);
        final WheelView endHour = new WheelView(this, hourValues, Math.min(endMin / 60, 23));
        final WheelView endMinute = new WheelView(this, minuteValues, endMin % 60);

        // 手动动过结束滚轮后，开始变化不再自动改结束；syncing 防止程序赋值被当成手动
        final boolean[] endTouched = {false};
        final boolean[] syncing = {false};

        Runnable syncEnd = () -> {
            int end = startHour.getSelection() * 60 + startMinute.getSelection()
                    + config.classDuration;
            if (end > 23 * 60 + 59) {
                end = 23 * 60 + 59;
            }
            syncing[0] = true;
            endHour.setSelection(end / 60);
            endMinute.setSelection(end % 60);
            syncing[0] = false;
        };
        WheelView.OnCenterChanged startListener = index -> {
            if (!endTouched[0]) {
                syncEnd.run();
            }
        };
        startHour.setOnCenterChangedListener(startListener);
        startMinute.setOnCenterChangedListener(startListener);
        WheelView.OnCenterChanged endListener = index -> {
            if (!syncing[0]) {
                endTouched[0] = true;
            }
        };
        endHour.setOnCenterChangedListener(endListener);
        endMinute.setOnCenterChangedListener(endListener);

        LinearLayout wheels = new LinearLayout(this);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(dp(60), dp(220));
        wheels.addView(startHour, new LinearLayout.LayoutParams(wheelLp));
        wheels.addView(makeWheelSeparator("："));
        wheels.addView(startMinute, new LinearLayout.LayoutParams(wheelLp));
        wheels.addView(makeWheelSeparator("—"));
        wheels.addView(endHour, new LinearLayout.LayoutParams(wheelLp));
        wheels.addView(makeWheelSeparator("："));
        wheels.addView(endMinute, new LinearLayout.LayoutParams(wheelLp));

        // 蓝框垫在滚轮下层，横跨整行套住中间项（仿 demo）
        FrameLayout content = new FrameLayout(this);
        View box = new View(this);
        box.setBackgroundResource(R.drawable.bg_wheel_center);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER_VERTICAL);
        boxLp.leftMargin = dp(16);
        boxLp.rightMargin = dp(16);
        content.addView(box, boxLp);
        content.addView(wheels, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(220), Gravity.CENTER));

        String title = getString(R.string.scheme_period_row, globalIndex + 1);
        Dialog dialog = makeBottomSheet(title, content, () -> {
            int start = startHour.getSelection() * 60 + startMinute.getSelection();
            int end = endHour.getSelection() * 60 + endMinute.getSelection();
            if (end <= start) {
                Toast.makeText(this, R.string.scheme_invalid_time, Toast.LENGTH_SHORT).show();
                return;
            }
            rerollFrom(session, posInSession, start, end);
            buildSessionGroups();
        });
        dialog.show();
    }

    /* ---------------- 重排 ---------------- */

    /**
     * 从时段内第 fromPos 节开始重排：该节用用户输入的起止，
     * 后面每节开始 = 上一节结束 + 课间（小/大按序数规则），结束 = 开始 + 上课时长。
     * 只重排本时段，不影响下午 / 晚上各自的开始时间。
     */
    private void rerollFrom(int session, int fromPos, int startMin, int endMin) {
        int base = sessionBase(session);
        int count = countOf(session);
        int start = startMin;
        for (int j = fromPos; j < count; j++) {
            int end = (j == fromPos) ? endMin : start + config.classDuration;
            times.set(base + j, new String[]{
                    DBHelper.formatMinutes(start), DBHelper.formatMinutes(end)});
            int gap = (j % 2 == 0) ? config.smallBreak : config.bigBreak;
            start = end + gap;
        }
        if (fromPos == 0) {
            // 该时段第一节的开始变化，后续重新推导以它为基准
            sessionStarts[session] = DBHelper.formatMinutes(startMin);
        }
    }

    /* ---------------- 交互 ---------------- */

    /** 调整节数；重新推导时间（同步到 config） */
    private void changeCount(int session, int newCount) {
        if (newCount < 0 || newCount > MAX_SESSION_COUNT) return;
        int total = config.morningCount + config.afternoonCount + config.eveningCount;
        if (total <= 1 && newCount < countOf(session)) {
            Toast.makeText(this, R.string.scheme_min_count, Toast.LENGTH_SHORT).show();
            return;
        }
        setCountOf(session, newCount);
        regenerateTimes();
        rebuild();
    }

    /* ---------------- 保存 / 删除 ---------------- */

    private void save() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etName.setError(getString(R.string.scheme_name_required));
            return;
        }

        syncStartsIntoConfig();
        config.name = name;

        DBHelper db = DBHelper.getInstance(this);
        if (schemeId < 0) {
            config.createdAt = System.currentTimeMillis();
            db.insertScheme(config); // 新建自动设为当前使用
        } else {
            config.id = schemeId;
            db.updateScheme(config);
            db.setActiveSchemeId(schemeId); // 「保存并使用」
        }

        Toast.makeText(this, R.string.scheme_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.scheme_delete_page)
                .setMessage(R.string.scheme_delete_confirm)
                .setPositiveButton(R.string.course_action_delete, (d, w) -> {
                    DBHelper.getInstance(this).deleteScheme(schemeId);
                    Toast.makeText(this, R.string.scheme_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.course_action_cancel, null)
                .show();
    }
}
