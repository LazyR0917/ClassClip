package com.example.classclip.common;
import com.example.classclip.R;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地数据库（原生 SQLite）。
 * - settings 表：键值对（学期第一周、上课周数、当前使用的时间方案等）
 * - courses 表：课程格数据（星期 + 起止小节），课程表页可点击编辑
 * - time_schemes 表：上课时间方案（上课时长/小课间/大课间/上下午晚上节数/三段开始时间）
 * - scheme_times 表：方案推导出的每小节时间（scheme_id + period_index 1~N 定位）
 * 后续待办等数据也建在这个库里。
 */
public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "classclip.db";
    private static final int DB_VERSION = 12;

    public static final String TABLE_SETTINGS = "settings";
    public static final String TABLE_COURSES = "courses";
    public static final String TABLE_TIMETABLES = "timetables";
    public static final String TABLE_TODOS = "todos";
    public static final String TABLE_TIME_SCHEMES = "time_schemes";
    public static final String TABLE_SCHEME_TIMES = "scheme_times";

    public static final String KEY_SEMESTER_START = "semester_start_millis";
    public static final String KEY_SEMESTER_WEEKS = "semester_weeks";
    public static final String KEY_ACTIVE_SCHEME = "active_scheme_id";
    public static final String KEY_ACTIVE_TIMETABLE = "active_timetable";

    private static DBHelper instance;

    public static synchronized DBHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DBHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_SETTINGS + " ("
                + "key TEXT PRIMARY KEY, "
                + "value TEXT NOT NULL)");

        createTimetablesTable(db);
        seedDefaultTimetable(db);

        createCoursesTable(db);
        seedCourses(db);

        createTimeSchemeTable(db);
        seedDefaultScheme(db);

        createTodosTable(db);

        createHabitsTable(db);
        createHabitRecordsTable(db);
        createHabitCategoriesTable(db);
        seedHabitCategories(db);
        seedHabits(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createCoursesTable(db);
            seedCourses(db);
        }
        if (oldVersion < 3) {
            // v2 及更早：旧 class_times 表迁成「默认」方案（先按 v3 结构建表）
            createTimeSchemeTableV3(db);
            migrateOldClassTimes(db);
            if (oldVersion < 2) {
                createCoursesTable(db);
                seedCourses(db);
            }
        }
        if (oldVersion < 4) {
            upgradeSchemesToV4(db);
            upgradeCoursesToV4(db);
        }
        if (oldVersion < 5) {
            upgradeCoursesToV5(db);
        }
        if (oldVersion < 6) {
            upgradeToV6(db);
        }
        if (oldVersion < 8) {
            upgradeIndexToV8(db);
        }
        if (oldVersion < 9) {
            // todos 表重建：去掉 节次 列、新增 time（HH:mm），格子按 时刻对区间 归属
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_TODOS);
            createTodosTable(db);
        }
        if (oldVersion < 10) {
            // todos 加优先级（默认中）与四象限（默认未设置），月历按优先级→时间排序
            db.execSQL("ALTER TABLE " + TABLE_TODOS
                    + " ADD COLUMN priority INTEGER NOT NULL DEFAULT 2");
            db.execSQL("ALTER TABLE " + TABLE_TODOS
                    + " ADD COLUMN quadrant INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 11) {
            // 习惯打卡：habits 表 + habit_records 打卡记录；老用户也补 4 条种子习惯
            createHabitsTable(db);
            createHabitRecordsTable(db);
            createHabitCategoriesTable(db);
            seedHabitCategories(db);
            // 老数据 category 存的是 0/1/2（学习/健康/生活下标），映射到种子分类 id 1/2/3
            db.execSQL("UPDATE " + TABLE_HABITS + " SET category = category + 1");
            seedHabits(db);
        }
        if (oldVersion < 12) {
            // 自定义分类表 + 种子（学习/健康/生活）；旧 habits.category 0/1/2 → 1/2/3
            createHabitCategoriesTable(db);
            seedHabitCategories(db);
            db.execSQL("UPDATE " + TABLE_HABITS + " SET category = category + 1");
        }
    }

    /* ---------------- v7 -> v8 迁移：槽位索引按课表隔离 ---------------- */

    /**
     * 旧索引 UNIQUE(day, start_period) 是全局唯一的：多课表下同槽位冲突、
     * 同课表单双周课程也冲突，导致导入课程插入失败。
     * 改为普通索引 (timetable_id, day, start_period)：同槽位允许存多条
     * （格子明细弹窗会全部列出，单双周/跨课表共存）。
     */
    private void upgradeIndexToV8(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS idx_courses_slot");
        db.execSQL("CREATE INDEX idx_courses_slot ON " + TABLE_COURSES
                + " (timetable_id, day, start_period)");
    }

    /* ---------------- todos 待办 ---------------- */

    /** 优先级：0=未设置 1=低 2=中 3=高（越大越靠前） */
    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_MID = 2;
    public static final int PRIORITY_HIGH = 3;

    /** 四象限：0=未设置 1=重要且紧急 2=重要不紧急 3=紧急不重要 4=不紧急不重要 */
    public static final int QUADRANT_NONE = 0;
    public static final int QUADRANT_IMPORTANT_URGENT = 1;
    public static final int QUADRANT_IMPORTANT_NOT_URGENT = 2;
    public static final int QUADRANT_URGENT_NOT_IMPORTANT = 3;
    public static final int QUADRANT_NEITHER = 4;

    private void createTodosTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TODOS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, "
                + "note TEXT NOT NULL DEFAULT '', "
                + "date_millis INTEGER NOT NULL, "   // 具体日期（零点）
                + "time TEXT NOT NULL DEFAULT '', "  // HH:mm；格子按时刻对上课区间归属
                + "done INTEGER NOT NULL DEFAULT 0, "
                + "priority INTEGER NOT NULL DEFAULT 2, "   // 0未设置 1低 2中 3高
                + "quadrant INTEGER NOT NULL DEFAULT 0)");  // 0未设置 1~4 四象限
    }

    /** 一条待办 */
    public static class Todo {
        public final long id;
        public final String title;
        public final String note;
        public final long dateMillis;
        public final String time;
        public final boolean done;
        public final int priority;
        public final int quadrant;

        public Todo(long id, String title, String note, long dateMillis,
                    String time, boolean done, int priority, int quadrant) {
            this.id = id;
            this.title = title;
            this.note = note;
            this.dateMillis = dateMillis;
            this.time = time;
            this.done = done;
            this.priority = priority;
            this.quadrant = quadrant;
        }
    }

    private Todo readTodo(Cursor c) {
        return new Todo(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                c.getString(4), c.getInt(5) == 1, c.getInt(6), c.getInt(7));
    }

    /** 某一天的全部待办（第 2 页待办列表 / 格子明细页用，按 time 升序） */
    public List<Todo> getTodosOfDay(long dateMillis) {
        List<Todo> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TODOS, null,
                "date_millis = ?", new String[]{String.valueOf(dateMillis)},
                null, null, "time ASC");
        while (c.moveToNext()) {
            list.add(readTodo(c));
        }
        c.close();
        return list;
    }

    /** 当前显示周区间内的全部待办（课程表页一次查出再按 日期+时刻 分组，避免逐格查询） */
    public List<Todo> getTodosBetween(long fromMillis, long toMillis) {
        List<Todo> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TODOS, null,
                "date_millis >= ? AND date_millis <= ?",
                new String[]{String.valueOf(fromMillis), String.valueOf(toMillis)},
                null, null, "date_millis ASC, time ASC");
        while (c.moveToNext()) {
            list.add(readTodo(c));
        }
        c.close();
        return list;
    }

    public Todo getTodo(long id) {
        Cursor c = getReadableDatabase().query(TABLE_TODOS, null,
                "_id = ?", new String[]{String.valueOf(id)},
                null, null, null);
        Todo todo = null;
        if (c.moveToFirst()) {
            todo = readTodo(c);
        }
        c.close();
        return todo;
    }

    public long insertTodo(String title, String note, long dateMillis, String time,
                           int priority, int quadrant) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("note", note);
        cv.put("date_millis", dateMillis);
        // time 列 NOT NULL：未设置时间存空串（存 null 会违反约束导致插入失败）
        cv.put("time", time == null ? "" : time);
        cv.put("done", 0);
        cv.put("priority", priority);
        cv.put("quadrant", quadrant);
        return getWritableDatabase().insert(TABLE_TODOS, null, cv);
    }

    public void updateTodo(long id, String title, String note,
                           long dateMillis, String time,
                           int priority, int quadrant) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("note", note);
        cv.put("date_millis", dateMillis);
        cv.put("time", time == null ? "" : time);
        cv.put("priority", priority);
        cv.put("quadrant", quadrant);
        getWritableDatabase().update(TABLE_TODOS, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTodo(long id) {
        getWritableDatabase().delete(TABLE_TODOS,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 勾选/取消完成 */
    public void setTodoDone(long id, boolean done) {
        ContentValues cv = new ContentValues();
        cv.put("done", done ? 1 : 0);
        getWritableDatabase().update(TABLE_TODOS, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /* ---------------- v5 -> v6 迁移：多课表 ---------------- */

    /**
     * 新增 timetables 表（多张课表并存，可切换），courses 挂 timetable_id。
     * 旧数据全部归入「默认课表」并设为当前使用。
     */
    private void upgradeToV6(SQLiteDatabase db) {
        createTimetablesTable(db);
        seedDefaultTimetable(db);
        db.execSQL("ALTER TABLE " + TABLE_COURSES
                + " ADD COLUMN timetable_id INTEGER NOT NULL DEFAULT 1");
        ContentValues av = new ContentValues();
        av.put("key", KEY_ACTIVE_TIMETABLE);
        av.put("value", "1");
        db.insertWithOnConflict(TABLE_SETTINGS, null, av, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /* ---------------- timetables 课表 ---------------- */

    private void createTimetablesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TIMETABLES + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL DEFAULT 0)");
    }

    private void seedDefaultTimetable(SQLiteDatabase db) {
        ContentValues cv = new ContentValues();
        cv.put("name", "默认课表");
        cv.put("created_at", System.currentTimeMillis());
        db.insert(TABLE_TIMETABLES, null, cv);
    }

    /** 一张课表：名称 + 课程数 */
    public static class Timetable {
        public final long id;
        public final String name;
        public final int courseCount;

        public Timetable(long id, String name, int courseCount) {
            this.id = id;
            this.name = name;
            this.courseCount = courseCount;
        }
    }

    public List<Timetable> getTimetables() {
        List<Timetable> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TIMETABLES, null, null, null,
                null, null, "created_at ASC, _id ASC");
        while (c.moveToNext()) {
            long id = c.getLong(0);
            list.add(new Timetable(id, c.getString(1), countCourses(id)));
        }
        c.close();
        return list;
    }

    private int countCourses(long timetableId) {
        Cursor c = getReadableDatabase().query(TABLE_COURSES,
                new String[]{"COUNT(*)"},
                "timetable_id = ?", new String[]{String.valueOf(timetableId)},
                null, null, null);
        int n = 0;
        if (c.moveToFirst()) {
            n = c.getInt(0);
        }
        c.close();
        return n;
    }

    /** 新建空白/导入课表，返回新表 id（不自动切换，切换由调用方决定） */
    public long insertTimetable(String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_TIMETABLES, null, cv);
    }

    public void renameTimetable(long id, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        getWritableDatabase().update(TABLE_TIMETABLES, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 删除课表及其全部课程；最后一张不可删。删的是当前表时自动切到剩余第一张 */
    public boolean deleteTimetable(long id) {
        if (getTimetables().size() <= 1) {
            return false;
        }
        getWritableDatabase().delete(TABLE_COURSES,
                "timetable_id = ?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete(TABLE_TIMETABLES,
                "_id = ?", new String[]{String.valueOf(id)});
        if (getActiveTimetableId() == id) {
            List<Timetable> rest = getTimetables();
            if (!rest.isEmpty()) {
                setActiveTimetable(rest.get(0).id);
            }
        }
        return true;
    }

    public long getActiveTimetableId() {
        String v = getSetting(KEY_ACTIVE_TIMETABLE);
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return 1;
        }
    }

    public void setActiveTimetable(long id) {
        putSetting(KEY_ACTIVE_TIMETABLE, String.valueOf(id));
    }

    /* ---------------- v4 -> v5 迁移 ---------------- */

    /** courses 增加任课老师 / 上课周范围（添加课程整页表单的字段） */
    private void upgradeCoursesToV5(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE_COURSES
                + " ADD COLUMN teacher TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE " + TABLE_COURSES
                + " ADD COLUMN week_start INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE " + TABLE_COURSES
                + " ADD COLUMN week_end INTEGER NOT NULL DEFAULT 20");
    }

    /* ---------------- v3 -> v4 迁移 ---------------- */

    /** time_schemes 补节数配置列，并按新模型重算每小节时间 */
    private void upgradeSchemesToV4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN class_duration INTEGER NOT NULL DEFAULT 45");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN small_break INTEGER NOT NULL DEFAULT 10");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN big_break INTEGER NOT NULL DEFAULT 20");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN morning_start TEXT NOT NULL DEFAULT '8:00'");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN afternoon_start TEXT NOT NULL DEFAULT '14:00'");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN evening_start TEXT NOT NULL DEFAULT '19:00'");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN morning_count INTEGER NOT NULL DEFAULT 4");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN afternoon_count INTEGER NOT NULL DEFAULT 4");
        db.execSQL("ALTER TABLE " + TABLE_TIME_SCHEMES
                + " ADD COLUMN evening_count INTEGER NOT NULL DEFAULT 2");

        // 旧方案只有 5 大节时间：取第 1/3/5 节开始时间作为上/下/晚第一节的开始
        Cursor c = db.query(TABLE_TIME_SCHEMES, new String[]{"_id"},
                null, null, null, null, "_id ASC");
        List<Long> ids = new ArrayList<>();
        while (c.moveToNext()) {
            ids.add(c.getLong(0));
        }
        c.close();

        for (long id : ids) {
            List<ClassTime> oldTimes = new ArrayList<>();
            Cursor t = db.query(TABLE_SCHEME_TIMES, new String[]{"start_time", "end_time"},
                    "scheme_id = ?", new String[]{String.valueOf(id)},
                    null, null, "period_index ASC");
            while (t.moveToNext()) {
                oldTimes.add(new ClassTime(0, t.getString(0), t.getString(1)));
            }
            t.close();

            String morning = oldTimes.size() > 0 ? oldTimes.get(0).start : "8:00";
            String afternoon = oldTimes.size() > 2 ? oldTimes.get(2).start : "14:00";
            String evening = oldTimes.size() > 4 ? oldTimes.get(4).start : "19:00";

            Scheme scheme = new Scheme();
            scheme.id = id;
            scheme.classDuration = 45;
            scheme.smallBreak = 10;
            scheme.bigBreak = 20;
            scheme.morningStart = morning;
            scheme.afternoonStart = afternoon;
            scheme.eveningStart = evening;
            scheme.morningCount = 4;
            scheme.afternoonCount = 4;
            scheme.eveningCount = 2;

            ContentValues cv = new ContentValues();
            putSchemeConfig(cv, scheme);
            db.update(TABLE_TIME_SCHEMES, cv, "_id = ?", new String[]{String.valueOf(id)});

            // 按新配置重写每小节时间
            db.delete(TABLE_SCHEME_TIMES, "scheme_id = ?", new String[]{String.valueOf(id)});
            saveSchemeTimes(db, id, deriveTimes(scheme));
        }
    }

    /** courses 表从「大节下标」改为「起止小节」，旧 1-2 节映射成小节 1~2 */
    private void upgradeCoursesToV4(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE courses_v4 ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "day INTEGER NOT NULL, "
                + "start_period INTEGER NOT NULL, "
                + "end_period INTEGER NOT NULL, "
                + "name TEXT NOT NULL, "
                + "room TEXT NOT NULL DEFAULT '', "
                + "color_index INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO courses_v4 "
                + "(_id, day, start_period, end_period, name, room, color_index) "
                + "SELECT _id, day, period * 2 + 1, period * 2 + 2, name, room, color_index "
                + "FROM " + TABLE_COURSES);
        db.execSQL("DROP TABLE " + TABLE_COURSES);
        db.execSQL("ALTER TABLE courses_v4 RENAME TO " + TABLE_COURSES);
        // v8 起改为普通索引（timetable_id, day, start_period）；旧库在 upgradeIndexToV8 重建
        db.execSQL("CREATE UNIQUE INDEX idx_courses_slot ON " + TABLE_COURSES
                + " (day, start_period)");
    }

    /* ---------------- v2 -> v3 迁移（历史路径） ---------------- */

    private void createTimeSchemeTableV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TIME_SCHEMES + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE " + TABLE_SCHEME_TIMES + " ("
                + "scheme_id INTEGER NOT NULL, "
                + "period_index INTEGER NOT NULL, "
                + "start_time TEXT NOT NULL, "
                + "end_time TEXT NOT NULL, "
                + "PRIMARY KEY (scheme_id, period_index))");
    }

    private void migrateOldClassTimes(SQLiteDatabase db) {
        List<String[]> times = new ArrayList<>();
        Cursor c = db.query("class_times", new String[]{"start_time", "end_time"},
                null, null, null, null, "period_index ASC");
        while (c.moveToNext()) {
            times.add(new String[]{c.getString(0), c.getString(1)});
        }
        c.close();

        ContentValues cv = new ContentValues();
        cv.put("name", "默认");
        long id = db.insert(TABLE_TIME_SCHEMES, null, cv);
        for (int p = 0; p < times.size() && p < 5; p++) {
            ContentValues tv = new ContentValues();
            tv.put("scheme_id", id);
            tv.put("period_index", p);
            tv.put("start_time", times.get(p)[0]);
            tv.put("end_time", times.get(p)[1]);
            db.insert(TABLE_SCHEME_TIMES, null, tv);
        }

        ContentValues av = new ContentValues();
        av.put("key", KEY_ACTIVE_SCHEME);
        av.put("value", String.valueOf(id));
        db.insertWithOnConflict(TABLE_SETTINGS, null, av, SQLiteDatabase.CONFLICT_REPLACE);

        db.execSQL("DROP TABLE IF EXISTS class_times");
    }

    /* ---------------- 时间方案 ---------------- */

    private void createTimeSchemeTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TIME_SCHEMES + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL UNIQUE, "
                + "created_at INTEGER NOT NULL DEFAULT 0, "
                + "class_duration INTEGER NOT NULL DEFAULT 45, "   // 每节课上课时长（分钟）
                + "small_break INTEGER NOT NULL DEFAULT 10, "      // 小课间休息（分钟）
                + "big_break INTEGER NOT NULL DEFAULT 20, "        // 大课间休息（分钟）
                + "morning_start TEXT NOT NULL DEFAULT '8:00', "   // 上午第一节开始
                + "afternoon_start TEXT NOT NULL DEFAULT '14:00', "// 下午第一节开始
                + "evening_start TEXT NOT NULL DEFAULT '19:00', "  // 晚上第一节开始
                + "morning_count INTEGER NOT NULL DEFAULT 4, "
                + "afternoon_count INTEGER NOT NULL DEFAULT 4, "
                + "evening_count INTEGER NOT NULL DEFAULT 2)");
        db.execSQL("CREATE TABLE " + TABLE_SCHEME_TIMES + " ("
                + "scheme_id INTEGER NOT NULL, "
                + "period_index INTEGER NOT NULL, "                // 1~N 小节
                + "start_time TEXT NOT NULL, "
                + "end_time TEXT NOT NULL, "
                + "PRIMARY KEY (scheme_id, period_index))");
    }

    /** 初始仅一个「默认」方案，不预置任何学校的作息 */
    private void seedDefaultScheme(SQLiteDatabase db) {
        Scheme scheme = new Scheme();
        scheme.name = "默认";
        scheme.createdAt = System.currentTimeMillis();
        scheme.classDuration = 45;
        scheme.smallBreak = 10;
        scheme.bigBreak = 20;
        scheme.morningStart = "8:00";
        scheme.afternoonStart = "14:00";
        scheme.eveningStart = "19:00";
        scheme.morningCount = 4;
        scheme.afternoonCount = 4;
        scheme.eveningCount = 2;

        long id = insertSchemeInternal(db, scheme);

        ContentValues av = new ContentValues();
        av.put("key", KEY_ACTIVE_SCHEME);
        av.put("value", String.valueOf(id));
        db.insertWithOnConflict(TABLE_SETTINGS, null, av, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private long insertSchemeInternal(SQLiteDatabase db, Scheme scheme) {
        ContentValues cv = new ContentValues();
        cv.put("name", scheme.name);
        cv.put("created_at", scheme.createdAt > 0 ? scheme.createdAt : System.currentTimeMillis());
        putSchemeConfig(cv, scheme);
        long id = db.insert(TABLE_TIME_SCHEMES, null, cv);
        saveSchemeTimes(db, id, deriveTimes(scheme));
        return id;
    }

    private void putSchemeConfig(ContentValues cv, Scheme scheme) {
        cv.put("class_duration", scheme.classDuration);
        cv.put("small_break", scheme.smallBreak);
        cv.put("big_break", scheme.bigBreak);
        cv.put("morning_start", scheme.morningStart);
        cv.put("afternoon_start", scheme.afternoonStart);
        cv.put("evening_start", scheme.eveningStart);
        cv.put("morning_count", scheme.morningCount);
        cv.put("afternoon_count", scheme.afternoonCount);
        cv.put("evening_count", scheme.eveningCount);
    }

    private void saveSchemeTimes(SQLiteDatabase db, long schemeId, List<String[]> times) {
        for (int p = 0; p < times.size(); p++) {
            ContentValues tv = new ContentValues();
            tv.put("scheme_id", schemeId);
            tv.put("period_index", p + 1);
            tv.put("start_time", times.get(p)[0]);
            tv.put("end_time", times.get(p)[1]);
            db.insert(TABLE_SCHEME_TIMES, null, tv);
        }
    }

    /**
     * 按作息配置推导每小节时间。
     * 规则：每大节内两小节之间用小课间，大节之间用大课间
     * （第1→2节小课间、第2→3节大课间、第3→4节小课间……与各校作息一致）。
     */
    public static List<String[]> deriveTimes(Scheme scheme) {
        List<String[]> result = new ArrayList<>();
        String[][] sessions = {
                {scheme.morningStart, String.valueOf(scheme.morningCount)},
                {scheme.afternoonStart, String.valueOf(scheme.afternoonCount)},
                {scheme.eveningStart, String.valueOf(scheme.eveningCount)},
        };
        for (String[] session : sessions) {
            int count = Integer.parseInt(session[1]);
            int start = toMinutes(session[0]);
            for (int j = 0; j < count; j++) {
                int end = start + scheme.classDuration;
                result.add(new String[]{formatMinutes(start), formatMinutes(end)});
                start = end + (j % 2 == 0 ? scheme.smallBreak : scheme.bigBreak);
            }
        }
        return result;
    }

    /** "8:00" -> 480；不合法返回 -1 */
    public static int toMinutes(String time) {
        if (time == null) return -1;
        String[] parts = time.trim().replace("：", ":").split(":");
        if (parts.length != 2) return -1;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 480 -> "8:00" */
    public static String formatMinutes(int minutes) {
        return String.format(Locale.CHINA, "%d:%02d", minutes / 60, minutes % 60);
    }

    /** 一个上课时间方案（含作息配置） */
    public static class Scheme {
        public long id;
        public String name = "";
        public long createdAt;
        public int classDuration = 45;
        public int smallBreak = 10;
        public int bigBreak = 20;
        public String morningStart = "8:00";
        public String afternoonStart = "14:00";
        public String eveningStart = "19:00";
        public int morningCount = 4;
        public int afternoonCount = 4;
        public int eveningCount = 2;

        public int totalPeriods() {
            return morningCount + afternoonCount + eveningCount;
        }
    }

    private static final String[] SCHEME_COLUMNS = {
            "_id", "name", "created_at", "class_duration", "small_break", "big_break",
            "morning_start", "afternoon_start", "evening_start",
            "morning_count", "afternoon_count", "evening_count"};

    private static Scheme readScheme(Cursor c) {
        Scheme s = new Scheme();
        s.id = c.getLong(0);
        s.name = c.getString(1);
        s.createdAt = c.getLong(2);
        s.classDuration = c.getInt(3);
        s.smallBreak = c.getInt(4);
        s.bigBreak = c.getInt(5);
        s.morningStart = c.getString(6);
        s.afternoonStart = c.getString(7);
        s.eveningStart = c.getString(8);
        s.morningCount = c.getInt(9);
        s.afternoonCount = c.getInt(10);
        s.eveningCount = c.getInt(11);
        return s;
    }

    public List<Scheme> getSchemes() {
        List<Scheme> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TIME_SCHEMES, SCHEME_COLUMNS,
                null, null, null, null, "_id ASC");
        while (c.moveToNext()) {
            list.add(readScheme(c));
        }
        c.close();
        return list;
    }

    /** 方案不存在返回 null */
    public Scheme getScheme(long id) {
        Cursor c = getReadableDatabase().query(TABLE_TIME_SCHEMES, SCHEME_COLUMNS,
                "_id = ?", new String[]{String.valueOf(id)}, null, null, null);
        Scheme s = c.moveToFirst() ? readScheme(c) : null;
        c.close();
        return s;
    }

    /** 某方案的每小节时间（periodIndex 1~N） */
    public List<ClassTime> getSchemeTimes(long schemeId) {
        List<ClassTime> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_SCHEME_TIMES, null,
                "scheme_id = ?", new String[]{String.valueOf(schemeId)},
                null, null, "period_index ASC");
        while (c.moveToNext()) {
            list.add(new ClassTime(c.getInt(1), c.getString(2), c.getString(3)));
        }
        c.close();
        return list;
    }

    public long getActiveSchemeId() {
        String v = getSetting(KEY_ACTIVE_SCHEME);
        if (v != null) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
            }
        }
        Cursor c = getReadableDatabase().query(TABLE_TIME_SCHEMES, new String[]{"_id"},
                null, null, null, null, "_id ASC LIMIT 1");
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return id;
    }

    public void setActiveSchemeId(long id) {
        putSetting(KEY_ACTIVE_SCHEME, String.valueOf(id));
    }

    /** 当前使用方案的时间（课程表页网格用） */
    public List<ClassTime> getActiveClassTimes() {
        return getSchemeTimes(getActiveSchemeId());
    }

    /** 新建方案（自动设为当前使用，对应「保存并使用」） */
    public long insertScheme(Scheme scheme) {
        long id = insertSchemeInternal(getWritableDatabase(), scheme);
        setActiveSchemeId(id);
        return id;
    }

    /** 编辑方案：改名 + 重写配置和每小节时间 */
    public void updateScheme(Scheme scheme) {
        ContentValues cv = new ContentValues();
        cv.put("name", scheme.name);
        putSchemeConfig(cv, scheme);
        getWritableDatabase().update(TABLE_TIME_SCHEMES, cv,
                "_id = ?", new String[]{String.valueOf(scheme.id)});
        getWritableDatabase().delete(TABLE_SCHEME_TIMES,
                "scheme_id = ?", new String[]{String.valueOf(scheme.id)});
        saveSchemeTimes(getWritableDatabase(), scheme.id, deriveTimes(scheme));
    }

    /** 删除方案；若删除的是当前使用的方案，自动切到剩下的第一个 */
    public void deleteScheme(long id) {
        getWritableDatabase().delete(TABLE_SCHEME_TIMES,
                "scheme_id = ?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete(TABLE_TIME_SCHEMES,
                "_id = ?", new String[]{String.valueOf(id)});
        if (getActiveSchemeId() == id) {
            List<Scheme> rest = getSchemes();
            setActiveSchemeId(rest.isEmpty() ? -1 : rest.get(0).id);
        }
    }

    /* ---------------- courses 课程数据 ---------------- */

    private void createCoursesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_COURSES + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "day INTEGER NOT NULL, "          // 0=周一 ... 6=周日
                + "start_period INTEGER NOT NULL, " // 起始小节（1~N）
                + "end_period INTEGER NOT NULL, "   // 结束小节（连课时 > start_period）
                + "name TEXT NOT NULL, "
                + "room TEXT NOT NULL DEFAULT '', "
                + "color_index INTEGER NOT NULL DEFAULT 0, "
                + "teacher TEXT NOT NULL DEFAULT '', "
                + "week_start INTEGER NOT NULL DEFAULT 1, "
                + "week_end INTEGER NOT NULL DEFAULT 20, "
                + "timetable_id INTEGER NOT NULL DEFAULT 1)");
        // 普通索引（非唯一）：同槽位可存多条——不同课表可同位置；同课表支持单双周
        db.execSQL("CREATE INDEX idx_courses_slot ON " + TABLE_COURSES
                + " (timetable_id, day, start_period)");
    }

    /** 初始课程数据：{星期下标, 大节下标(0=1-2节), 课程名, 教室, 配色下标} */
    private static final Object[][] SEED_COURSES = {
            {0, 0, "高等数学", "A203", 0},
            {0, 1, "大学英语", "A101", 1},
            {1, 0, "线性代数", "B302", 2},
            {1, 2, "Python程序设计", "机房402", 3},
            {2, 1, "数据结构", "A505", 4},
            {2, 2, "大学体育", "操场", 5},
            {3, 0, "大学物理", "D201", 0},
            {3, 3, "思想道德与法治", "B203", 1},
            {4, 1, "物理实验", "实验楼205", 3},
            {4, 3, "主题班会", "A203", 2},
    };

    private void seedCourses(SQLiteDatabase db) {
        for (Object[] s : SEED_COURSES) {
            int big = (Integer) s[1];
            ContentValues cv = new ContentValues();
            cv.put("day", (Integer) s[0]);
            cv.put("start_period", big * 2 + 1);
            cv.put("end_period", big * 2 + 2);
            cv.put("name", (String) s[2]);
            cv.put("room", (String) s[3]);
            cv.put("color_index", (Integer) s[4]);
            db.insert(TABLE_COURSES, null, cv);
        }
    }

    /** 一个课程格：星期 + 起止小节 */
    public static class Course {
        public final long id;
        public final int day;
        public final int startPeriod;
        public final int endPeriod;
        public final String name;
        public final String room;
        public final int colorIndex;
        public final String teacher;
        public final int weekStart;
        public final int weekEnd;
        public final long timetableId;

        public Course(long id, int day, int startPeriod, int endPeriod,
                      String name, String room, int colorIndex,
                      String teacher, int weekStart, int weekEnd, long timetableId) {
            this.id = id;
            this.day = day;
            this.startPeriod = startPeriod;
            this.endPeriod = endPeriod;
            this.name = name;
            this.room = room;
            this.colorIndex = colorIndex;
            this.teacher = teacher;
            this.weekStart = weekStart;
            this.weekEnd = weekEnd;
            this.timetableId = timetableId;
        }
    }

    public List<Course> getAllCourses() {
        List<Course> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_COURSES, null, null, null,
                null, null, "day ASC, start_period ASC");
        while (c.moveToNext()) {
            list.add(readCourse(c));
        }
        c.close();
        return list;
    }

    /** 某张课表的课程（课程表页只显示当前使用的课表） */
    public List<Course> getCourses(long timetableId) {
        List<Course> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_COURSES, null,
                "timetable_id = ?", new String[]{String.valueOf(timetableId)},
                null, null, "day ASC, start_period ASC");
        while (c.moveToNext()) {
            list.add(readCourse(c));
        }
        c.close();
        return list;
    }

    private Course readCourse(Cursor c) {
        return new Course(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3),
                c.getString(4), c.getString(5), c.getInt(6),
                c.getString(7), c.getInt(8), c.getInt(9), c.getLong(10));
    }

    public Course getCourse(long id) {
        Cursor c = getReadableDatabase().query(TABLE_COURSES, null,
                "_id = ?", new String[]{String.valueOf(id)},
                null, null, null);
        Course course = null;
        if (c.moveToFirst()) {
            course = readCourse(c);
        }
        c.close();
        return course;
    }

    /** 同一课表里同名的全部课程（多时段课程编辑：一次带出所有时段） */
    public List<Course> getCoursesByName(long timetableId, String name) {
        List<Course> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_COURSES, null,
                "timetable_id = ? AND name = ?",
                new String[]{String.valueOf(timetableId), name},
                null, null, "day ASC, start_period ASC");
        while (c.moveToNext()) {
            list.add(readCourse(c));
        }
        c.close();
        return list;
    }

    /** 命中某个格子的课程：同一天且 起始节<=节<=结束节（跨节块） */
    public List<Course> getCoursesForCell(long timetableId, int day, int period) {
        List<Course> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_COURSES, null,
                "timetable_id = ? AND day = ? AND start_period <= ? AND end_period >= ?",
                new String[]{String.valueOf(timetableId), String.valueOf(day),
                        String.valueOf(period), String.valueOf(period)},
                null, null, "start_period ASC");
        while (c.moveToNext()) {
            list.add(readCourse(c));
        }
        c.close();
        return list;
    }

    /** 简化版：无老师/周范围时用默认值，写入当前使用的课表（添加课程页用） */
    public long insertCourse(int day, int startPeriod, int endPeriod,
                             String name, String room, int colorIndex) {
        return insertCourse(day, startPeriod, endPeriod, name, room, colorIndex,
                "", 1, 20, getActiveTimetableId());
    }

    /** 完整参数：指定课表（导入新课表时挂到新表） */
    public long insertCourse(int day, int startPeriod, int endPeriod,
                             String name, String room, int colorIndex,
                             String teacher, int weekStart, int weekEnd,
                             long timetableId) {
        ContentValues cv = new ContentValues();
        cv.put("day", day);
        cv.put("start_period", startPeriod);
        cv.put("end_period", endPeriod);
        cv.put("name", name);
        cv.put("room", room);
        cv.put("color_index", colorIndex);
        cv.put("teacher", teacher);
        cv.put("week_start", weekStart);
        cv.put("week_end", weekEnd);
        cv.put("timetable_id", timetableId);
        return getWritableDatabase().insert(TABLE_COURSES, null, cv);
    }

    public void updateCourse(long id, String name, String room) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("room", room);
        getWritableDatabase().update(TABLE_COURSES, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteCourse(long id) {
        getWritableDatabase().delete(TABLE_COURSES,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /* ---------------- settings 键值对 ---------------- */

    public void putSetting(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        getWritableDatabase().insertWithOnConflict(TABLE_SETTINGS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 返回 null 表示该键不存在 */
    public String getSetting(String key) {
        Cursor c = getReadableDatabase().query(TABLE_SETTINGS, new String[]{"value"},
                "key = ?", new String[]{key}, null, null, null);
        String value = null;
        if (c.moveToFirst()) {
            value = c.getString(0);
        }
        c.close();
        return value;
    }

    public boolean hasSemesterStart() {
        return getSetting(KEY_SEMESTER_START) != null;
    }

    public long getSemesterStartMillis() {
        String v = getSetting(KEY_SEMESTER_START);
        return v == null ? 0 : Long.parseLong(v);
    }

    public void setSemesterStartMillis(long millis) {
        putSetting(KEY_SEMESTER_START, String.valueOf(millis));
    }

    public int getSemesterWeeks() {
        String v = getSetting(KEY_SEMESTER_WEEKS);
        return v == null ? 20 : Integer.parseInt(v);
    }

    public void setSemesterWeeks(int weeks) {
        putSetting(KEY_SEMESTER_WEEKS, String.valueOf(weeks));
    }

    /* ---------------- habits 习惯打卡（DB v11） ---------------- */

    public static final String TABLE_HABITS = "habits";
    public static final String TABLE_HABIT_RECORDS = "habit_records";

    /** 分类（habit_categories 表 id）：1=学习 2=健康 3=生活（种子），可自定义新增；0=无分类 */
    public static final int HABIT_CAT_NONE = 0;
    public static final int HABIT_CAT_STUDY = 1;
    public static final int HABIT_CAT_HEALTH = 2;
    public static final int HABIT_CAT_LIFE = 3;

    /** 频率类型：1=每天 2=指定周几 3=每周 N 次（均只是目标，不强制） */
    public static final int HABIT_FREQ_DAILY = 1;
    public static final int HABIT_FREQ_WEEKDAYS = 2;
    public static final int HABIT_FREQ_TIMES = 3;

    private void createHabitsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_HABITS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "category INTEGER NOT NULL DEFAULT 0, "      // 0学习 1健康 2生活
                + "icon INTEGER NOT NULL DEFAULT 0, "          // 图标索引（页内数组）
                + "color_index INTEGER NOT NULL DEFAULT 0, "   // 习惯配色索引（页内数组）
                + "freq_type INTEGER NOT NULL DEFAULT 1, "     // 1每天 2指定周几 3每周N次
                + "freq_days INTEGER NOT NULL DEFAULT 127, "   // type2：bit0=周一 … bit6=周日
                + "freq_times INTEGER NOT NULL DEFAULT 3, "    // type3：每周目标次数
                + "created_at INTEGER NOT NULL DEFAULT 0)");
    }

    private void createHabitRecordsTable(SQLiteDatabase db) {
        // 每个习惯每天最多一条打卡记录
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_HABIT_RECORDS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "habit_id INTEGER NOT NULL, "
                + "date_millis INTEGER NOT NULL, "
                + "UNIQUE(habit_id, date_millis))");
    }

    public static final String TABLE_HABIT_CATEGORIES = "habit_categories";

    private void createHabitCategoriesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_HABIT_CATEGORIES + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL)");
    }

    /** 种子分类：学习(1)/健康(2)/生活(3) */
    private void seedHabitCategories(SQLiteDatabase db) {
        String[] names = {"学习", "健康", "生活"};
        for (String name : names) {
            ContentValues cv = new ContentValues();
            cv.put("name", name);
            db.insert(TABLE_HABIT_CATEGORIES, null, cv);
        }
    }

    /** 一个分类 */
    public static class HabitCategory {
        public final long id;
        public final String name;

        public HabitCategory(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 全部分类（按 id 升序，种子在前） */
    public List<HabitCategory> getHabitCategories() {
        List<HabitCategory> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_HABIT_CATEGORIES, null,
                null, null, null, null, "_id ASC");
        while (c.moveToNext()) {
            list.add(new HabitCategory(c.getLong(0), c.getString(1)));
        }
        c.close();
        return list;
    }

    public long insertHabitCategory(String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        return getWritableDatabase().insert(TABLE_HABIT_CATEGORIES, null, cv);
    }

    /** 重命名分类 */
    public void renameHabitCategory(long id, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        getWritableDatabase().update(TABLE_HABIT_CATEGORIES, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 某分类下的习惯数 */
    public int getHabitCountOfCategory(long categoryId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_HABITS + " WHERE category = ?",
                new String[]{String.valueOf(categoryId)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }

    /**
     * 删除分类：该分类下的习惯连同打卡记录一并删除。
     */
    public void deleteHabitCategory(long id) {
        SQLiteDatabase db = getWritableDatabase();
        // 找到该分类下所有习惯 id，连打卡记录一起删
        Cursor c = db.query(TABLE_HABITS, new String[]{"_id"},
                "category = ?", new String[]{String.valueOf(id)}, null, null, null);
        java.util.List<Long> habitIds = new ArrayList<>();
        while (c.moveToNext()) {
            habitIds.add(c.getLong(0));
        }
        c.close();
        for (long habitId : habitIds) {
            db.delete(TABLE_HABIT_RECORDS, "habit_id = ?",
                    new String[]{String.valueOf(habitId)});
            db.delete(TABLE_HABITS, "_id = ?", new String[]{String.valueOf(habitId)});
        }
        db.delete(TABLE_HABIT_CATEGORIES, "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 首装/升级补种子：原假数据的 4 条习惯 */
    private void seedHabits(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        Object[][] seeds = {
                // name, category, icon, colorIndex, freqType, freqDays, freqTimes
                // （colorIndex 对应页内习惯配色数组：0紫 1橙 2青 3靛 4粉 5蓝）
                {"背 30 个英语单词", HABIT_CAT_STUDY, 0, 0, HABIT_FREQ_DAILY, 127, 7},
                {"阅读 20 分钟", HABIT_CAT_STUDY, 1, 1, HABIT_FREQ_DAILY, 127, 7},
                {"慢跑 2 公里", HABIT_CAT_HEALTH, 2, 2, HABIT_FREQ_WEEKDAYS,
                        (1 << 0) | (1 << 2) | (1 << 4), 3},   // 周一/三/五
                {"23:00 前睡觉", HABIT_CAT_LIFE, 3, 3, HABIT_FREQ_DAILY, 127, 7},
        };
        for (Object[] s : seeds) {
            ContentValues cv = new ContentValues();
            cv.put("name", (String) s[0]);
            cv.put("category", (Integer) s[1]);
            cv.put("icon", (Integer) s[2]);
            cv.put("color_index", (Integer) s[3]);
            cv.put("freq_type", (Integer) s[4]);
            cv.put("freq_days", (Integer) s[5]);
            cv.put("freq_times", (Integer) s[6]);
            cv.put("created_at", now);
            db.insert(TABLE_HABITS, null, cv);
        }
    }

    /** 一条习惯 */
    public static class Habit {
        public final long id;
        public final String name;
        public final int category;
        public final int icon;
        public final int colorIndex;
        public final int freqType;
        public final int freqDays;   // type2：bit0=周一 … bit6=周日
        public final int freqTimes;  // type3：每周目标次数
        public final long createdAt;

        public Habit(long id, String name, int category, int icon, int colorIndex,
                     int freqType, int freqDays, int freqTimes, long createdAt) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.icon = icon;
            this.colorIndex = colorIndex;
            this.freqType = freqType;
            this.freqDays = freqDays;
            this.freqTimes = freqTimes;
            this.createdAt = createdAt;
        }

        /** 该习惯在指定星期（Calendar.DAY_OF_WEEK）是否为计划打卡日 */
        public boolean isPlannedOn(int calendarDow) {
            if (freqType == HABIT_FREQ_DAILY) {
                return true;
            }
            if (freqType == HABIT_FREQ_WEEKDAYS) {
                int bit = (calendarDow + 5) % 7;   // 周一=bit0 … 周日=bit6
                return (freqDays & (1 << bit)) != 0;
            }
            return false;   // type3 无固定计划日
        }
    }

    private Habit readHabit(Cursor c) {
        return new Habit(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getLong(8));
    }

    /** 全部习惯（按创建顺序） */
    public List<Habit> getHabits() {
        List<Habit> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_HABITS, null,
                null, null, null, null, "_id ASC");
        while (c.moveToNext()) {
            list.add(readHabit(c));
        }
        c.close();
        return list;
    }

    public Habit getHabit(long id) {
        Cursor c = getReadableDatabase().query(TABLE_HABITS, null,
                "_id = ?", new String[]{String.valueOf(id)}, null, null, null);
        Habit habit = null;
        if (c.moveToFirst()) {
            habit = readHabit(c);
        }
        c.close();
        return habit;
    }

    public long insertHabit(String name, int category, int icon, int colorIndex,
                            int freqType, int freqDays, int freqTimes) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("category", category);
        cv.put("icon", icon);
        cv.put("color_index", colorIndex);
        cv.put("freq_type", freqType);
        cv.put("freq_days", freqDays);
        cv.put("freq_times", freqTimes);
        cv.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_HABITS, null, cv);
    }

    public void updateHabit(long id, String name, int category, int icon, int colorIndex,
                            int freqType, int freqDays, int freqTimes) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("category", category);
        cv.put("icon", icon);
        cv.put("color_index", colorIndex);
        cv.put("freq_type", freqType);
        cv.put("freq_days", freqDays);
        cv.put("freq_times", freqTimes);
        getWritableDatabase().update(TABLE_HABITS, cv,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 删除习惯（连同打卡记录） */
    public void deleteHabit(long id) {
        getWritableDatabase().delete(TABLE_HABIT_RECORDS,
                "habit_id = ?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete(TABLE_HABITS,
                "_id = ?", new String[]{String.valueOf(id)});
    }

    /** 某习惯某天（零点毫秒）是否已打卡 */
    public boolean isHabitChecked(long habitId, long dateMillis) {
        Cursor c = getReadableDatabase().query(TABLE_HABIT_RECORDS, new String[]{"_id"},
                "habit_id = ? AND date_millis = ?",
                new String[]{String.valueOf(habitId), String.valueOf(dateMillis)},
                null, null, null);
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    /** 打卡（幂等） */
    public void insertHabitRecord(long habitId, long dateMillis) {
        ContentValues cv = new ContentValues();
        cv.put("habit_id", habitId);
        cv.put("date_millis", dateMillis);
        getWritableDatabase().insertWithOnConflict(TABLE_HABIT_RECORDS, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 取消打卡 */
    public void deleteHabitRecord(long habitId, long dateMillis) {
        getWritableDatabase().delete(TABLE_HABIT_RECORDS,
                "habit_id = ? AND date_millis = ?",
                new String[]{String.valueOf(habitId), String.valueOf(dateMillis)});
    }

    /** 某习惯某天区间内（含端点）的打卡日期集合（零点毫秒） */
    public java.util.Set<Long> getHabitRecordDays(long habitId, long fromMillis, long toMillis) {
        java.util.Set<Long> days = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().query(TABLE_HABIT_RECORDS, new String[]{"date_millis"},
                "habit_id = ? AND date_millis >= ? AND date_millis <= ?",
                new String[]{String.valueOf(habitId),
                        String.valueOf(fromMillis), String.valueOf(toMillis)},
                null, null, null);
        while (c.moveToNext()) {
            days.add(c.getLong(0));
        }
        c.close();
        return days;
    }

    /** 某天全部打卡记录：habitId → 已打卡（一次查询供列表用） */
    public java.util.Set<Long> getCheckedHabitIdsOfDay(long dateMillis) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().query(TABLE_HABIT_RECORDS, new String[]{"habit_id"},
                "date_millis = ?", new String[]{String.valueOf(dateMillis)},
                null, null, null);
        while (c.moveToNext()) {
            ids.add(c.getLong(0));
        }
        c.close();
        return ids;
    }

    /* ---------------- class_time 单节时间 ---------------- */

    /** 一节课的时间定义（periodIndex 从 1 开始） */
    public static class ClassTime {
        public final int periodIndex;
        public final String start;
        public final String end;

        public ClassTime(int periodIndex, String start, String end) {
            this.periodIndex = periodIndex;
            this.start = start;
            this.end = end;
        }
    }
}
