package com.xyq.ffmpegdemo.timeline.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.xyq.ffmpegdemo.timeline.TimelineConfig;

import android.widget.MyHorizontalScrollView;

/**
 * 时间轴横向滚动容器，双指捏合缩放 1 秒像素跨度（以 centerLine 为锚点）。
 */
public class CustomHorizontalScrollView extends MyHorizontalScrollView {

    public interface OnTouchDownEventListener {
        void onTouchDownEvent(MotionEvent event);
    }

    public interface OnTimelineScaleListener {
        void onTimelineScaleChanged(double anchorTimeSec, boolean snapEnd);
    }

    private OnTouchDownEventListener onTouchDownEventListener;
    private OnTimelineScaleListener onTimelineScaleListener;

    private ScaleGestureDetector scaleGestureDetector;
    private TimelineConfig timelineConfig;
    private int trackHeaderWidthPx;
    private double durationSec;
    private boolean scaleEnabled;

    private float scalePxPerSecondStart;
    private float scaleCumulativeFactor = 1f;
    private double scaleAnchorTimeSec;
    private boolean isPinchScaling;

    public CustomHorizontalScrollView(Context context) {
        super(context);
        init(context);
    }

    public CustomHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CustomHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public CustomHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.OnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!scaleEnabled || durationSec <= 0 || timelineConfig == null) {
                    return false;
                }
                scalePxPerSecondStart = timelineConfig.getSecondSpanPx();
                scaleCumulativeFactor = 1f;
                scaleAnchorTimeSec = getPlayheadTimeSec();
                isPinchScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!scaleEnabled || durationSec <= 0 || timelineConfig == null) {
                    return false;
                }
                scaleCumulativeFactor *= detector.getScaleFactor();
                float rawPx = scalePxPerSecondStart * scaleCumulativeFactor;
                float newPx = TimelineConfig.clampPxPerSecond(
                        timelineConfig.getMajorTickSpacingPx(), rawPx);
                if (Math.abs(newPx - timelineConfig.getSecondSpanPx()) < 0.5f) {
                    return true;
                }
                applyPxPerSecond(newPx, false);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isPinchScaling = false;
            }
        });
    }

    public void setOnTouchDownEventListener(OnTouchDownEventListener listener) {
        this.onTouchDownEventListener = listener;
    }

    public void setOnTimelineScaleListener(OnTimelineScaleListener listener) {
        this.onTimelineScaleListener = listener;
    }

    public void setTimelineConfig(TimelineConfig config) {
        this.timelineConfig = config;
    }

    public void setTrackHeaderWidthPx(int widthPx) {
        this.trackHeaderWidthPx = widthPx;
    }

    public void setDurationSec(double durationSec) {
        this.durationSec = durationSec;
    }

    public void setScaleEnabled(boolean enabled) {
        this.scaleEnabled = enabled;
    }

    public boolean isPinchScaling() {
        return isPinchScaling;
    }

    public double getPlayheadTimeSec() {
        if (durationSec <= 0 || timelineConfig == null || timelineConfig.getSecondSpanPx() <= 0) {
            return 0.0;
        }
        int viewportW = getWidth();
        if (viewportW <= 0) {
            return 0.0;
        }
        float contentPx = getScrollX() /*+ viewportW / 2f - trackHeaderWidthPx*/;
        double timeSec = timelineConfig.pxToTimeSec(contentPx);
        return Math.max(0.0, Math.min(durationSec, timeSec));
    }

    public void scrollToTimeSec(double timeSec) {
        if (durationSec <= 0 || timelineConfig == null) {
            return;
        }
        int viewportW = getWidth();
        if (viewportW <= 0) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(durationSec, timeSec));
        int scrollX = (int) timelineConfig.timeSecToPx(clamped);
        if (getScrollX() != scrollX) {
            scrollTo(scrollX, 0);
        }


        /*int contentW = timelineConfig.getContentWidthPx();
        float contentPos = trackHeaderWidthPx + timelineConfig.timeSecToPx(clamped);
        int maxScroll = Math.max(0, trackHeaderWidthPx + contentW + trackHeaderWidthPx - viewportW);
        int scrollX = (int) Math.max(0, Math.min(maxScroll, contentPos - viewportW / 2f));
        if (getScrollX() != scrollX) {
            scrollTo(scrollX, 0);
        }*/
    }

    private void applyPxPerSecond(float pxPerSecond, boolean snapEnd) {
        timelineConfig.setPxPerSecond(pxPerSecond);
        if (onTimelineScaleListener != null) {
            onTimelineScaleListener.onTimelineScaleChanged(scaleAnchorTimeSec, snapEnd);
        }
        scrollToTimeSec(scaleAnchorTimeSec);
        post(() -> scrollToTimeSec(scaleAnchorTimeSec));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (onTouchDownEventListener != null) {
                onTouchDownEventListener.onTouchDownEvent(ev);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (scaleEnabled && scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(ev);
            if (ev.getPointerCount() >= 2) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }
}
