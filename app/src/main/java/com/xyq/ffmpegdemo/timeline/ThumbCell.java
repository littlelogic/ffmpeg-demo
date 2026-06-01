package com.xyq.ffmpegdemo.timeline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.badlogic.utils.ALog;

public class ThumbCell {

    public int oriId = -1;
    public float width;
    public float height;

    public ThumbCell(float width_, float height_,int oriId_) {
        width = width_;
        height = height_;
        oriId = oriId_;
        free();
    }

    Bitmap bmp;
    boolean precise;

    public void setBitmap(Bitmap bmp_,boolean precise_) {
        bmp = bmp_;
        precise = precise_;
    }

    public int curFrameNum;
    public double curTime;

    public void setTimeId(int curFrameNum_, double curTime_) {
        curFrameNum = curFrameNum_;
        curTime = curTime_;
    }

    public void free() {
        curFrameNum = oriId;
        curTime = oriId;
    }


    private RectF drawRect = new RectF();
    private Paint bitmapPaint = new  Paint(Paint.ANTI_ALIAS_FLAG);
    {
        bitmapPaint.setFilterBitmap(true);
    }

    public void draw(Canvas canvas,float x) {
        if (bmp != null && !bmp.isRecycled()) {
            ALog.i("-260531p1q-ThumbCell-draw "
                    +" curFrameNum:"+curFrameNum
                    +" curTime:"+curTime
            );
            drawRect.set(x,0,x + width,height);
            canvas.drawBitmap(bmp, null, drawRect, bitmapPaint);
        }
    }






}
