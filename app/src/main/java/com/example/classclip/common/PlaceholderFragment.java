package com.example.classclip.common;
import com.example.classclip.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 占位 Fragment：骨架阶段先用一个居中文字占位，
 * 后续每个页面会替换成各自的独立 Fragment 和真实布局。
 */
public class PlaceholderFragment extends Fragment {

    /** 页面序号，对应 ViewPager2 的 position（0~3） */
    private static final String ARG_POSITION = "position";

    public static PlaceholderFragment newInstance(int position) {
        PlaceholderFragment fragment = new PlaceholderFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POSITION, position);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_placeholder, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView tvPlaceholder = view.findViewById(R.id.tv_placeholder);
        int position = 0;
        if (getArguments() != null) {
            position = getArguments().getInt(ARG_POSITION, 0);
        }
        int textRes;
        switch (position) {
            case 1:  textRes = R.string.placeholder_todo;    break;
            case 2:  textRes = R.string.placeholder_habit;   break;
            case 3:  textRes = R.string.placeholder_plan;    break;
            default: textRes = R.string.placeholder_schedule; break;
        }
        tvPlaceholder.setText(textRes);
    }
}
