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

    public Bitmap realBmp;
    boolean precise;

    public void setBitmap(Bitmap bmp_,boolean precise_) {
        realBmp = bmp_;
        precise = precise_;
    }

    public int curFrameNum;
    public double curTime;

    public void setTimeId(int curFrameNum_, double curTime_) {
        curFrameNum = curFrameNum_;
        curTime = curTime_;
    }

    public void free() {
        if (realBmp != null) {
            tmpBmp = realBmp;
            tmpFrameNum = curFrameNum;
            tmpTime = curTime;
        }

        realBmp = null;
        precise = false;

        curFrameNum = oriId;
        curTime = oriId;
    }

    public boolean realIsValid() {
        if (/*precise &&*/ realBmp != null && curFrameNum >= 0) {
            return true;
        }
        return false;
    }

    public boolean thisDrawing = false;

    public int showIndex = 0;


    public int tmpFrameNum;
    public double tmpTime;
    public Bitmap tmpBmp;

    public boolean tmpIsValid() {
        if (tmpBmp != null && tmpFrameNum >= 0) {
            return true;
        }
        return false;
    }

    /*public void tmpSetRealData(ThumbCell from) {
        tmpBmp = from.realBmp;
        tmpFrameNum = from.curFrameNum;
        tmpTime = from.curTime;
    }*/
    public void tmpSetRealData() {
        tmpBmp = this.realBmp;
        tmpFrameNum = this.curFrameNum;
        tmpTime = this.curTime;
    }

    public void tmpSetTmpData(ThumbCell from) {
        tmpBmp = from.tmpBmp;
        tmpFrameNum = from.tmpFrameNum;
        tmpTime = from.tmpTime;
    }


    private RectF drawRect = new RectF();
    private Paint bitmapPaint = new  Paint(Paint.ANTI_ALIAS_FLAG);
    {
        bitmapPaint.setFilterBitmap(true);
    }

    public float drawX = 0f;

    public void setDrawX(float x) {
        drawX = x;
    }

    public void drawSelf(Canvas canvas) {
        if (curFrameNum < 0) {
            return;
        }
        if (drawX == 0) {
            ALog.i("-260531p1q-ThumbCell-drawSelf "
                    +" curFrameNum:"+curFrameNum
                    +" drawX:"+drawX
            );
        }
        if (realBmp != null && !realBmp.isRecycled()) {
            ALog.i("-260531p1q-ThumbCell-draw "
                    +" curFrameNum:"+curFrameNum
                    +" curTime:"+curTime
            );
            drawRect.set(drawX,0,drawX + width,height);
            canvas.drawBitmap(realBmp, null, drawRect, bitmapPaint);
        } else {
            if (tmpBmp != null && !tmpBmp.isRecycled()) {
                ALog.i("-260531p1q-ThumbCell-draw "
                        +" curFrameNum:"+curFrameNum
                        +" curTime:"+curTime
                );
                drawRect.set(drawX,0,drawX + width,height);
                canvas.drawBitmap(tmpBmp, null, drawRect, bitmapPaint);
            }
        }
    }






    private static RectF drawRect_s = new RectF();
    private static Paint bitmapPaint_s = new  Paint(Paint.ANTI_ALIAS_FLAG);
    static{
        bitmapPaint_s.setFilterBitmap(true);
    }

    public static void draw(Canvas canvas,Bitmap targetBmp,float x,float width,float height) {
        if (targetBmp != null && !targetBmp.isRecycled()) {
            ALog.i("-260531p1q-ThumbCell-draw "
            );
            drawRect_s.set(x,0,x + width,height);
            canvas.drawBitmap(targetBmp, null, drawRect_s, bitmapPaint_s);
        }
    }



}
