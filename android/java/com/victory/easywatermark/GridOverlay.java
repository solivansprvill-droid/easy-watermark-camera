package com.victory.easywatermark;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 取景器网格线（井字三分线） */
public class GridOverlay extends View {

    private final Paint paint = new Paint();

    public GridOverlay(Context ctx) { super(ctx); init(); }
    public GridOverlay(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        paint.setColor(Color.argb(140, 255, 255, 255));
        paint.setStrokeWidth(1.5f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        for (int i = 1; i <= 2; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, paint);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, paint);
        }
    }
}
