package com.example.classclip.common;
import com.example.classclip.R;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * 横向滑动拦截布局（待办页根布局用）：
 * - 从按下点累计位移：横向超过 60px 且明显大于纵向（2 倍以上）才判定为横向滑动，
 *   锁定后整段消费、并阻止父级 ViewPager 翻页（周待办表切周 / 月待办表切月）
 * - 回调返回 true 表示本页处理了这次滑动；返回 false 照常放行
 * - 纵向滚动（列表/月历上下滑）不受影响
 * - 可设置「热区」：只在热区 View 内按下才触发横向滑动，避免占用子控件（如待办条目）的
 *   左滑删除/重置手势
 */
public class HorizontalSwipeLayout extends LinearLayout {

    /** 横向滑动回调：toLeft=true 表示向左滑（看更晚的周/月）；返回是否已处理 */
    public interface OnHorizontalSwipe {
        boolean onSwipe(boolean toLeft);
    }

    /** 累计横向位移达到该值才判定为横向滑动（px） */
    private static final float LOCK_DISTANCE_PX = 60f;

    private OnHorizontalSwipe listener;
    private View hotspot;          // 仅在该区域内按下才触发横向滑动；null = 整页
    private final Rect hotspotRect = new Rect();
    private boolean locked;
    private boolean decided;      // 本次手势是否已做出「横向/纵向」判定
    private boolean inHotspot;    // 本次手势按下点是否落在热区内
    private float downX, downY;

    public HorizontalSwipeLayout(Context context) {
        this(context, null);
    }

    public HorizontalSwipeLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOnHorizontalSwipe(OnHorizontalSwipe listener) {
        this.listener = listener;
    }

    /** 设置横向滑动的热区（只在热区内按下才触发）；传 null 恢复整页 */
    public void setSwipeHotspot(View hotspot) {
        this.hotspot = hotspot;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                locked = false;
                decided = false;
                inHotspot = isInHotspot(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (inHotspot && !decided && !locked) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    // 横向明显大于纵向才判定，避免竖向滑动列表时误触发
                    if (Math.abs(dx) > LOCK_DISTANCE_PX && Math.abs(dx) > Math.abs(dy) * 2) {
                        decided = true;
                        if (listener != null && listener.onSwipe(dx < 0)) {
                            locked = true; // 本页处理了：消费剩余手势，拦住 ViewPager
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            return true;
                        }
                        // 本页不处理：放行给子控件/ViewPager
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (locked && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                locked = false;
                decided = false;
                inHotspot = false;
                break;
        }
        if (locked) {
            return true; // 横向手势已消费
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 判断按下点是否落在热区内（无热区视为整页命中） */
    private boolean isInHotspot(MotionEvent ev) {
        if (hotspot == null) {
            return true;
        }
        hotspot.getGlobalVisibleRect(hotspotRect);
        int rawX = (int) ev.getRawX();
        int rawY = (int) ev.getRawY();
        return hotspotRect.contains(rawX, rawY);
    }
}

