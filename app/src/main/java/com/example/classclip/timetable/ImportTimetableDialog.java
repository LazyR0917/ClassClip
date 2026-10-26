package com.example.classclip.timetable;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入课程表弹窗（多种方式）：
 * - 粘贴文本：把教务系统/学校 App 复制的课表文字粘进来，自动解析课程/星期/节次/教室
 * - 选择文件：.txt / .csv 文本、.xls 表格（按表格坐标解析，列=星期），并提取周范围/教师
 * - 解析预览确认后：新建一张课表存入「课表管理」（不覆盖旧课表），自动切换为当前使用
 */
public class ImportTimetableDialog extends DialogFragment {

    /** 导入完成后通知课程表页刷新 */
    public interface OnImportListener {
        void onImportFinished();
    }

    /** 单条解析结果 */
    private static class ParsedCourse {
        int day = -1;            // 0=周一 ... 6=周日
        int startPeriod = -1;    // 起始小节（1 起）
        int endPeriod = -1;      // 结束小节（连课时 > startPeriod）
        String name = "";
        String room = "";
        String teacher = "";     // 启发式：单元格最后一行 2~4 个汉字
        int weekStart = 1;       // 上课周范围（解析不到时 1~20）
        int weekEnd = 20;
    }

    /** 匹配「周一 / 星期天」等星期词（含数字 1~7） */
    private static final Pattern DAY_PATTERN =
            Pattern.compile("(?:周|星期)\\s*([一二三四五六日天1-7])");
    /** 匹配「1-2节 / 第3~4节」等连节课 */
    private static final Pattern RANGE_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})\\s*节");
    /** 匹配「第5节 / 5节」等单节课 */
    private static final Pattern SINGLE_PATTERN =
            Pattern.compile("第?\\s*(\\d{1,2})\\s*节");

    private EditText etPaste;
    private View previewScroll;
    private TextView tvPreview;
    private TextView btnConfirm;
    private View textArea;
    private View fileArea;
    private TextView tabText;
    private TextView tabFile;

    private List<ParsedCourse> parsed;
    private boolean parsedOk;
    /** 最近所选文件名（去扩展名后作为导入课表默认名；粘贴路径为空串） */
    private String lastFileName = "";

    /** 系统文件选择器 */
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_import_timetable, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etPaste = view.findViewById(R.id.et_import_paste);
        previewScroll = view.findViewById(R.id.preview_scroll);
        tvPreview = view.findViewById(R.id.tv_import_preview);
        btnConfirm = view.findViewById(R.id.btn_import_confirm);
        textArea = view.findViewById(R.id.import_text_area);
        fileArea = view.findViewById(R.id.import_file_area);
        tabText = view.findViewById(R.id.tab_import_text);
        tabFile = view.findViewById(R.id.tab_import_file);

        tabText.setOnClickListener(v -> switchTab(true));
        tabFile.setOnClickListener(v -> switchTab(false));
        view.findViewById(R.id.btn_parse_preview).setOnClickListener(v -> {
            lastFileName = ""; // 手动解析粘贴内容：课表名用默认
            parseAndPreview();
        });
        view.findViewById(R.id.btn_pick_file)
                .setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));
        view.findViewById(R.id.btn_import_cancel).setOnClickListener(v -> dismiss());
        btnConfirm.setOnClickListener(v -> confirmImport());

        switchTab(true);
    }

    /** 弹窗加宽：两侧各留 16dp 边距，最宽 440dp（粘贴区需要更宽） */
    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            float density = getResources().getDisplayMetrics().density;
            int margin = (int) (16 * density);
            int maxWidth = (int) (440 * density);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = Math.min(dm.widthPixels - 2 * margin, maxWidth);
            getDialog().getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /* ---------------- 方式切换 ---------------- */

    /** true = 粘贴文本，false = 选择文件 */
    private void switchTab(boolean textTab) {
        tabText.setBackgroundResource(textTab
                ? R.drawable.bg_chip_selected : R.drawable.bg_chip_normal);
        tabText.setTextColor(color(textTab ? R.color.white : R.color.brand_text_dark));
        tabFile.setBackgroundResource(textTab
                ? R.drawable.bg_chip_normal : R.drawable.bg_chip_selected);
        tabFile.setTextColor(color(textTab ? R.color.brand_text_dark : R.color.white));
        textArea.setVisibility(textTab ? View.VISIBLE : View.GONE);
        fileArea.setVisibility(textTab ? View.GONE : View.VISIBLE);
    }

    /* ---------------- 解析 ---------------- */

    private void parseAndPreview() {
        String text = etPaste.getText().toString();
        parsed = parseText(text);
        parsedOk = !parsed.isEmpty();

        previewScroll.setVisibility(View.VISIBLE);
        if (parsedOk) {
            tvPreview.setText(buildPreview(parsed));
        } else {
            tvPreview.setText(getString(R.string.import_parsed_none));
        }
        btnConfirm.setAlpha(parsedOk ? 1f : 0.4f);
        previewScroll.scrollTo(0, 0);
    }

    /** 逐行解析课表文字：星期 + 节次必配，剩余部分里猜课程名和教室 */
    private List<ParsedCourse> parseText(String text) {
        List<ParsedCourse> result = new ArrayList<>();
        Set<String> occupied = new HashSet<>();
        if (TextUtils.isEmpty(text)) return result;

        for (String rawLine : text.split("\\n+")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher dm = DAY_PATTERN.matcher(line);
            int day = -1;
            String dayToken = null;
            if (dm.find()) {
                day = dayIndex(dm.group(1));
                dayToken = dm.group();
            }

            int startPeriod = -1;
            int endPeriod = -1;
            String periodToken = null;
            Matcher rm = RANGE_PATTERN.matcher(line);
            if (rm.find()) {
                startPeriod = smallPeriod(Integer.parseInt(rm.group(1)));
                endPeriod = smallPeriod(Integer.parseInt(rm.group(2)));
                if (startPeriod > endPeriod) {
                    int tmp = startPeriod;
                    startPeriod = endPeriod;
                    endPeriod = tmp;
                }
                periodToken = rm.group();
            } else {
                Matcher sm = SINGLE_PATTERN.matcher(line);
                if (sm.find()) {
                    startPeriod = smallPeriod(Integer.parseInt(sm.group(1)));
                    endPeriod = startPeriod;
                    periodToken = sm.group();
                }
            }

            if (day < 0 || startPeriod < 0 || endPeriod < 0) continue; // 不含星期或节次的行跳过

            // 去掉已识别的片段和分隔符，剩下的 token 里找课程名 / 教室
            String rest = line.replaceFirst(Pattern.quote(dayToken), " ")
                    .replaceFirst(Pattern.quote(periodToken), " ")
                    .replaceAll("[()（）\\[\\]【】、，,;；/|·]+", " ");

            String name = null;
            String room = null;
            for (String token : rest.trim().split("\\s+")) {
                if (token.isEmpty()) continue;
                if (name == null && !isRoomLike(token)) {
                    name = token;
                } else if (room == null && isRoomLike(token)) {
                    room = token;
                } else if (name == null) {
                    name = token;
                }
            }
            if (TextUtils.isEmpty(name)) continue;

            ParsedCourse course = new ParsedCourse();
            course.day = day;
            course.startPeriod = startPeriod;
            course.endPeriod = endPeriod;
            course.name = name;
            course.room = room == null ? "" : room;
            // 文本路径同样提取 周范围 / 教师（末位 2~4 个汉字的 token，且不像教室）
            int[] weeks = extractWeeks(rest);
            if (weeks != null) {
                course.weekStart = weeks[0];
                course.weekEnd = weeks[1];
            }
            String[] tokens = rest.trim().split("\\s+");
            for (int i = tokens.length - 1; i >= 0; i--) {
                String x = tokens[i].trim();
                if (x.matches("[一-龥]{2,4}") && !isRoomLike(x) && !x.equals(name)) {
                    course.teacher = x;
                    break;
                }
            }

            String key = day + "-" + startPeriod;
            if (occupied.contains(key)) {
                // 同一格子重复出现：后面的覆盖前面的
                for (int i = 0; i < result.size(); i++) {
                    ParsedCourse old = result.get(i);
                    if (old.day == day && old.startPeriod == startPeriod) {
                        result.set(i, course);
                        break;
                    }
                }
            } else {
                occupied.add(key);
                result.add(course);
            }
        }
        return result;
    }

    /** 「一/二/.../日/天/1~7」转星期下标（0=周一） */
    private static int dayIndex(String s) {
        String[] names = {"一", "二", "三", "四", "五", "六"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(s)) return i;
        }
        if ("日".equals(s) || "天".equals(s)) return 6;
        try {
            int n = Integer.parseInt(s);
            if (n >= 1 && n <= 7) return n - 1;
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    /** 校验小节号（支持 1~15 节），不合法返回 -1 */
    private static int smallPeriod(int n) {
        return (n >= 1 && n <= 15) ? n : -1;
    }

    /** 教室特征：含 2~4 位数字（A203 / 机房402）或楼/教室/机房/操场等关键词 */
    private static boolean isRoomLike(String token) {
        if (token.matches(".*\\d{2,4}.*")) return true;
        return token.contains("楼") || token.contains("教室") || token.contains("机房")
                || token.contains("操场") || token.contains("实验室") || token.contains("馆");
    }

    private String buildPreview(List<ParsedCourse> courses) {
        StringBuilder sb = new StringBuilder("识别到 ")
                .append(courses.size()).append(" 门课：\n");
        for (ParsedCourse c : courses) {
            sb.append(TimetableFragment.DAY_NAMES[c.day]).append(' ');
            if (c.endPeriod > c.startPeriod) {
                sb.append("第").append(c.startPeriod).append("-").append(c.endPeriod).append("节");
            } else {
                sb.append("第").append(c.startPeriod).append("节");
            }
            sb.append("  ").append(c.name);
            if (!TextUtils.isEmpty(c.room)) {
                sb.append(' ').append(c.room);
            }
            if (!TextUtils.isEmpty(c.teacher)) {
                sb.append(' ').append(c.teacher);
            }
            sb.append("  第").append(c.weekStart).append('-').append(c.weekEnd).append("周");
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /* ---------------- 文件导入 ---------------- */

    private void onFilePicked(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            byte[] bytes = readBytes(uri);
            String name = queryDisplayName(uri);
            lastFileName = name; // 课表默认名取文件名

            if (looksBinary(bytes)) {
                // 二进制 .xls：按表格坐标解析（列 = 星期，单元格 = 课程块）
                if (!name.endsWith(".xls")) {
                    Toast.makeText(requireContext(),
                            R.string.import_unsupported, Toast.LENGTH_LONG).show();
                    return;
                }
                importXls(bytes);
                return;
            }

            String content = decodeText(bytes);
            if (content == null || content.trim().isEmpty()) {
                Toast.makeText(requireContext(),
                        R.string.import_unsupported, Toast.LENGTH_LONG).show();
                return;
            }
            // 填进粘贴框方便查看和微调，再走同一套解析
            switchTab(true);
            etPaste.setText(content);
            parseAndPreview();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.import_read_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = requireContext().getContentResolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while (in != null && (len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        if (in != null) {
            in.close();
        }
        return out.toByteArray();
    }

    /** 取文件名（小写），用于判断扩展名 */
    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = requireContext().getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0).toLowerCase(Locale.CHINA);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.toLowerCase(Locale.CHINA);
    }

    /** 文本类内容解码：UTF-8 优先，出现乱码替换符时回退 GBK（教务导出常见 GB2312/GBK） */
    private String decodeText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(bytes, "GBK");
            } catch (Exception ignored) {
            }
        }
        return utf8;
    }

    /** 前 1KB 含 NUL 字节视为二进制（OLE2/zip 等） */
    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- .xls 导入：按表格坐标解析 ---------------- */

    /** 二进制 .xls：提取纯文本展示 + 按坐标解析课程，直接出预览 */
    private void importXls(byte[] bytes) {
        String text = workbookText(bytes);
        parsed = parseXlsCourses(bytes);
        if (parsed.isEmpty()) {
            parsed = parseText(text); // 兜底：按文本解析
        }
        parsedOk = !parsed.isEmpty();
        switchTab(true);
        etPaste.setText(text);
        previewScroll.setVisibility(View.VISIBLE);
        tvPreview.setText(parsedOk ? buildPreview(parsed)
                : getString(R.string.import_parsed_none));
        btnConfirm.setAlpha(parsedOk ? 1f : 0.4f);
        previewScroll.scrollTo(0, 0);
    }

    /** 用 Apache POI 提取 .xls 纯文本（粘贴框展示用） */
    private String workbookText(byte[] bytes) {
        try {
            Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes));
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).trim();
                        if (value.isEmpty()) continue;
                        if (line.length() > 0) {
                            line.append(' ');
                        }
                        line.append(value);
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
            workbook.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 按表格坐标解析 .xls：
     * - 在前 10 行找「周一 ~ 周日」表头，建立 列号 -> 星期 映射（无表头则按列序假设）
     * - 每个单元格视为一门课块：提取节次（数字区间 / 中文数字）、课程名、教室
     */
    private List<ParsedCourse> parseXlsCourses(byte[] bytes) {
        List<ParsedCourse> result = new ArrayList<>();
        try {
            Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes));
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;

                // 1) 找星期表头
                int[] colToDay = null;
                int headerRow = sheet.getFirstRowNum() - 1;
                int scanEnd = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 10);
                for (int r = sheet.getFirstRowNum(); r <= scanEnd; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    int[] map = new int[32];
                    java.util.Arrays.fill(map, -1);
                    boolean found = false;
                    short lastCol = row.getLastCellNum();
                    for (int c = 0; c < lastCol && c < 32; c++) {
                        int day = dayIndexOf(cellText(row.getCell(c), formatter));
                        if (day >= 0) {
                            map[c] = day;
                            found = true;
                        }
                    }
                    if (found) {
                        colToDay = map;
                        headerRow = r;
                        break;
                    }
                }
                if (colToDay == null) {
                    // 无表头：按列序假设第 1~7 列 = 周一~周日
                    colToDay = new int[32];
                    java.util.Arrays.fill(colToDay, -1);
                    for (int c = 1; c <= 7; c++) {
                        colToDay[c] = c - 1;
                    }
                }

                // 2) 逐单元格解析课程块（一格可能含多门课，按空行拆段）
                Set<String> occupied = new HashSet<>();
                for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    short lastCol = row.getLastCellNum();
                    for (int c = 0; c < lastCol && c < 32; c++) {
                        if (colToDay[c] < 0) continue;
                        for (ParsedCourse course : parseCellCourses(
                                cellText(row.getCell(c), formatter), colToDay[c])) {
                            if (course == null) continue;
                            String key = course.day + "-" + course.startPeriod;
                            if (!occupied.contains(key)) {
                                occupied.add(key);
                                result.add(course);
                            }
                        }
                    }
                }
            }
            workbook.close();
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return result;
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        String value = formatter.formatCellValue(cell);
        return value == null ? "" : value.trim();
    }

    /** 单元格文本里的星期（周一 / 星期三 / 周天 / 周3），没有返回 -1 */
    private static int dayIndexOf(String text) {
        if (TextUtils.isEmpty(text)) return -1;
        Matcher m = DAY_PATTERN.matcher(text);
        return m.find() ? dayIndex(m.group(1)) : -1;
    }

    /**
     * 解析一个课程单元格：可能含多门课（同一时段、不同周数的前后半学期课），
     * 按空行拆成多段，每段独立提取 节次/课程名/教室/教师/周范围。
     * 解析不出节次的一段视为非课程段跳过。
     */
    private List<ParsedCourse> parseCellCourses(String text, int day) {
        List<ParsedCourse> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return result;
        if (text.contains("备注")) {
            text = text.substring(0, text.indexOf("备注"));
        }
        // 按空行拆段（一格多门课之间以空行分隔）
        for (String seg : text.split("\\n\\s*\\n")) {
            ParsedCourse course = parseSingleCellCourse(seg, day);
            if (course != null) {
                result.add(course);
            }
        }
        return result;
    }

    /** 解析单元格内的一段（一门课） */
    private ParsedCourse parseSingleCellCourse(String text, int day) {
        if (TextUtils.isEmpty(text)) return null;

        int start = -1;
        int end = -1;
        Matcher range = Pattern.compile(
                "(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})\\s*节").matcher(text);
        Matcher chinese = Pattern.compile(
                "第\\s*([一二三四五六七八九十]{2,4})\\s*节").matcher(text);
        Matcher single = Pattern.compile(
                "第?\\s*(\\d{1,2})\\s*节").matcher(text);
        if (range.find()) {
            start = Integer.parseInt(range.group(1));
            end = Integer.parseInt(range.group(2));
        } else if (chinese.find()) {
            int[] se = chinesePeriod(chinese.group(1));
            if (se == null) return null;
            start = se[0];
            end = se[1];
        } else if (single.find()) {
            start = Integer.parseInt(single.group(1));
            end = start;
        } else {
            return null;
        }
        if (start < 1 || end > 15 || end < start) return null;

        String teacher = extractTeacher(text);
        String name = extractCourseName(text, teacher);
        if (TextUtils.isEmpty(name)) return null;

        ParsedCourse course = new ParsedCourse();
        course.day = day;
        course.startPeriod = start;
        course.endPeriod = end;
        course.name = name;
        course.room = extractRoom(text);
        course.teacher = teacher;
        int[] weeks = extractWeeks(text);
        if (weeks != null) {
            course.weekStart = weeks[0];
            course.weekEnd = weeks[1];
        }
        return course;
    }

    /** 上课周范围：支持「5-8周」「第5-8周」「[5-8]周」「1-14([周])」「1-5,9-10([周])」「8(周)」等；没有返回 null */
    private static int[] extractWeeks(String text) {
        // 0) 逗号分隔多段（单双周/隔周上课）：1-5,9-10([周]) → 取所有段的 min~max
        Matcher multi = Pattern.compile(
                "(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})(\\s*[,，、]\\s*(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2}))+")
                .matcher(text);
        if (multi.find()) {
            int min = 99, max = 0;
            Matcher seg = Pattern.compile(
                    "(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})").matcher(multi.group());
            while (seg.find()) {
                int a = clampWeek(Integer.parseInt(seg.group(1)));
                int b = clampWeek(Integer.parseInt(seg.group(2)));
                min = Math.min(min, Math.min(a, b));
                max = Math.max(max, Math.max(a, b));
            }
            return new int[]{min, max};
        }
        // 1) 区间：数字-数字 后跟（可多个括号/「第」前缀）直到「周」字，如 1-14([周])、第5-8周、[5-8]周
        Matcher range = Pattern.compile(
                "(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})\\s*[第\\(\\（\\[\\【]*\\s*[\\[\\]\\]\\)\\）\\】]*\\s*周")
                .matcher(text);
        if (range.find()) {
            int a = clampWeek(Integer.parseInt(range.group(1)));
            int b = clampWeek(Integer.parseInt(range.group(2)));
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        // 2) 单周：8(周)、[8周]、第8周、8([周])
        Matcher single = Pattern.compile(
                "(\\d{1,2})\\s*[第\\(\\（\\[\\【]*\\s*[\\[\\]\\]\\)\\）\\】]*\\s*周").matcher(text);
        if (single.find()) {
            int w = clampWeek(Integer.parseInt(single.group(1)));
            return new int[]{w, w};
        }
        // 3) 无「周」字的括号区间兜底：[1-16] / (1-16) / 第1-16
        Matcher bracketRange = Pattern.compile(
                "[第\\(\\（\\[\\【]\\s*(\\d{1,2})\\s*[-~～—–]\\s*(\\d{1,2})\\s*[\\)\\）\\]\\】]")
                .matcher(text);
        if (bracketRange.find()) {
            int a = clampWeek(Integer.parseInt(bracketRange.group(1)));
            int b = clampWeek(Integer.parseInt(bracketRange.group(2)));
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        // 4) 无「周」字的括号单数字兜底：[8] / (8)
        Matcher bracketSingle = Pattern.compile(
                "[\\(\\（\\[\\【]\\s*(\\d{1,2})\\s*[\\)\\）\\]\\】]").matcher(text);
        if (bracketSingle.find()) {
            int w = clampWeek(Integer.parseInt(bracketSingle.group(1)));
            return new int[]{w, w};
        }
        return null;
    }

    private static int clampWeek(int w) {
        if (w < 1) return 1;
        return Math.min(w, 30);
    }

    /** 教师：单元格文本最后一行 2~4 个纯汉字（教务导出通常把教师放在末尾） */
    private static String extractTeacher(String text) {
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String x = lines[i].replaceAll("[\\s　]+", "");
            if (x.matches("[一-龥]{2,4}")) {
                return x;
            }
        }
        return "";
    }

    /** 中文数字节次：七八 -> 7-8，九十 -> 9-10 */
    private static int[] chinesePeriod(String s) {
        String digits = "一二三四五六七八九";
        if (s.length() == 2) {
            int a = digits.indexOf(s.charAt(0)) + 1;
            int b = s.charAt(1) == '十' ? 10 : digits.indexOf(s.charAt(1)) + 1;
            if (a > 0 && b > 0) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        return null;
    }

    /** 课程名：整格文本去掉 周数 / 节次 / 教室 / 教师（teacher 行与 ≤3 字短行）后拼接 */
    private String extractCourseName(String text, String teacher) {
        String t = text;
        // 0) 多段周数整块：1-5,9-10([周]) → 整体去掉
        t = t.replaceAll(
                "(?:\\d{1,2}\\s*[-~～—–]\\s*\\d{1,2}\\s*[,，、]\\s*)+\\d{1,2}\\s*[-~～—–]\\s*\\d{1,2}\\s*[\\(\\（\\[\\【]*周[\\)\\）\\]\\】]*", " ");
        t = t.replaceAll("\\d{1,2}\\s*[-~～—–]\\s*\\d{1,2}\\s*[\\(\\（\\[\\【]*周[\\)\\）\\]\\】]*", " ");
        t = t.replaceAll("\\d{1,2}\\s*[\\(\\（\\[\\【]*周[\\)\\）\\]\\】]*", " ");
        t = t.replaceAll("[\\[\\【]?\\s*\\d{1,2}\\s*[-~～—–]\\s*\\d{1,2}\\s*节\\s*[\\]\\】]?", " ");
        t = t.replaceAll("第\\s*[一二三四五六七八九十]{2,4}\\s*节", " ");
        t = t.replaceAll("第?\\s*\\d{1,2}\\s*节", " ");
        t = t.replaceAll("[一-龥]{1,8}楼\\s*\\d+(\\([^)]*\\))?", " ");

        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\\n")) {
            String x = line.replaceAll("[\\s　]+", "");
            if (x.length() <= 3) continue; // 教师名等短行
            if (!TextUtils.isEmpty(teacher) && x.equals(teacher)) continue;
            sb.append(x);
        }
        return sb.toString();
    }

    /** 教室：「第一教学楼405」「逸夫楼433(网络与信息安全实验室)」这类 */
    private String extractRoom(String text) {
        Matcher m = Pattern.compile(
                "[一-龥]{1,8}楼\\s*\\d+(\\([^)]*\\))?").matcher(text);
        return m.find() ? m.group().trim() : "";
    }

    /* ---------------- 确认导入 ---------------- */

    private void confirmImport() {
        if (!parsedOk || parsed == null) {
            parseAndPreview();
            if (!parsedOk) {
                Toast.makeText(requireContext(),
                        R.string.import_parsed_none, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 导入即新建一张课表并存入「课表管理」，自动切换为当前使用；不销毁原有课表
        DBHelper db = DBHelper.getInstance(requireContext());
        long timetableId = db.insertTimetable(uniqueTimetableName(db, suggestTimetableName()));
        for (ParsedCourse c : parsed) {
            int colorIndex = (c.day * 5 + c.startPeriod) % TimetableFragment.COLOR_BG.length;
            db.insertCourse(c.day, c.startPeriod, c.endPeriod, c.name, c.room, colorIndex,
                    c.teacher, c.weekStart, c.weekEnd, timetableId);
        }
        db.setActiveTimetable(timetableId);

        Toast.makeText(requireContext(),
                getString(R.string.import_done, parsed.size()), Toast.LENGTH_SHORT).show();
        Fragment parent = getParentFragment();
        if (parent instanceof OnImportListener) {
            ((OnImportListener) parent).onImportFinished();
        }
        dismiss();
    }

    /** 新课表命名：文件导入用文件名（去扩展名），粘贴导入用固定名 */
    private String suggestTimetableName() {
        if (!TextUtils.isEmpty(lastFileName)) {
            String base = lastFileName.replaceAll("\\.(xls|xlsx|txt|csv)$", "");
            if (!TextUtils.isEmpty(base)) {
                return base;
            }
        }
        return getString(R.string.tt_paste_import);
    }

    /** 与已有课表重名时追加序号 */
    private String uniqueTimetableName(DBHelper db, String base) {
        List<String> names = new ArrayList<>();
        for (DBHelper.Timetable t : db.getTimetables()) {
            names.add(t.name);
        }
        if (!names.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")";
            if (!names.contains(candidate)) {
                return candidate;
            }
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }
}
