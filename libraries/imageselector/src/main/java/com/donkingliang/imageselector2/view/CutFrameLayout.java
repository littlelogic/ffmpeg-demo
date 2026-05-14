package com.donkingliang.imageselector2.view;
// com.donkingliang.imageselector2.view.CutFrameLayout

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Region;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class CutFrameLayout extends FrameLayout {

    public CutFrameLayout(@NonNull Context context) {
        super(context);
    }

    public CutFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CutFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private Path path = new Path();
    private Boolean toTopDisappear = null;
    private float per = 0;

    public void setToTopDisappear(Boolean mark) {
        toTopDisappear = mark;
    }

    public void setDisappearPer(float per_) {
        per = per_;
    }

    boolean dispatchDraw_mark = true;

    @Override
    public void draw(Canvas canvas) {
        if (toTopDisappear == null) {
            super.draw(canvas);
            return;
        }
        dispatchDraw_mark = false;
        if (toTopDisappear) {
            path.reset();
            path.addRect(0, 0, getWidth(), per * this.getHeight(), Path.Direction.CW);
            canvas.clipPath(path, Region.Op.INTERSECT);
        } else {
            path.reset();
            path.addRect(0, per * this.getHeight(), getWidth(), getHeight(), Path.Direction.CW);
        }
        super.draw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (toTopDisappear == null) {
            super.dispatchDraw(canvas);
            return;
        }
        if (!dispatchDraw_mark) {
            dispatchDraw_mark = true;
            super.dispatchDraw(canvas);
            return;
        }
        if (toTopDisappear) {
            path.reset();
            path.addRect(0, 0, getWidth(), per * this.getHeight(), Path.Direction.CW);
            canvas.clipPath(path, Region.Op.INTERSECT);
        } else {
            path.reset();
            path.addRect(0, per * this.getHeight(), getWidth(), getHeight(), Path.Direction.CW);
        }
        super.dispatchDraw(canvas);
    }
}
