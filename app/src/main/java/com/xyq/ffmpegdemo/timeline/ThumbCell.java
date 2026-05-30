package com.xyq.ffmpegdemo.timeline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

public class ThumbCell {


    public float width;
    public float height;

    public ThumbCell(float width_, float height_) {
        width = width_;
        height = height_;
    }

    Bitmap bmp;

    public void setBitmap(Bitmap bmp_) {
        bmp = bmp_;
    }

    public int curFrameNum;
    public Double curTime;

    public void setTimeId(int curFrameNum_, Double curTime_) {
        curFrameNum = curFrameNum_;
        curTime = curTime_;
    }

    private RectF drawRect = new RectF();
    private Paint bitmapPaint = new  Paint(Paint.ANTI_ALIAS_FLAG);
    {
        bitmapPaint.setFilterBitmap(true);
    }

    public void draw(Canvas canvas,float x) {
        if (bmp != null && !bmp.isRecycled()) {
            drawRect.set(x,0,x + width,height);
            canvas.drawBitmap(bmp, null, drawRect, bitmapPaint);
        }
    }






}
