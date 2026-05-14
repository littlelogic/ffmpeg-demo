package com.donkingliang.imageselector2;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.donkingliang.imageselector.R;
import com.donkingliang.imageselector2.adapter.ImageAdapter;
import com.donkingliang.imageselector2.entry.Media;

import java.util.ArrayList;

public class MediaPagerShowFragment extends Fragment {


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.adapter_pager_item, container, false);
    }

    private boolean isSingle;
    private boolean canPreview = true;
    private int mMaxCount;
    private ImageSelectorActivity mImageSelectorActivity;

    public void setData(ImageSelectorActivity mImageSelectorActivity_,
                        boolean isSingle_,boolean canPreview_,int mMaxCount_){
        mImageSelectorActivity = mImageSelectorActivity_;
        isSingle = isSingle_;
        canPreview = canPreview_;
        mMaxCount = mMaxCount_;
    }

    private RecyclerView rvImage;
    private ImageAdapter mAdapter;
    private GridLayoutManager mLayoutManager;


    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rvImage = view.findViewById(R.id.rv_image);

        // 判断屏幕方向
        Configuration configuration = getResources().getConfiguration();
        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            mLayoutManager = new GridLayoutManager(this.getContext(), 3);
        } else {
            mLayoutManager = new GridLayoutManager(this.getContext(), 5);
        }

        rvImage.setLayoutManager(mLayoutManager);
        mAdapter = new ImageAdapter(mImageSelectorActivity, mMaxCount, isSingle, canPreview);
        rvImage.setAdapter(mAdapter);
        ((SimpleItemAnimator) rvImage.getItemAnimator()).setSupportsChangeAnimations(false);

        mAdapter.setOnImageSelectListener(new ImageAdapter.OnImageSelectListener() {
            @Override
            public void OnImageSelect(Media image, boolean isSelect, int selectCount) {
                setSelectImageCount(selectCount);
            }
        });

        if (data != null) {
            mAdapter.refresh(data,useCamera);
        }
        rvImage.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                changeTime();
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                changeTime();
            }
        });
    }

    private void setSelectImageCount(int count) {
        mImageSelectorActivity.setSelectImageCount(count);
    }

    ArrayList<Media> data;
    boolean useCamera;

    public void refresh(ArrayList<Media> data_, boolean useCamera_) {
        if (mAdapter != null) {
            mAdapter.refresh(data_,useCamera_);
        } else {
            data = data_;
            useCamera = useCamera_;
        }
    }

    public void notifyDataSetChanged() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void changeTime() {

    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


}
