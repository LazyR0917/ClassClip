package com.example.classclip.todo;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 待办列表适配器（真实数据，复用 todos 表）。
 * 列表结构：[待完成分组头, 若干待完成, 已完成分组头, (若干已完成)]，
 * 两个分组头均可点击展开/收起。左滑条目露出删除按钮。
 */
public class TodoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 条目点击回调（进编辑） */
    public interface OnTodoClick {
        void onClick(DBHelper.Todo todo);
    }

    /** 删除确认回调 */
    public interface OnTodoDelete {
        void onDelete(DBHelper.Todo todo, int position);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_FOOTER = 2;

    /** 可变待办（勾选时翻转 done） */
    static class MutableTodo {
        final long id;
        final String title;
        final String note;
        final long dateMillis;
        final String time;
        final int priority;
        final int quadrant;
        boolean done;

        MutableTodo(DBHelper.Todo t) {
            this.id = t.id;
            this.title = t.title;
            this.note = t.note;
            this.dateMillis = t.dateMillis;
            this.time = t.time;
            this.priority = t.priority;
            this.quadrant = t.quadrant;
            this.done = t.done;
        }
    }

    private static class Entry {
        final int type;
        final MutableTodo item;
        final boolean isDoneHeader;

        Entry(MutableTodo item) {
            this.type = TYPE_ITEM;
            this.item = item;
            this.isDoneHeader = false;
        }

        Entry(boolean isDoneHeader) {
            this.type = TYPE_HEADER;
            this.item = null;
            this.isDoneHeader = isDoneHeader;
        }

        Entry() {
            this.type = TYPE_FOOTER;
            this.item = null;
            this.isDoneHeader = false;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<MutableTodo> pendingItems = new ArrayList<>();
    private final List<MutableTodo> doneItems = new ArrayList<>();
    private boolean pendingCollapsed = false;
    private boolean doneCollapsed = false;

    private final OnTodoClick clickListener;
    private final OnTodoDelete deleteListener;
    private TodoSwipeDelete.OnReset resetListener;

    /** 左滑露出的条目位置 */
    private int openSwipePosition = -1;
    private boolean closingSwipe = false;

    public TodoAdapter(OnTodoClick clickListener, OnTodoDelete deleteListener) {
        this.clickListener = clickListener;
        this.deleteListener = deleteListener;
    }

    /** 左滑「重置」按钮回调（由 TodoSwipeDelete.attach 注入） */
    public void setResetListener(TodoSwipeDelete.OnReset resetListener) {
        this.resetListener = resetListener;
    }

    /** 重新加载数据（未完成排前，已完成排后） */
    public void reload(List<DBHelper.Todo> todos) {
        pendingItems.clear();
        doneItems.clear();
        for (DBHelper.Todo t : todos) {
            MutableTodo m = new MutableTodo(t);
            if (m.done) doneItems.add(m);
            else pendingItems.add(m);
        }
        openSwipePosition = -1;
        rebuildEntries();
        notifyDataSetChanged();
    }

    public void setMode(int mode) {
        // 模式切换时收起状态保留，仅刷新
        rebuildEntries();
        notifyDataSetChanged();
    }

    public int getEntriesSize() {
        return entries.size();
    }

    /** 该位置是否是真实待办条目（而非分组头/底部提示） */
    public boolean isItemPosition(int position) {
        return position >= 0 && position < entries.size()
                && entries.get(position).type == TYPE_ITEM;
    }

    /** 删除一条 */
    public void removeItem(DBHelper.Todo todo) {
        MutableTodo target = null;
        for (MutableTodo m : pendingItems) if (m.id == todo.id) target = m;
        for (MutableTodo m : doneItems) if (m.id == todo.id) target = m;
        if (target != null) {
            pendingItems.remove(target);
            doneItems.remove(target);
        }
        openSwipePosition = -1;
        rebuildEntries();
        notifyDataSetChanged();
    }

    /** 左滑露出删除 */
    public void setOpenSwipe(int position) {
        if (openSwipePosition >= 0 && openSwipePosition != position) {
            closeSwipe();
        }
        openSwipePosition = position;
        notifyItemChanged(position);
    }

    /** 点其他位置时收回 */
    public void maybeCloseSwipe(int touchedPosition) {
        if (openSwipePosition >= 0 && touchedPosition != openSwipePosition) {
            closeSwipe();
        }
    }

    /** 右滑：收回指定位置滑开的卡片（与第 1 页明细页一致） */
    public void resetSwipe(int position) {
        if (position == openSwipePosition) {
            closeSwipe();
        } else {
            notifyItemChanged(position);
        }
    }

    private void closeSwipe() {
        if (openSwipePosition < 0 || openSwipePosition >= entries.size()) {
            openSwipePosition = -1;
            return;
        }
        int old = openSwipePosition;
        openSwipePosition = -1;
        notifyItemChanged(old);
    }

    private void rebuildEntries() {
        entries.clear();
        entries.add(new Entry(false)); // 未完成头
        if (!pendingCollapsed) {
            for (MutableTodo m : pendingItems) entries.add(new Entry(m));
        }
        entries.add(new Entry(true)); // 已完成头
        if (!doneCollapsed) {
            for (MutableTodo m : doneItems) entries.add(new Entry(m));
        }
        entries.add(new Entry()); // 底部提示
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new SectionViewHolder(inflater.inflate(R.layout.item_todo_section, parent, false));
        }
        if (viewType == TYPE_FOOTER) {
            TextView footer = new TextView(parent.getContext());
            footer.setGravity(Gravity.CENTER);
            footer.setTextSize(11);
            footer.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.brand_text_gray));
            footer.setText(parent.getContext().getString(R.string.todo_hint));
            float density = parent.getContext().getResources().getDisplayMetrics().density;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, (int) (6 * density), 0, (int) (4 * density));
            footer.setLayoutParams(lp);
            return new FooterHolder(footer);
        }
        return new ItemViewHolder(inflater.inflate(R.layout.item_todo, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (entry.type == TYPE_HEADER) {
            ((SectionViewHolder) holder).bind(entry);
        } else if (entry.type == TYPE_ITEM) {
            ((ItemViewHolder) holder).bind(entry.item, position);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /* ---------------- 分组头 ---------------- */

    private class SectionViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvTitle;
        private final TextView tvAction;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_section_title);
            tvAction = itemView.findViewById(R.id.tv_section_action);
        }

        void bind(Entry entry) {
            if (entry.isDoneHeader) {
                tvTitle.setText(itemView.getContext()
                        .getString(R.string.todo_section_done, doneItems.size()));
                tvAction.setText(itemView.getContext().getString(doneCollapsed
                        ? R.string.todo_expand : R.string.todo_collapse));
                itemView.setOnClickListener(v -> {
                    doneCollapsed = !doneCollapsed;
                    rebuildEntries();
                    notifyDataSetChanged();
                });
            } else {
                tvTitle.setText(itemView.getContext()
                        .getString(R.string.todo_section_pending, pendingItems.size()));
                tvAction.setText(itemView.getContext().getString(pendingCollapsed
                        ? R.string.todo_expand : R.string.todo_collapse));
                itemView.setOnClickListener(v -> {
                    pendingCollapsed = !pendingCollapsed;
                    rebuildEntries();
                    notifyDataSetChanged();
                });
            }
        }
    }

    private static class FooterHolder extends RecyclerView.ViewHolder {
        FooterHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    /* ---------------- 待办条目 ---------------- */

    private class ItemViewHolder extends RecyclerView.ViewHolder {
        private final View root;
        private final View foreground;
        private final View deleteBtn;
        private final View resetBtn;
        private final View checkboxPending;
        private final View checkboxDone;
        private final TextView tvTitle;
        private final TextView tvSubtitle;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView;
            foreground = itemView.findViewById(R.id.item_todo_foreground);
            deleteBtn = itemView.findViewById(R.id.btn_todo_delete);
            resetBtn = itemView.findViewById(R.id.btn_todo_reset);
            checkboxPending = itemView.findViewById(R.id.checkbox_pending);
            checkboxDone = itemView.findViewById(R.id.checkbox_done);
            tvTitle = itemView.findViewById(R.id.tv_todo_item_title);
            tvSubtitle = itemView.findViewById(R.id.tv_todo_item_subtitle);
        }

        void bind(MutableTodo item, int position) {
            // 完成态：灰底 + 标题灰 + 实心勾
            foreground.setBackgroundResource(item.done
                    ? R.drawable.bg_card_gray : R.drawable.bg_card_white);
            checkboxPending.setVisibility(item.done ? View.GONE : View.VISIBLE);
            checkboxDone.setVisibility(item.done ? View.VISIBLE : View.GONE);
            tvTitle.setText(item.title);
            tvTitle.setTextColor(ContextCompat.getColor(root.getContext(),
                    item.done ? R.color.brand_text_gray : R.color.brand_text_dark));
            tvSubtitle.setText(buildSubtitle(item));

            // 勾选切换完成
            View check = item.done ? checkboxDone : checkboxPending;
            check.setOnClickListener(v -> toggleDone(item));

            // 点击进编辑
            foreground.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onClick(toDbTodo(item));
                }
            });

            // 删除按钮
            deleteBtn.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(toDbTodo(item), position);
                }
            });

            // 重置按钮：变回未完成（由页面落库并刷新）
            resetBtn.setOnClickListener(v -> {
                if (resetListener != null) {
                    resetListener.onReset(toDbTodo(item));
                }
            });

            // 恢复滑开状态（露出 重置+删除 两个按钮）
            if (position == openSwipePosition) {
                float density = root.getContext().getResources().getDisplayMetrics().density;
                foreground.setTranslationX(-112 * density);
                deleteBtn.setVisibility(View.VISIBLE);
                resetBtn.setVisibility(View.VISIBLE);
            } else {
                foreground.setTranslationX(0);
                deleteBtn.setVisibility(View.INVISIBLE);
                resetBtn.setVisibility(View.INVISIBLE);
            }
        }

        private void toggleDone(MutableTodo item) {
            item.done = !item.done;
            DBHelper.getInstance(root.getContext()).setTodoDone(item.id, item.done);
            // 移动到对应分组
            pendingItems.remove(item);
            doneItems.remove(item);
            if (item.done) doneItems.add(item);
            else pendingItems.add(item);
            openSwipePosition = -1;
            rebuildEntries();
            notifyDataSetChanged();
        }

        private String buildSubtitle(MutableTodo item) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(item.dateMillis);
            StringBuilder sb = new StringBuilder(String.format(Locale.CHINA, "%d月%d日",
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
            if (item.time != null && !item.time.isEmpty()) {
                sb.append(" ").append(item.time);
            }
            if (item.note != null && !item.note.isEmpty()) {
                sb.append(" · ").append(item.note);
            }
            return sb.toString();
        }
    }

    private static DBHelper.Todo toDbTodo(MutableTodo m) {
        return new DBHelper.Todo(m.id, m.title, m.note, m.dateMillis, m.time, m.done,
                m.priority, m.quadrant);
    }
}
