package com.example.basilience;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Full-screen dimmed scrim with a single rounded-rect spotlight cutout,
 * used by CoachMarkTour to highlight one on-screen element at a time.
 */
public class CoachMarkOverlayView extends View {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap scrimBitmap;
    private Canvas scrimCanvas;

    private final RectF spotlightRect = new RectF();
    private float spotlightRadius;
    private boolean hasSpotlight;

    public CoachMarkOverlayView(Context context) {
        this(context, null);
    }

    public CoachMarkOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);

        scrimPaint.setColor(Color.parseColor("#CC000000"));

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dpToPx(2.5f));
        ringPaint.setColor(ContextCompat.getColor(context, R.color.primary_light));
    }

    /** Sets the spotlight cutout, in this view's own local coordinates. */
    public void setSpotlight(RectF rect, float radius) {
        spotlightRect.set(rect);
        spotlightRadius = radius;
        hasSpotlight = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (scrimBitmap != null) {
            scrimBitmap.recycle();
            scrimBitmap = null;
            scrimCanvas = null;
        }
        if (w > 0 && h > 0) {
            scrimBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scrimCanvas = new Canvas(scrimBitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scrimBitmap == null || scrimCanvas == null) return;

        scrimBitmap.eraseColor(Color.TRANSPARENT);
        scrimCanvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        if (hasSpotlight) {
            scrimCanvas.drawRoundRect(spotlightRect, spotlightRadius, spotlightRadius, clearPaint);
        }
        canvas.drawBitmap(scrimBitmap, 0, 0, null);

        if (hasSpotlight) {
            canvas.drawRoundRect(spotlightRect, spotlightRadius, spotlightRadius, ringPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrimBitmap != null) {
            scrimBitmap.recycle();
            scrimBitmap = null;
        }
        scrimCanvas = null;
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
