package com.example.classclip.todo;
import com.example.classclip.R;

import com.example.classclip.common.DBHelper;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 待办列表滑动交互（待办页 / 某天待办列表页共用，与第 1 页格子明细页一致）：
 * - 左滑卡片露出「重置 + 删除」两个按钮（不真正操作，点按钮由页面回调）
 * - 右滑把已滑开的卡片推回盖住；点其他卡片 / 列表空白处也收回
 * - 分组头 / 底部提示条目不允许滑动
 */
public final class TodoSwipeDelete {

    /** 单个按钮宽度（重置 + 删除 各 56dp，总露出 112dp） */
    private static final int BTN_DP = 56;

    /** 重置回调：把已完成待办变回未完成 */
    public interface OnReset {
        void onReset(DBHelper.Todo todo);
    }

    private TodoSwipeDelete() {
    }

    public static void attach(RecyclerView rv, final TodoAdapter adapter, final OnReset resetListener) {
        final float density = rv.getContext().getResources().getDisplayMetrics().density;
        final float maxW = BTN_DP * 2 * density;

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                // 只对真实待办条目滑动，分组头/底部提示直接弹回
                if (!adapter.isItemPosition(position)) {
                    adapter.notifyItemChanged(position);
                    return;
                }
                if (direction == ItemTouchHelper.RIGHT) {
                    // 右滑：收回滑开的卡片
                    adapter.resetSwipe(position);
                    return;
                }
                // 左滑露出「重置 + 删除」，不真正操作
                adapter.setOpenSwipe(position);
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                        @NonNull RecyclerView.ViewHolder viewHolder) {
                // 分组头/底部提示不允许滑动
                if (!adapter.isItemPosition(viewHolder.getBindingAdapterPosition())) {
                    return makeMovementFlags(0, 0);
                }
                return makeMovementFlags(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                return 0.5f;
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas,
                                    @NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState,
                                    boolean isCurrentlyActive) {
                float clamped;
                if (dX < 0) {
                    // 左滑：最多露出两个按钮宽
                    clamped = Math.max(dX, -maxW);
                } else {
                    // 右滑：从滑开位置推回 0，不允许越过右边距
                    clamped = Math.min(dX, 0);
                }
                View fg = viewHolder.itemView.findViewById(R.id.item_todo_foreground);
                View del = viewHolder.itemView.findViewById(R.id.btn_todo_delete);
                View reset = viewHolder.itemView.findViewById(R.id.btn_todo_reset);
                if (fg != null) {
                    fg.setTranslationX(clamped);
                    del.setVisibility(clamped < 0 ? View.VISIBLE : View.INVISIBLE);
                    reset.setVisibility(clamped < 0 ? View.VISIBLE : View.INVISIBLE);
                }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(rv);

        // 点其他卡片/空白收回
        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    View child = rv.findChildViewUnder(e.getX(), e.getY());
                    int touched = (child == null) ? -1 : rv.getChildAdapterPosition(child);
                    adapter.maybeCloseSwipe(touched);
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) { }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
        });

        // 重置按钮（左滑露出后点击）：由页面把待办变回未完成
        adapter.setResetListener(resetListener);
    }
}
