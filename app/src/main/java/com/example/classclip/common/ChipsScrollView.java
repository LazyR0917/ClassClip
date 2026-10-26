package com.example.classclip.common;
import com.example.classclip.R;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

import androidx.annotation.Nullable;

/**
 * 分类横向滚动条（习惯页 chips 用）：
 * - 纯滚动浏览分类，松手后不做任何自动选中（选中由用户点 chip 完成）
 * - 与列表区域的手势互不干扰：chips 上的横向拖动只滚动 chips 本身
 */
public class ChipsScrollView extends HorizontalScrollView {

    public ChipsScrollView(Context context) {
        super(context);
        init();
    }

    public ChipsScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
    }
}
