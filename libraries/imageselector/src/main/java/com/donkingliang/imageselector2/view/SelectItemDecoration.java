package com.donkingliang.imageselector2.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

public class SelectItemDecoration extends RecyclerView.ItemDecoration {

    private int sideMargin;
    private int itemMargin;

    public SelectItemDecoration(int sideMargin, int itemMargin) {
        this.sideMargin = sideMargin;
        this.itemMargin = itemMargin;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view); // item position
        if (position == RecyclerView.NO_POSITION) {
            return;
        }
        int itemCount = parent.getAdapter().getItemCount();
        if (position == 0) {
            outRect.left = sideMargin;
            outRect.right = itemMargin;
        } else if (position == itemCount - 1) {
            outRect.right = sideMargin;
        } else {
            outRect.right = itemMargin;
        }
    }
}
