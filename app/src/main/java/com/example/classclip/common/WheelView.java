package com.example.classclip.common;
import com.example.classclip.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.core.content.ContextCompat;

/**
 * 轻量滚轮（仿 iOS 样式，纯自绘 View，无 RecyclerView 回收/重绑问题）：
 * - 固定行高 44dp，高度恒为 5 行（上下各露出 2 档）；中间项深色加粗，越靠边越浅
 * - 手势：拖动跟随手指；快滑带惯性（OverScroller），松手吸附到最近一项
 * - setSelection(index) 程序定位（结束滚轮随开始自动补）；OnCenterChanged 监听落位项
 */
public class WheelView extends View {

    public interface OnCenterChanged {
        void onCenter(int index);
    }

    private static final int ITEM_HEIGHT_DP = 44;
    private static final long SNAP_DURATION = 180;

    private final String[] values;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int itemH;
    /**
     * 偏移量范围：视图高 5 行、中心在第 2.5 行处，
     * 第 i 项居中需要 offsetY = (2 - i) * itemH，
     * 故范围 [-(count-3)*itemH, +2*itemH]（两端留出空白，和 iOS 滚轮一致）。
     */
    private final int minOffset;
    private final int maxOffset;
    private final int minFlingVelocity;

    private int selectedIndex;
    private float offsetY;

    private OnCenterChanged listener;

    private final OverScroller scroller = new OverScroller(getContext());
    private boolean flinging;
    private VelocityTracker velocityTracker;
    private float lastTouchY;
    private ValueAnimator snapAnimator;

    public WheelView(Context context, String[] values, int initialIndex) {
        super(context);
        this.values = values;
        this.selectedIndex = clamp(initialIndex);
        this.itemH = dp(ITEM_HEIGHT_DP);
        this.maxOffset = 2 * itemH;
        this.minOffset = -(values.length - 3) * itemH;
        this.offsetY = (2 - selectedIndex) * itemH;
        this.minFlingVelocity =
                ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        textPaint.setTextSize(sp(16));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnCenterChangedListener(OnCenterChanged listener) {
        this.listener = listener;
    }

    /** 当前停在中间的项的下标 */
    public int getSelection() {
        return selectedIndex;
    }

    /** 程序定位到某项（吸附动画） */
    public void setSelection(int index) {
        int target = clamp(index);
        if (target == selectedIndex && snapAnimator == null && !flinging) {
            return;
        }
        animateOffsetTo((2 - target) * itemH, () -> updateIndex(target));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(dp(60), widthMeasureSpec);
        setMeasuredDimension(width, 5 * itemH);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                stopAnimations();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastTouchY;
                lastTouchY = event.getY();
                // 向下滑动 = 数值减小（内容跟随手指，与惯性滚动方向保持一致）
                offsetY = clampOffset(offsetY + dy);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                velocityTracker.computeCurrentVelocity(1000);
                float vy = velocityTracker.getYVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.abs(vy) > minFlingVelocity) {
                    flinging = true;
                    scroller.fling(0, Math.round(offsetY), 0, Math.round(vy),
                            0, 0, minOffset, maxOffset);
                    postInvalidateOnAnimation();
                } else {
                    settleToNearest();
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void stopAnimations() {
        scroller.abortAnimation();
        flinging = false;
        // 先清引用再 cancel：被取消动画的 onAnimationEnd 里 snapAnimator != anim，不会执行旧回调
        ValueAnimator running = snapAnimator;
        snapAnimator = null;
        if (running != null && running.isRunning()) {
            running.cancel();
        }
    }

    private void settleToNearest() {
        int index = clamp(Math.round(2 - offsetY / (float) itemH));
        animateOffsetTo((2 - index) * itemH, () -> updateIndex(index));
    }

    private void animateOffsetTo(float target, Runnable onEnd) {
        stopAnimations();
        final ValueAnimator anim = ValueAnimator.ofFloat(offsetY, target);
        snapAnimator = anim;
        anim.setDuration(SNAP_DURATION);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            offsetY = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 只响应「当前」动画的结束；被新动画/手势顶掉的旧动画不执行回调
                if (snapAnimator == anim) {
                    snapAnimator = null;
                    onEnd.run();
                }
            }
        });
        anim.start();
    }

    private void updateIndex(int index) {
        offsetY = (2 - index) * itemH;
        if (index != selectedIndex) {
            selectedIndex = index;
            if (listener != null) {
                listener.onCenter(index);
            }
        }
    }

    @Override
    public void computeScroll() {
        if (!flinging) {
            return;
        }
        if (scroller.computeScrollOffset()) {
            offsetY = clampOffset(scroller.getCurrY());
            invalidate();
            postInvalidateOnAnimation();
        } else {
            flinging = false;
            settleToNearest();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float center = getHeight() / 2f;
        int first = Math.max(0, (int) Math.floor(-offsetY / itemH) - 1);
        int last = Math.min(values.length - 1,
                (int) Math.ceil((getHeight() - offsetY) / itemH));

        int dark = ContextCompat.getColor(getContext(), R.color.brand_text_dark);
        int gray = ContextCompat.getColor(getContext(), R.color.brand_text_gray);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baselineShift = -(fm.ascent + fm.descent) / 2;

        for (int i = first; i <= last; i++) {
            float itemCenter = offsetY + i * itemH + itemH / 2f;
            float dist = Math.abs(itemCenter - center);
            boolean isCenter = dist < itemH / 2f;

            textPaint.setTypeface(isCenter ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            textPaint.setColor(isCenter ? dark : gray);
            float fade = Math.min(1f, dist / (2.5f * itemH));
            textPaint.setAlpha(isCenter ? 255 : Math.round(255 * (1f - 0.65f * fade)));
            canvas.drawText(values[i], getWidth() / 2f,
                    itemCenter + baselineShift, textPaint);
        }
    }

    private float clampOffset(float value) {
        if (value > maxOffset) return maxOffset;
        if (value < minOffset) return minOffset;
        return value;
    }

    private int clamp(int index) {
        if (index < 0) return 0;
        return Math.min(index, values.length - 1);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
