package com.victory.easywatermark;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 图片编辑画布：裁剪（四角拖拽）/ 滤镜（ColorMatrix 预览）/ 加字（拖动定位）/ 遮挡（马赛克·涂鸦） */
public class EditorView extends View {

    public enum Mode { CROP, FILTER, TEXT, MOSAIC }

    private static final String[] FILTER_NAMES = {"原图", "黑白", "复古", "冷色", "暖色", "鲜艳"};

    private static class Stroke {
        Path path;          // 源图像素坐标
        boolean mosaic;     // true=马赛克 false=涂鸦画笔
        int color;
    }

    private Bitmap src;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();       // 图片适配后绘制区域（视图坐标）
    private final RectF crop = new RectF();      // 裁剪框（视图坐标）

    private Mode mode = Mode.CROP;
    private ColorMatrix filter;                  // null = 原图
    private int filterIndex = 0;
    private String text;
    private float textNX = 0.5f, textNY = 0.75f; // 文字锚点（相对图片区域 0..1）
    private float textSizeFrac = 0.055f;
    private float cropRatio = 0f;                // 0=自由，>0 = 宽/高

    // 遮挡
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private boolean penMosaic = true;            // true=马赛克笔 false=涂鸦笔
    private Bitmap mosaicBmp;                    // 像素化缓存

    private int dragHandle = -1;
    private boolean movingCrop = false;
    private boolean movingText = false;

    public EditorView(Context ctx) { super(ctx); }
    public EditorView(Context ctx, AttributeSet attrs) { super(ctx, attrs); }

    public void setBitmap(Bitmap b) {
        src = b;
        mosaicBmp = null;
        requestLayout();
        invalidate();
    }

    public static String[] filterNames() { return FILTER_NAMES; }

    public void setMode(Mode m) { mode = m; invalidate(); }
    public Mode getMode() { return mode; }

    public void setAspect(float ratio) { cropRatio = ratio; resetCrop(); invalidate(); }

    public void setFilterIndex(int idx) {
        filterIndex = idx;
        filter = matrixFor(idx);
        invalidate();
    }

    public int getFilterIndex() { return filterIndex; }

    public void setText(String t) {
        text = (t == null || t.trim().isEmpty()) ? null : t.trim();
        invalidate();
    }

    public String getText() { return text; }

    public void setPenMosaic(boolean mosaic) { penMosaic = mosaic; }
    public boolean isPenMosaic() { return penMosaic; }

    public void undoStroke() {
        if (!strokes.isEmpty()) { strokes.remove(strokes.size() - 1); invalidate(); }
    }

    public void clearStrokes() {
        strokes.clear();
        invalidate();
    }

    public void resetAll() {
        filterIndex = 0;
        filter = null;
        text = null;
        textNX = 0.5f;
        textNY = 0.75f;
        cropRatio = 0f;
        strokes.clear();
        resetCrop();
        invalidate();
    }

    // ---------- 布局与坐标 ----------

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        computeDst();
        if (crop.isEmpty()) resetCrop();
    }

    private void computeDst() {
        if (src == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float bw = src.getWidth(), bh = src.getHeight();
        float scale = Math.min(vw / bw, vh / bh);
        float w = bw * scale, h = bh * scale;
        dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    private void resetCrop() {
        if (dst.isEmpty()) return;
        crop.set(dst);
        crop.inset(dst.width() * 0.06f, dst.height() * 0.06f);
        if (cropRatio > 0) fitRatio();
    }

    private void fitRatio() {
        float cw = crop.width(), ch = crop.height();
        float newH = cw / cropRatio;
        if (newH <= ch) {
            float cy = crop.centerY();
            crop.top = cy - newH / 2f;
            crop.bottom = cy + newH / 2f;
        } else {
            float newW = ch * cropRatio;
            float cx = crop.centerX();
            crop.left = cx - newW / 2f;
            crop.right = cx + newW / 2f;
        }
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (src == null) return false;
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mode == Mode.CROP) {
                    dragHandle = hitHandle(x, y);
                    if (dragHandle >= 0) return true;
                    if (crop.contains(x, y)) { movingCrop = true; return true; }
                } else if (mode == Mode.TEXT && text != null) {
                    movingText = true;
                    moveText(x, y);
                    return true;
                } else if (mode == Mode.MOSAIC) {
                    current = new Stroke();
                    current.mosaic = penMosaic;
                    current.color = 0xE5FF3B30;
                    current.path = new Path();
                    float[] pt = toSrc(x, y);
                    current.path.moveTo(pt[0], pt[1]);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mode == Mode.CROP && dragHandle >= 0) {
                    resizeCrop(dragHandle, x, y);
                    return true;
                } else if (mode == Mode.CROP && movingCrop) {
                    moveCrop(x, y);
                    return true;
                } else if (mode == Mode.TEXT && movingText && text != null) {
                    moveText(x, y);
                    return true;
                } else if (mode == Mode.MOSAIC && current != null) {
                    float[] pt = toSrc(x, y);
                    current.path.lineTo(pt[0], pt[1]);
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == Mode.MOSAIC && current != null) {
                    strokes.add(current);
                    current = null;
                    invalidate();
                }
                dragHandle = -1;
                movingCrop = false;
                movingText = false;
                return true;
        }
        return false;
    }

    /** 视图坐标 → 源图像素坐标 */
    private float[] toSrc(float x, float y) {
        float sx = clamp((x - dst.left) * src.getWidth() / dst.width(), 0, src.getWidth());
        float sy = clamp((y - dst.top) * src.getHeight() / dst.height(), 0, src.getHeight());
        return new float[]{sx, sy};
    }

    private int hitHandle(float x, float y) {
        float r = Math.max(48, dst.width() * 0.06f);
        float[] xs = {crop.left, crop.right, crop.left, crop.right};
        float[] ys = {crop.top, crop.top, crop.bottom, crop.bottom};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(x - xs[i]) <= r && Math.abs(y - ys[i]) <= r) return i;
        }
        return -1;
    }

    private void resizeCrop(int handle, float x, float y) {
        float minW = dst.width() * 0.15f, minH = dst.height() * 0.15f;
        x = clamp(x, dst.left, dst.right);
        y = clamp(y, dst.top, dst.bottom);
        switch (handle) {
            case 0: crop.left = x; crop.top = y; break;
            case 1: crop.right = x; crop.top = y; break;
            case 2: crop.left = x; crop.bottom = y; break;
            case 3: crop.right = x; crop.bottom = y; break;
        }
        if (crop.width() < minW || crop.height() < minH) {
            resetCrop();
        } else if (cropRatio > 0) {
            float cx = (handle == 0 || handle == 2) ? crop.right : crop.left;
            float cy = (handle == 0 || handle == 1) ? crop.bottom : crop.top;
            float w = Math.abs(x - cx), h = Math.abs(y - cy);
            if (w / cropRatio <= h) h = w / cropRatio; else w = h * cropRatio;
            boolean toRight = (handle == 1 || handle == 3);
            boolean toBottom = (handle == 2 || handle == 3);
            if (toRight) { crop.left = cx; crop.right = cx + w; } else { crop.right = cx; crop.left = cx - w; }
            if (toBottom) { crop.top = cy; crop.bottom = cy + h; } else { crop.bottom = cy; crop.top = cy - h; }
        }
        invalidate();
    }

    private void moveCrop(float x, float y) {
        float dx = x - crop.centerX(), dy = y - crop.centerY();
        translateCrop(dx * 0.5f, dy * 0.5f);
        invalidate();
    }

    private void translateCrop(float dx, float dy) {
        float l = crop.left + dx, t = crop.top + dy, r = crop.right + dx, b = crop.bottom + dy;
        if (l < dst.left) dx += dst.left - l;
        if (t < dst.top) dy += dst.top - t;
        if (r > dst.right) dx -= r - dst.right;
        if (b > dst.bottom) dy -= b - dst.bottom;
        crop.offset(dx, dy);
    }

    private void moveText(float x, float y) {
        textNX = clamp((x - dst.left) / dst.width(), 0.05f, 0.95f);
        textNY = clamp((y - dst.top) / dst.height(), 0.05f, 0.95f);
        invalidate();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas c) {
        if (src == null) return;
        computeDst();
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (filter != null) p.setColorFilter(new ColorMatrixColorFilter(filter));
        c.drawBitmap(src, null, dst, p);

        if (!strokes.isEmpty() || current != null) drawStrokes(c, dst);

        if (mode == Mode.CROP) {
            drawDimAndCrop(c);
        } else if (mode == Mode.TEXT && text != null) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(textSizeFrac * dst.width());
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setShadowLayer(8, 0, 3, 0xAA000000);
            c.drawText(text, dst.left + textNX * dst.width(),
                    dst.top + textNY * dst.height(), tp);
        }
    }

    private void drawStrokes(Canvas c, RectF target) {
        float sx = target.width() / src.getWidth();
        float sy = target.height() / src.getHeight();
        c.save();
        c.translate(target.left, target.top);
        c.scale(sx, sy);
        List<Stroke> all = new ArrayList<>(strokes);
        if (current != null) all.add(current);
        for (Stroke s : all) {
            if (s.mosaic) {
                if (mosaicBmp == null) {
                    mosaicBmp = Bitmap.createScaledBitmap(src,
                            Math.max(1, src.getWidth() / 22),
                            Math.max(1, src.getHeight() / 22), true);
                }
                Paint blocky = new Paint();   // 无滤波 → 马赛克块状
                c.save();
                c.clipPath(s.path);
                c.drawBitmap(mosaicBmp, 0, 0, blocky);
                c.restore();
            } else {
                Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
                pen.setColor(s.color);
                pen.setStyle(Paint.Style.STROKE);
                pen.setStrokeJoin(Paint.Join.ROUND);
                pen.setStrokeCap(Paint.Cap.ROUND);
                pen.setStrokeWidth(src.getWidth() * 0.012f);
                c.drawPath(s.path, pen);
            }
        }
        c.restore();
    }

    private void drawDimAndCrop(Canvas c) {
        Paint dim = new Paint();
        dim.setColor(0x99000000);
        c.drawRect(dst.left, dst.top, dst.right, crop.top, dim);
        c.drawRect(dst.left, crop.bottom, dst.right, dst.bottom, dim);
        c.drawRect(dst.left, crop.top, crop.left, crop.bottom, dim);
        c.drawRect(crop.right, crop.top, dst.right, crop.bottom, dim);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(2f);
        line.setStyle(Paint.Style.STROKE);
        c.drawRect(crop, line);

        line.setStrokeWidth(1f);
        line.setColor(0x66FFFFFF);
        for (int i = 1; i <= 2; i++) {
            c.drawLine(crop.left, crop.top + crop.height() * i / 3f,
                    crop.right, crop.top + crop.height() * i / 3f, line);
            c.drawLine(crop.left + crop.width() * i / 3f, crop.top,
                    crop.left + crop.width() * i / 3f, crop.bottom, line);
        }

        Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        handle.setColor(0xFFFFFFFF);
        handle.setStrokeWidth(6f);
        float len = Math.min(crop.width(), crop.height()) * 0.12f;
        corner(c, handle, crop.left, crop.top, len, 1, 1);
        corner(c, handle, crop.right, crop.top, len, -1, 1);
        corner(c, handle, crop.left, crop.bottom, len, 1, -1);
        corner(c, handle, crop.right, crop.bottom, len, -1, -1);
    }

    private void corner(Canvas c, Paint p, float x, float y, float len, int sx, int sy) {
        c.drawLine(x, y, x + len * sx, y, p);
        c.drawLine(x, y, x, y + len * sy, p);
    }

    // ---------- 输出 ----------

    /** 应用裁剪 + 滤镜 + 文字 + 遮挡，返回结果 Bitmap（新对象） */
    public Bitmap apply() {
        if (src == null) return null;
        float sx = src.getWidth() / dst.width();
        float sy = src.getHeight() / dst.height();

        Bitmap out;
        int cropL = 0, cropT = 0;
        if (mode == Mode.CROP) {
            int l = (int) Math.max(0, Math.floor((crop.left - dst.left) * sx));
            int t = (int) Math.max(0, Math.floor((crop.top - dst.top) * sy));
            int r = (int) Math.min(src.getWidth(), Math.ceil((crop.right - dst.left) * sx));
            int b = (int) Math.min(src.getHeight(), Math.ceil((crop.bottom - dst.top) * sy));
            int w = r - l, h = b - t;
            if (w < 16 || h < 16 || (w == src.getWidth() && h == src.getHeight())) {
                out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(out);
                cc.drawBitmap(src, 0, 0, null);
            } else {
                out = Bitmap.createBitmap(src, l, t, w, h);
                cropL = l; cropT = t;
            }
        } else {
            out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cc = new Canvas(out);
            cc.drawBitmap(src, 0, 0, null);
        }

        boolean hasFilter = filter != null;
        boolean hasText = text != null && mode == Mode.TEXT;
        boolean hasStrokes = !strokes.isEmpty();

        if (hasFilter || hasText || hasStrokes) {
            Bitmap res = Bitmap.createBitmap(out.getWidth(), out.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(res);
            Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            if (hasFilter) pp.setColorFilter(new ColorMatrixColorFilter(filter));
            c.drawBitmap(out, 0, 0, pp);
            if (hasText) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFFFFFFFF);
                tp.setTextSize(textSizeFrac * res.getWidth());
                tp.setTextAlign(Paint.Align.CENTER);
                tp.setShadowLayer(8, 0, 3, 0xAA000000);
                c.drawText(text, textNX * res.getWidth(), textNY * res.getHeight(), tp);
            }
            // 遮挡：笔迹在源图坐标，映射到裁剪后坐标
            if (hasStrokes) {
                Matrix m = new Matrix();
                m.setTranslate(-cropL, -cropT);
                Bitmap pix = null;
                for (Stroke s : strokes) {
                    Path tp = new Path();
                    tp.addPath(s.path, m);
                    if (s.mosaic) {
                        if (pix == null) {
                            pix = Bitmap.createScaledBitmap(out,
                                    Math.max(1, out.getWidth() / 22),
                                    Math.max(1, out.getHeight() / 22), true);
                        }
                        c.save();
                        c.clipPath(tp);
                        Paint blocky = new Paint();
                        c.drawBitmap(pix, 0, 0, blocky);
                        c.restore();
                    } else {
                        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
                        pen.setColor(s.color);
                        pen.setStyle(Paint.Style.STROKE);
                        pen.setStrokeJoin(Paint.Join.ROUND);
                        pen.setStrokeCap(Paint.Cap.ROUND);
                        pen.setStrokeWidth(src.getWidth() * 0.012f);
                        c.drawPath(tp, pen);
                    }
                }
            }
            if (out != src) out.recycle();
            return res;
        }
        return out;
    }

    private static ColorMatrix matrixFor(int idx) {
        switch (idx) {
            case 1: {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(0f);
                return m;
            }
            case 2:
                return new ColorMatrix(new float[]{
                        0.393f, 0.769f, 0.189f, 0, 0,
                        0.349f, 0.686f, 0.168f, 0, 0,
                        0.272f, 0.534f, 0.131f, 0, 0,
                        0, 0, 0, 1, 0});
            case 3:
                return new ColorMatrix(new float[]{
                        0.9f, 0, 0, 0, 0,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 1.12f, 0, 8f,
                        0, 0, 0, 1, 0});
            case 4:
                return new ColorMatrix(new float[]{
                        1.1f, 0, 0, 0, 10f,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 0.88f, 0, 0,
                        0, 0, 0, 1, 0});
            case 5: {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(1.55f);
                return m;
            }
            default:
                return null;
        }
    }
}
